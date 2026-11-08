"""Unit tests. A fake runner stands in for herdr, wt, ps and tailscale. Fixtures follow real Herdr 0.9.1 output."""
import hashlib
import inspect
import http.client
import http.server
import io
import json
import os
import re
import shutil
import socket
import ssl
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import unittest.mock
from contextlib import redirect_stderr
from dataclasses import replace
from pathlib import Path

import gateway as g

OWNER_ID = 6053541319899216
LOGIN = "owner@example.com"
ALLOWED = frozenset({"mac", "mac.tail1234.ts.net", "100.96.1.2", "localhost", "127.0.0.1"})
SCRATCH = "wt-scratch"
ROOT = Path(__file__).resolve().parent

STATUS_JSON = json.dumps({
    "BackendState": "Running",
    "Self": {"UserID": OWNER_ID, "TailscaleIPs": ["100.96.1.2"], "DNSName": "mac.tail1234.ts.net.",
             "HostName": "Mac", "Tags": None},
    "User": {str(OWNER_ID): {"ID": OWNER_ID, "LoginName": LOGIN}},
})


def whois_json(user_id=OWNER_ID, tags=None):
    return json.dumps({"UserProfile": {"ID": user_id, "LoginName": LOGIN}, "Node": {"Name": "x.", "Tags": tags}})


class Res:
    def __init__(self, rc=0, stdout="", stderr=""):
        self.returncode, self.stdout, self.stderr = rc, stdout, stderr


def err(code, message="m"):
    return Res(1, "", json.dumps({"error": {"code": code, "message": message}, "id": "cli:x"}))


class FakeClock:
    def __init__(self):
        self.t = 1000.0

    def now(self):
        return self.t

    def sleep(self, seconds):
        self.t += seconds


def tab_row(tab="w1:t1", label="orchestrator"):
    return {"agent_status": "idle", "focused": True, "label": label, "number": 1, "pane_count": 1,
            "tab_id": tab, "workspace_id": tab.split(":")[0]}


def agent_row(pane="w1:p1", tab="w1:t1", kind="claude", status="idle", name=None, terminal="term_1"):
    row = {"agent": kind, "agent_status": status, "cwd": "/somewhere", "focused": True, "pane_id": pane,
           "revision": 1, "state_change_seq": 7, "tab_id": tab, "terminal_id": terminal,
           "terminal_title": "private title", "workspace_id": pane.split(":")[0]}
    if name:
        row["name"] = name
    return row


def pane_row(pane="w1:p1", tab="w1:t1", kind="claude", status="idle", label=None, rows=40):
    row = {"agent_status": status, "cwd": "/somewhere", "focused": False, "pane_id": pane, "revision": 0,
           "scroll": {"max_offset_from_bottom": 0, "offset_from_bottom": 0, "viewport_rows": rows},
           "tab_id": tab, "terminal_id": "t", "workspace_id": pane.split(":")[0]}
    if kind:
        row["agent"] = kind
    if label:
        row["label"] = label
    return row


class FakeSession:
    def __init__(self, running=True, tabs=None, agents=None, panes=None):
        self.running = running
        self.tabs = [tab_row()] if tabs is None else tabs
        self.agents = [agent_row()] if agents is None else agents
        self.panes = [pane_row()] if panes is None else panes
        self.get_status = None  # What `agent get` says, when it differs from the listing.
        self.get_row = None
        self.output = "orchestrator output"
        self.visible = "visible screen"
        self.ansi = ""  # What `agent read --format ansi` answers.
        self.explain_state = "idle"
        self.deep_read_error = None  # What a read of more than the visible screen answers.


# Every flag the gateway gives git. Anything else starting with "-" in a git argv is a violation: a name taken as an
# option.
KNOWN_GIT_FLAGS = frozenset({"--branch", "--path-format=absolute", "--git-common-dir", "--verify", "--quiet", "-q",
                             "--short", "-1", "-g", "--no-show-signature", "--porcelain", "--no-tags", "--heads",
                             "--date=unix", "--"})


class FakeRunner:
    """Answers like the real tools and records a violation for anything the gateway must never run."""
    def __init__(self, sessions=None, session_dirs=None):
        self.sessions = {SCRATCH: FakeSession()} if sessions is None else sessions
        self.session_dirs = session_dirs or {}
        self.calls = []
        self.violations = []
        self.ps_output = ""
        self.ps_fails = False
        self.wt_names = {}
        self.wt_up_result = Res(0, "Herdr session started\n")
        self.prompt_result = Res(0, '{"id":"cli:agent:prompt","result":{"type":"ok"}}')
        self.on_prompt = None
        self.on_keys = None
        self.fail = {}  # (session, verb) -> Res, or an exception to raise.
        self.home = None  # Where the fake `wt new` puts worktrees: <home>/worktrees/<repo>/<slug>.
        self.wt_new_result = None  # What `wt new` answers instead of making the worktree, if set.
        self.on_wt_new = None
        self.new_sessions_listed = True  # Whether a session made by `wt new` shows up in `herdr session list`.
        self.wt_new_stdout = ""  # Extra lines the fake `wt new` prints on stdout after its own, on success.
        self.wt_new_stderr = ""  # What the fake `wt new` prints on stderr, on success.
        self.git_fail = {}  # git verb -> Res, or an exception to raise, instead of running real git.
        self.lock = threading.Lock()

    def named(self, *verb):
        return [c for c in self.calls if c["argv"][3:3 + len(verb)] == list(verb)]

    def run(self, argv, timeout=15, cwd=None, extra_env=None, detach=False, input_text=None):
        with self.lock:
            self.calls.append({"argv": list(argv), "timeout": timeout, "cwd": cwd, "extra_env": extra_env,
                               "detach": detach, "input_text": input_text})
        self._police(argv, extra_env, detach)
        if argv[0] == "ps":
            return Res(1, "", "no ps") if self.ps_fails else Res(0, self.ps_output)
        if os.path.basename(argv[0]) == "wt":
            return self._wt(argv, cwd)
        if argv[0] == "herdr":
            return self._herdr(argv)
        if argv[0] == "git":
            outcome = self.git_fail.get(argv[3]) if len(argv) > 3 else None
            if isinstance(outcome, Exception):
                raise outcome
            if outcome is not None:
                return outcome
            return run_git(argv[1:], cwd)  # Real git, on the test's own scratch repos only.
        raise AssertionError(f"unexpected command {argv}")

    def git_calls(self):
        return [c["argv"] for c in self.calls if c["argv"][0] == "git"]

    def wt_calls(self, verb):
        return [c for c in self.calls if os.path.basename(c["argv"][0]) == "wt" and c["argv"][1] == verb]

    def git_calls_of(self, verb):
        return [c for c in self.calls if c["argv"][0] == "git" and c["argv"][3:4] == [verb]]

    def _police(self, argv, extra_env=None, detach=False):
        joined = " ".join(argv[:6])
        if argv == ["herdr"]:
            self.violations.append("bare herdr")
        if os.path.basename(argv[0]) == "wt" and argv[1:] not in (["name"], ["up"]) and not (
                len(argv) == 3 and argv[1] == "new" and not argv[2].startswith("-")) and not (
                len(argv) == 5 and argv[1] == "new" and not argv[2].startswith("-") and argv[3] == "--base"
                and re.fullmatch(r"refs/remotes/origin/[A-Za-z0-9._/][A-Za-z0-9._/-]*", argv[4])):
            self.violations.append(f"wt {argv[1:]}")
        if argv[0] == "git":
            read_only = (["check-ref-format", "--branch"], ["rev-parse", "--path-format=absolute"],
                         ["show-ref", "--verify"], ["symbolic-ref", "-q"], ["log", "-1"], ["worktree", "list"],
                         ["for-each-ref", "--format=%(refname)%09%(committerdate:unix)"])
            remote = re.fullmatch(r"[A-Za-z0-9._/][A-Za-z0-9._/-]*", argv[6]) if len(argv) == 7 else None
            fetch = (argv[3:6] == ["fetch", "--no-tags", "origin"] and len(argv) == 7
                     and re.fullmatch(r"\+refs/heads/([A-Za-z0-9._/-]+):refs/remotes/origin/\1", argv[6]))
            ls_remote = (argv[3:6] == ["ls-remote", "--heads", "origin"] and remote is not None
                         and argv[6].startswith("refs/heads/"))
            if argv[1] != "-C" or not (argv[3:5] in read_only or fetch or ls_remote):
                self.violations.append(f"git {argv[1:]}")
            if (fetch or ls_remote) and (not detach or (extra_env or {}).get("GIT_TERMINAL_PROMPT") != "0"):
                self.violations.append(f"git {argv[3]} could prompt")
            if any(part.startswith("-") and part not in KNOWN_GIT_FLAGS and not part.startswith("--format=")
                   for part in argv[3:]):
                self.violations.append(f"git option {argv[3:]}")
        if argv[0] == "herdr" and "--session" not in argv[:2] and argv[1:] != ["session", "list", "--json"]:
            self.violations.append(f"no --session: {joined}")
        if argv[0] == "herdr" and argv[1] == "--session":
            verb = argv[3:5]
            allowed = (["tab", "list"], ["agent", "list"], ["pane", "list"], ["agent", "get"], ["agent", "read"],
                       ["agent", "prompt"], ["agent", "send-keys"], ["agent", "explain"])
            if verb not in allowed:
                self.violations.append(f"forbidden verb {verb}")
            name = argv[2]
            if not name.startswith("wt-"):
                self.violations.append(f"non wt session {name}")
            if name in self.sessions and not self.sessions[name].running:
                self.violations.append(f"command against stopped session: {joined}")

    def _wt(self, argv, cwd):
        if argv[1] == "name":
            if cwd not in self.wt_names:
                return Res(1, "", "no such directory")
            return Res(0, self.wt_names[cwd] + "\n")
        if argv[1] == "new":
            return self._wt_new(argv[2], cwd, *argv[4:5])
        return self.wt_up_result

    def _wt_new(self, branch, cwd, base=None):
        """What the real `wt new <branch> [--base REF]` does, minus the editor: the worktree, then a running
        session."""
        if self.on_wt_new:
            self.on_wt_new()
        if self.wt_new_result is not None:
            return self.wt_new_result
        path = self.home / "worktrees" / os.path.basename(cwd) / g.wt_slug(branch)
        path.parent.mkdir(parents=True, exist_ok=True)
        if run_git(["-C", cwd, "show-ref", "--verify", "--quiet", f"refs/heads/{branch}"], cwd).returncode == 0:
            if base:
                return Res(1, "", f"wt: branch {branch} already exists; --base only applies to new branches\n")
            made = run_git(["-C", cwd, "worktree", "add", str(path), branch], cwd)
        else:
            if not base:
                base = run_git(["-C", cwd, "symbolic-ref", "-q", "--short", "refs/remotes/origin/HEAD"], cwd)
                base = base.stdout.strip() if base.returncode == 0 else "HEAD"
            made = run_git(["-C", cwd, "worktree", "add", "-b", branch, str(path), base], cwd)
        if made.returncode != 0:
            return Res(1, "", made.stderr)
        name = f"wt-{g.wt_slug(os.path.basename(cwd))}-{g.wt_slug(branch)}"
        self.wt_names[str(path)] = name
        if self.new_sessions_listed:
            self.sessions[name] = FakeSession()
        return Res(0, f"Herdr session {name} started\n{self.wt_new_stdout}{path}\n", self.wt_new_stderr)

    def _herdr(self, argv):
        if argv[1:] == ["session", "list", "--json"]:
            base = "/home/x/.config/herdr/sessions"
            rows = [{"default": False, "name": n, "running": s.running,
                     "session_dir": self.session_dirs.get(n, f"{base}/{n}"),
                     "socket_path": f"{base}/{n}/herdr.sock"} for n, s in self.sessions.items()]
            return Res(0, json.dumps({"sessions": rows}))
        name, session = argv[2], self.sessions[argv[2]]
        key = tuple(argv[3:5])
        if (name, key) in self.fail:
            outcome = self.fail[(name, key)]
            if isinstance(outcome, Exception):
                raise outcome
            return outcome
        if key == ("tab", "list"):
            return Res(0, json.dumps({"id": "cli:tab:list", "result": {"tabs": session.tabs, "type": "tab_list"}}))
        if key == ("agent", "list"):
            return Res(0, json.dumps({"id": "c", "result": {"agents": session.agents, "type": "agent_list"}}))
        if key == ("pane", "list"):
            return Res(0, json.dumps({"id": "c", "result": {"panes": session.panes, "type": "pane_list"}}))
        if key == ("agent", "get"):
            row = session.get_row
            if row is None:
                row = next((a for a in session.agents if a["pane_id"] == argv[5]), None)
                if row is None:
                    return err("agent_not_found")
                row = dict(row)
                if session.get_status:
                    row["agent_status"] = session.get_status
            return Res(0, json.dumps({"id": "c", "result": {"agent": row, "type": "agent_info"}}))
        if key == ("agent", "read"):
            if "ansi" in argv:
                return Res(0, session.ansi)
            if "visible" in argv:
                return Res(0, session.visible)
            if session.deep_read_error:
                return session.deep_read_error
            return Res(0, session.output)
        if key == ("agent", "explain"):
            return Res(0, json.dumps({"agent": "amp", "state": session.explain_state}))
        if key == ("agent", "prompt"):
            if self.on_prompt:
                self.on_prompt()
            return self.prompt_result
        if key == ("agent", "send-keys"):
            if self.on_keys:
                self.on_keys()
            return Res(0, '{"result":{"type":"ok"}}')
        raise AssertionError(f"unexpected herdr call {argv}")


# Scratch repos made by the tests use no global or system git config, so the user's own settings never matter.
GIT_ENV = {**{k: v for k, v in os.environ.items() if not k.startswith("GIT_")}, "GIT_CONFIG_GLOBAL": os.devnull,
           "GIT_CONFIG_NOSYSTEM": "1", "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.com",
           "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@example.com", "GIT_TERMINAL_PROMPT": "0"}


def run_git(args, cwd=None):
    done = subprocess.run(["git", *args], capture_output=True, encoding="utf-8", errors="replace", cwd=cwd,
                          env=GIT_ENV, stdin=subprocess.DEVNULL, timeout=30)
    return Res(done.returncode, done.stdout, done.stderr)


def make_repo(path, origin=True, subject="first commit"):
    """A main checkout with one commit on main, and optionally a local stand-in for origin/HEAD."""
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    for args in (["init", "-q", "-b", "main", str(path)],
                 ["-C", str(path), "commit", "-q", "--allow-empty", "-m", subject]):
        assert run_git(args).returncode == 0, args
    if origin:
        for args in (["-C", str(path), "update-ref", "refs/remotes/origin/main", "HEAD"],
                     ["-C", str(path), "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main"]):
            assert run_git(args).returncode == 0, args
    return path


def commit_on(seed, branch, subject):
    """A new commit on `branch` in a seed clone, pushed to its origin. Returns the full sha."""
    for args in (["-C", str(seed), "checkout", "-q", branch],
                 ["-C", str(seed), "commit", "-q", "--allow-empty", "-m", subject],
                 ["-C", str(seed), "push", "-q", "origin", branch]):
        assert run_git(args).returncode == 0, args
    return run_git(["-C", str(seed), "rev-parse", branch]).stdout.strip()


def make_origin(home, dest, branches, head="main", checkout=None, origin_head=True):
    """A local bare repo standing in for origin, a seed clone that pushes to it, and a main checkout cloned from it at
    `dest`. Each branch has its own commit, "on <branch>", so every branch has its own sha. The bare repo and the seed
    sit under <home>/remotes, outside any repo root. Returns (main checkout, seed)."""
    dest = Path(dest)
    remotes = Path(home) / "remotes"
    bare, seed = remotes / f"{dest.name}.git", remotes / f"{dest.name}-seed"
    remotes.mkdir(parents=True, exist_ok=True)
    for args in (["init", "-q", "--bare", "-b", head, str(bare)], ["init", "-q", "-b", head, str(seed)],
                 ["-C", str(seed), "commit", "-q", "--allow-empty", "-m", "first commit"],
                 ["-C", str(seed), "remote", "add", "origin", str(bare)]):
        assert run_git(args).returncode == 0, args
    for branch in branches:
        if branch != head:
            assert run_git(["-C", str(seed), "branch", branch, head]).returncode == 0, branch
            assert run_git(["-C", str(seed), "checkout", "-q", branch]).returncode == 0, branch
            assert run_git(["-C", str(seed), "commit", "-q", "--allow-empty", "-m", f"on {branch}"]).returncode == 0
    assert run_git(["-C", str(seed), "push", "-q", "origin", "--all"]).returncode == 0
    dest.parent.mkdir(parents=True, exist_ok=True)
    assert run_git(["clone", "-q", str(bare), str(dest)]).returncode == 0
    if checkout:
        assert run_git(["-C", str(dest), "checkout", "-q", checkout]).returncode == 0
    if not origin_head:
        assert run_git(["-C", str(dest), "remote", "set-head", "origin", "-d"]).returncode == 0
    return dest, seed


MF_BRANCHES = ("master", "production-4.6.0", "production-4.6.1", "production-4.6.2", "production-4.6.3", "uat",
               "develop", "feature/login")


class FakeTailscale:
    def __init__(self):
        self.status = Res(0, STATUS_JSON)
        self.whois = Res(0, whois_json())

    def run(self, argv, timeout=15, cwd=None, extra_env=None, detach=False, input_text=None):
        if argv[:2] == ["tailscale", "status"]:
            return self.status
        if argv[:2] == ["tailscale", "whois"]:
            return self.whois
        raise AssertionError(argv)


CONFIG = g.Config(bind="127.0.0.1", port=0, tailnet_ip="100.96.1.2", allowed_hosts=ALLOWED, wt="wt")
PASSCODE = "correct horse"
RECORD = g.hash_passcode(PASSCODE, iterations=1000) if hasattr(g, "hash_passcode") else None


def build(runner=None, clock=None, config=CONFIG, tailscale=None, voice=None, interpreter=None, speech=None,
          speech_reason=None):
    runner = runner or FakeRunner()
    clock = clock or FakeClock()
    extra = {"voice": voice} if voice is not None else {}
    if interpreter is not None:
        extra["interpreter"] = interpreter
    if speech is not None:
        extra["speech"] = speech
    if speech_reason is not None:
        extra["speech_reason"] = speech_reason
    return g.Gateway.build(config, runner=runner, identity_runner=tailscale or FakeTailscale(), clock=clock,
                           passcode=RECORD, **extra)


# No test may reach the network: only loopback connections are let through, so Deepgram is never called for real.
_REAL_CREATE_CONNECTION = socket.create_connection


def _loopback_only(address, *args, **kwargs):
    if address[0] not in ("127.0.0.1", "localhost", "::1"):
        raise AssertionError(f"a test tried to connect to {address[0]}")
    return _REAL_CREATE_CONNECTION(address, *args, **kwargs)


# The real data dir must never get a remembered-base file from a test.
REAL_BASES_FILE = ROOT / "data" / "bases.json"
_REAL_BASES_BEFORE = None


def setUpModule():
    global _REAL_BASES_BEFORE
    socket.create_connection = _loopback_only
    _REAL_BASES_BEFORE = REAL_BASES_FILE.exists()


def tearDownModule():
    socket.create_connection = _REAL_CREATE_CONNECTION
    if not _REAL_BASES_BEFORE and REAL_BASES_FILE.exists():
        raise AssertionError(f"a test wrote {REAL_BASES_FILE}")


class GatewayCase(unittest.TestCase):
    def setUp(self):
        self.runner = FakeRunner()
        self.clock = FakeClock()
        self.app = build(self.runner, self.clock)

    def tearDown(self):
        self.assertEqual(self.runner.violations, [])

    def refused(self, status, fn, *args):
        with self.assertRaises(g.RequestRejected) as caught:
            fn(*args)
        self.assertEqual(caught.exception.status, status, caught.exception.message)
        return caught.exception.message

    def set_status(self, status, name=SCRATCH):
        self.runner.sessions[name].agents[0]["agent_status"] = status


# ---------------------------------------------------------------- pure functions

class ParseSessionsTests(unittest.TestCase):
    def listing(self, *rows):
        return json.dumps({"sessions": [
            {"name": n, "running": r, "session_dir": f"/s/{n}", "default": False, "socket_path": "x"}
            for n, r in rows]})

    def test_only_wt_sessions(self):
        text = self.listing(("default", True), ("phone", True), ("superset-a", True), ("resume-x", False),
                            ("wt-a", True), ("wt-b", False), ("wt--", True), ("xwt-c", True))
        self.assertEqual([(s.name, s.running) for s in g.parse_sessions(text)],
                         [("wt-a", True), ("wt-b", False), ("wt--", True)])

    def test_keeps_session_dir(self):
        self.assertEqual(g.parse_sessions(self.listing(("wt-a", False)))[0].session_dir, "/s/wt-a")

    def test_running_must_be_exactly_true(self):
        text = json.dumps({"sessions": [{"name": "wt-a", "running": "yes", "session_dir": "/s"}]})
        self.assertFalse(g.parse_sessions(text)[0].running)

    def test_odd_names_are_dropped(self):
        text = self.listing(("wt-a b", True), ("wt-a;rm", True), ("wt-" + "a" * 80, True))
        self.assertEqual(g.parse_sessions(text), [])

    def test_unreadable_output_raises(self):
        for text in ("", "not json", "[]", '{"sessions": 3}'):
            with self.assertRaises(g.HerdrError):
                g.parse_sessions(text)

    def test_hash_suffix_names_are_allowed(self):
        name = "wt-investor-agent-feat-voice-con-fefdb007"
        self.assertEqual(g.parse_sessions(self.listing((name, True)))[0].name, name)


class AttachedTests(unittest.TestCase):
    def test_same_rule_as_the_wt_script(self):
        ps = "\n".join([
            "herdr session attach wt-a",
            "/Users/x/.local/bin/herdr session attach wt-b --flag",
            "herdr --session wt-c server",
            "vim herdr session attach wt-d",
            "notherdr session attach wt-e",
            "herdr session list wt-f",
            "herdr session attach",
            "",
        ])
        self.assertEqual(g.attached_names(ps), {"wt-a", "wt-b"})


class GroupingTests(unittest.TestCase):
    def test_attached_running_stopped(self):
        sessions = [g.Session("wt-z", True, ""), g.Session("wt-a", True, ""), g.Session("wt-s", False, ""),
                    g.Session("wt-x", False, "")]
        groups = g.group_sessions(sessions, {"wt-z", "wt-x"})
        self.assertEqual([s.name for s in groups["attached"]], ["wt-z"])
        self.assertEqual([s.name for s in groups["running"]], ["wt-a"])
        # A stopped session cannot be attached: a stale match must not hide it.
        self.assertEqual([s.name for s in groups["stopped"]], ["wt-s", "wt-x"])


class CleanTextTests(unittest.TestCase):
    def test_keeps_newlines_and_unicode(self):
        self.assertEqual(g.clean_text("hello\nwörld"), "hello\nwörld")

    def test_strips_control_characters(self):
        self.assertEqual(g.clean_text("a\x00b\x1b[31mc\x07d\x7fe\x85f\tg"), "ab[31mcdefg")

    def test_carriage_returns_become_newlines(self):
        self.assertEqual(g.clean_text("a\r\nb\rc"), "a\nb\nc")

    def test_lone_surrogates_are_removed(self):
        self.assertEqual(g.clean_text("a\ud800b"), "ab")

    def test_trims_outer_whitespace(self):
        self.assertEqual(g.clean_text("  \n hi \n "), "hi")


class FlagSafeTests(unittest.TestCase):
    def test_ordinary_text_is_untouched(self):
        self.assertEqual(g.flag_safe("what is 2 - 1"), "what is 2 - 1")

    def test_leading_dash_cannot_look_like_a_flag(self):
        for text in ("--wait", "-x", "--", "- item"):
            self.assertFalse(g.flag_safe(text).startswith("-"))
            self.assertEqual(g.flag_safe(text).strip(), text)


class OrchestratorTests(unittest.TestCase):
    def test_label_must_match_exactly(self):
        tabs = [tab_row("w1:t1", "Orchestrator"), tab_row("w1:t2", "orchestrator-2"), tab_row("w1:t3", " orchestrator")]
        self.assertEqual(g.find_orchestrators(tabs, [agent_row(tab="w1:t1"), agent_row("w1:p2", "w1:t2")]), [])

    def test_finds_the_agent_in_the_tab(self):
        tabs = [tab_row("w1:t1"), tab_row("w1:t2", "task")]
        agents = [agent_row("w1:p2", "w1:t2", name="worker"), agent_row("w1:p1", "w1:t1")]
        self.assertEqual([a["pane_id"] for a in g.find_orchestrators(tabs, agents)], ["w1:p1"])

    def test_several(self):
        tabs = [tab_row("w1:t1"), tab_row("w2:t1")]
        agents = [agent_row("w1:p1", "w1:t1"), agent_row("w2:p1", "w2:t1")]
        self.assertEqual(len(g.find_orchestrators(tabs, agents)), 2)

    def test_row_without_an_agent_does_not_count(self):
        row = agent_row()
        row.pop("agent")
        self.assertEqual(g.find_orchestrators([tab_row()], [row]), [])


class StopKeyTests(unittest.TestCase):
    def test_per_kind(self):
        self.assertEqual(g.stop_keys_for("claude"), ("esc",))
        self.assertEqual(g.stop_keys_for("codex"), ("esc",))
        self.assertEqual(g.stop_keys_for("amp"), ("esc", "esc"))
        self.assertEqual(g.stop_keys_for("anything"), ("esc",))


class WorktreeCandidateTests(unittest.TestCase):
    def test_reads_identity_cwd(self):
        text = json.dumps({"version": 3, "workspaces": [
            {"identity_cwd": "/a/b", "tabs": []}, {"identity_cwd": "/a/b"}, {"identity_cwd": "/c"}]})
        self.assertEqual(g.worktree_candidates(text), ["/a/b", "/c"])

    def test_only_absolute_paths(self):
        text = json.dumps({"workspaces": [{"identity_cwd": "rel"}, {"identity_cwd": 3}, {}, {"identity_cwd": ""}]})
        self.assertEqual(g.worktree_candidates(text), [])

    def test_unreadable(self):
        for text in ("", "nope", "[]", '{"workspaces": 1}', '{"workspaces": [1]}'):
            self.assertEqual(g.worktree_candidates(text), [])


class OtherUrlTests(unittest.TestCase):
    def test_good(self):
        for value in ("https://sam-nix.tail1234.ts.net", "https://sam-nix.tail1234.ts.net/",
                      "https://sam-nix.tail1234.ts.net:8443/"):
            self.assertTrue(g.valid_other_url(value), value)

    def test_bad(self):
        for value in ("http://sam-nix.tail1234.ts.net", "https://example.com", "https://ts.net", "javascript:alert(1)",
                      "https://evil.com/.ts.net", "https://user:pw@a.ts.net", "https://a.ts.net.evil.com",
                      "https://a.ts.net/?x=1", "https://a.ts.net/#x", " https://a.ts.net", "https://a b.ts.net",
                      "https://a.ts.net\\@evil.com", "", None, 3):
            self.assertFalse(g.valid_other_url(value), value)


class ChildEnvTests(unittest.TestCase):
    def test_removes_what_differs_from_real_wt_servers(self):
        env = {"PATH": "/bin", "HOME": "/h", "CLAUDE_CONFIG_DIR": "/x", "HERDR_ENV": "1", "HERDR_SESSION": "s",
               "VOICE_GATEWAY_PORT": "1", "VOICE_GATEWAY_RESTART_CLAUDE_CONFIG_DIR": "/y"}
        self.assertEqual(g.child_env(env), {"PATH": "/bin", "HOME": "/h"})

    def test_removes_the_markers_of_a_claude_session_the_gateway_was_started_from(self):
        # Found live: CLAUDE_CODE_CHILD_SESSION reached a restarted session and turned off Claude's transcript saving.
        env = {"PATH": "/bin", "CLAUDECODE": "1", "CLAUDE_CODE_CHILD_SESSION": "1", "CLAUDE_CODE_SESSION_ID": "x",
               "CLAUDE_CODE_MESSAGING_TOKEN": "x", "CLAUDE_PID": "1", "CLAUDE_EFFORT": "x"}
        self.assertEqual(g.child_env(env), {"PATH": "/bin"})

    def test_explicit_extra_only(self):
        self.assertEqual(g.child_env({"PATH": "/bin"}, {"CLAUDE_CONFIG_DIR": "/y"}),
                         {"PATH": "/bin", "CLAUDE_CONFIG_DIR": "/y"})

    def test_no_self_update_switches_any_more(self):
        self.assertFalse(hasattr(g, "NO_SELF_UPDATE_ENV"))
        self.assertEqual(g.child_env({}), {})

    def test_real_runner_applies_it(self):
        code = "import json,os;print(json.dumps(sorted(os.environ)))"
        leaked = {"CLAUDE_CONFIG_DIR": "/x", "HERDR_SOCKET_PATH": "/s", "VOICE_GATEWAY_DATA": "/d"}
        with unittest.mock.patch.dict(os.environ, leaked):
            names = json.loads(g.CommandRunner().run([sys.executable, "-c", code]).stdout)
            extra = json.loads(g.CommandRunner().run([sys.executable, "-c", code],
                                                     extra_env={"CLAUDE_CONFIG_DIR": "/y"}).stdout)
        self.assertNotIn("CLAUDE_CONFIG_DIR", names)
        self.assertFalse([n for n in names if n.startswith(("HERDR_", "VOICE_GATEWAY_"))])
        self.assertIn("CLAUDE_CONFIG_DIR", extra)

    def test_wt_editor_is_passed_through_untouched(self):
        # The user wants `wt new` to open its default editor (a Zed window on the Mac): WT_EDITOR is never touched.
        self.assertEqual(g.child_env({"WT_EDITOR": "zed", "PATH": "/bin"}), {"WT_EDITOR": "zed", "PATH": "/bin"})
        self.assertEqual(g.child_env({"PATH": "/bin"}), {"PATH": "/bin"})
        code = "import os;print(os.environ.get('WT_EDITOR', '<unset>'))"
        for value in ("zed", "none", "nvim -u NONE"):
            with unittest.mock.patch.dict(os.environ, {"WT_EDITOR": value}):
                out = g.CommandRunner().run([sys.executable, "-c", code], detach=True).stdout.strip()
            self.assertEqual(out, value)
        with unittest.mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("WT_EDITOR", None)
            self.assertEqual(g.CommandRunner().run([sys.executable, "-c", code]).stdout.strip(), "<unset>")

    def test_real_runner_passes_input_on_stdin(self):
        code = "import sys;print(sys.stdin.read()[::-1])"
        self.assertEqual(g.CommandRunner().run([sys.executable, "-c", code], input_text="--flag-like text").stdout,
                         "txet ekil-galf--\n")
        self.assertEqual(g.CommandRunner().run([sys.executable, "-c", code]).stdout, "\n")  # No input: empty.

    def test_real_runner_cwd_and_detach(self):
        code = "import os;print(os.getcwd(), os.getsid(0) == os.getpid())"
        with tempfile.TemporaryDirectory() as tmp:
            out = g.CommandRunner().run([sys.executable, "-c", code], cwd=tmp, detach=True).stdout.split()
            self.assertEqual(os.path.realpath(out[0]), os.path.realpath(tmp))
            self.assertEqual(out[1], "True")


class DetachedOutputTests(unittest.TestCase):
    def test_a_background_child_holding_stdout_does_not_hang_the_run(self):
        # `wt up` leaves a shell behind with its stdout open for as long as the Herdr server lives.
        started = time.time()
        result = g.CommandRunner().run(["/bin/sh", "-c", "sleep 4 & echo started; echo oops >&2"], timeout=3, detach=True)
        self.assertLess(time.time() - started, 2)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), "started")
        self.assertEqual(result.stderr.strip(), "oops")


class HostOriginTests(unittest.TestCase):
    def test_host(self):
        self.assertTrue(g.host_allowed("mac.tail1234.ts.net", ALLOWED, 8766))
        self.assertTrue(g.host_allowed("MAC:8766", ALLOWED, 8766))
        self.assertFalse(g.host_allowed("mac:9", ALLOWED, 8766))
        self.assertFalse(g.host_allowed("evil.com", ALLOWED, 8766))
        self.assertFalse(g.host_allowed(None, ALLOWED, 8766))

    def test_origin(self):
        self.assertTrue(g.origin_allowed("https://mac.tail1234.ts.net", ALLOWED, 8766))
        self.assertTrue(g.origin_allowed("http://mac:8766", ALLOWED, 8766))
        self.assertFalse(g.origin_allowed("null", ALLOWED, 8766))
        self.assertFalse(g.origin_allowed("https://evil.com", ALLOWED, 8766))
        self.assertFalse(g.origin_allowed(None, ALLOWED, 8766))

    def test_bind(self):
        self.assertTrue(g.validate_bind("127.0.0.1", "100.96.1.2"))
        self.assertTrue(g.validate_bind("100.96.1.2", "100.96.1.2"))
        self.assertFalse(g.validate_bind("0.0.0.0", "100.96.1.2"))


# ---------------------------------------------------------------- identity

class IdentityTests(unittest.TestCase):
    def setUp(self):
        self.tailscale = FakeTailscale()
        self.clock = FakeClock()
        self.identity = g.Identity(self.tailscale, self.clock)

    def test_owner_node(self):
        self.assertTrue(self.identity.allow("100.96.1.9", {}))

    def test_other_user(self):
        self.tailscale.whois = Res(0, whois_json(user_id=99))
        self.assertFalse(self.identity.allow("100.96.1.9", {}))

    def test_tagged_node(self):
        self.tailscale.whois = Res(0, whois_json(tags=["tag:x"]))
        self.assertFalse(self.identity.allow("100.96.1.9", {}))

    def test_errors_deny(self):
        self.tailscale.whois = Res(1, "", "boom")
        self.assertFalse(self.identity.allow("100.96.1.9", {}))
        self.tailscale.whois = Res(0, "not json")
        self.assertFalse(self.identity.allow("100.96.1.8", {}))

    def test_bad_address_denies(self):
        self.assertFalse(self.identity.allow("nonsense", {}))

    def test_loopback_needs_the_owner_header(self):
        self.assertFalse(self.identity.allow("127.0.0.1", {}))
        self.assertFalse(self.identity.allow("127.0.0.1", {"Tailscale-User-Login": "someone@else"}))
        self.assertTrue(self.identity.allow("127.0.0.1", {"Tailscale-User-Login": LOGIN}))


class MachineTests(unittest.TestCase):
    def status(self, **changes):
        return {**json.loads(STATUS_JSON), **changes}

    def test_reads_address_and_names(self):
        machine = g.machine_from_status(self.status(), {})
        self.assertEqual(machine.tailnet_ip, "100.96.1.2")
        self.assertIn("mac.tail1234.ts.net", machine.allowed_hosts)
        self.assertIn("127.0.0.1", machine.allowed_hosts)

    def test_fails_closed(self):
        for status in ("x", self.status(BackendState="Stopped"), self.status(Self=None)):
            with self.assertRaises(g.MachineError):
                g.machine_from_status(status, {})

    def test_hosts_override(self):
        machine = g.machine_from_status(self.status(), {"VOICE_GATEWAY_HOSTS": "a,b.c"})
        self.assertIn("b.c", machine.allowed_hosts)
        self.assertNotIn("mac", machine.allowed_hosts)


# ---------------------------------------------------------------- list

class ListTests(GatewayCase):
    def setUp(self):
        super().setUp()
        self.runner.sessions = {
            "default": FakeSession(),
            "phone": FakeSession(),
            "superset-x": FakeSession(),
            "wt-attached": FakeSession(),
            "wt-running": FakeSession(
                tabs=[tab_row(), tab_row("w1:t2", "task")],
                agents=[agent_row(), agent_row("w1:p2", "w1:t2", "kiro", "working", name="worker")],
                panes=[pane_row(), pane_row("w1:p2", "w1:t2", "kiro", "working", label="workers")]),
            "wt-stopped": FakeSession(running=False),
        }
        self.runner.ps_output = "herdr session attach wt-attached\nherdr --session wt-running server\n"

    def test_groups(self):
        view = self.app.sessions_view()
        self.assertEqual([s["name"] for s in view["attached"]], ["wt-attached"])
        self.assertEqual([s["name"] for s in view["running"]], ["wt-running"])
        self.assertEqual([s["name"] for s in view["stopped"]], ["wt-stopped"])

    def test_ps_uses_the_args_column(self):
        self.app.sessions_view()
        self.assertIn(["ps", "-axo", "args="], [c["argv"] for c in self.runner.calls])

    def test_agents_with_name_or_kind_pane_label_and_state(self):
        agents = self.app.sessions_view()["running"][0]["agents"]
        self.assertEqual(agents, [
            {"name": "claude", "kind": "claude", "pane_label": "", "status": "idle", "orchestrator": True},
            {"name": "worker", "kind": "kiro", "pane_label": "workers", "status": "working", "orchestrator": False},
        ])

    def test_titles_and_paths_are_not_passed_on(self):
        text = json.dumps(self.app.sessions_view())
        self.assertNotIn("private title", text)
        self.assertNotIn("/somewhere", text)

    def test_no_command_against_stopped_or_other_sessions(self):
        self.app.sessions_view()
        targets = {c["argv"][2] for c in self.runner.calls if c["argv"][:2] == ["herdr", "--session"]}
        self.assertEqual(targets, {"wt-attached", "wt-running"})

    def test_list_does_not_read_any_pane(self):
        self.app.sessions_view()
        self.assertEqual(self.runner.named("agent", "read"), [])

    def test_one_broken_session_does_not_break_the_list(self):
        self.runner.fail[("wt-running", ("agent", "list"))] = err("server_not_running")
        view = self.app.sessions_view()
        row = view["running"][0]
        self.assertEqual(row["agents"], [])
        self.assertTrue(row["error"])
        self.assertEqual([s["name"] for s in view["attached"]], ["wt-attached"])

    def test_failing_ps_only_loses_the_attached_label(self):
        self.runner.ps_fails = True
        view = self.app.sessions_view()
        self.assertEqual(view["attached"], [])
        self.assertEqual({s["name"] for s in view["running"]}, {"wt-attached", "wt-running"})

    def test_herdr_failure_is_reported(self):
        self.runner.run = lambda *a, **k: Res(1, "", "boom")
        self.refused(502, self.app.sessions_view)

    def test_other_url_is_passed_to_the_page(self):
        self.assertIsNone(self.app.sessions_view()["other_url"])
        app = build(self.runner, self.clock, replace(CONFIG, other_url="https://nix.tail1.ts.net"))
        self.assertEqual(app.sessions_view()["other_url"], "https://nix.tail1.ts.net")


# ---------------------------------------------------------------- detail

class DetailTests(GatewayCase):
    def test_output_of_the_orchestrator(self):
        view = self.app.session_view(SCRATCH)
        self.assertEqual(view["output"], "orchestrator output")
        read = self.runner.named("agent", "read")[0]["argv"]
        self.assertEqual(read, ["herdr", "--session", SCRATCH, "agent", "read", "w1:p1", "--source",
                                "recent-unwrapped", "--lines", "40"])

    def read_lines(self):
        reads = [c["argv"] for c in self.runner.named("agent", "read") if "ansi" not in c["argv"]]
        return [argv[argv.index("--lines") + 1] for argv in reads]

    def test_polling_never_reads_past_the_visible_screen(self):
        # Reading further makes Herdr scroll the agent's own screen. Found live: an attached client got a 10 KB redraw
        # for every such read, and none for a read within the screen.
        self.runner.sessions[SCRATCH].panes = [pane_row(rows=23)]
        self.app.session_view(SCRATCH)
        self.assertEqual(self.read_lines(), ["23"])

    def test_viewport_unknown_uses_a_small_read(self):
        row = pane_row()
        del row["scroll"]
        self.runner.sessions[SCRATCH].panes = [row]
        self.app.session_view(SCRATCH)
        self.assertEqual(self.read_lines(), [str(g.FALLBACK_READ_LINES)])

    def test_bad_viewport_values_use_a_small_read(self):
        for bad in (0, -1, "40", True, None):
            row = pane_row()
            row["scroll"]["viewport_rows"] = bad
            self.runner.sessions[SCRATCH].panes = [row]
            self.runner.calls.clear()
            self.app.session_view(SCRATCH)
            self.assertEqual(self.read_lines(), [str(g.FALLBACK_READ_LINES)], bad)

    def test_earlier_output_is_a_deep_read_when_settled(self):
        for status in ("idle", "done"):
            self.set_status(status)
            self.runner.calls.clear()
            self.app.session_view(SCRATCH, earlier=True)
            self.assertEqual(self.read_lines(), [str(g.EARLIER_READ_LINES)])

    def test_earlier_output_stays_on_screen_otherwise(self):
        for status in ("working", "blocked", "unknown"):
            self.set_status(status)
            self.runner.calls.clear()
            self.app.session_view(SCRATCH, earlier=True)
            self.assertEqual(self.read_lines(), ["40"], status)

    def test_falls_back_to_the_visible_screen(self):
        # Real answer when the state changes during a deep read.
        self.runner.sessions[SCRATCH].deep_read_error = err("agent_not_idle", "cannot read while blocked")
        view = self.app.session_view(SCRATCH, earlier=True)
        self.assertEqual(view["output"], "visible screen")
        self.assertEqual(view["problem"], "")

    def test_restored_agent_not_yet_seen_is_unknown(self):
        # Found live: for over a second after a restart Herdr says idle with a shell still in the foreground.
        self.runner.sessions[SCRATCH].agents[0]["state_change_seq"] = 0
        view = self.app.session_view(SCRATCH)
        self.assertEqual(view["orchestrator"]["status"], "unknown")
        self.assertFalse(view["actions"]["prompt"])

    def test_pane_ids_are_not_given_to_the_page(self):
        self.assertNotIn("w1:p1", json.dumps(self.app.session_view(SCRATCH)))

    def test_only_the_orchestrator_is_read(self):
        self.runner.sessions[SCRATCH] = FakeSession(
            tabs=[tab_row(), tab_row("w1:t2", "task")],
            agents=[agent_row("w1:p2", "w1:t2", name="worker"), agent_row()],
            panes=[pane_row(), pane_row("w1:p2", "w1:t2", label="x")])
        view = self.app.session_view(SCRATCH)
        self.assertEqual(len(view["agents"]), 2)
        panes = {c["argv"][5] for c in self.runner.named("agent", "read")}
        self.assertEqual(panes, {"w1:p1"})

    def actions(self, status):
        self.set_status(status)
        return self.app.session_view(SCRATCH)["actions"]

    def test_actions_when_idle_or_done(self):
        for status in ("idle", "done"):
            self.assertEqual(self.actions(status), {"prompt": True, "keys": [], "stop": False, "restart": False})

    def test_actions_when_blocked(self):
        self.assertEqual(self.actions("blocked"), {"prompt": False, "keys": ["1", "2", "up", "down", "enter", "esc"],
                                                   "stop": False, "restart": False})

    def test_actions_when_working(self):
        self.assertEqual(self.actions("working"), {"prompt": False, "keys": [], "stop": True, "restart": False})

    def test_actions_when_unknown(self):
        self.assertEqual(self.actions("unknown"), {"prompt": False, "keys": [], "stop": False, "restart": False})

    def test_no_orchestrator(self):
        self.runner.sessions[SCRATCH] = FakeSession(agents=[], panes=[pane_row(kind=None)])
        view = self.app.session_view(SCRATCH)
        self.assertIsNone(view["orchestrator"])
        self.assertTrue(view["problem"])
        self.assertEqual(view["actions"], {"prompt": False, "keys": [], "stop": False, "restart": False})
        self.assertEqual(self.runner.named("agent", "read"), [])

    def test_several_orchestrators(self):
        self.runner.sessions[SCRATCH] = FakeSession(
            tabs=[tab_row(), tab_row("w2:t1")],
            agents=[agent_row(), agent_row("w2:p1", "w2:t1")], panes=[])
        view = self.app.session_view(SCRATCH)
        self.assertIsNone(view["orchestrator"])
        self.assertIn("everal", view["problem"])
        self.assertEqual(self.runner.named("agent", "read"), [])

    def test_stopped_runs_no_session_command(self):
        self.runner.sessions[SCRATCH].running = False
        view = self.app.session_view(SCRATCH)
        self.assertEqual(view["state"], "stopped")
        self.assertEqual(view["actions"], {"prompt": False, "keys": [], "stop": False, "restart": True})
        self.assertEqual([c for c in self.runner.calls if c["argv"][:2] == ["herdr", "--session"]], [])

    def test_attached_state(self):
        self.runner.ps_output = f"herdr session attach {SCRATCH}\n"
        self.assertEqual(self.app.session_view(SCRATCH)["state"], "attached")

    def test_unknown_or_foreign_names_are_refused(self):
        self.runner.sessions.update({"default": FakeSession(), "phone": FakeSession(), "superset-x": FakeSession()})
        for name in ("default", "phone", "superset-x", "wt-absent", "", None, "../etc", "wt-a b"):
            self.refused(404, self.app.session_view, name)
        self.assertEqual([c for c in self.runner.calls if c["argv"][:2] == ["herdr", "--session"]], [])

    def test_output_is_capped(self):
        self.runner.sessions[SCRATCH].output = "x" * (g.MAX_OUTPUT_CHARS + 50)
        self.assertEqual(len(self.app.session_view(SCRATCH)["output"]), g.MAX_OUTPUT_CHARS)

    def test_read_failure_is_a_problem_not_an_error(self):
        self.runner.fail[(SCRATCH, ("agent", "read"))] = err("agent_not_found")
        view = self.app.session_view(SCRATCH)
        self.assertEqual(view["output"], "")
        self.assertTrue(view["problem"])

    def test_kiro_dialog_counts_as_blocked(self):
        session = self.runner.sessions[SCRATCH]
        session.agents = [agent_row(kind="kiro")]
        session.visible = "stuff\n  Allow once\n  Deny once\n"
        view = self.app.session_view(SCRATCH)
        self.assertEqual(view["orchestrator"]["status"], "blocked")
        self.assertFalse(view["actions"]["prompt"])

    def test_amp_working_screen_counts_as_working(self):
        session = self.runner.sessions[SCRATCH]
        session.agents = [agent_row(kind="amp")]
        session.explain_state = "working"
        self.assertEqual(self.app.session_view(SCRATCH)["orchestrator"]["status"], "working")


# ---------------------------------------------------------------- prompt

class PromptTests(GatewayCase):
    def send(self, text="hello", session=SCRATCH, **extra):
        return self.app.prompt({"session": session, "text": text, **extra})

    def prompts(self):
        return [c["argv"] for c in self.runner.named("agent", "prompt")]

    def test_exact_command(self):
        self.send("/hello there")
        self.assertEqual(self.prompts(), [["herdr", "--session", SCRATCH, "agent", "prompt", "w1:p1", "/hello there"]])

    def test_idle_and_done_are_sent(self):
        self.set_status("done")
        self.send()
        self.assertEqual(len(self.prompts()), 1)

    def test_other_states_are_refused(self):
        for status in ("working", "blocked", "unknown"):
            self.set_status(status)
            self.assertIn(status, self.refused(409, self.send))
        self.assertEqual(self.prompts(), [])

    def test_missing_status_is_unknown(self):
        del self.runner.sessions[SCRATCH].agents[0]["agent_status"]
        self.refused(409, self.send)

    def test_restored_agent_not_yet_seen_is_refused(self):
        self.runner.sessions[SCRATCH].agents[0]["state_change_seq"] = 0
        self.assertIn("unknown", self.refused(409, self.send))
        self.assertEqual(self.prompts(), [])

    def test_recheck_also_sees_a_restored_agent(self):
        row = agent_row()
        row["state_change_seq"] = 0
        self.runner.sessions[SCRATCH].get_row = row
        self.refused(409, self.send)
        self.assertEqual(self.prompts(), [])

    def test_a_listing_without_the_counter_is_not_refused(self):
        del self.runner.sessions[SCRATCH].agents[0]["state_change_seq"]
        self.send()
        self.assertEqual(len(self.prompts()), 1)

    def test_recheck_right_before_sending(self):
        self.runner.sessions[SCRATCH].get_status = "working"
        self.refused(409, self.send)
        self.assertEqual(self.prompts(), [])
        verbs = [c["argv"][3:5] for c in self.runner.calls if c["argv"][:2] == ["herdr", "--session"]]
        self.assertEqual(verbs[-1], ["agent", "get"])

    def test_refuses_if_the_pane_changed_occupant(self):
        self.runner.sessions[SCRATCH].get_row = agent_row(terminal="term_other")
        self.refused(409, self.send)
        self.runner.sessions[SCRATCH].get_row = agent_row(kind="codex")
        self.refused(409, self.send)
        self.assertEqual(self.prompts(), [])

    def test_no_agent(self):
        self.runner.sessions[SCRATCH].agents = []
        self.refused(409, self.send)

    def test_agent_only_outside_the_orchestrator_tab(self):
        self.runner.sessions[SCRATCH] = FakeSession(
            tabs=[tab_row(), tab_row("w1:t2", "task")], agents=[agent_row("w1:p2", "w1:t2")], panes=[])
        self.refused(409, self.send)
        self.assertEqual(self.prompts(), [])

    def test_several_orchestrators(self):
        self.runner.sessions[SCRATCH] = FakeSession(
            tabs=[tab_row()], agents=[agent_row(), agent_row("w1:p2", "w1:t1")], panes=[])
        self.refused(409, self.send)
        self.assertEqual(self.prompts(), [])

    def test_orchestrator_is_listed_fresh_every_time(self):
        self.send()
        self.clock.t += 100
        self.runner.sessions[SCRATCH].tabs = [tab_row("w1:t9")]
        self.runner.sessions[SCRATCH].agents = [agent_row("w1:p9", "w1:t9")]
        self.send()
        self.assertEqual([p[5] for p in self.prompts()], ["w1:p1", "w1:p9"])

    def test_client_cannot_choose_the_pane(self):
        self.runner.sessions[SCRATCH] = FakeSession(
            tabs=[tab_row(), tab_row("w1:t2", "task")],
            agents=[agent_row(), agent_row("w1:p2", "w1:t2", name="worker")], panes=[])
        self.send(pane="w1:p2", pane_id="w1:p2", target="w1:p2", agent="worker")
        self.assertEqual([p[5] for p in self.prompts()], ["w1:p1"])

    def test_session_must_be_wt_and_listed(self):
        self.runner.sessions.update({"default": FakeSession(), "phone": FakeSession(), "superset-x": FakeSession()})
        for name in ("default", "phone", "superset-x", "wt-absent", "", None, 3, ["wt-scratch"], "--help",
                     "wt-scratch --x"):
            self.refused(404, self.send, "hi", name)
        self.assertEqual(self.prompts(), [])

    def test_stopped_session(self):
        self.runner.sessions[SCRATCH].running = False
        self.refused(409, self.send)
        self.assertEqual([c for c in self.runner.calls if c["argv"][:2] == ["herdr", "--session"]], [])

    def test_text_is_validated(self):
        for text in (None, 3, ["x"], "", "   \n ", "\x00\x01"):
            self.refused(400, self.send, text)
        self.assertEqual(self.prompts(), [])

    def test_length_cap(self):
        self.refused(400, self.send, "x" * (g.MAX_PROMPT_CHARS + 1))
        self.send("x" * g.MAX_PROMPT_CHARS)
        self.assertEqual(len(self.prompts()), 1)

    def test_control_characters_are_stripped(self):
        self.send("a\x1b[2Jb\nc\x03")
        self.assertTrue(self.prompts()[0][-1].startswith("a[2Jb\nc "))

    def test_dash_text_is_one_safe_argument(self):
        self.send("--wait now")
        argv = self.prompts()[0]
        self.assertEqual(argv[5:6], ["w1:p1"])
        self.assertEqual(len(argv), 7)
        self.assertTrue(argv[6].startswith(" --wait now "))
        self.assertNotIn("--", argv)

    def test_double_tap_while_sending_sends_once(self):
        entered, release = threading.Event(), threading.Event()

        def hold():
            entered.set()
            release.wait(5)
        self.runner.on_prompt = hold
        results = []
        first = threading.Thread(target=lambda: results.append(self.send()))
        first.start()
        self.assertTrue(entered.wait(5))
        self.refused(409, self.send)
        release.set()
        first.join(5)
        self.assertEqual(len(self.prompts()), 1)
        self.assertEqual(len(results), 1)

    def test_double_tap_after_the_first_returned_sends_once(self):
        # Herdr can still report idle just after submission, so the lock alone is not enough.
        self.send()
        self.clock.t += g.PROMPT_REPEAT_SECONDS - 0.1
        self.refused(409, self.send)
        self.assertEqual(len(self.prompts()), 1)
        self.clock.t += 0.2
        self.send()
        self.assertEqual(len(self.prompts()), 2)

    def test_locks_are_per_session(self):
        self.runner.sessions["wt-other"] = FakeSession()
        self.send()
        self.send(session="wt-other")
        self.assertEqual(len(self.prompts()), 2)

    def test_timeout_says_it_may_have_arrived(self):
        self.runner.fail[(SCRATCH, ("agent", "prompt"))] = subprocess.TimeoutExpired("herdr", 15)
        message = self.refused(502, self.send)
        self.assertIn("may", message)
        self.clock.t += 0.1
        self.runner.fail.clear()
        self.refused(409, self.send)

    def test_lock_is_released_after_a_failure(self):
        self.set_status("working")
        self.refused(409, self.send)
        self.set_status("idle")
        self.send()
        self.assertEqual(len(self.prompts()), 1)

    def test_herdr_blocked_answer_is_a_refusal(self):
        self.runner.prompt_result = err("agent_blocked", "agent is blocked")
        self.refused(409, self.send)

    def test_errors_do_not_carry_the_text(self):
        self.runner.prompt_result = err("something", "failed")
        self.assertNotIn("secret words", self.refused(502, self.send, "secret words"))

    def test_kiro_dialog_is_refused(self):
        session = self.runner.sessions[SCRATCH]
        session.agents = [agent_row(kind="kiro")]
        session.visible = "  Allow once\n  Deny once\n"
        self.refused(409, self.send)
        self.assertEqual(self.prompts(), [])

    def test_amp_turn_is_refused(self):
        session = self.runner.sessions[SCRATCH]
        session.agents = [agent_row(kind="amp")]
        session.explain_state = "working"
        self.refused(409, self.send)
        self.assertEqual(self.prompts(), [])

    def test_odd_pane_ids_from_herdr_are_refused(self):
        self.runner.sessions[SCRATCH].agents = [agent_row(pane="--wait")]
        self.refused(409, self.send)
        self.assertEqual(self.prompts(), [])


# ---------------------------------------------------------------- keys and stop

class KeysTests(GatewayCase):
    def press(self, key="1", session=SCRATCH, **extra):
        return self.app.keys({"session": session, "key": key, "screen_token": g.screen_token("visible screen"), **extra})

    def sent(self):
        return [c["argv"] for c in self.runner.named("agent", "send-keys")]

    def test_allowlist_when_blocked(self):
        self.set_status("blocked")
        for key in ("1", "2", "up", "down", "enter", "esc"):
            self.clock.t += 60
            self.press(key)
        self.assertEqual([a[6:] for a in self.sent()], [["1"], ["2"], ["up"], ["down"], ["enter"], ["esc"]])
        self.assertEqual(self.sent()[0][:6], ["herdr", "--session", SCRATCH, "agent", "send-keys", "w1:p1"])

    def test_other_keys_refused(self):
        self.set_status("blocked")
        for key in ("3", "ctrl+c", "tab", "y", "ENTER", "enter esc", "--help", "", None, 1, ["1"]):
            self.refused(400, self.press, key)
        self.assertEqual(self.sent(), [])

    def test_only_when_blocked(self):
        for status in ("idle", "done", "working", "unknown"):
            self.set_status(status)
            self.refused(409, self.press)
        self.assertEqual(self.sent(), [])

    def test_recheck_right_before_sending(self):
        self.set_status("blocked")
        self.runner.sessions[SCRATCH].get_status = "idle"
        self.refused(409, self.press)
        self.assertEqual(self.sent(), [])

    def test_never_to_another_pane(self):
        self.runner.sessions[SCRATCH] = FakeSession(
            tabs=[tab_row(), tab_row("w1:t2", "task")],
            agents=[agent_row(status="blocked"), agent_row("w1:p2", "w1:t2", status="blocked", name="w")], panes=[])
        self.press("1", pane="w1:p2")
        self.assertEqual([a[5] for a in self.sent()], ["w1:p1"])

    def test_refused_when_only_another_agent_is_blocked(self):
        self.runner.sessions[SCRATCH] = FakeSession(
            tabs=[tab_row(), tab_row("w1:t2", "task")],
            agents=[agent_row(), agent_row("w1:p2", "w1:t2", status="blocked", name="w")], panes=[])
        self.refused(409, self.press)
        self.assertEqual(self.sent(), [])

    def test_foreign_session(self):
        self.runner.sessions["phone"] = FakeSession(agents=[agent_row(status="blocked")])
        self.refused(404, self.press, "1", "phone")

    def test_double_tap_of_a_choice_sends_once(self):
        self.set_status("blocked")
        self.press("enter")
        self.clock.t += 0.1
        self.refused(409, self.press, "enter")
        self.assertEqual(len(self.sent()), 1)

    def test_arrow_keys_can_be_tapped_quickly(self):
        self.set_status("blocked")
        self.press("down")
        self.clock.t += g.NAVIGATE_REPEAT_SECONDS + 0.01
        self.press("down")
        self.assertEqual(len(self.sent()), 2)


class StopTests(GatewayCase):
    def stop(self, session=SCRATCH, **extra):
        return self.app.stop({"session": session, **extra})

    def sent(self):
        return [c["argv"] for c in self.runner.named("agent", "send-keys")]

    def test_only_when_working(self):
        for status in ("idle", "done", "blocked", "unknown"):
            self.set_status(status)
            self.refused(409, self.stop)
        self.assertEqual(self.sent(), [])

    def test_claude_stop_key(self):
        self.set_status("working")
        self.stop()
        self.assertEqual(self.sent(), [["herdr", "--session", SCRATCH, "agent", "send-keys", "w1:p1", "esc"]])

    def test_amp_stop_keys(self):
        self.runner.sessions[SCRATCH].agents = [agent_row(kind="amp", status="working")]
        self.stop()
        self.assertEqual(self.sent()[0][6:], ["esc", "esc"])

    def test_recheck(self):
        self.set_status("working")
        self.runner.sessions[SCRATCH].get_status = "idle"
        self.refused(409, self.stop)
        self.assertEqual(self.sent(), [])

    def test_double_tap(self):
        self.set_status("working")
        self.stop()
        self.clock.t += 0.1
        self.refused(409, self.stop)
        self.assertEqual(len(self.sent()), 1)

    def test_tap_again_later_works(self):
        self.set_status("working")
        self.stop()
        self.clock.t += g.ACTION_REPEAT_SECONDS + 0.1
        self.stop()
        self.assertEqual(len(self.sent()), 2)

    def test_foreign_session(self):
        self.runner.sessions["default"] = FakeSession(agents=[agent_row(status="working")])
        self.refused(404, self.stop, "default")


# ---------------------------------------------------------------- restart

class RestartTests(GatewayCase):
    def setUp(self):
        super().setUp()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.sessions_dir = Path(self.tmp.name) / "sessions" / SCRATCH
        self.sessions_dir.mkdir(parents=True)
        self.worktree = str(Path(self.tmp.name) / "repo")
        os.mkdir(self.worktree)
        self.write_saved(self.worktree)
        self.runner.session_dirs[SCRATCH] = str(self.sessions_dir)
        self.runner.sessions[SCRATCH].running = False
        self.runner.wt_names[self.worktree] = SCRATCH

    def write_saved(self, *paths):
        (self.sessions_dir / "session.json").write_text(json.dumps(
            {"version": 3, "workspaces": [{"identity_cwd": p, "tabs": []} for p in paths]}))

    def restart(self, session=SCRATCH, **extra):
        return self.app.restart({"session": session, **extra})

    def wt_calls(self):
        return [c for c in self.runner.calls if c["argv"][0] == "wt"]

    def test_confirms_then_starts(self):
        self.restart()
        calls = self.wt_calls()
        self.assertEqual([c["argv"] for c in calls], [["wt", "name"], ["wt", "up"]])
        self.assertEqual([c["cwd"] for c in calls], [self.worktree, self.worktree])

    def test_wt_up_is_detached_from_the_gateway(self):
        self.restart()
        self.assertTrue(self.wt_calls()[1]["detach"])

    def test_no_account_override_by_default(self):
        self.restart()
        self.assertFalse(self.wt_calls()[1]["extra_env"])

    def test_explicit_account_override_reaches_only_wt_up(self):
        app = build(self.runner, self.clock, replace(CONFIG, restart_claude_config_dir="/h/.claude-personal"))
        app.restart({"session": SCRATCH})
        calls = self.wt_calls()
        self.assertFalse(calls[0]["extra_env"])
        self.assertEqual(calls[1]["extra_env"], {"CLAUDE_CONFIG_DIR": "/h/.claude-personal"})

    def test_config_reads_the_override_only_when_set(self):
        self.assertIsNone(g.Config.from_env({}).restart_claude_config_dir)
        self.assertIsNone(g.Config.from_env({"CLAUDE_CONFIG_DIR": "/x"}).restart_claude_config_dir)
        env = {"VOICE_GATEWAY_RESTART_CLAUDE_CONFIG_DIR": "/h/.claude-personal"}
        self.assertEqual(g.Config.from_env(env).restart_claude_config_dir, "/h/.claude-personal")

    def test_mismatch_is_refused_and_says_why(self):
        self.runner.wt_names[self.worktree] = "wt-something-else"
        message = self.refused(409, self.restart)
        self.assertIn("wt-something-else", message)
        self.assertEqual([c["argv"] for c in self.wt_calls()], [["wt", "name"]])

    def test_second_candidate_can_match(self):
        other = str(Path(self.tmp.name) / "other")
        os.mkdir(other)
        self.write_saved(other, self.worktree)
        self.runner.wt_names[other] = "wt-other"
        self.restart()
        self.assertEqual(self.wt_calls()[-1]["cwd"], self.worktree)

    def test_missing_saved_state(self):
        (self.sessions_dir / "session.json").unlink()
        self.assertIn("saved", self.refused(409, self.restart))
        self.assertEqual(self.wt_calls(), [])

    def test_saved_state_without_a_path(self):
        (self.sessions_dir / "session.json").write_text("{}")
        self.refused(409, self.restart)
        self.assertEqual(self.wt_calls(), [])

    def test_path_gone(self):
        self.write_saved(str(Path(self.tmp.name) / "gone"))
        self.assertIn("gone", self.refused(409, self.restart))
        self.assertEqual(self.wt_calls(), [])

    def test_wt_name_failure(self):
        self.runner.wt_names.clear()
        self.refused(409, self.restart)
        self.assertEqual([c["argv"] for c in self.wt_calls()], [["wt", "name"]])

    def test_session_dir_must_belong_to_the_session(self):
        self.runner.session_dirs[SCRATCH] = str(Path(self.tmp.name) / "sessions" / "wt-other")
        self.refused(409, self.restart)
        self.assertEqual(self.wt_calls(), [])

    def test_running_session_is_refused(self):
        self.runner.sessions[SCRATCH].running = True
        self.refused(409, self.restart)
        self.assertEqual(self.wt_calls(), [])

    def test_foreign_session(self):
        self.runner.sessions["phone"] = FakeSession(running=False)
        self.runner.session_dirs["phone"] = str(self.sessions_dir)
        self.refused(404, self.restart, "phone")
        self.assertEqual(self.wt_calls(), [])

    def test_wt_up_failure(self):
        self.runner.wt_up_result = Res(1, "", "wt: session did not come up\n")
        self.assertIn("did not come up", self.refused(502, self.restart))

    def test_wt_missing(self):
        app = build(self.runner, self.clock, replace(CONFIG, wt=None))
        self.refused(409, app.restart, {"session": SCRATCH})

    def test_no_other_session_is_touched(self):
        self.restart()
        herdr = [c["argv"] for c in self.runner.calls if c["argv"][0] == "herdr"]
        self.assertEqual(herdr, [["herdr", "session", "list", "--json"]])


class WtPathTests(unittest.TestCase):
    def test_uses_path_first(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "wt"
            path.write_text("#!/bin/sh\n")
            path.chmod(0o755)
            self.assertEqual(g.find_wt({"PATH": tmp, "HOME": "/nonexistent"}), str(path))

    def test_falls_back_to_known_places(self):
        with tempfile.TemporaryDirectory() as home:
            place = Path(home) / ".nix-profile" / "bin"
            place.mkdir(parents=True)
            (place / "wt").write_text("#!/bin/sh\n")
            (place / "wt").chmod(0o755)
            self.assertEqual(g.find_wt({"PATH": "/nonexistent", "HOME": home}), str(place / "wt"))

    def test_absent(self):
        self.assertIsNone(g.find_wt({"PATH": "/nonexistent", "HOME": "/nonexistent"}))


# ---------------------------------------------------------------- passcode and login

class PasscodeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.data = Path(self.tmp.name) / "data"

    def test_hash_is_salted_and_not_the_passcode(self):
        one, two = g.hash_passcode("same words", iterations=10), g.hash_passcode("same words", iterations=10)
        self.assertNotEqual(one["salt"], two["salt"])
        self.assertNotEqual(one["hash"], two["hash"])
        self.assertNotIn("same words", json.dumps(one))

    def test_verify(self):
        record = g.hash_passcode("right words", iterations=10)
        self.assertTrue(g.verify_passcode("right words", record))
        self.assertFalse(g.verify_passcode("wrong words", record))

    def test_verify_with_a_broken_record_denies(self):
        for record in ({}, {"salt": "zz", "hash": "zz", "iterations": 10}, {"salt": "00", "hash": "00", "iterations": 0},
                       {"salt": "00", "hash": "00", "iterations": "10"}, None):
            self.assertFalse(g.verify_passcode("x", record))

    def test_comparison_is_constant_time(self):
        self.assertIn("hmac.compare_digest", (ROOT / "gateway.py").read_text())

    def test_store_makes_0600_file_and_0700_dir(self):
        g.store_passcode(self.data, g.hash_passcode("right words", iterations=10))
        path = g.passcode_path(self.data)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.data.stat().st_mode), 0o700)

    def test_store_then_load(self):
        record = g.hash_passcode("right words", iterations=10)
        g.store_passcode(self.data, record)
        self.assertEqual(g.load_passcode(self.data), record)

    def test_store_replaces(self):
        g.store_passcode(self.data, g.hash_passcode("first words", iterations=10))
        g.store_passcode(self.data, g.hash_passcode("second words", iterations=10))
        self.assertTrue(g.verify_passcode("second words", g.load_passcode(self.data)))
        self.assertEqual([p.name for p in self.data.iterdir()], [g.passcode_path(self.data).name])

    def test_load_missing_says_how_to_set_it(self):
        with self.assertRaises(g.PasscodeError) as caught:
            g.load_passcode(self.data)
        self.assertIn("--set-passcode", str(caught.exception))

    def test_load_refuses_loose_permissions(self):
        g.store_passcode(self.data, g.hash_passcode("right words", iterations=10))
        g.passcode_path(self.data).chmod(0o644)
        with self.assertRaises(g.PasscodeError):
            g.load_passcode(self.data)

    def test_load_refuses_a_symlink(self):
        self.data.mkdir()
        target = Path(self.tmp.name) / "elsewhere"
        target.write_text("{}")
        target.chmod(0o600)
        g.passcode_path(self.data).symlink_to(target)
        with self.assertRaises(g.PasscodeError):
            g.load_passcode(self.data)

    def test_load_refuses_garbage(self):
        self.data.mkdir()
        path = g.passcode_path(self.data)
        path.write_text("not json")
        path.chmod(0o600)
        with self.assertRaises(g.PasscodeError):
            g.load_passcode(self.data)

    def run_set(self, *answers):
        replies = iter(answers)
        out = io.StringIO()
        code = g.set_passcode(self.data, ask=lambda _prompt: next(replies), out=out, iterations=10)
        return code, out.getvalue()

    def test_set_passcode_stores_after_confirming(self):
        code, _ = self.run_set("long enough", "long enough")
        self.assertEqual(code, 0)
        self.assertTrue(g.verify_passcode("long enough", g.load_passcode(self.data)))

    def test_set_passcode_minimum_length(self):
        code, out = self.run_set("short77", "short77")
        self.assertEqual(code, 1)
        self.assertIn("8", out)
        self.assertFalse(g.passcode_path(self.data).exists())

    def test_set_passcode_exactly_eight(self):
        self.assertEqual(self.run_set("12345678", "12345678")[0], 0)

    def test_set_passcode_must_match(self):
        code, _ = self.run_set("long enough", "different one")
        self.assertEqual(code, 1)
        self.assertFalse(g.passcode_path(self.data).exists())

    def test_set_passcode_output_has_no_passcode(self):
        _, out = self.run_set("long enough", "long enough")
        self.assertNotIn("long enough", out)


class ThrottleTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.throttle = g.LoginThrottle(self.clock)

    def fail(self, client="a", times=1):
        for _ in range(times):
            self.throttle.failure(client)

    def test_four_failures_do_not_lock(self):
        self.fail(times=4)
        self.assertEqual(self.throttle.locked_for("a"), 0)

    def test_fifth_failure_locks(self):
        self.fail(times=5)
        self.assertGreater(self.throttle.locked_for("a"), 0)

    def test_delay_grows(self):
        self.fail(times=5)
        first = self.throttle.locked_for("a")
        self.clock.t += first + 1
        self.assertEqual(self.throttle.locked_for("a"), 0)
        self.fail()
        self.assertGreater(self.throttle.locked_for("a"), first)

    def test_delay_has_a_ceiling(self):
        self.fail(times=60)
        self.assertLessEqual(self.throttle.locked_for("a"), g.LOGIN_MAX_DELAY_SECONDS)

    def test_global_lock_covers_other_clients(self):
        for index in range(5):
            self.fail(f"client-{index}")
        self.assertGreater(self.throttle.locked_for("fresh"), 0)

    def test_success_clears_own_counter_and_global_but_not_others(self):
        self.fail("attacker", 3)
        self.fail("user", 1)
        self.throttle.success("user")
        self.fail("attacker", 2)
        self.assertGreater(self.throttle.locked_for("attacker"), 0)
        self.assertEqual(self.throttle.locked_for("user"), 0)

    def test_client_table_is_bounded(self):
        for index in range(g.LOGIN_MAX_CLIENTS * 2):
            self.throttle.failure(f"c{index}")
            self.throttle.success(f"c{index}")
            self.throttle.failure(f"c{index}")
        self.assertLessEqual(len(self.throttle.clients), g.LOGIN_MAX_CLIENTS)


class CookieSessionTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.cookies = g.CookieSessions(self.clock)

    def test_token_is_long_and_random(self):
        one, two = self.cookies.create(), self.cookies.create()
        self.assertNotEqual(one, two)
        self.assertGreaterEqual(len(one), 40)

    def test_valid_until_seven_days(self):
        token = self.cookies.create()
        self.clock.t += 7 * 86400 - 1
        self.assertTrue(self.cookies.valid(token))
        self.clock.t += 2
        self.assertFalse(self.cookies.valid(token))

    def test_unknown_or_empty(self):
        self.cookies.create()
        for token in ("nope", "", None, 3):
            self.assertFalse(self.cookies.valid(token))

    def test_tokens_are_not_stored_in_the_clear(self):
        token = self.cookies.create()
        self.assertNotIn(token, json.dumps(list(self.cookies.sessions)))

    def test_table_is_bounded(self):
        for _ in range(g.MAX_LOGIN_SESSIONS + 5):
            self.cookies.create()
        self.assertLessEqual(len(self.cookies.sessions), g.MAX_LOGIN_SESSIONS)


class GatewayLoginTests(GatewayCase):
    def test_right_passcode(self):
        self.assertTrue(self.app.cookies.valid(self.app.login("a", PASSCODE)))

    def test_wrong_passcode(self):
        self.refused(401, self.app.login, "a", "wrong one")

    def test_bad_types_count_as_wrong(self):
        for value in (None, 3, ["x"], "x" * 5000):
            self.refused(401, self.app.login, "a", value)

    def test_lockout_even_for_the_right_passcode(self):
        for _ in range(5):
            self.refused(401, self.app.login, "a", "wrong one")
        self.refused(429, self.app.login, "a", PASSCODE)
        self.refused(429, self.app.login, "b", PASSCODE)

    def test_cookie_works_after_the_delay(self):
        for _ in range(5):
            self.refused(401, self.app.login, "a", "wrong one")
        self.clock.t += g.LOGIN_BASE_DELAY_SECONDS + 1
        self.assertTrue(self.app.login("a", PASSCODE))


# ---------------------------------------------------------------- http

class HttpCase(unittest.TestCase):
    def setUp(self):
        self.runner = FakeRunner()
        self.clock = FakeClock()
        self.tailscale = FakeTailscale()
        self.app = self.make_app()
        self.log = io.StringIO()
        self.server = g.make_server(self.app.config, self.app, self.log)
        self.port = self.server.server_address[1]
        self.server.config = replace(self.app.config, port=self.port)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self._stop)
        self.token = None

    def make_voice(self):
        return None

    def make_app(self):
        return build(self.runner, self.clock, tailscale=self.tailscale, voice=self.make_voice())

    def _stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)

    def headers(self, **overrides):
        base = {"Host": f"127.0.0.1:{self.port}", "Tailscale-User-Login": LOGIN}
        if self.token:
            base["Cookie"] = f"{g.COOKIE_NAME}={self.token}"
        base.update({k.replace("_", "-"): v for k, v in overrides.items()})
        return {k: v for k, v in base.items() if v is not None}

    def request(self, method, path, body=None, raw=None, **header_overrides):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            headers = self.headers(**header_overrides)
            data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
            connection.putrequest(method, path, skip_host=True, skip_accept_encoding=True)
            for name, value in headers.items():
                connection.putheader(name, value)
            if data is not None:
                connection.putheader("Content-Length", str(len(data)))
            connection.endheaders(data)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read(), response
        finally:
            connection.close()

    def post(self, path, body, **overrides):
        base = {"Origin": f"http://127.0.0.1:{self.port}", "Content_Type": "application/json"}
        return self.request("POST", path, body, **{**base, **overrides})

    def log_in(self):
        status, headers, _, _ = self.post("/api/login", {"passcode": PASSCODE})
        self.assertEqual(status, 200)
        self.token = re.search(rf"{g.COOKIE_NAME}=([^;]+)", headers["Set-Cookie"]).group(1)


API_GET = ("/api/sessions", f"/api/session?name={SCRATCH}", "/api/audio?id=0123456789abcdef", "/api/worktree/setup")
API_POST = ("/api/prompt", "/api/keys", "/api/stop", "/api/restart", "/api/announced", "/api/worktree/plan",
            "/api/worktree/create", "/api/transcribe")
LOGIN_PAGES = ("/login", "/login.js", "/app.css")


class HttpGuardTests(HttpCase):
    def test_bad_host_everywhere(self):
        for path in ("/", "/login", *API_GET):
            self.assertEqual(self.request("GET", path, Host="evil.com")[0], 403)
        self.assertEqual(self.post("/api/login", {"passcode": PASSCODE}, Host="evil.com")[0], 403)

    def test_no_identity_everywhere(self):
        self.log_in()
        for path in ("/", "/login", "/login.js", "/app.js", "/app.css", *API_GET):
            self.assertEqual(self.request("GET", path, Tailscale_User_Login=None)[0], 403, path)
        for path in ("/api/login", *API_POST):
            self.assertEqual(self.post(path, {}, Tailscale_User_Login=None)[0], 403, path)

    def test_forged_identity_is_denied(self):
        self.assertEqual(self.request("GET", "/login", Tailscale_User_Login="someone@else")[0], 403)

    def test_repeated_identity_header_is_denied(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        connection.putrequest("GET", "/login", skip_host=True)
        connection.putheader("Host", f"127.0.0.1:{self.port}")
        connection.putheader("Tailscale-User-Login", LOGIN)
        connection.putheader("Tailscale-User-Login", "x@y")
        connection.endheaders()
        self.assertEqual(connection.getresponse().status, 403)
        connection.close()

    def test_tailscale_down_denies(self):
        self.tailscale.status = Res(1, "", "down")
        self.app.identity.clear()
        self.assertEqual(self.request("GET", "/login")[0], 403)

    def test_api_needs_cookie(self):
        for path in API_GET:
            self.assertEqual(self.request("GET", path)[0], 401, path)
        for path in API_POST:
            self.assertEqual(self.post(path, {"session": SCRATCH, "text": "x", "key": "1"})[0], 401, path)
        self.assertEqual(self.runner.named("agent", "prompt"), [])
        self.assertEqual(self.runner.named("agent", "send-keys"), [])

    def test_bad_cookie(self):
        self.token = "not-a-real-token"
        self.assertEqual(self.request("GET", API_GET[0])[0], 401)
        self.assertEqual(self.post("/api/prompt", {"session": SCRATCH, "text": "x"})[0], 401)

    def test_expired_cookie(self):
        self.log_in()
        self.clock.t += 7 * 86400 + 1
        self.assertEqual(self.request("GET", API_GET[0])[0], 401)

    def test_login_pages_need_identity_alone(self):
        for path in LOGIN_PAGES:
            self.assertEqual(self.request("GET", path)[0], 200, path)

    def test_app_page_redirects_to_login(self):
        status, headers, _, _ = self.request("GET", "/")
        self.assertEqual(status, 303)
        self.assertEqual(headers["Location"], "/login")

    def test_app_script_needs_cookie(self):
        self.assertEqual(self.request("GET", "/app.js")[0], 401)

    def test_login_redirects_home_when_logged_in(self):
        self.log_in()
        status, headers, _, _ = self.request("GET", "/login")
        self.assertEqual(status, 303)
        self.assertEqual(headers["Location"], "/")

    def test_post_guards(self):
        self.log_in()
        body = {"session": SCRATCH, "text": "x"}
        self.assertEqual(self.post("/api/prompt", body, Origin="https://evil.com")[0], 403)
        self.assertEqual(self.post("/api/prompt", body, Origin=None)[0], 403)
        self.assertEqual(self.post("/api/prompt", body, Content_Type="text/plain")[0], 415)
        self.assertEqual(self.post("/api/prompt", None, raw=b"{bad")[0], 400)
        self.assertEqual(self.post("/api/prompt", None, raw=b"[1]")[0], 400)
        self.assertEqual(self.post("/api/prompt", None, raw=b"x" * (g.MAX_BODY_BYTES + 1))[0], 413)
        self.assertEqual(self.runner.named("agent", "prompt"), [])

    def test_login_post_guards(self):
        self.assertEqual(self.post("/api/login", {"passcode": PASSCODE}, Origin="https://evil.com")[0], 403)
        self.assertEqual(self.post("/api/login", {"passcode": PASSCODE}, Content_Type="text/plain")[0], 415)

    def test_security_headers(self):
        _, headers, _, _ = self.request("GET", "/login")
        csp = headers["Content-Security-Policy"]
        self.assertIn("default-src 'self'", csp)
        self.assertIn("script-src 'self'", csp)
        self.assertNotIn("unsafe", csp)
        directives = dict(part.strip().split(" ", 1) for part in csp.split(";"))
        self.assertEqual(directives["media-src"], "'self' blob:")  # This gateway, or a MediaSource fed from it.
        for word in ("*", "data:", "https:", "http:"):
            self.assertNotIn(word, csp)
        self.assertEqual(headers["X-Frame-Options"], "DENY")
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")

    def test_unknown_paths(self):
        self.log_in()
        self.assertEqual(self.request("GET", "/gateway.py")[0], 404)
        self.assertEqual(self.request("GET", "/../gateway.py")[0], 404)
        self.assertEqual(self.request("GET", "/api/jobs")[0], 404)
        self.assertEqual(self.post("/api/jobs", {"text": "x"})[0], 404)


class HttpLoginTests(HttpCase):
    def test_cookie_attributes_over_http(self):
        _, headers, _, _ = self.post("/api/login", {"passcode": PASSCODE})
        cookie = headers["Set-Cookie"]
        self.assertIn("HttpOnly", cookie)
        self.assertIn("SameSite=Strict", cookie)
        self.assertIn("Path=/", cookie)
        self.assertIn("Max-Age=604800", cookie)
        self.assertNotIn("Secure", cookie)

    def test_secure_over_https(self):
        status, headers, _, _ = self.post("/api/login", {"passcode": PASSCODE}, Origin="https://mac.tail1234.ts.net",
                                          Host="mac.tail1234.ts.net")
        self.assertEqual(status, 200)
        self.assertIn("Secure", headers["Set-Cookie"])

    def test_wrong_passcode(self):
        status, headers, _, _ = self.post("/api/login", {"passcode": "wrong"})
        self.assertEqual(status, 401)
        self.assertNotIn("Set-Cookie", headers)

    def test_lockout(self):
        for _ in range(5):
            self.assertEqual(self.post("/api/login", {"passcode": "wrong"})[0], 401)
        status, headers, body, _ = self.post("/api/login", {"passcode": PASSCODE})
        self.assertEqual(status, 429)
        self.assertIn("Retry-After", headers)
        self.assertGreater(json.loads(body)["retry_after"], 0)

    def test_passcode_is_not_logged(self):
        self.post("/api/login", {"passcode": "wrong-passcode-text"})
        self.assertNotIn("wrong-passcode-text", self.log.getvalue())

    def test_login_works_with_many_failing_cookies_absent(self):
        self.log_in()
        self.assertEqual(self.request("GET", "/")[0], 200)


class HttpFlowTests(HttpCase):
    def setUp(self):
        super().setUp()
        self.log_in()

    def test_list(self):
        status, _, body, _ = self.request("GET", "/api/sessions")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["running"][0]["name"], SCRATCH)

    def test_detail(self):
        status, _, body, _ = self.request("GET", f"/api/session?name={SCRATCH}")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["output"], "orchestrator output")

    def test_earlier_output_parameter(self):
        self.assertEqual(self.request("GET", f"/api/session?name={SCRATCH}&earlier=1")[0], 200)
        reads = [c["argv"] for c in self.runner.named("agent", "read") if "ansi" not in c["argv"]]
        self.assertEqual(reads[-1][-1], str(g.EARLIER_READ_LINES))
        self.request("GET", f"/api/session?name={SCRATCH}")
        reads = [c["argv"] for c in self.runner.named("agent", "read") if "ansi" not in c["argv"]]
        self.assertEqual(reads[-1][-1], "40")

    def test_detail_of_foreign_session(self):
        self.runner.sessions["phone"] = FakeSession()
        self.assertEqual(self.request("GET", "/api/session?name=phone")[0], 404)
        self.assertEqual(self.request("GET", "/api/session")[0], 404)

    def test_prompt_and_refusal(self):
        self.assertEqual(self.post("/api/prompt", {"session": SCRATCH, "text": "hi"})[0], 200)
        self.assertEqual(len(self.runner.named("agent", "prompt")), 1)
        self.clock.t += 100
        self.runner.sessions[SCRATCH].agents[0]["agent_status"] = "working"
        status, _, body, _ = self.post("/api/prompt", {"session": SCRATCH, "text": "hi"})
        self.assertEqual(status, 409)
        self.assertIn("working", json.loads(body)["error"])

    def test_keys(self):
        self.runner.sessions[SCRATCH].agents[0]["agent_status"] = "blocked"
        token = json.loads(self.request("GET", f"/api/session?name={SCRATCH}")[2])["screen_token"]
        self.assertEqual(self.post("/api/keys", {"session": SCRATCH, "key": "down", "screen_token": token})[0], 200)
        self.assertEqual(self.post("/api/keys", {"session": SCRATCH, "key": "ctrl+c", "screen_token": token})[0], 400)

    def test_key_refusal_carries_the_new_screen(self):
        self.runner.sessions[SCRATCH].agents[0]["agent_status"] = "blocked"
        status, _, body, _ = self.post("/api/keys", {"session": SCRATCH, "key": "1", "screen_token": "old"})
        self.assertEqual(status, 409)
        answer = json.loads(body)
        self.assertEqual(answer["output"], "visible screen")
        self.assertEqual(answer["screen_token"], g.screen_token("visible screen"))

    def test_poll_returns_events_and_acknowledgement_removes_them(self):
        self.post("/api/prompt", {"session": SCRATCH, "text": "hi"})
        sent = self.runner.named("agent", "prompt")[-1]["argv"][-1]
        nonce = re.search(r"SPOKEN\[([0-9a-f]+)\]", sent).group(1)
        self.runner.sessions[SCRATCH].visible = f"⏺ SPOKEN[{nonce}]: All done.\n"
        listing = json.loads(self.request("GET", "/api/sessions")[2])
        detail = json.loads(self.request("GET", f"/api/session?name={SCRATCH}")[2])
        self.assertEqual([e["text"] for e in listing["events"]], ["All done."])
        self.assertEqual(detail["events"], listing["events"])
        self.assertEqual(listing["waiting"], [])
        ids = [e["id"] for e in listing["events"]]
        self.assertEqual(self.post("/api/announced", {"ids": ids})[0], 200)
        self.assertEqual(json.loads(self.request("GET", "/api/sessions")[2])["events"], [])

    def test_acknowledgement_is_validated(self):
        for body in ({}, {"ids": "x"}, {"ids": [1]}, {"ids": ["x"] * 65}):
            self.assertEqual(self.post("/api/announced", body)[0], 400, body)

    def test_stop(self):
        self.runner.sessions[SCRATCH].agents[0]["agent_status"] = "working"
        self.assertEqual(self.post("/api/stop", {"session": SCRATCH})[0], 200)

    def test_restart_of_running_session(self):
        self.assertEqual(self.post("/api/restart", {"session": SCRATCH})[0], 409)

    def test_prompt_text_is_not_logged(self):
        self.post("/api/prompt", {"session": SCRATCH, "text": "secret words here"})
        self.assertNotIn("secret words", self.log.getvalue())

    def test_app_page(self):
        self.assertEqual(self.request("GET", "/")[0], 200)
        self.assertEqual(self.request("GET", "/app.js")[0], 200)

    def test_herdr_trouble_is_a_clean_error(self):
        self.runner.fail[(SCRATCH, ("tab", "list"))] = err("server_not_running", "no server")
        status, _, body, _ = self.post("/api/prompt", {"session": SCRATCH, "text": "hi"})
        self.assertEqual(status, 502)
        self.assertIn("server_not_running", json.loads(body)["error"])


# ---------------------------------------------------------------- start up

class MainTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.data = Path(self.tmp.name) / "data"

    def env(self, **extra):
        return {"VOICE_GATEWAY_DATA": str(self.data), "VOICE_GATEWAY_PORT": "0", **extra}

    def run_main(self, env, argv=()):
        out = io.StringIO()
        with redirect_stderr(out):
            code = g.main(list(argv), env=env, runner=FakeTailscale())
        return code, out.getvalue()

    def test_refuses_without_a_passcode_and_says_how(self):
        code, out = self.run_main(self.env())
        self.assertEqual(code, 1)
        self.assertIn("--set-passcode", out)
        self.assertFalse(self.data.exists())

    def test_refuses_a_bad_other_url(self):
        g.store_passcode(self.data, g.hash_passcode("long enough", iterations=10))
        code, out = self.run_main(self.env(VOICE_GATEWAY_OTHER_URL="http://evil.com"))
        self.assertEqual(code, 1)
        self.assertIn("VOICE_GATEWAY_OTHER_URL", out)

    def test_refuses_a_relative_account_override(self):
        g.store_passcode(self.data, g.hash_passcode("long enough", iterations=10))
        code, out = self.run_main(self.env(VOICE_GATEWAY_RESTART_CLAUDE_CONFIG_DIR="rel/path"))
        self.assertEqual(code, 1)
        self.assertIn("VOICE_GATEWAY_RESTART_CLAUDE_CONFIG_DIR", out)

    def test_refuses_bad_bind(self):
        g.store_passcode(self.data, g.hash_passcode("long enough", iterations=10))
        code, out = self.run_main(self.env(VOICE_GATEWAY_BIND="0.0.0.0"))
        self.assertEqual(code, 1)
        self.assertIn("0.0.0.0", out)

    def test_unknown_argument(self):
        with self.assertRaises(SystemExit) as caught:
            with redirect_stderr(io.StringIO()):
                g.main(["--nonsense"], env=self.env(), runner=FakeTailscale())
        self.assertEqual(caught.exception.code, 2)

    def test_default_port_is_not_the_search_bridge(self):
        self.assertEqual(g.Config.from_env({}).port, 8766)

    def test_set_passcode_flag_needs_no_tailscale(self):
        class NoRunner:
            def run(self, *a, **k):
                raise AssertionError("no command should run")
        answers = iter(["long enough", "long enough"])
        with redirect_stderr(io.StringIO()):
            code = g.main(["--set-passcode"], env=self.env(), runner=NoRunner(), ask=lambda _p: next(answers))
        self.assertEqual(code, 0)
        self.assertTrue(g.passcode_path(self.data).exists())


# ---------------------------------------------------------------- source rules

class SourceRuleTests(unittest.TestCase):
    def source(self):
        return (ROOT / "gateway.py").read_text()

    def code_lines(self):
        """gateway.py without comments and docstrings, so rules can be stated in prose."""
        text = re.sub(r'""".*?"""', "", self.source(), flags=re.S)
        return "\n".join(line.split("#", 1)[0] for line in text.splitlines())

    def test_forbidden_commands_are_nowhere(self):
        code = self.code_lines()
        # Gateway._wt directly, or as the `wt` callable NewWorktrees is handed.
        self.assertEqual(set(re.findall(r'(?:self\._wt|\bwt)\(\["(\w+)"', code)), {"name", "up", "new"})
        self.assertEqual(set(re.findall(r'self\._git\([^,]+, "([\w-]+)"', code)),
                         {"check-ref-format", "rev-parse", "show-ref", "symbolic-ref", "log", "worktree",
                          "for-each-ref"})
        # Only these two reach origin: ls-remote while planning (read only), fetch only on a tapped Create.
        self.assertEqual(set(re.findall(r'self\._git_remote\([^,]+, [^,]+, "([\w-]+)"', code)), {"ls-remote", "fetch"})
        self.assertEqual(len(re.findall(r'"fetch"', code)), 1)
        self.assertNotIn('"push"', code)
        self.assertNotIn('"worktree", "add"', code)
        self.assertNotIn('"worktree", "remove"', code)
        self.assertIsNone(re.search(r'\[\s*"(?:down|rm)"', code))  # Never the first word of any command.
        for pattern in (r'"rm"', r'"stop"\s*,', r'"delete"', r'"server"', r'"focus"', r'"attach"\s*,\s*name',
                        r'"pane"\s*,\s*"send-text"', r'"pane"\s*,\s*"run"', r'"agent"\s*,\s*"start"',
                        r"shell\s*=\s*True"):
            self.assertIsNone(re.search(pattern, code), pattern)

    def test_old_machinery_is_gone(self):
        code = self.code_lines()
        for word in ("JobStore", "JobRunner", "parse_agent_prefix", "RESULT_INSTRUCTION", "AgentSpec", '"phone"',
                     "results"):
            self.assertNotIn(word, code)

    def test_nothing_specific_to_this_machine(self):
        self.assertNotIn("/Users/", self.source())
        self.assertNotIn("/home/", self.source())

    def test_python_39_compatible_annotations(self):
        self.assertIn("from __future__ import annotations", self.source())


class PageRuleTests(unittest.TestCase):
    def page(self, name):
        return (ROOT / "static" / name).read_text()

    def test_no_html_injection_points(self):
        for name in ("app.js", "login.js"):
            source = self.page(name)
            for word in ("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "alert(",
                         "confirm(", "prompt("):
                self.assertNotIn(word, source, f"{name}: {word}")

    def test_no_inline_script_or_style(self):
        for name in ("index.html", "login.html"):
            source = self.page(name)
            self.assertIsNone(re.search(r"<script(?![^>]*\bsrc=)", source))
            self.assertNotIn("<style", source)
            self.assertIsNone(re.search(r"\son\w+=", source))
            self.assertIsNone(re.search(r"\sstyle=", source))

    def test_speech_never_sends(self):
        source = self.page("app.js")
        body = source[source.index("function startListening"):source.index("function onMic")]
        self.assertNotIn("onSend", body)
        self.assertNotIn("/api/prompt", body)
        self.assertNotIn(".click(", body)

    def test_client_sends_session_name_only(self):
        source = self.page("app.js")
        for word in ("pane_id", "paneId", "w1:"):
            self.assertNotIn(word, source)

    def test_every_action_button_exists(self):
        html = self.page("index.html")
        for element in ("mic", "text", "send", "keys", "stop", "restart", "restart-confirm", "restart-cancel",
                        "output", "other", "earlier", "earlier-toggle", "mute", "announce", "draft-warning",
                        "new-worktree", "new-view", "new-back", "new-note", "plan", "plan-card", "plan-create",
                        "plan-cancel", "candidates"):
            self.assertIn(f'id="{element}"', html)
        for key in ("1", "2", "up", "down", "enter", "esc"):
            self.assertIn(f'data-key="{key}"', html)

    def test_only_event_text_is_ever_spoken(self):
        source = self.page("app.js")
        self.assertEqual(source.count("new SpeechSynthesisUtterance("), 2)  # The silent unlock and the event's text.
        self.assertIn('new SpeechSynthesisUtterance("")', source)
        self.assertIn("new SpeechSynthesisUtterance(words)", source)
        body = source[source.index("function announce"):source.index("// ---- End of announcements")]
        for word in (".output", "renderedOutput", '$("output")', '$("earlier")'):
            self.assertNotIn(word, body)

    def test_speech_is_unlocked_during_the_send_tap_before_anything_waits(self):
        source = self.page("app.js")
        body = source[source.index("async function onSend"):source.index("function askRestart")]
        self.assertLess(body.index("unlockSpeech()"), body.index("await "))

    def test_announcements_do_not_send_prompts(self):
        source = self.page("app.js")
        body = source[source.index("function announce"):source.index("// ---- End of announcements")]
        self.assertNotIn("/api/prompt", body)
        self.assertNotIn("onSend", body)

    def test_browser_storage_is_guarded(self):
        source = self.page("app.js")
        for match in re.finditer(r"localStorage", source):
            self.assertIn("try {", source[max(0, match.start() - 40):match.start()])

    def test_keys_carry_the_token(self):
        source = self.page("app.js")
        self.assertIn("screen_token: state.screenToken", source)

    def test_stopped_group_is_collapsed(self):
        self.assertIn("details", self.page("app.js") + self.page("index.html"))
        self.assertNotIn('<details open', self.page("index.html"))


# ---------------------------------------------------------------- spoken summaries: pure functions

NONCE = "3fa9c2d1"
TAG = f"SPOKEN[{NONCE}]:"


def wrapped(text, width, first_prefix="> ", rest_prefix="  "):
    """Text laid out the way Claude's input echo wraps it: word wrap, with an indent on every line after the first."""
    lines, current = [], first_prefix
    for word in text.split(" "):
        if len(current) + len(word) + 1 > width and current.strip():
            lines.append(current.rstrip())
            current = rest_prefix
        current += word + " "
    lines.append(current.rstrip())
    return "\n".join(lines)


class SummaryInstructionTests(unittest.TestCase):
    def test_plain_prompts_want_a_summary(self):
        for text in ("what is 2", "- a list", "--wait", "#note", "a /path", "x!"):
            self.assertTrue(g.wants_summary(text), text)

    def test_commands_are_left_alone(self):
        for text in ("/compact", "/help me", "!ls", "! ls"):
            self.assertFalse(g.wants_summary(text), text)

    def test_nonces_are_short_random_and_differ(self):
        one, two = g.new_nonce(), g.new_nonce()
        self.assertNotEqual(one, two)
        self.assertRegex(one, r"^[0-9a-f]{8}$")

    def test_instruction_asks_for_the_exact_form(self):
        text = g.with_summary_instruction("what is 2", NONCE)
        self.assertTrue(text.startswith("what is 2 "))
        self.assertIn(TAG, text)
        for words in ("two plain sentences", "no code", "paths", "markdown", "read aloud"):
            self.assertIn(words, text)

    def test_instruction_stays_on_one_line(self):
        self.assertEqual(len(g.summary_instruction(NONCE).splitlines()), 1)


class FindSpokenTests(unittest.TestCase):
    def find(self, screen):
        return g.find_spoken(screen, NONCE)

    def test_after_claudes_bullet(self):
        self.assertEqual(self.find(f"⏺ {TAG} The answer is 399.\n"), "The answer is 399.")

    def test_other_markers_and_whitespace(self):
        for line in (TAG, f"  {TAG}", f"● {TAG}", f"• {TAG}", f"- {TAG}", f"* {TAG}", f"  ⏺  {TAG}"):
            self.assertEqual(self.find(f"{line} Done.\n"), "Done.", line)

    def test_wrapped_reply_is_joined(self):
        screen = (f"⏺ {TAG} The tests passed and the fix is in the login\n  module, so you can merge it now.\n\n"
                  "───────\n❯ \n───────\n  ? for shortcuts\n")
        self.assertEqual(self.find(screen), "The tests passed and the fix is in the login module, so you can merge it now.")

    def test_reply_text_on_the_next_line(self):
        self.assertEqual(self.find(f"⏺ {TAG}\n  All done.\n\n"), "All done.")

    def test_stops_at_what_follows_the_reply(self):
        for after in ("\n❯ typed\n", "\n───\n", "\n  ? for shortcuts\n", "\n⏺ more\n", "\nplain\n"):
            self.assertEqual(self.find(f"⏺ {TAG} Done.{after}"), "Done.", after)
        for after in ("  ───\n", "  ❯ x\n", "  > x\n", "  ⏺ x\n", "  │ x\n", "plain unindented\n"):
            self.assertEqual(self.find(f"⏺ {TAG} Done.\n{after}"), "Done.", after)

    def test_wrong_nonce_never_matches(self):
        self.assertIsNone(self.find("⏺ SPOKEN[deadbeef]: Old reply.\n"))

    def test_mid_sentence_never_matches(self):
        for line in (f"⏺ I will end with {TAG} like that.", f"  The form is {TAG} here", f"x{TAG} no", f"⏺ x {TAG} y"):
            self.assertIsNone(self.find(line + "\n"), line)

    def test_echo_lines_never_match(self):
        for marker in (">", "❯", "│", "│ >", "│ ❯"):
            self.assertIsNone(self.find(f"{marker} {TAG} real looking words\n"), marker)

    def test_echoed_instruction_never_matches_at_any_width(self):
        # Wrapping can put the tag first on a line of the echo, which is exactly what the real reply looks like.
        echo_text = "what is 19 times 21? " + g.summary_instruction(NONCE)
        starts = 0
        for width in range(20, 200):
            screen = wrapped(echo_text, width)
            starts += any(line.strip().startswith(TAG) for line in screen.splitlines())
            self.assertIsNone(self.find(screen + "\n\n✻ Thinking…\n"), width)
        self.assertGreater(starts, 0, "no width put the tag at the start of a line, so this tested nothing")

    def test_the_reply_is_found_below_a_wrapped_echo(self):
        echo_text = "what is 19 times 21? " + g.summary_instruction(NONCE)
        for width in range(20, 200):
            screen = wrapped(echo_text, width) + f"\n\n⏺ 399\n\n⏺ {TAG} The answer is 399.\n\n───\n❯ \n───\n"
            self.assertEqual(self.find(screen), "The answer is 399.", width)

    def test_empty_reply_is_no_reply(self):
        self.assertIsNone(self.find(f"⏺ {TAG}\n\n"))

    def test_only_the_tagged_line_is_taken(self):
        secret = "SECRET CODE OUTPUT"
        self.assertEqual(self.find(f"⏺ {secret}\n  {secret}\n\n⏺ {TAG} Done.\n\n{secret}\n"), "Done.")

    def test_nonce_is_matched_literally(self):
        self.assertIsNone(g.find_spoken(f"{TAG} x\n", "3fa9c2d."))


# Real screens from Claude Code on the scratch session, cut to the lines that matter.
REAL_ECHO_WITH_TAG_FIRST = """❯ What is 19 times 21? Reply with only the number. Do not use any tools.
  ................................................ (Sent from my phone. End your reply with one final line of at most
  two plain sentences, written to be read aloud, with no code, paths or markdown, in exactly this form:
  SPOKEN[f7ccaf70]: your spoken summary)
"""
REAL_REPLY = """
⏺ 399
  SPOKEN[f7ccaf70]: Nineteen times twenty-one is three hundred and ninety-nine.

✻ Churned for 1s · done 11:24 PM
 ○ Delta · Not set up  /delta-setup                                                                           [-]

────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
❯\xa0
────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
  scratch on  master [?]  ctx 5%
  ⏵⏵ bypass permissions on (shift+tab to cycle)
"""
REAL_AFTER_A_QUESTION = """❯ Use the AskUserQuestion tool to ask me whether I prefer tea or coffee. After I answer, reply in one short sentence.
  (Sent from my phone. End your reply with one final line of at most two plain sentences, written to be read aloud,
  with no code, paths or markdown, in exactly this form: SPOKEN[a76900b2]: your spoken summary)

⏺\xa0User answered Claude's questions:
  ⎿ \xa0· Do you prefer tea or coffee? → Tea

⏺ You prefer tea.
  SPOKEN[a76900b2]: You said you prefer tea.

✻ Cooked for 2s · done 11:19 PM
"""


class RealScreenTests(unittest.TestCase):
    def test_real_echo_with_the_tag_first_on_a_line_is_not_a_reply(self):
        self.assertIsNone(g.find_spoken(REAL_ECHO_WITH_TAG_FIRST, "f7ccaf70"))

    def test_real_reply_below_that_echo(self):
        self.assertEqual(g.find_spoken(REAL_ECHO_WITH_TAG_FIRST + REAL_REPLY, "f7ccaf70"),
                         "Nineteen times twenty-one is three hundred and ninety-nine.")

    def test_real_reply_after_a_question(self):
        self.assertEqual(g.find_spoken(REAL_AFTER_A_QUESTION, "a76900b2"), "You said you prefer tea.")

    def test_real_screen_with_an_older_reply_and_a_new_nonce(self):
        self.assertIsNone(g.find_spoken(REAL_AFTER_A_QUESTION, "f7ccaf70"))


class CleanSpokenTests(unittest.TestCase):
    def test_control_and_format_characters_go(self):
        self.assertEqual(g.clean_spoken("a\x00b\x07c\x1b​d‮e"), "abcde")

    def test_ansi_sequences_go_whole(self):
        self.assertEqual(g.clean_spoken("\x1b[31mred\x1b[0m"), "red")

    def test_whitespace_collapses(self):
        self.assertEqual(g.clean_spoken("  a\n\tb \r\n c d "), "a b c d")

    def test_cap_at_a_word(self):
        text = g.clean_spoken("word " * 200)
        self.assertLessEqual(len(text), g.MAX_SPOKEN_CHARS)
        self.assertGreater(len(text), g.MAX_SPOKEN_CHARS - 10)
        self.assertTrue(text.endswith("word"))

    def test_cap_without_spaces(self):
        self.assertEqual(len(g.clean_spoken("x" * 1000)), g.MAX_SPOKEN_CHARS)


class SpokenNameTests(unittest.TestCase):
    def test_reads_like_words(self):
        self.assertEqual(g.spoken_name("wt-voice-gateway"), "voice gateway")
        self.assertEqual(g.spoken_name("wt-a_b.c"), "a b c")

    def test_the_hash_suffix_is_not_read(self):
        self.assertEqual(g.spoken_name("wt-investor-agent-feat-voice-con-fefdb007"), "investor agent feat voice con")

    def test_nothing_left_still_says_something(self):
        self.assertEqual(g.spoken_name("wt--"), "worktree")
        self.assertEqual(g.spoken_name("wt-"), "worktree")


# ---------------------------------------------------------------- spoken summaries: flow

class AnnouncementTests(GatewayCase):
    def setUp(self):
        super().setUp()
        self.session = self.runner.sessions[SCRATCH]

    def send(self, text="what is 2", session=SCRATCH):
        return self.app.prompt({"session": session, "text": text})

    def sent_text(self):
        return self.runner.named("agent", "prompt")[-1]["argv"][-1]

    def nonce(self):
        return re.search(r"SPOKEN\[([0-9a-f]+)\]", self.sent_text()).group(1)

    def events(self):
        return self.app.sessions_view()["events"]

    def settle(self):
        self.clock.t += g.SETTLE_SECONDS + 1

    def screen_reads(self):
        """Reads of what is on the screen, not the styled read of the input box that a send makes."""
        return [c["argv"] for c in self.runner.named("agent", "read") if "ansi" not in c["argv"]]

    def test_no_send_no_events_and_no_reads(self):
        self.assertEqual(self.app.sessions_view()["events"], [])
        self.assertEqual(self.app.session_view(SCRATCH)["events"], [])
        self.assertEqual(self.app.sessions_view()["waiting"], [])
        self.assertEqual([c for c in self.runner.named("agent", "read") if "ansi" not in c["argv"]][1:], [])

    def test_work_started_elsewhere_makes_no_event(self):
        self.set_status("working")
        self.events()
        self.set_status("blocked")
        self.events()
        self.set_status("idle")
        self.session.visible = f"⏺ {TAG} From the Mac.\n"
        self.settle()
        self.assertEqual(self.events(), [])

    def test_the_list_reads_no_pane_without_a_pending_send(self):
        self.runner.sessions["wt-other"] = FakeSession()
        self.send()
        self.session.visible = "still going"
        self.set_status("working")
        self.events()
        self.assertEqual(self.screen_reads(), [])

    def test_prompt_is_recorded_as_waiting(self):
        self.send()
        self.assertEqual(self.app.sessions_view()["waiting"], [SCRATCH])

    def test_summary_event(self):
        self.send()
        self.session.visible = f"> what is 2\n\n⏺ SPOKEN[{self.nonce()}]: It is two.\n\n───\n❯ \n───\n"
        events = self.events()
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["kind"], "summary")
        self.assertEqual(events[0]["text"], "It is two.")
        self.assertEqual(events[0]["session"], SCRATCH)
        self.assertEqual(events[0]["worktree"], "scratch")
        self.assertTrue(events[0]["id"])
        self.assertEqual(self.app.sessions_view()["waiting"], [])

    def test_tag_is_read_from_visible_rows_only(self):
        self.session.panes = [pane_row(rows=23)]
        self.send()
        self.session.visible = f"⏺ SPOKEN[{self.nonce()}]: Done.\n"
        self.events()
        reads = self.screen_reads()
        self.assertEqual(len(reads), 1)
        self.assertEqual(reads[0][5:], ["w1:p1", "--source", "visible", "--lines", "23"])

    def test_echo_alone_is_not_a_summary(self):
        self.send()
        self.session.visible = wrapped(self.sent_text(), 60) + "\n"
        self.assertEqual(self.events(), [])

    def test_event_is_produced_once_and_repeats_until_acknowledged(self):
        self.send()
        self.session.visible = f"⏺ SPOKEN[{self.nonce()}]: Done.\n"
        first = self.events()
        second = self.app.session_view(SCRATCH)["events"]
        self.assertEqual(first, second)
        self.assertEqual(self.app.acknowledge({"ids": [first[0]["id"]]}), {"ok": True})
        self.assertEqual(self.events(), [])
        self.settle()
        self.assertEqual(self.events(), [])

    def test_ids_are_unique_across_sends(self):
        ids = set()
        for _ in range(3):
            self.send()
            self.session.visible = f"⏺ SPOKEN[{self.nonce()}]: Done.\n"
            ids.add(self.events()[-1]["id"])
            self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        self.assertEqual(len(ids), 3)

    def test_settled_without_a_line_waits_then_says_check_screen(self):
        self.send()
        self.session.visible = "no line"
        self.assertEqual(self.events(), [])
        self.clock.t += g.SETTLE_SECONDS - 1
        self.assertEqual(self.events(), [])
        self.clock.t += 2
        events = self.events()
        self.assertEqual([e["kind"] for e in events], ["check-screen"])
        self.assertEqual(events[0]["text"], "scratch finished, check the screen")

    def test_settling_is_measured_from_the_first_quiet_poll_not_the_send(self):
        self.send()
        self.session.visible = "no line"
        self.clock.t += 3600  # The phone was away. The reply may only just have finished.
        self.assertEqual(self.events(), [])
        self.settle()
        self.assertEqual(len(self.events()), 1)

    def test_idle_between_steps_does_not_decide(self):
        self.send()
        self.session.visible = "first step"
        self.events()
        self.clock.t += g.SETTLE_SECONDS - 1
        self.set_status("working")
        self.assertEqual(self.events(), [])
        self.set_status("done")
        self.clock.t += g.SETTLE_SECONDS - 1
        self.assertEqual(self.events(), [])
        self.session.visible = f"⏺ SPOKEN[{self.nonce()}]: Finished.\n"
        self.assertEqual([e["text"] for e in self.events()], ["Finished."])

    def test_commands_are_unchanged_and_end_in_check_screen(self):
        for text in ("/compact", "!ls"):
            self.send(text)
            self.assertEqual(self.sent_text(), text)
            self.assertEqual(self.events()[1:], [])
            self.settle()
            events = self.events()
            self.assertEqual(events[-1]["kind"], "check-screen")
            self.app.acknowledge({"ids": [e["id"] for e in events]})
            self.clock.t += g.PROMPT_REPEAT_SECONDS

    def test_command_never_reads_the_screen_for_a_tag(self):
        self.send("/compact")
        self.settle()
        self.events()
        self.settle()
        self.events()
        self.assertEqual(self.screen_reads(), [])

    def test_blocked_says_needs_you_at_once_and_never_reads_the_dialog(self):
        self.send()
        self.session.visible = "DIALOG TEXT do you want to delete things"
        self.set_status("blocked")
        events = self.events()
        self.assertEqual([e["kind"] for e in events], ["needs-you"])
        self.assertEqual(events[0]["text"], "scratch needs you")
        self.assertNotIn("DIALOG", json.dumps(events))
        self.assertEqual(self.screen_reads(), [])

    def test_a_send_that_blocked_still_announces_its_finish(self):
        self.send()
        nonce = self.nonce()
        self.set_status("blocked")
        self.assertEqual(len(self.events()), 1)
        self.set_status("idle")
        self.session.visible = f"⏺ SPOKEN[{nonce}]: Finished.\n"
        self.settle()
        self.assertEqual([e["kind"] for e in self.events()], ["needs-you", "summary"])

    def test_session_stopping_says_check_screen(self):
        self.send()
        self.session.running = False
        events = self.events()
        self.assertEqual([e["kind"] for e in events], ["check-screen"])
        self.assertIn("scratch", events[0]["text"])
        self.assertIn("check the screen", events[0]["text"])

    def test_session_vanishing_says_check_screen(self):
        self.send()
        del self.runner.sessions[SCRATCH]
        self.assertEqual([e["kind"] for e in self.events()], ["check-screen"])

    def test_agent_gone_says_check_screen_after_settling(self):
        self.send()
        self.session.agents = []
        self.assertEqual(self.events(), [])
        self.settle()
        self.assertEqual([e["kind"] for e in self.events()], ["check-screen"])

    def test_pane_holding_another_agent_says_check_screen_after_settling(self):
        self.send()
        nonce = self.nonce()
        self.session.agents = [agent_row(terminal="term_new")]
        self.session.visible = f"⏺ SPOKEN[{nonce}]: Should not be read.\n"
        self.assertEqual(self.events(), [])
        self.settle()
        self.assertEqual([e["kind"] for e in self.events()], ["check-screen"])

    def test_herdr_failing_says_check_screen_after_settling(self):
        self.send()
        self.runner.fail[(SCRATCH, ("agent", "list"))] = err("server_not_running")
        self.assertEqual(self.events(), [])
        self.settle()
        self.assertEqual([e["kind"] for e in self.events()], ["check-screen"])

    def test_a_failed_read_of_a_settled_screen_does_not_break_the_poll(self):
        self.send()
        self.runner.fail[(SCRATCH, ("agent", "read"))] = err("agent_not_found")
        self.assertEqual(self.events(), [])
        self.settle()
        self.assertEqual([e["kind"] for e in self.events()], ["check-screen"])

    def test_a_new_send_replaces_the_record(self):
        self.send()
        old = self.nonce()
        self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        self.send()
        self.assertNotEqual(self.nonce(), old)
        self.session.visible = f"⏺ SPOKEN[{old}]: Old reply.\n"
        self.assertEqual(self.events(), [])
        self.session.visible = f"⏺ SPOKEN[{self.nonce()}]: New reply.\n"
        self.assertEqual([e["text"] for e in self.events()], ["New reply."])

    def test_a_refused_send_keeps_the_record_it_found(self):
        self.send()
        nonce = self.nonce()
        self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        self.runner.prompt_result = err("agent_blocked")
        self.refused(409, self.send)
        self.session.visible = f"⏺ SPOKEN[{nonce}]: First reply.\n"
        self.assertEqual([e["text"] for e in self.events()], ["First reply."])

    def test_a_send_refused_before_sending_makes_no_record(self):
        self.set_status("working")
        self.refused(409, self.send)
        self.assertEqual(self.app.sessions_view()["waiting"], [])

    def test_a_timed_out_send_may_have_arrived_so_it_is_recorded(self):
        self.runner.fail[(SCRATCH, ("agent", "prompt"))] = subprocess.TimeoutExpired("herdr", 15)
        self.refused(502, self.send)
        self.assertEqual(self.app.sessions_view()["waiting"], [SCRATCH])

    def test_either_view_announces_any_session(self):
        self.runner.sessions["wt-other"] = FakeSession()
        self.send()
        self.session.visible = f"⏺ SPOKEN[{self.nonce()}]: Done.\n"
        events = self.app.session_view("wt-other")["events"]
        self.assertEqual([e["session"] for e in events], [SCRATCH])

    def test_stopped_detail_view_still_carries_events(self):
        self.runner.sessions["wt-other"] = FakeSession(running=False)
        self.send()
        self.session.visible = f"⏺ SPOKEN[{self.nonce()}]: Done.\n"
        self.assertEqual(len(self.app.session_view("wt-other")["events"]), 1)

    def test_summary_text_is_capped_and_cleaned(self):
        self.send()
        self.session.visible = f"⏺ SPOKEN[{self.nonce()}]: " + "wo\x07rd " * 200 + "\n"
        text = self.events()[0]["text"]
        self.assertLessEqual(len(text), g.MAX_SPOKEN_CHARS)
        self.assertNotIn("\x07", text)

    def test_event_list_is_bounded(self):
        for _ in range(g.MAX_EVENTS + 5):
            self.send()
            self.session.visible = f"⏺ SPOKEN[{self.nonce()}]: Done.\n"
            self.events()
            self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        self.assertEqual(len(self.events()), g.MAX_EVENTS)

    def test_listing_failure_leaves_the_record_waiting(self):
        self.send()
        real = self.app.herdr.sessions
        calls = []

        def fail_second():
            calls.append(1)
            if len(calls) > 1:
                raise g.HerdrError("herdr_timeout")
            return real()
        self.app.herdr.sessions = fail_second
        self.assertEqual(self.app.sessions_view()["events"], [])
        self.assertEqual(self.app.announcements()["waiting"], [SCRATCH])

    def test_acknowledging_unknown_ids_is_harmless(self):
        self.assertEqual(self.app.acknowledge({"ids": ["nope"]}), {"ok": True})

    def test_acknowledge_validates(self):
        for payload in ({}, {"ids": "x"}, {"ids": [1]}, {"ids": ["x"] * 65}):
            self.refused(400, self.app.acknowledge, payload)

    def test_two_polls_at_once_make_one_event(self):
        self.send()
        self.session.visible = f"⏺ SPOKEN[{self.nonce()}]: Done.\n"
        with g.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda _i: self.app.announcements(), range(8)))
        self.assertEqual(len(self.events()), 1)


# ---------------------------------------------------------------- keys must match the screen

DIALOG = """───────────────
 Allow this?

 ❯ 1. Yes
   2. No

 Enter to confirm · Esc to cancel
"""


class ScreenTokenTests(unittest.TestCase):
    def test_same_screen_same_token(self):
        self.assertEqual(g.screen_token(DIALOG), g.screen_token(DIALOG))

    def test_different_selection_different_token(self):
        self.assertNotEqual(g.screen_token(DIALOG), g.screen_token(DIALOG.replace("❯ 1. Yes\n   2. No", "  1. Yes\n ❯ 2. No")))

    def test_trailing_spaces_and_outer_blank_lines_do_not_count(self):
        self.assertEqual(g.screen_token(DIALOG), g.screen_token("\n\n" + DIALOG.replace("\n", "   \n") + "\n\n"))

    def test_empty_screen_has_no_token(self):
        for text in ("", "   \n \n", None):
            self.assertIsNone(g.screen_token(text))

    def test_handles_odd_text(self):
        self.assertTrue(g.screen_token("a\ud800b"))


class KeyTokenTests(GatewayCase):
    def setUp(self):
        super().setUp()
        self.set_status("blocked")
        self.session = self.runner.sessions[SCRATCH]
        self.session.visible = DIALOG

    def view(self):
        return self.app.session_view(SCRATCH)

    def press(self, key="1", **extra):
        return self.app.keys({"session": SCRATCH, "key": key, **extra})

    def sent(self):
        return [c["argv"] for c in self.runner.named("agent", "send-keys")]

    def refusal(self, **extra):
        with self.assertRaises(g.RequestRejected) as caught:
            self.press(**extra)
        self.assertEqual(caught.exception.status, 409)
        return caught.exception

    def test_view_shows_the_dialog_from_the_read_that_made_the_token(self):
        view = self.view()
        self.assertEqual(view["output"], DIALOG)
        self.assertEqual(view["screen_token"], g.screen_token(DIALOG))
        reads = [c["argv"] for c in self.runner.named("agent", "read")]
        self.assertEqual(len(reads), 1)
        self.assertEqual(reads[0][5:], ["w1:p1", "--source", "visible", "--lines", "40"])

    def test_no_token_unless_blocked(self):
        for status in ("idle", "done", "working", "unknown"):
            self.set_status(status)
            self.assertIsNone(self.view()["screen_token"], status)

    def test_stopped_or_no_orchestrator_has_no_token(self):
        self.session.agents = []
        self.assertIsNone(self.view()["screen_token"])
        self.session.running = False
        self.assertIsNone(self.view()["screen_token"])

    def test_matching_token_sends(self):
        self.assertEqual(self.press(screen_token=self.view()["screen_token"]), {"ok": True})
        self.assertEqual(len(self.sent()), 1)

    def test_server_rereads_right_before_sending(self):
        token = self.view()["screen_token"]
        self.runner.calls.clear()
        self.press(screen_token=token)
        verbs = [c["argv"][3:5] for c in self.runner.calls if c["argv"][:2] == ["herdr", "--session"]]
        self.assertEqual(verbs[-2:], [["agent", "read"], ["agent", "send-keys"]])
        read = self.runner.named("agent", "read")[-1]["argv"]
        self.assertEqual(read[5:], ["w1:p1", "--source", "visible", "--lines", "40"])

    def test_changed_screen_refuses_with_the_new_one(self):
        token = self.view()["screen_token"]
        other = DIALOG.replace("Allow this?", "Delete everything?")
        self.session.visible = other
        refused = self.refusal(screen_token=token)
        self.assertEqual(self.sent(), [])
        self.assertEqual(refused.extra["output"], other)
        self.assertEqual(refused.extra["screen_token"], g.screen_token(other))
        self.assertIn("changed", refused.message)

    def test_missing_or_bad_token_fails_closed(self):
        for payload in ({}, {"screen_token": None}, {"screen_token": ""}, {"screen_token": 3}, {"screen_token": ["x"]},
                        {"screen_token": "nope"}):
            self.refusal(**payload)
        self.assertEqual(self.sent(), [])

    def test_blank_screen_fails_closed(self):
        self.session.visible = "   \n"
        self.refusal(screen_token=g.screen_token(DIALOG))
        self.assertEqual(self.sent(), [])

    def test_read_failure_fails_closed(self):
        token = self.view()["screen_token"]
        self.runner.fail[(SCRATCH, ("agent", "read"))] = err("agent_not_found")
        self.refused(409, self.press, "1")
        self.runner.fail[(SCRATCH, ("agent", "read"))] = err("boom")
        with self.assertRaises(g.RequestRejected):
            self.press(screen_token=token)
        self.assertEqual(self.sent(), [])

    def test_keys_hidden_when_the_screen_cannot_be_read(self):
        self.runner.fail[(SCRATCH, ("agent", "read"))] = err("boom")
        view = self.view()
        self.assertEqual(view["actions"]["keys"], [])
        self.assertIsNone(view["screen_token"])
        self.assertTrue(view["problem"])

    def test_refusal_does_not_start_the_repeat_wait(self):
        token = self.view()["screen_token"]
        self.refusal(screen_token="old")
        self.press(screen_token=token)
        self.assertEqual(len(self.sent()), 1)

    def test_a_second_tap_on_a_moved_dialog_is_refused(self):
        token = self.view()["screen_token"]
        self.press("down", screen_token=token)
        self.session.visible = DIALOG.replace("❯ 1. Yes\n   2. No", "  1. Yes\n ❯ 2. No")
        self.clock.t += g.NAVIGATE_REPEAT_SECONDS + 0.01
        self.refusal(key="enter", screen_token=token)
        self.assertEqual([a[6:] for a in self.sent()], [["down"]])

    def test_stop_takes_no_token(self):
        self.set_status("working")
        self.assertEqual(self.app.stop({"session": SCRATCH}), {"ok": True})


# ---------------------------------------------------------------- half-typed text
# Styled reads captured from a real Claude Code box on the scratch session, with the footer shortened.

BOX_RULE = "\x1b[0m\x1b[38;2;136;136;136m" + "─" * 119 + "\x1b[0m"
BOX_FOOT = ("  \x1b[0m\x1b[1m\x1b[38;5;6mscratch\x1b[0m\x1b[38;2;153;153;153m on \x1b[0m\x1b[1m\x1b[38;5;5m master\x1b[0m\r\n"
            "  \x1b[0m\x1b[38;2;255;107;128m⏵⏵ bypass permissions on\x1b[0m\x1b[38;2;153;153;153m (shift+tab to cycle)\x1b[0m\r\n")
BOX_TIP = "\x1b[0m\x1b[48;2;48;48;48m \x1b[0m\x1b[38;2;153;153;153m\x1b[48;2;48;48;48m○ Tip \x1b[0m\r\n"


def box(*lines, above="earlier reply\r\n" + BOX_TIP):
    return above + BOX_RULE + "\r\n" + "".join(line + "\r\n" for line in lines) + BOX_RULE + "\r\n" + BOX_FOOT


# Claude puts a no-break space after the arrow, which is easy to miss.
EMPTY_BOX = box('❯\xa0\x1b[0m\x1b[2mTry "refactor <filepath>"\x1b[0m')
TYPED_BOX = box("❯\xa0half typed words")


class DraftStateTests(unittest.TestCase):
    def state(self, screen, kind="claude"):
        return g.draft_state(kind, screen)

    def test_empty_box_with_its_dim_placeholder_is_clear(self):
        self.assertEqual(self.state(EMPTY_BOX), "clear")

    def test_bare_empty_box_is_clear(self):
        self.assertEqual(self.state(box("❯ ")), "clear")
        self.assertEqual(self.state(box("❯    ")), "clear")

    def test_typed_text(self):
        self.assertEqual(self.state(TYPED_BOX), "present")

    def test_typed_words_that_look_like_the_placeholder(self):
        # A plain read cannot tell this from an empty box. Only the styling can.
        self.assertEqual(self.state(box('❯ Try "refactor <filepath>"')), "present")

    def test_multi_line_typed_text(self):
        self.assertEqual(self.state(box("❯ first line of draft", "  second line of draft")), "present")

    def test_long_wrapped_text_and_paste(self):
        self.assertEqual(self.state(box("❯ long draft words", "  words long", "  draft words")), "present")
        self.assertEqual(self.state(box("❯ pasted one     ", "  pasted two", "  pasted three")), "present")

    def test_typed_slash_with_the_menu_above(self):
        above = "  \x1b[0m\x1b[38;2;153;153;153m  /\x1b[0m\x1b[1mhe\x1b[0m\x1b[38;2;153;153;153mlp   Show help\x1b[0m\r\n" + BOX_TIP
        self.assertEqual(self.state(box("❯ /he", above=above)), "present")

    def test_shell_mode_counts_as_typed(self):
        # The `!` was typed at the Mac, and anything sent now would run as a shell command.
        pink = "\x1b[0m\x1b[38;2;253;93;177m"
        screen = (f"{pink}{'─' * 119}\x1b[0m\r\n{pink}! \x1b[0m\x1b[2mTry \"refactor <filepath>\"\x1b[0m\r\n"
                  f"{pink}{'─' * 119}\x1b[0m\r\n  {pink}! for shell mode\x1b[0m\r\n")
        self.assertEqual(self.state(screen), "present")

    def test_dim_ghost_text_after_typed_text_is_still_typed(self):
        self.assertEqual(self.state(box("❯ typ\x1b[0m\x1b[2med ghost\x1b[0m")), "present")

    def test_rule_lines_in_the_reply_do_not_confuse_it(self):
        above = f"{BOX_RULE}\r\n❯ old typed looking text\r\n{BOX_RULE}\r\nreply\r\n"
        self.assertEqual(self.state(box('❯ \x1b[0m\x1b[2mTry\x1b[0m', above=above)), "clear")

    def test_anything_unexpected_is_unknown_never_clear(self):
        for screen in ("", "no box at all\r\n", f"{BOX_RULE}\r\n", f"{BOX_RULE}\r\n{BOX_RULE}\r\n",
                       box("plain line without a prompt"), box("\x1b[38;5;1m❯ coloured\x1b[0m", above=""),
                       box("❯ \x1b[38;5;1mcoloured words\x1b[0m")):
            self.assertEqual(self.state(screen), "unknown", repr(screen))

    def test_other_kinds_are_always_unknown(self):
        for kind in ("codex", "amp", "kiro", "pi", "agy", ""):
            self.assertEqual(self.state(TYPED_BOX, kind), "unknown", kind)
            self.assertEqual(self.state(EMPTY_BOX, kind), "unknown", kind)


class DraftFlowTests(GatewayCase):
    def setUp(self):
        super().setUp()
        self.session = self.runner.sessions[SCRATCH]
        self.session.ansi = EMPTY_BOX

    def send(self, text="hello"):
        return self.app.prompt({"session": SCRATCH, "text": text})

    def prompts(self):
        return self.runner.named("agent", "prompt")

    def ansi_reads(self):
        return [c["argv"] for c in self.runner.named("agent", "read") if "ansi" in c["argv"]]

    def test_view_says_clear_for_an_empty_box(self):
        self.assertEqual(self.app.session_view(SCRATCH)["draft"], "clear")

    def test_view_says_present_for_typed_text(self):
        self.session.ansi = TYPED_BOX
        self.assertEqual(self.app.session_view(SCRATCH)["draft"], "present")

    def test_styled_read_stays_on_the_screen(self):
        self.session.panes = [pane_row(rows=23)]
        self.app.session_view(SCRATCH)
        self.assertEqual(self.ansi_reads()[0][5:], ["w1:p1", "--source", "visible", "--lines", "23", "--format", "ansi"])

    def test_view_reads_no_box_unless_text_could_be_sent(self):
        for status in ("working", "blocked", "unknown"):
            self.set_status(status)
            self.runner.calls.clear()
            self.assertEqual(self.app.session_view(SCRATCH)["draft"], "unknown", status)
            self.assertEqual(self.ansi_reads(), [], status)

    def test_other_kinds_are_not_read_and_warn(self):
        self.session.agents = [agent_row(kind="codex")]
        self.assertEqual(self.app.session_view(SCRATCH)["draft"], "unknown")
        self.assertEqual(self.send()["draft"], "unknown")
        self.assertEqual(self.ansi_reads(), [])
        self.assertEqual(len(self.prompts()), 1)

    def test_typed_text_refuses_the_send_and_touches_nothing(self):
        self.session.ansi = TYPED_BOX
        message = self.refused(409, self.send)
        self.assertIn("unsent text", message)
        self.assertIn("Mac", message)
        self.assertEqual(self.prompts(), [])
        self.assertEqual(self.runner.named("agent", "send-keys"), [])
        self.assertEqual(self.app.sessions_view()["waiting"], [])

    def test_a_refusal_does_not_start_the_repeat_wait(self):
        self.session.ansi = TYPED_BOX
        self.refused(409, self.send)
        self.session.ansi = EMPTY_BOX
        self.assertEqual(self.send()["draft"], "clear")
        self.assertEqual(len(self.prompts()), 1)

    def test_checked_right_before_sending(self):
        self.send()
        verbs = [c["argv"][3:5] for c in self.runner.calls if c["argv"][:2] == ["herdr", "--session"]]
        self.assertEqual(verbs[-3:], [["pane", "list"], ["agent", "read"], ["agent", "prompt"]])

    def test_unknown_box_does_not_refuse(self):
        self.session.ansi = "no box here"
        self.assertEqual(self.send()["draft"], "unknown")
        self.assertEqual(len(self.prompts()), 1)

    def test_read_failure_fails_open_and_says_so(self):
        self.runner.fail[(SCRATCH, ("agent", "read"))] = err("boom")
        self.assertEqual(self.send()["draft"], "unknown")
        self.assertEqual(len(self.prompts()), 1)
        self.assertEqual(self.app.session_view(SCRATCH)["draft"], "unknown")


# ---------------------------------------------------------------- the finish after a dialog

class DialogThenFinishTests(GatewayCase):
    send, sent_text, nonce, events, settle, screen_reads = (
        AnnouncementTests.send, AnnouncementTests.sent_text, AnnouncementTests.nonce, AnnouncementTests.events,
        AnnouncementTests.settle, AnnouncementTests.screen_reads)

    def setUp(self):
        super().setUp()
        self.session = self.runner.sessions[SCRATCH]

    def kinds(self):
        return [e["kind"] for e in self.events()]

    def finish(self, nonce, text="Finished."):
        self.set_status("idle")
        self.session.visible = f"⏺ SPOKEN[{nonce}]: {text}\n"

    def test_needs_you_then_the_summary_with_its_own_id(self):
        self.send()
        nonce = self.nonce()
        self.set_status("blocked")
        self.assertEqual(self.kinds(), ["needs-you"])
        self.assertEqual(self.app.sessions_view()["waiting"], [SCRATCH])
        self.finish(nonce, "Tea it is.")
        events = self.events()
        self.assertEqual([(e["kind"], e["text"]) for e in events], [("needs-you", "scratch needs you"),
                                                                     ("summary", "Tea it is.")])
        self.assertEqual(len({e["id"] for e in events}), 2)
        self.assertEqual(self.app.sessions_view()["waiting"], [])

    def test_staying_blocked_over_many_polls_says_needs_you_once(self):
        self.send()
        self.set_status("blocked")
        for _ in range(5):
            self.events()
            self.clock.t += g.SETTLE_SECONDS
        self.assertEqual(self.kinds(), ["needs-you"])
        self.assertEqual(self.screen_reads(), [])

    def test_each_new_block_says_needs_you_again(self):
        self.send()
        nonce = self.nonce()
        for between in ("working", "idle", "done"):
            self.set_status("blocked")
            self.events()
            self.set_status(between)
            self.session.visible = "between dialogs"
            self.events()
        self.set_status("blocked")
        self.events()
        self.finish(nonce)
        events = self.events()
        self.assertEqual([e["kind"] for e in events], ["needs-you"] * 4 + ["summary"])
        self.assertEqual(len({e["id"] for e in events}), 5)

    def test_acknowledged_needs_you_is_not_said_again_while_still_blocked(self):
        self.send()
        nonce = self.nonce()
        self.set_status("blocked")
        first = self.events()
        self.app.acknowledge({"ids": [first[0]["id"]]})
        self.assertEqual(self.events(), [])
        self.finish(nonce)
        self.assertEqual(self.kinds(), ["summary"])

    def test_finish_is_said_once(self):
        self.send()
        nonce = self.nonce()
        self.set_status("blocked")
        self.events()
        self.finish(nonce)
        self.events()
        self.set_status("blocked")
        self.events()
        self.settle()
        self.assertEqual(self.kinds(), ["needs-you", "summary"])

    def test_time_on_a_dialog_does_not_count_as_settling(self):
        self.send()
        self.set_status("blocked")
        self.events()
        self.clock.t += g.SETTLE_SECONDS * 4
        self.set_status("idle")
        self.session.visible = "no line yet"
        self.assertEqual(self.kinds(), ["needs-you"])
        self.settle()
        self.assertEqual(self.kinds(), ["needs-you", "check-screen"])

    def test_quiet_before_a_dialog_does_not_carry_over(self):
        self.send()
        self.session.visible = "no line yet"
        self.events()
        self.clock.t += g.SETTLE_SECONDS - 1
        self.set_status("blocked")
        self.events()
        self.set_status("idle")
        self.clock.t += 2
        self.assertEqual(self.kinds(), ["needs-you"])

    def test_session_stopping_while_blocked_says_check_screen(self):
        self.send()
        self.set_status("blocked")
        self.events()
        self.session.running = False
        self.assertEqual(self.kinds(), ["needs-you", "check-screen"])
        self.assertEqual(self.app.sessions_view()["waiting"], [])

    def test_command_that_blocks_then_settles(self):
        self.send("/compact")
        self.set_status("blocked")
        self.events()
        self.set_status("idle")
        self.events()
        self.settle()
        self.assertEqual(self.kinds(), ["needs-you", "check-screen"])
        self.assertEqual(self.screen_reads(), [])

    def test_a_new_send_replaces_a_blocked_record(self):
        self.send("first question")
        old_nonce = self.nonce()
        old_record = self.app.announcer.pending[SCRATCH]
        self.set_status("blocked")
        self.events()
        self.set_status("idle")  # Answered at the Mac between two polls.
        self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        self.send("second question")
        self.assertIsNot(self.app.announcer.pending[SCRATCH], old_record)
        self.session.visible = f"⏺ SPOKEN[{old_nonce}]: Old reply.\n"
        self.assertEqual(self.kinds(), ["needs-you"])  # The new send starts clear, and the old tag is not its own.
        self.set_status("blocked")
        self.events()
        self.finish(self.nonce(), "New reply.")
        events = self.events()
        self.assertEqual([(e["kind"], e["text"]) for e in events],
                         [("needs-you", "scratch needs you"), ("needs-you", "scratch needs you"),
                          ("summary", "New reply.")])
        self.assertEqual(len({e["id"] for e in events}), 3)

    def test_a_replaced_record_makes_no_more_events(self):
        self.send()
        old_record = self.app.announcer.pending[SCRATCH]
        self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        self.send()
        self.app.announcer.emit(old_record, "needs-you", "stale", final=False)
        self.app.announcer.emit(old_record, "summary", "stale")
        self.assertEqual(self.app.announcer.unacknowledged(), [])

    def test_two_polls_at_once_on_a_dialog_make_one_needs_you(self):
        self.send()
        self.set_status("blocked")
        with g.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda _i: self.app.announcements(), range(8)))
        self.assertEqual(self.kinds(), ["needs-you"])


# ---------------------------------------------------------------- the reap marker

class ClearReapMarkerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.reap = self.base / "wt-reap"
        self.reap.mkdir()

    def test_removes_a_regular_file(self):
        (self.reap / SCRATCH).write_text("1794000000\n")
        self.assertEqual(g.clear_reap_marker(self.reap, SCRATCH), "removed")
        self.assertFalse((self.reap / SCRATCH).exists())

    def test_missing_file_and_missing_directory_are_fine(self):
        self.assertEqual(g.clear_reap_marker(self.reap, SCRATCH), "missing")
        gone = self.base / "no-such-dir"
        self.assertEqual(g.clear_reap_marker(gone, SCRATCH), "missing")
        self.assertFalse(gone.exists())

    def test_no_directory_configured(self):
        self.assertEqual(g.clear_reap_marker(None, SCRATCH), "missing")

    def test_names_that_are_not_one_plain_session_name_are_refused(self):
        outside = self.base / "victim"
        outside.write_text("keep")
        (self.reap / "wt-a").mkdir()
        (self.reap / "wt-a" / "b").write_text("keep")
        for name in ("wt-a/b", "../victim", "wt-../victim", "/etc/hosts", "wt-a\\b", ".", "..", "", None, 3,
                     "victim", "wt-a\x00b", "wt-" + "x" * 70):
            self.assertEqual(g.clear_reap_marker(self.reap, name), "refused", name)
        self.assertEqual(outside.read_text(), "keep")
        self.assertEqual((self.reap / "wt-a" / "b").read_text(), "keep")

    def test_a_symlink_is_not_followed_or_removed(self):
        target = self.base / "target"
        target.write_text("keep")
        (self.reap / SCRATCH).symlink_to(target)
        self.assertEqual(g.clear_reap_marker(self.reap, SCRATCH), "kept")
        self.assertTrue((self.reap / SCRATCH).is_symlink())
        self.assertEqual(target.read_text(), "keep")

    def test_a_directory_is_left_alone(self):
        (self.reap / SCRATCH).mkdir()
        (self.reap / SCRATCH / "inside").write_text("keep")
        self.assertEqual(g.clear_reap_marker(self.reap, SCRATCH), "kept")
        self.assertEqual((self.reap / SCRATCH / "inside").read_text(), "keep")

    def test_a_reap_directory_that_is_a_symlink_is_not_followed(self):
        real = self.base / "real-reap"
        real.mkdir()
        (real / SCRATCH).write_text("1794000000\n")
        link = self.base / "linked-reap"
        link.symlink_to(real)
        self.assertEqual(g.clear_reap_marker(link, SCRATCH), "refused")
        self.assertTrue((real / SCRATCH).exists())

    def test_a_reap_path_that_is_a_file_is_refused(self):
        not_dir = self.base / "file"
        not_dir.write_text("x")
        self.assertIn(g.clear_reap_marker(not_dir, SCRATCH), ("refused", "missing"))
        self.assertEqual(not_dir.read_text(), "x")

    def test_nothing_else_in_the_directory_changes(self):
        (self.reap / SCRATCH).write_text("1")
        for other in ("wt-other", f"{SCRATCH}.bak", f"x{SCRATCH}", f"{SCRATCH}-2"):
            (self.reap / other).write_text("keep")
        before = {p.name: (p.read_text(), p.stat().st_mtime_ns) for p in self.reap.iterdir() if p.name != SCRATCH}
        g.clear_reap_marker(self.reap, SCRATCH)
        after = {p.name: (p.read_text(), p.stat().st_mtime_ns) for p in self.reap.iterdir()}
        self.assertEqual(after, before)

    def test_an_unwritable_directory_is_a_quiet_failure(self):
        (self.reap / SCRATCH).write_text("1")
        self.reap.chmod(0o500)
        self.addCleanup(self.reap.chmod, 0o700)
        self.assertEqual(g.clear_reap_marker(self.reap, SCRATCH), "failed")


class ReapOnSendTests(GatewayCase):
    def setUp(self):
        super().setUp()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.reap = Path(self.tmp.name) / "wt-reap"
        self.reap.mkdir()
        self.app = build(self.runner, self.clock, config=replace(CONFIG, reap_dir=self.reap))
        self.marker = self.reap / SCRATCH
        self.marker.write_text("1794000000\n")
        self.session = self.runner.sessions[SCRATCH]

    def send(self, text="hello"):
        return self.app.prompt({"session": SCRATCH, "text": text})

    def test_a_sent_prompt_removes_the_marker_after_herdr_accepts_it(self):
        seen = []
        self.runner.on_prompt = lambda: seen.append(self.marker.exists())
        self.send()
        self.assertEqual(seen, [True])
        self.assertFalse(self.marker.exists())

    def test_commands_count_too(self):
        self.send("/compact")
        self.assertFalse(self.marker.exists())

    def test_only_this_sessions_marker(self):
        other = self.reap / "wt-other"
        other.write_text("1")
        self.send()
        self.assertEqual(other.read_text(), "1")

    def test_refusals_keep_the_marker(self):
        self.set_status("working")
        self.refused(409, self.send)
        self.set_status("idle")
        self.runner.prompt_result = err("agent_blocked")
        self.refused(409, self.send)
        self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        self.session.ansi = TYPED_BOX
        self.refused(409, self.send)
        self.assertTrue(self.marker.exists())

    def test_a_timeout_keeps_the_marker(self):
        self.runner.fail[(SCRATCH, ("agent", "prompt"))] = subprocess.TimeoutExpired("herdr", 15)
        self.refused(502, self.send)
        self.assertTrue(self.marker.exists())

    def test_keys_stop_and_restart_keep_the_marker(self):
        self.set_status("blocked")
        self.app.keys({"session": SCRATCH, "key": "1", "screen_token": g.screen_token("visible screen")})
        self.clock.t += 60
        self.set_status("working")
        self.app.stop({"session": SCRATCH})
        self.clock.t += 60
        self.session.running = False
        self.refused(409, self.app.restart, {"session": SCRATCH})  # No saved state here, so it refuses.
        self.assertTrue(self.marker.exists())

    def test_viewing_keeps_the_marker(self):
        self.app.sessions_view()
        self.app.session_view(SCRATCH)
        self.assertTrue(self.marker.exists())

    def test_a_failure_to_remove_never_fails_the_send(self):
        self.reap.chmod(0o500)
        self.addCleanup(self.reap.chmod, 0o700)
        self.assertEqual(self.send()["ok"], True)
        self.assertTrue(self.marker.exists())

    def test_no_reap_directory_is_fine(self):
        app = build(self.runner, self.clock, config=replace(CONFIG, reap_dir=Path(self.tmp.name) / "absent"))
        self.assertEqual(app.prompt({"session": SCRATCH, "text": "hi"})["ok"], True)
        self.assertFalse((Path(self.tmp.name) / "absent").exists())


class ReapConfigTests(unittest.TestCase):
    def test_default_is_under_home(self):
        config = g.Config.from_env({"HOME": "/h/someone"})
        self.assertEqual(config.reap_dir, Path("/h/someone/.config/herdr/wt-reap"))

    def test_override(self):
        config = g.Config.from_env({"HOME": "/h/someone", "VOICE_GATEWAY_REAP_DIR": "/t/reap"})
        self.assertEqual(config.reap_dir, Path("/t/reap"))

    def test_no_home_means_no_reap_directory(self):
        self.assertIsNone(g.Config.from_env({}).reap_dir)

    def test_the_shared_test_config_touches_no_real_directory(self):
        self.assertIsNone(CONFIG.reap_dir)

    def test_a_relative_override_is_refused_at_start(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp) / "data"
            g.store_passcode(data, g.hash_passcode("long enough", iterations=10))
            out = io.StringIO()
            with redirect_stderr(out):
                code = g.main([], env={"VOICE_GATEWAY_DATA": str(data), "VOICE_GATEWAY_REAP_DIR": "rel/reap"},
                              runner=FakeTailscale())
        self.assertEqual(code, 1)
        self.assertIn("VOICE_GATEWAY_REAP_DIR", out.getvalue())


# ---------------------------------------------------------------- Deepgram voice

KEY = "0123456789abcdef0123456789abcdef01234567"
FAKE_MP3 = b"ID3\x04\x00\x00\x00\x00\x00\x00" + b"\xff\xfb\x90\x00" + b"\x00" * 64


class FakeDeepgram:
    """A local stand-in for POST /v1/speak and /v2/speak as the docs describe them: Token auth, JSON {"text"},
    audio/mpeg back. Like the real one, /v1/speak takes Aura models only and /v2/speak Flux models only (400
    otherwise), and any other path is 404. `url` is the origin only, since the voice decides the path.

    HTTP/1.1 keep-alive like the real one. `chunks` sends the answer chunked, one chunk at a time, each after its gate
    in `gates` if it has one. `misbehave` maps a request number (from 1) to "hang-up" (close with no answer),
    "partial-status", "partial-body" or "cut" (one chunk, then close). `idle_timeout` closes idle connections.
    """
    def __init__(self, tls=None):
        self.requests = []
        self.status, self.content_type, self.body, self.delay, self.extra_headers = 200, "audio/mpeg", FAKE_MP3, 0, {}
        self.chunks, self.gates, self.drip, self.misbehave, self.idle_timeout = None, {}, 0, {}, None
        self.connections = 0  # TCP connections accepted, used or not.
        self.live = 0  # Connections still open.
        self.lock = threading.Lock()
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def setup(self):
                self.timeout = fake.idle_timeout
                super().setup()
                with fake.lock:
                    fake.connections += 1
                    fake.live += 1

            def finish(self):
                with fake.lock:
                    fake.live -= 1
                super().finish()

            def do_POST(self):
                length = int(self.headers.get("Content-Length") or 0)
                with fake.lock:
                    fake.requests.append({"path": self.path, "headers": dict(self.headers),
                                          "body": self.rfile.read(length)})
                    action = fake.misbehave.get(len(fake.requests))
                try:
                    self.answer(action)
                except OSError:
                    self.close_connection = True

            def wrong_place(self):
                """The real service's split: Aura on /v1/speak, Flux on /v2/speak, nothing else."""
                parts = g.urlsplit(self.path)
                family = {"/v1/speak": "aura-", "/v2/speak": "flux-"}.get(parts.path)
                model = g.parse_qs(parts.query).get("model", [""])[0]
                if family is None:
                    return 404
                return None if model.startswith(family) else 400

            def answer(self, action):
                refused = self.wrong_place()
                if refused is not None:
                    body = json.dumps({"err_code": "WRONG_PLACE", "err_msg": "m", "request_id": "r"}).encode()
                    self.send_response(refused)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                if action == "hang-up":
                    self.close_connection = True
                    return
                if action == "partial-status":
                    self.wfile.write(b"HTTP/1.1 2")
                    self.close_connection = True
                    return
                if action == "partial-body":
                    self.wfile.write(b"HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: 1000\r\n\r\nID3")
                    self.close_connection = True
                    return
                if fake.delay:
                    time.sleep(fake.delay)
                self.send_response(fake.status)
                self.send_header("Content-Type", fake.content_type)
                for name, value in fake.extra_headers.items():
                    self.send_header(name, value)
                if fake.chunks is None:
                    self.send_header("Content-Length", str(len(fake.body)))
                    self.end_headers()
                    self.wfile.write(fake.body)
                    return
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                for index, chunk in enumerate(fake.chunks):
                    if index in fake.gates:
                        fake.gates[index].wait(10)
                    if index and fake.drip:
                        time.sleep(fake.drip)
                    self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                    if action == "cut":
                        self.close_connection = True
                        return
                self.wfile.write(b"0\r\n\r\n")

            def log_message(self, *args):
                pass

        class Server(http.server.ThreadingHTTPServer):
            daemon_threads = True

            def handle_error(self, request, client_address):
                pass

        self.server = Server(("127.0.0.1", 0), Handler)
        if tls is not None:  # An ssl.SSLContext for the server side: the fake then speaks HTTPS, as Deepgram does.
            self.server.socket = tls.wrap_socket(self.server.socket, server_side=True)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        scheme, host = ("https", "localhost") if tls is not None else ("http", "127.0.0.1")
        self.url = f"{scheme}://{host}:{self.server.server_address[1]}"

    def close(self):
        for gate in self.gates.values():
            gate.set()
        self.server.shutdown()
        self.server.server_close()

    def last(self):
        request = self.requests[-1]
        parts = g.urlsplit(request["path"])
        return parts.path, g.parse_qs(parts.query), request["headers"], json.loads(request["body"])


def wait_for(condition, seconds=5.0):
    """True once condition() holds, polling; False if it never did within the time."""
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if condition():
            return True
        time.sleep(0.01)
    return condition()


class FakeVoice:
    """Stands in for DeepgramVoice inside the gateway. Each clip comes in two chunks.

    `before[text]` is waited for before the first chunk, `gates[text]` before the second. `fail` fails before any
    audio, `fail_after` after the first chunk.
    """
    def __init__(self):
        self.texts = []
        self.fail = None
        self.fail_after = None
        self.audio = FAKE_MP3
        self.before, self.gates = {}, {}
        self.lock = threading.Lock()

    def stream(self, text):
        with self.lock:
            self.texts.append(text)
            number = len(self.texts)
        if text in self.before:
            self.before[text].wait(10)
        if self.fail:
            raise self.fail
        audio = self.audio + str(number).encode()
        yield audio[:len(audio) // 2]
        if text in self.gates:
            self.gates[text].wait(10)
        if self.fail_after:
            raise self.fail_after
        yield audio[len(audio) // 2:]

    def synthesise(self, text):
        return b"".join(self.stream(text))

    def release(self):
        for gate in (*self.before.values(), *self.gates.values()):
            gate.set()


AURA_NAMES = ("aura-2-thalia-en", "aura-asteria-en", "aura-2-draco-en", "aura-2-celeste-es")
FLUX_NAMES = ("flux-cole-en", "flux-haley-en", "flux-alexis-de", "flux-ab-fil", "flux-" + "a" * 20 + "-en")


class DeepgramValidationTests(unittest.TestCase):
    def test_voice_names(self):
        for name in AURA_NAMES + FLUX_NAMES:
            self.assertTrue(g.valid_deepgram_voice(name), name)
        for name in ("", "thalia", "aura-2-thalia-en&x=1", "aura-2-Thalia-en", " aura-2-thalia-en", "aura-2-thalia",
                     "aura-2-thalia-en/", "../aura", "aura-2-" + "a" * 60 + "-en", None, 3,
                     "flux-Cole-en", "FLUX-cole-en", "flux-cole-EN", "flux-cole", "flux-cole-e", "flux-cole-engl",
                     "flux--en", "flux-c-en", "flux-2-cole-en", "flux-cole-en-x", "flux-cole-en ", " flux-cole-en",
                     "flux-cole-en&encoding=wav", "flux-cole-en/", "flux-cole_x-en", "flux-" + "a" * 21 + "-en",
                     "aura-2-cole-eng", "flux-colé-en", "cole-en", "v2/flux-cole-en"):
            self.assertFalse(g.valid_deepgram_voice(name), name)

    def test_the_voice_name_decides_the_endpoint(self):
        for name in FLUX_NAMES:
            self.assertEqual(g.deepgram_endpoint(name), "v2", name)
        for name in AURA_NAMES:
            self.assertEqual(g.deepgram_endpoint(name), "v1", name)
        for name in ("flux-Cole-en", "aura-2-thalia-en&x=1", "eleven-x-en", "", None):
            self.assertIsNone(g.deepgram_endpoint(name), name)
        self.assertEqual(g.DEEPGRAM_PATHS, {"v1": "/v1/speak", "v2": "/v2/speak"})

    def test_default_voice_is_flux_cole(self):
        self.assertEqual(g.DEFAULT_DEEPGRAM_VOICE, "flux-cole-en")
        self.assertEqual(g.Config.from_env({}).deepgram_voice, "flux-cole-en")
        for name in ("aura-2-draco-en", "aura-2-thalia-en", "flux-haley-en"):
            self.assertEqual(g.Config.from_env({"VOICE_GATEWAY_DEEPGRAM_VOICE": name}).deepgram_voice, name)

    def test_text_limit_per_endpoint(self):
        self.assertEqual(g.DEEPGRAM_MAX_CHARS, {"v1": 2000, "v2": 2000})

    def test_real_endpoint(self):
        self.assertEqual(g.DEEPGRAM_URL, "https://api.deepgram.com")
        self.assertEqual(g.Config.from_env({}).deepgram_url, g.DEEPGRAM_URL)

    def test_test_endpoint_must_be_loopback(self):
        for url in ("http://127.0.0.1:9", "https://localhost:8443", "http://[::1]:9", "http://127.0.0.1/",
                    "http://127.0.0.1"):
            self.assertTrue(g.valid_test_voice_url(url), url)
        for url in ("https://api.deepgram.com", "http://example.com", "http://127.0.0.1.nip.io/",
                    "http://localhost.evil.com/", "http://user:pw@127.0.0.1", "ftp://127.0.0.1/",
                    "http://127.0.0.1/?model=x", "http://127.0.0.1/#x", " http://127.0.0.1/",
                    "http://127.0.0.1/a b", "http://100.96.1.2", "", None, "127.0.0.1:9",
                    "http://127.0.0.1:9/v1/speak", "http://127.0.0.1:9/v2/speak", "http://127.0.0.1:9//"):
            self.assertFalse(g.valid_test_voice_url(url), url)

    def test_test_endpoint_comes_only_from_its_own_variable(self):
        config = g.Config.from_env({"VOICE_GATEWAY_DEEPGRAM_TEST_URL": "http://127.0.0.1:9"})
        self.assertEqual(config.deepgram_url, "http://127.0.0.1:9")

    def run_main(self, env):
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp) / "data"
            g.store_passcode(data, g.hash_passcode("long enough", iterations=10))
            out = io.StringIO()
            with redirect_stderr(out):
                code = g.main([], env={"VOICE_GATEWAY_DATA": str(data), **env}, runner=FakeTailscale())
        return code, out.getvalue()

    def test_start_refuses_a_bad_voice_or_test_endpoint(self):
        for name in ("aura-2-thalia-en&x=1", "flux-Cole-en", "eleven-cole-en", "flux-cole-en&speed=1.5"):
            code, out = self.run_main({"VOICE_GATEWAY_DEEPGRAM_VOICE": name})
            self.assertEqual(code, 1, name)
            self.assertIn("VOICE_GATEWAY_DEEPGRAM_VOICE", out)
            self.assertIn("flux-cole-en", out)  # Both forms are named.
            self.assertIn("aura-2-thalia-en", out)
        for url in ("https://api.deepgram.com", "http://127.0.0.1:9/v1/speak"):
            code, out = self.run_main({"VOICE_GATEWAY_DEEPGRAM_TEST_URL": url})
            self.assertEqual(code, 1, url)
            self.assertIn("VOICE_GATEWAY_DEEPGRAM_TEST_URL", out)


class DeepgramVoiceTests(unittest.TestCase):
    """Aura on /v1/speak. FluxVoiceTests runs every test here again for Flux on /v2/speak."""
    VOICE, OTHER_VOICE, ENDPOINT, PATH = "aura-2-thalia-en", "aura-2-draco-en", "v1", "/v1/speak"

    def setUp(self):
        self.fake = FakeDeepgram()
        self.addCleanup(self.fake.close)

    def voice(self, **overrides):
        return g.DeepgramVoice(KEY, overrides.get("voice", self.VOICE), self.fake.url,
                               timeout=overrides.get("timeout", 5))

    def failure(self, voice, text="Hello."):
        with self.assertRaises(g.VoiceError) as caught:
            voice.synthesise(text)
        self.assertNotIn(KEY, str(caught.exception))
        self.assertNotIn(KEY, caught.exception.message)
        return caught.exception

    def test_request_is_shaped_as_the_docs_say(self):
        self.assertEqual(self.voice().synthesise("It is two."), FAKE_MP3)
        path, query, headers, body = self.fake.last()
        self.assertEqual(path, self.PATH)
        self.assertEqual(query, {"model": [self.VOICE], "encoding": ["mp3"]})
        self.assertEqual(headers["Authorization"], f"Token {KEY}")
        self.assertEqual(headers["Content-Type"], "application/json")
        self.assertEqual(body, {"text": "It is two."})
        self.assertEqual(len(self.fake.requests), 1)

    def test_the_voice_says_its_endpoint(self):
        voice = self.voice()
        self.assertEqual((voice.voice, voice.endpoint), (self.VOICE, self.ENDPOINT))

    def test_chosen_voice_is_the_model(self):
        self.voice(voice=self.OTHER_VOICE).synthesise("Hi.")
        self.assertEqual(self.fake.last()[0], self.PATH)
        self.assertEqual(self.fake.last()[1]["model"], [self.OTHER_VOICE])

    def test_a_name_that_is_neither_is_refused_before_any_request(self):
        for name in ("eleven-cole-en", "flux-Cole-en", f"{self.VOICE}&encoding=wav", "", None):
            with self.assertRaises(ValueError):
                g.DeepgramVoice(KEY, name, self.fake.url)
        self.assertEqual(self.fake.requests, [])

    def test_error_statuses_are_kept_and_nothing_else(self):
        self.fake.content_type = "application/json"
        for status in (400, 401, 402, 403, 413, 429, 500, 503):
            self.fake.status = status
            self.fake.body = json.dumps({"err_code": "X", "err_msg": f"echo {KEY}", "request_id": "r"}).encode()
            error = self.failure(self.voice())
            self.assertEqual(error.status, status)
            self.assertIn(str(status), error.message)
            self.assertNotIn("echo", error.message)

    def test_a_redirect_is_not_followed(self):
        self.fake.status = 302
        self.fake.extra_headers = {"Location": "http://127.0.0.1:1/elsewhere"}
        self.fake.body = b""
        self.assertEqual(self.failure(self.voice()).status, 302)
        self.assertEqual(len(self.fake.requests), 1)

    def test_answer_must_be_mp3_audio(self):
        self.fake.content_type = "text/html"
        self.fake.body = b"<html>"
        self.failure(self.voice())
        self.fake.content_type = "audio/mpeg"
        self.fake.body = b""
        self.failure(self.voice())

    def test_answer_size_is_capped(self):
        self.fake.body = b"\xff" * 5000
        with unittest.mock.patch.object(g, "MAX_AUDIO_BYTES", 4000):
            self.failure(self.voice())

    def test_slow_answer_times_out(self):
        self.fake.delay = 1.5
        error = self.failure(self.voice(timeout=0.3))
        self.assertIsNone(error.status)

    def test_nobody_listening(self):
        url = self.fake.url
        self.fake.close()
        error = self.failure(g.DeepgramVoice(KEY, self.VOICE, url, timeout=2))
        self.assertIsNone(error.status)
        self.fake = FakeDeepgram()  # So the cleanup has something to close.

    def test_text_past_the_endpoints_limit_is_never_sent(self):
        limit = g.DEEPGRAM_MAX_CHARS[self.ENDPOINT]
        self.failure(self.voice(), "x" * (limit + 1))
        self.failure(self.voice(), "")
        self.assertEqual(self.fake.requests, [])
        self.assertEqual(self.voice().synthesise("x" * limit), FAKE_MP3)
        self.assertEqual(len(self.fake.requests), 1)

    def test_the_endpoints_own_limit_is_used(self):
        other = "v1" if self.ENDPOINT == "v2" else "v2"
        with unittest.mock.patch.dict(g.DEEPGRAM_MAX_CHARS, {self.ENDPOINT: 10, other: 5000}):
            self.failure(self.voice(), "x" * 11)
        self.assertEqual(self.fake.requests, [])

    def test_https_verifies_certificates(self):
        context = g.DeepgramVoice.tls_context()
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(context.check_hostname)

    def test_the_real_endpoint_uses_verified_https_with_a_timeout(self):
        with unittest.mock.patch.object(g.http.client, "HTTPSConnection") as https, \
                unittest.mock.patch.object(g.http.client, "HTTPConnection") as plain:
            https.return_value.request.side_effect = OSError("no network in tests")
            with self.assertRaises(g.VoiceError):
                g.DeepgramVoice(KEY, self.VOICE, g.DEEPGRAM_URL, timeout=7).synthesise("Hi.")
        plain.assert_not_called()
        args, kwargs = https.call_args
        self.assertEqual(args[0], "api.deepgram.com")
        self.assertEqual(args[1:2] or (kwargs.get("port"),), (443,))
        self.assertEqual(kwargs["timeout"], 7)
        self.assertEqual(kwargs["context"].verify_mode, ssl.CERT_REQUIRED)
        self.assertTrue(kwargs["context"].check_hostname)
        self.assertNotIn("set_tunnel", [name for name, _a, _k in https.return_value.method_calls])
        method, target = https.return_value.request.call_args[0][:2]
        self.assertEqual((method, target), ("POST", f"{self.PATH}?model={self.VOICE}&encoding=mp3"))

    def test_the_key_is_not_in_the_repr(self):
        self.assertNotIn(KEY, repr(self.voice()))


class FluxVoiceTests(DeepgramVoiceTests):
    """Every Aura test above, again for Flux on /v2/speak, which the real service keeps apart from /v1/speak."""
    VOICE, OTHER_VOICE, ENDPOINT, PATH = "flux-cole-en", "flux-haley-en", "v2", "/v2/speak"

    def test_v2_sends_audio_mpeg_too(self):
        # Measured 2026-11-08: /v2/speak answers `audio/mpeg`, chunked. Anything else is still refused.
        for kind in ("audio/mpeg; charset=binary", "Audio/MPEG"):
            self.fake.content_type = kind
            self.assertEqual(self.voice().synthesise("Hi."), FAKE_MP3)
        for kind in ("audio/mp3", "audio/wav", "application/octet-stream", "application/json"):
            self.fake.content_type = kind
            self.assertEqual(self.failure(self.voice()).status, 200, kind)


class DeepgramStreamTests(unittest.TestCase):
    """The answer is passed on chunk by chunk, over a connection of its own for each clip.
    Aura here; FluxStreamTests runs it all again for Flux."""
    VOICE = "aura-2-thalia-en"

    def setUp(self):
        self.fake = FakeDeepgram()
        self.addCleanup(self.fake.close)

    def voice(self, timeout=5):
        voice = g.DeepgramVoice(KEY, self.VOICE, self.fake.url, timeout=timeout)
        return voice

    def test_chunks_are_passed_on_as_they_arrive(self):
        self.fake.chunks = [FAKE_MP3, b"second", b"third"]
        self.fake.gates = {1: threading.Event()}
        threading.Timer(1.0, self.fake.gates[1].set).start()
        started = time.monotonic()
        chunks = self.voice().stream("Hi.")
        first = next(chunks)
        self.assertLess(time.monotonic() - started, 0.6)
        self.assertEqual(first, FAKE_MP3)
        self.assertEqual(first + b"".join(chunks), FAKE_MP3 + b"secondthird")

    def test_stream_keeps_every_check(self):
        voice = self.voice()
        self.fake.chunks = [b""]
        with self.assertRaises(g.VoiceError):
            list(voice.stream("Hi."))
        self.fake.chunks, self.fake.content_type = [b"<html>"], "text/html"
        with self.assertRaises(g.VoiceError):
            list(voice.stream("Hi."))
        self.fake.content_type = "audio/mpeg"
        self.fake.chunks = [b"\xff" * 3000, b"\xff" * 3000]
        with unittest.mock.patch.object(g, "MAX_AUDIO_BYTES", 4000), self.assertRaises(g.VoiceError) as caught:
            list(voice.stream("Hi."))
        self.assertEqual(caught.exception.status, 200)

    def test_the_overall_deadline_holds_while_chunks_keep_coming(self):
        self.fake.chunks, self.fake.drip = [FAKE_MP3] * 8, 0.15
        started = time.monotonic()
        with self.assertRaises(g.VoiceError) as caught:
            list(self.voice(timeout=0.5).stream("Hi."))
        self.assertIsNone(caught.exception.status)
        self.assertLess(time.monotonic() - started, 0.9)

    def test_each_clip_opens_and_closes_its_own_connection(self):
        voice = self.voice()
        self.assertEqual(voice.synthesise("One."), FAKE_MP3)
        self.assertTrue(wait_for(lambda: self.fake.live == 0))
        self.assertEqual(voice.synthesise("Two."), FAKE_MP3)
        self.assertTrue(wait_for(lambda: self.fake.live == 0))
        self.assertEqual((self.fake.connections, len(self.fake.requests)), (2, 2))

    def test_a_failed_clip_closes_its_connection_too(self):
        self.fake.chunks = [FAKE_MP3, b"rest"]
        self.fake.misbehave = {1: "cut"}
        with self.assertRaises(g.VoiceError):
            b"".join(self.voice().stream("Hi."))
        self.fake.status, self.fake.content_type, self.fake.chunks = 429, "application/json", None
        self.fake.body = b"{}"
        with self.assertRaises(g.VoiceError):
            self.voice().synthesise("Hi.")
        self.assertTrue(wait_for(lambda: self.fake.live == 0))

    def test_nothing_is_opened_ahead_or_kept(self):
        voice = self.voice()
        time.sleep(0.2)
        self.assertEqual(self.fake.connections, 0)
        for name in ("want_warm", "has_warm_connection", "_keep", "_tend", "_take", "_give_back", "_alive", "close"):
            self.assertFalse(hasattr(voice, name), name)
        for name in ("WARM_CHECK_SECONDS", "WARM_RENEW_SECONDS", "WARM_MAX_AGE_SECONDS", "WARM_IDLE_SECONDS"):
            self.assertFalse(hasattr(g, name), name)
        self.assertEqual([t.name for t in threading.enumerate() if t.name == "deepgram-warm"], [])

    def test_two_clips_at_once_each_get_a_connection(self):
        self.fake.chunks = [FAKE_MP3, b"end"]
        self.fake.gates = {1: threading.Event()}
        voice = self.voice()
        first = voice.stream("One.")
        self.assertEqual(next(first), FAKE_MP3)
        threading.Timer(0.3, self.fake.gates[1].set).start()
        self.assertEqual(voice.synthesise("Two."), FAKE_MP3 + b"end")
        self.assertEqual(b"".join(first), b"end")
        self.assertEqual(self.fake.connections, 2)

    def test_no_retry_when_deepgram_hangs_up(self):
        self.fake.misbehave = {1: "hang-up"}
        with self.assertRaises(g.VoiceError) as caught:
            self.voice().synthesise("Hi.")
        self.assertIsNone(caught.exception.status)
        self.assertEqual(len(self.fake.requests), 1)

    def test_no_retry_once_a_byte_came_back(self):
        voice = self.voice()
        for number, action in ((1, "partial-status"), (2, "partial-body"), (3, "cut")):
            self.fake.chunks = [FAKE_MP3, b"rest"] if action == "cut" else None
            self.fake.misbehave = {number: action}
            with self.assertRaises(g.VoiceError):
                b"".join(voice.stream("Hi."))
            self.assertEqual(len(self.fake.requests), number, action)

    def test_no_retry_after_a_timeout(self):
        self.fake.delay = 1.0
        with self.assertRaises(g.VoiceError):
            self.voice(timeout=0.3).synthesise("Hi.")
        self.assertEqual(len(self.fake.requests), 1)

    def test_an_error_answer_is_not_retried_and_the_key_stays_out(self):
        self.fake.status, self.fake.content_type = 429, "application/json"
        self.fake.body = json.dumps({"err_msg": f"slow down {KEY}"}).encode()
        with self.assertRaises(g.VoiceError) as caught:
            self.voice().synthesise("Hi.")
        self.assertEqual(caught.exception.status, 429)
        self.assertNotIn(KEY, caught.exception.message)
        self.assertEqual(len(self.fake.requests), 1)


class FluxStreamTests(DeepgramStreamTests):
    """Streaming, a connection per clip, no retry and the deadline, again for Flux on /v2/speak."""
    VOICE = "flux-cole-en"

    def test_every_request_went_to_v2(self):
        voice = self.voice()
        voice.synthesise("One.")
        voice.synthesise("Two.")
        self.assertEqual({g.urlsplit(r["path"]).path for r in self.fake.requests}, {"/v2/speak"})


class TlsClipTests(unittest.TestCase):
    """A clip over a real local TLS server, with a certificate the voice is told to trust: verified HTTPS end to end."""
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cert, key = Path(cls.tmp.name) / "cert.pem", Path(cls.tmp.name) / "key.pem"
        try:
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key),
                            "-out", str(cert), "-days", "2", "-subj", "/CN=localhost",
                            "-addext", "subjectAltName=DNS:localhost"], check=True, capture_output=True, timeout=60)
        except (OSError, subprocess.SubprocessError):
            cls.tmp.cleanup()
            raise unittest.SkipTest("openssl is needed to make a test certificate")
        cls.server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        cls.server_context.load_cert_chain(cert, key)
        cls.cert = cert

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def setUp(self):
        self.fake = FakeDeepgram(tls=self.server_context)
        self.addCleanup(self.fake.close)

    def test_each_clip_has_its_own_verified_connection(self):
        trusting = ssl.create_default_context(cafile=str(self.cert))
        with unittest.mock.patch.object(g.DeepgramVoice, "tls_context", staticmethod(lambda: trusting)):
            voice = g.DeepgramVoice(KEY, "aura-2-thalia-en", self.fake.url, timeout=5)
            self.assertEqual(voice.synthesise("Hi."), FAKE_MP3)
            self.assertEqual(voice.synthesise("Again."), FAKE_MP3)
        self.assertEqual((self.fake.connections, len(self.fake.requests)), (2, 2))

    def test_an_untrusted_certificate_is_refused(self):
        error = None
        try:
            g.DeepgramVoice(KEY, "aura-2-thalia-en", self.fake.url, timeout=5).synthesise("Hi.")
        except g.VoiceError as caught:
            error = caught
        self.assertIsNotNone(error)
        self.assertEqual(self.fake.requests, [])


class DeepgramKeyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.data = Path(self.tmp.name) / "data"

    def run_set(self, answer):
        out = io.StringIO()
        code = g.set_deepgram_key(self.data, ask=lambda _prompt: answer, out=out)
        return code, out.getvalue()

    def test_set_stores_a_private_file(self):
        code, out = self.run_set(f"  {KEY}  ")
        self.assertEqual(code, 0)
        path = g.deepgram_key_path(self.data)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.data.stat().st_mode), 0o700)
        self.assertEqual(g.load_deepgram_key(self.data), KEY)
        self.assertNotIn(KEY, out)
        self.assertIn("Restart", out)

    def test_set_refuses_an_unusable_key(self):
        for answer in ("", "   ", "short", "has space inside the key value", "line\nbreak" + "x" * 30, "é" * 40):
            code, out = self.run_set(answer)
            self.assertEqual(code, 1, answer)
            self.assertFalse(g.deepgram_key_path(self.data).exists())

    def test_set_replaces(self):
        self.run_set(KEY)
        self.run_set("f" * 40)
        self.assertEqual(g.load_deepgram_key(self.data), "f" * 40)
        self.assertEqual([p.name for p in self.data.iterdir()], [g.deepgram_key_path(self.data).name])

    def test_no_file_means_no_key(self):
        self.assertIsNone(g.load_deepgram_key(self.data))

    def test_loose_mode_is_refused(self):
        self.run_set(KEY)
        g.deepgram_key_path(self.data).chmod(0o640)
        with self.assertRaises(g.KeyFileError) as caught:
            g.load_deepgram_key(self.data)
        self.assertIn("chmod 600", str(caught.exception))
        self.assertNotIn(KEY, str(caught.exception))

    def test_a_symlink_or_directory_is_refused(self):
        self.data.mkdir()
        target = Path(self.tmp.name) / "elsewhere"
        target.write_text(KEY)
        target.chmod(0o600)
        g.deepgram_key_path(self.data).symlink_to(target)
        with self.assertRaises(g.KeyFileError):
            g.load_deepgram_key(self.data)
        g.deepgram_key_path(self.data).unlink()
        g.deepgram_key_path(self.data).mkdir()
        with self.assertRaises(g.KeyFileError):
            g.load_deepgram_key(self.data)

    def test_garbage_is_refused_without_showing_it(self):
        self.data.mkdir()
        path = g.deepgram_key_path(self.data)
        path.write_text("not a key at all\n")
        path.chmod(0o600)
        with self.assertRaises(g.KeyFileError) as caught:
            g.load_deepgram_key(self.data)
        self.assertNotIn("not a key", str(caught.exception))

    def test_clear(self):
        self.run_set(KEY)
        out = io.StringIO()
        self.assertEqual(g.clear_deepgram_key(self.data, out=out), 0)
        self.assertFalse(g.deepgram_key_path(self.data).exists())
        self.assertEqual(g.clear_deepgram_key(self.data, out=io.StringIO()), 0)

    def test_clear_removes_a_link_not_its_target(self):
        self.data.mkdir()
        target = Path(self.tmp.name) / "elsewhere"
        target.write_text("keep")
        g.deepgram_key_path(self.data).symlink_to(target)
        g.clear_deepgram_key(self.data, out=io.StringIO())
        self.assertFalse(g.deepgram_key_path(self.data).is_symlink())
        self.assertEqual(target.read_text(), "keep")

    def test_voice_from_config(self):
        config = replace(CONFIG, data=self.data)
        self.assertIsNone(g.voice_from_config(config))
        self.run_set(KEY)
        voice = g.voice_from_config(config)
        self.assertIsInstance(voice, g.DeepgramVoice)
        self.assertEqual((voice.voice, voice.endpoint), ("flux-cole-en", "v2"))
        aura = g.voice_from_config(replace(config, deepgram_voice="aura-2-thalia-en"))
        self.assertEqual((aura.voice, aura.endpoint), ("aura-2-thalia-en", "v1"))
        g.deepgram_key_path(self.data).chmod(0o644)
        with self.assertRaises(g.KeyFileError):
            g.voice_from_config(config)


class DeepgramMainTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.data = Path(self.tmp.name) / "data"
        self.audio_dir = Path(self.tmp.name) / "audio"
        self.audio_dir.mkdir()
        self.fake = FakeDeepgram()
        self.addCleanup(self.fake.close)

    def env(self, **extra):
        return {"VOICE_GATEWAY_DATA": str(self.data), "VOICE_GATEWAY_DEEPGRAM_TEST_URL": self.fake.url, **extra}

    def run_main(self, argv, env=None, ask=None):
        class NoRunner:
            def run(self, *a, **k):
                raise AssertionError("no command should run")
        out, err_out = io.StringIO(), io.StringIO()
        with redirect_stderr(err_out), unittest.mock.patch("sys.stdout", out), \
                unittest.mock.patch.object(g.tempfile, "tempdir", str(self.audio_dir)):
            code = g.main(list(argv), env=env or self.env(), runner=NoRunner(), ask=ask or (lambda _p: KEY))
        text = out.getvalue() + err_out.getvalue()
        self.assertNotIn(KEY, text)
        return code, text

    def test_set_and_clear_need_no_tailscale_or_passcode(self):
        self.assertEqual(self.run_main(["--set-deepgram-key"])[0], 0)
        self.assertEqual(g.load_deepgram_key(self.data), KEY)
        self.assertEqual(self.run_main(["--clear-deepgram-key"])[0], 0)
        self.assertIsNone(g.load_deepgram_key(self.data))

    def test_test_voice_success_saves_the_audio(self):
        self.run_main(["--set-deepgram-key"])
        code, text = self.run_main(["--test-voice"])
        self.assertEqual(code, 0)
        saved = list(self.audio_dir.iterdir())
        self.assertEqual(len(saved), 1)
        self.assertEqual(saved[0].read_bytes(), FAKE_MP3)
        self.assertEqual(stat.S_IMODE(saved[0].stat().st_mode), 0o600)
        self.assertIn(str(saved[0]), text)
        self.assertEqual(self.fake.last()[3], {"text": g.TEST_VOICE_SENTENCE})
        self.assertEqual(self.fake.last()[2]["Authorization"], f"Token {KEY}")

    def test_test_voice_uses_the_configured_voice_and_names_its_endpoint(self):
        self.run_main(["--set-deepgram-key"])
        code, text = self.run_main(["--test-voice"])
        self.assertEqual(code, 0)
        self.assertEqual(self.fake.last()[:2], ("/v2/speak", {"model": ["flux-cole-en"], "encoding": ["mp3"]}))
        self.assertIn("flux-cole-en", text)
        self.assertIn("/v2/speak", text)
        code, text = self.run_main(["--test-voice"], env=self.env(VOICE_GATEWAY_DEEPGRAM_VOICE="aura-2-thalia-en"))
        self.assertEqual(code, 0)
        self.assertEqual(self.fake.last()[:2], ("/v1/speak", {"model": ["aura-2-thalia-en"], "encoding": ["mp3"]}))
        self.assertIn("aura-2-thalia-en", text)
        self.assertIn("/v1/speak", text)
        self.assertEqual(len(self.fake.requests), 2)

    def test_test_voice_failure_gives_the_status_only(self):
        self.run_main(["--set-deepgram-key"])
        self.fake.status, self.fake.content_type = 401, "application/json"
        self.fake.body = json.dumps({"err_code": "INVALID_AUTH", "err_msg": "Invalid credentials."}).encode()
        code, text = self.run_main(["--test-voice"])
        self.assertEqual(code, 1)
        self.assertIn("401", text)
        self.assertIn("flux-cole-en", text)
        self.assertIn("/v2/speak", text)
        self.assertNotIn("Invalid credentials", text)
        self.assertEqual(list(self.audio_dir.iterdir()), [])
        self.assertEqual(len(self.fake.requests), 1)  # No second try.

    def test_test_voice_refuses_a_name_that_is_neither(self):
        self.run_main(["--set-deepgram-key"])
        code, text = self.run_main(["--test-voice"], env=self.env(VOICE_GATEWAY_DEEPGRAM_VOICE="flux-Cole-en"))
        self.assertEqual(code, 1)
        self.assertIn("VOICE_GATEWAY_DEEPGRAM_VOICE", text)
        self.assertEqual(self.fake.requests, [])

    def test_test_voice_without_a_key_says_how(self):
        code, text = self.run_main(["--test-voice"])
        self.assertEqual(code, 1)
        self.assertIn("--set-deepgram-key", text)
        self.assertEqual(self.fake.requests, [])

    def test_test_voice_with_a_loose_key_file_refuses(self):
        self.run_main(["--set-deepgram-key"])
        g.deepgram_key_path(self.data).chmod(0o644)
        code, _ = self.run_main(["--test-voice"])
        self.assertEqual(code, 1)
        self.assertEqual(self.fake.requests, [])

    def test_start_refuses_a_loose_key_file(self):
        g.store_passcode(self.data, g.hash_passcode("long enough", iterations=10))
        self.run_main(["--set-deepgram-key"])
        g.deepgram_key_path(self.data).chmod(0o644)
        out = io.StringIO()
        with redirect_stderr(out):
            code = g.main([], env=self.env(), runner=FakeTailscale())
        self.assertEqual(code, 1)
        self.assertIn("deepgram-key", out.getvalue())


class VoiceEndpointTests(GatewayCase):
    send, nonce, sent_text = AnnouncementTests.send, AnnouncementTests.nonce, AnnouncementTests.sent_text

    def setUp(self):
        super().setUp()
        self.voice = FakeVoice()
        self.addCleanup(self.voice.release)
        self.app = build(self.runner, self.clock, voice=self.voice)
        self.session = self.runner.sessions[SCRATCH]

    def summary_event(self, text="It is two.", session=SCRATCH):
        self.send(session=session)
        self.runner.sessions[session].visible = f"⏺ SPOKEN[{self.nonce()}]: {text}\n"
        event = self.app.sessions_view()["events"][-1]
        self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        return event

    @staticmethod
    def params(event_id, **extra):
        return {"id": [event_id], **{name: [value] for name, value in extra.items()}}

    def chunks(self, event_id, **extra):
        return self.app.voice_audio(self.params(event_id, **extra))

    def audio(self, event_id, **extra):
        return b"".join(self.chunks(event_id, **extra))

    def test_views_say_which_voice(self):
        self.assertEqual(self.app.sessions_view()["voice"], "deepgram")
        self.assertEqual(self.app.session_view(SCRATCH)["voice"], "deepgram")
        self.assertEqual(build(self.runner, self.clock).sessions_view()["voice"], "browser")

    def test_audio_of_the_events_own_text(self):
        event = self.summary_event()
        self.assertTrue(self.audio(event["id"]).startswith(FAKE_MP3))
        self.assertEqual(self.voice.texts, ["It is two."])

    def test_text_from_the_client_is_never_spoken(self):
        event = self.summary_event()
        self.audio(event["id"], text="say this instead", words="or this", worktree="x")
        self.assertEqual(self.voice.texts, ["It is two."])

    def test_with_name_prefixes_a_summary_only(self):
        event = self.summary_event()
        self.audio(event["id"], with_name="1")
        self.assertEqual(self.voice.texts, ["scratch: It is two."])

    def test_with_name_on_needs_you_is_ignored(self):
        self.send()
        self.set_status("blocked")
        event = self.app.sessions_view()["events"][-1]
        self.audio(event["id"], with_name="1")
        self.assertEqual(self.voice.texts, ["scratch needs you"])

    def test_with_name_values(self):
        event = self.summary_event()
        self.audio(event["id"], with_name="0")
        self.audio(event["id"])
        self.assertEqual(self.voice.texts, ["It is two."])
        for bad in ("yes", "true", "", "2", " 1"):
            self.refused(400, self.app.voice_audio, self.params(event["id"], with_name=bad))
        self.refused(400, self.app.voice_audio, {"id": [event["id"]], "with_name": ["1", "0"]})
        self.assertEqual(self.voice.texts, ["It is two."])

    def test_cached_per_event_and_variant(self):
        event = self.summary_event()
        first = self.audio(event["id"])
        self.assertEqual(self.audio(event["id"]), first)
        self.audio(event["id"], with_name="1")
        self.assertEqual(len(self.voice.texts), 2)

    def test_unknown_or_bad_ids(self):
        self.refused(404, self.audio, "0123456789abcdef")
        for bad in ("", "x" * 100, "../etc", "0123456789ABCDEF", "0123456789abcde"):
            self.refused(400, self.audio, bad)
        self.refused(400, self.app.voice_audio, {})
        self.refused(400, self.app.voice_audio, {"id": ["0123456789abcdef", "0123456789abcdef"]})
        self.assertEqual(self.voice.texts, [])

    def test_acknowledged_events_have_no_audio_and_their_cache_is_dropped(self):
        event = self.summary_event()
        self.audio(event["id"])
        self.assertEqual(self.app.audio.size(), 1)
        self.app.acknowledge({"ids": [event["id"]]})
        self.assertEqual(self.app.audio.size(), 0)
        self.refused(404, self.audio, event["id"])

    def test_no_key_means_no_audio(self):
        self.app = build(self.runner, self.clock)
        event = self.summary_event()
        self.refused(404, self.audio, event["id"])

    def test_a_failure_before_any_audio_is_502_and_never_asked_for_again(self):
        event = self.summary_event()
        self.voice.fail = g.VoiceError(401, "Deepgram refused the request: HTTP 401.")
        message = self.refused(502, self.audio, event["id"])
        self.assertIn("401", message)
        self.assertNotIn(KEY, message)
        self.voice.fail = None
        self.assertIn("401", self.refused(502, self.audio, event["id"]))  # A browser retrying on its own.
        self.assertEqual(len(self.voice.texts), 1)
        self.assertEqual(self.app.audio.size(), 0)
        self.audio(event["id"], with_name="1")  # The other variant is its own clip.
        self.assertEqual(len(self.voice.texts), 2)

    def test_failures_are_forgotten_with_their_event(self):
        first = self.summary_event()
        self.voice.fail = g.VoiceError(500, "x")
        self.refused(502, self.audio, first["id"])
        second = self.summary_event()
        self.refused(502, self.audio, second["id"])
        self.app.acknowledge({"ids": [first["id"]]})
        self.assertEqual(list(self.app.failed), [(second["id"], False, "deepgram")])
        for _ in range(g.MAX_EVENTS):
            self.summary_event()
        self.app.sessions_view()
        self.assertEqual(self.app.failed, {})

    def test_a_failure_after_the_first_chunk_reaches_the_reader_and_is_not_cached(self):
        event = self.summary_event()
        self.voice.fail_after = g.VoiceError(None, "Deepgram did not answer in time.")
        chunks = self.chunks(event["id"])
        self.assertTrue(next(chunks).startswith(FAKE_MP3[:8]))
        with self.assertRaises(g.VoiceError):
            next(chunks)
        self.assertEqual(self.app.audio.size(), 0)
        self.voice.fail_after = None
        self.refused(502, self.audio, event["id"])
        self.assertEqual(len(self.voice.texts), 1)

    def test_the_first_chunk_comes_before_the_clip_is_finished(self):
        event = self.summary_event()
        self.voice.gates["It is two."] = threading.Event()
        chunks = self.chunks(event["id"])
        self.assertTrue(next(chunks).startswith(b"ID3"))
        self.voice.gates["It is two."].set()
        self.assertTrue(b"".join(chunks))

    def test_a_second_request_mid_stream_reads_along(self):
        event = self.summary_event()
        self.voice.gates["It is two."] = threading.Event()
        first = self.chunks(event["id"])
        head = next(first)
        second = self.chunks(event["id"])
        self.voice.gates["It is two."].set()
        self.assertEqual(head + b"".join(first), b"".join(second))
        self.assertEqual(len(self.voice.texts), 1)

    def test_a_reader_that_stops_does_not_stop_the_download(self):
        event = self.summary_event()
        self.voice.gates["It is two."] = threading.Event()
        chunks = self.chunks(event["id"])
        next(chunks)
        chunks.close()  # The phone hung up.
        self.voice.gates["It is two."].set()
        self.assertTrue(wait_for(lambda: self.app.audio.size() == 1))
        self.audio(event["id"])
        self.assertEqual(len(self.voice.texts), 1)

    def test_many_requests_at_once_call_deepgram_once(self):
        event = self.summary_event()
        with g.ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda _i: self.audio(event["id"]), range(4)))
        self.assertEqual(len(set(results)), 1)
        self.assertEqual(len(self.voice.texts), 1)

    def test_a_slow_clip_does_not_hold_up_another_event(self):
        slow = self.summary_event("Slow one.")
        self.runner.sessions["wt-other"] = FakeSession()
        quick = self.summary_event("Quick one.", session="wt-other")
        self.voice.before["Slow one."] = threading.Event()
        with g.ThreadPoolExecutor(max_workers=2) as pool:
            waiting = pool.submit(self.audio, slow["id"])
            self.assertTrue(wait_for(lambda: "Slow one." in self.voice.texts))
            started = time.monotonic()
            self.assertTrue(self.audio(quick["id"]).startswith(FAKE_MP3))
            self.assertLess(time.monotonic() - started, 1.0)
            self.assertFalse(waiting.done())
            self.voice.before["Slow one."].set()
            self.assertTrue(waiting.result(5))

    def test_acknowledged_mid_stream_is_not_cached(self):
        event = self.summary_event()
        self.voice.gates["It is two."] = threading.Event()
        chunks = self.chunks(event["id"])
        next(chunks)
        self.app.acknowledge({"ids": [event["id"]]})
        self.voice.gates["It is two."].set()
        b"".join(chunks)
        self.assertEqual(self.app.audio.size(), 0)

    def test_cache_expires(self):
        event = self.summary_event()
        self.audio(event["id"])
        self.clock.t += g.AUDIO_TTL_SECONDS + 1
        self.app.sessions_view()
        self.assertEqual(self.app.audio.size(), 0)

    def test_cache_is_small(self):
        for _ in range(g.AUDIO_CACHE_ENTRIES + 3):
            self.audio(self.summary_event()["id"])
        self.assertLessEqual(self.app.audio.size(), g.AUDIO_CACHE_ENTRIES)

    def test_cache_has_a_byte_cap(self):
        self.voice.audio = b"\xff" * (g.AUDIO_CACHE_BYTES // 2 + 1)
        for _ in range(3):
            self.audio(self.summary_event()["id"])
        self.assertLessEqual(self.app.audio.bytes(), g.AUDIO_CACHE_BYTES)

    def test_evicted_events_drop_their_audio(self):
        first = self.summary_event()
        self.audio(first["id"])
        for _ in range(g.MAX_EVENTS):
            self.summary_event()
        self.assertNotIn(first["id"], [e["id"] for e in self.app.announcer.unacknowledged()])
        self.assertEqual(self.app.audio.size(), 0)

    def test_nothing_warms_the_voice(self):
        # FakeVoice has no want_warm: a send, polls and an unacknowledged event must not ask for one.
        self.assertFalse(hasattr(self.voice, "want_warm"))
        self.send()
        self.set_status("working")
        self.app.sessions_view()
        self.app.session_view(SCRATCH)
        self.set_status("idle")
        self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        self.summary_event()
        self.app.sessions_view()


class HttpVoiceTests(HttpCase):
    """The audio route with the default voice, Flux on /v2/speak. AuraHttpVoiceTests runs it again for Aura."""
    VOICE = "flux-cole-en"

    def make_voice(self):
        self.fake = FakeDeepgram()
        self.addCleanup(self.fake.close)
        voice = g.DeepgramVoice(KEY, self.VOICE, self.fake.url, timeout=5)
        return voice

    def setUp(self):
        super().setUp()
        self.log_in()

    def event(self):
        self.post("/api/prompt", {"session": SCRATCH, "text": "hi"})
        sent = self.runner.named("agent", "prompt")[-1]["argv"][-1]
        nonce = re.search(r"SPOKEN\[([0-9a-f]+)\]", sent).group(1)
        self.runner.sessions[SCRATCH].visible = f"⏺ SPOKEN[{nonce}]: All done.\n"
        listing = json.loads(self.request("GET", "/api/sessions")[2])
        self.assertEqual(listing["voice"], "deepgram")
        self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        return listing["events"][-1]

    def open_audio(self, path):
        """The response with only its headers read, so a test can read the body as it comes."""
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        self.addCleanup(connection.close)
        connection.putrequest("GET", path, skip_host=True, skip_accept_encoding=True)
        for name, value in self.headers().items():
            connection.putheader(name, value)
        connection.endheaders()
        return connection.getresponse()

    def test_audio_over_http(self):
        event = self.event()
        status, headers, body, _ = self.request("GET", f"/api/audio?id={event['id']}")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "audio/mpeg")
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(headers["Transfer-Encoding"], "chunked")
        self.assertNotIn("Content-Length", headers)
        self.assertIn("Content-Security-Policy", headers)
        self.assertEqual(body, FAKE_MP3)
        self.assertEqual(self.fake.last()[3], {"text": "All done."})

    def test_audio_is_relayed_as_it_arrives(self):
        event = self.event()
        self.fake.chunks = [FAKE_MP3, b"second", b"third"]
        self.fake.gates = {1: threading.Event()}
        threading.Timer(1.0, self.fake.gates[1].set).start()
        started = time.monotonic()
        response = self.open_audio(f"/api/audio?id={event['id']}")
        self.assertEqual(response.status, 200)
        first = response.read1(65536)
        self.assertLess(time.monotonic() - started, 0.6)
        self.assertTrue(FAKE_MP3.startswith(first) and first)
        self.assertEqual(first + response.read(), FAKE_MP3 + b"secondthird")

    def test_a_second_request_mid_stream_makes_no_second_call(self):
        event = self.event()
        self.fake.chunks = [FAKE_MP3, b"end"]
        self.fake.gates = {1: threading.Event()}
        first = self.open_audio(f"/api/audio?id={event['id']}")
        head = first.read1(65536)
        second = self.open_audio(f"/api/audio?id={event['id']}")
        self.fake.gates[1].set()
        self.assertEqual(head + first.read(), FAKE_MP3 + b"end")
        self.assertEqual(second.read(), FAKE_MP3 + b"end")
        self.assertEqual(self.request("GET", f"/api/audio?id={event['id']}")[2], FAKE_MP3 + b"end")  # From the cache.
        self.assertEqual(len(self.fake.requests), 1)

    def test_a_range_past_the_start_is_refused(self):
        event = self.event()
        path = f"/api/audio?id={event['id']}"
        status, headers, _, _ = self.request("GET", path, Range="bytes=40960-")
        self.assertEqual(status, 416)
        self.assertEqual(headers["Content-Range"], "bytes */*")
        self.assertEqual(self.fake.requests, [])
        for value in ("bytes=0-", None):
            self.assertEqual(self.request("GET", path, Range=value)[0], 200, value)
        self.assertEqual(self.request("GET", path, Range="bytes=0-99")[0], 416)

    def test_a_browser_retrying_a_failed_clip_never_calls_deepgram_again(self):
        event = self.event()
        self.fake.chunks = [FAKE_MP3, b"never sent"]
        self.fake.misbehave = {1: "cut"}
        with self.assertRaises((http.client.IncompleteRead, ConnectionError)):
            self.open_audio(f"/api/audio?id={event['id']}").read()
        self.assertTrue(wait_for(lambda: self.request("GET", f"/api/audio?id={event['id']}")[0] == 502))
        self.assertEqual(len(self.fake.requests), 1)

    def test_with_name_over_http(self):
        event = self.event()
        self.assertEqual(self.request("GET", f"/api/audio?id={event['id']}&with_name=1")[0], 200)
        self.assertEqual(self.fake.last()[3], {"text": "scratch: All done."})
        self.assertEqual(self.request("GET", f"/api/audio?id={event['id']}&with_name=yes")[0], 400)

    def test_a_failure_before_audio_is_502_with_the_status_only(self):
        event = self.event()
        self.fake.status, self.fake.content_type = 401, "application/json"
        self.fake.body = json.dumps({"err_msg": f"bad key {KEY}"}).encode()
        status, _, body, _ = self.request("GET", f"/api/audio?id={event['id']}")
        self.assertEqual(status, 502)
        self.assertIn("401", json.loads(body)["error"])
        self.assertNotIn(KEY.encode(), body)

    def test_a_failure_mid_stream_ends_the_body_unfinished(self):
        event = self.event()
        self.fake.chunks = [FAKE_MP3, b"never sent"]
        self.fake.misbehave = {1: "cut"}
        response = self.open_audio(f"/api/audio?id={event['id']}")
        self.assertEqual(response.status, 200)
        with self.assertRaises((http.client.IncompleteRead, ConnectionError)):
            response.read()

    def test_guards(self):
        event = self.event()
        path = f"/api/audio?id={event['id']}"
        self.assertEqual(self.request("GET", path, Host="evil.com")[0], 403)
        self.assertEqual(self.request("GET", path, Tailscale_User_Login=None)[0], 403)
        self.assertEqual(self.request("GET", "/api/audio?id=0123456789abcdef")[0], 404)
        self.assertEqual(self.request("GET", "/api/audio")[0], 400)
        self.token = None
        self.assertEqual(self.request("GET", path)[0], 401)
        self.assertEqual(self.fake.requests, [])

    def test_the_old_post_route_is_gone(self):
        event = self.event()
        self.assertEqual(self.post("/api/voice", {"id": event["id"]})[0], 404)
        self.assertEqual(self.fake.requests, [])

    def test_the_key_never_reaches_a_response_or_the_log(self):
        event = self.event()
        bodies = [self.request("GET", f"/api/audio?id={event['id']}")[2]]
        self.fake.status, self.fake.content_type = 401, "application/json"
        self.fake.body = json.dumps({"err_msg": f"bad key {KEY}"}).encode()
        second = self.event()
        status, _, body, _ = self.request("GET", f"/api/audio?id={second['id']}")
        self.assertEqual(status, 502)
        bodies.append(body)
        for path in ("/", "/app.js", "/api/sessions", f"/api/session?name={SCRATCH}"):
            bodies.append(self.request("GET", path)[2])
        for body in bodies:
            self.assertNotIn(KEY.encode(), body)
        self.assertNotIn(KEY, self.log.getvalue())
        self.assertNotIn(event["id"], self.log.getvalue())  # The log has the path without the query.

    def test_the_voice_decides_the_path(self):
        event = self.event()
        self.assertEqual(self.request("GET", f"/api/audio?id={event['id']}")[0], 200)
        self.assertEqual(self.fake.last()[:2], (g.DEEPGRAM_PATHS[g.deepgram_endpoint(self.VOICE)],
                                                {"model": [self.VOICE], "encoding": ["mp3"]}))


class AuraHttpVoiceTests(HttpVoiceTests):
    VOICE = "aura-2-thalia-en"


class VoiceSourceTests(unittest.TestCase):
    def test_no_urllib_request(self):
        code = SourceRuleTests.code_lines(SourceRuleTests())
        self.assertNotIn("urlopen", code)
        self.assertNotIn("urllib.request", code)

    def test_no_proxy_tunnel_or_redirect_following(self):
        code = SourceRuleTests.code_lines(SourceRuleTests())
        self.assertNotIn("set_tunnel", code)
        self.assertNotIn("getproxies", code)
        self.assertNotIn("voice_lock", code)

    def test_no_kept_connection(self):
        code = SourceRuleTests.code_lines(SourceRuleTests())
        for word in ("want_warm", "WARM_", "_keeper", "select.select", "deepgram-warm"):
            self.assertNotIn(word, code)


class VoicePageTests(unittest.TestCase):
    def page(self, name="app.js"):
        return (ROOT / "static" / name).read_text()

    def section(self, start, end):
        source = self.page()
        return source[source.index(start):source.index(end)]

    def test_send_tap_and_chrome_speech_are_unchanged(self):
        # Local speech (2026-11-08) added a dictation switch around Chrome speech, so the hash now covers Chrome's own
        # recogniser only; stopListening and onMic changed on purpose and are tested in LocalSpeechPageTests.
        sections = {("async function onSend", "function askRestart"):
                    "f9fa30edb53ef18e9e2d8d0afd880a6fb9d75e2c0d6c7320f59db7c9fb369149",
                    ("function startListening", "// ---- Local Whisper"):
                    "6f1ac03215696f4a7cc70b39aa94c50e6966a16cbc47794570d2b59d73282a28"}
        for (start, end), digest in sections.items():
            self.assertEqual(hashlib.sha256(self.section(start, end).encode()).hexdigest(), digest, start)

    def test_audio_is_asked_for_by_event_id_only(self):
        source = self.page()
        self.assertNotIn("/api/voice", source)
        self.assertEqual(source.count("/api/audio?"), 1)
        line = next(line for line in source.splitlines() if "/api/audio?" in line)
        self.assertIn("`/api/audio?id=${encodeURIComponent(event.id)}&with_name=${withName(event) ? 1 : 0}"
                      "&engine=${engine}`", line)
        for word in ("words", "text", ".output"):
            self.assertNotIn(word, line)
        # The engine is one of a fixed list, never anything stored or typed.
        self.assertIn('const SERVER_ENGINES = ["chatterbox", "deepgram"];', source)
        chain = self.section("function engineChain", "function fallbackNote")
        self.assertIn("SERVER_ENGINES.includes(engine)", chain)

    def test_one_audio_element_fed_while_it_downloads(self):
        source = self.page()
        self.assertEqual(source.count("new Audio("), 1)
        self.assertIn('MediaSource.isTypeSupported("audio/mpeg")', source)
        self.assertIn("player.src = stream ? stream.url : audioUrl(event, engine)", source)  # Plain src without MSE.
        self.assertEqual(source.count("URL.createObjectURL("), 1)
        self.assertIn("URL.createObjectURL(media)", source)  # A MediaSource, never a downloaded blob.
        self.assertIn("URL.revokeObjectURL(url)", source)
        self.assertNotIn(".blob()", source)
        self.assertIn('addSourceBuffer("audio/mpeg")', source)

    def test_the_audio_is_fetched_with_a_plain_get(self):
        body = self.section("async function openStream", "async function feed")
        call = body[body.index("fetch("):]
        call = call[:call.index(");") + 2]
        self.assertEqual(call, "fetch(audioUrl(event, engine), { signal: controller.signal });")
        self.assertIn('startsWith("audio/mpeg")', body)
        self.assertIn("response.ok", body)

    def test_a_download_that_fails_ends_the_stream(self):
        body = self.section("async function feed", "// Each event is heard once")
        self.assertIn('endOfStream("network")', body)
        self.assertIn("reader.cancel()", body)
        self.assertIn('"abort"', body)  # Given up before the element opened it: the feed does not wait forever.

    def test_the_audio_starts_when_the_text_is_shown(self):
        body = self.section("function announce(", "// The phone's own voice")
        self.assertLess(body.index('$("announce").textContent = words'), body.index("sayWithServer(event, words)"))
        say = self.section("function sayWithServer", "// The audio as it comes in")
        self.assertNotIn("setTimeout", say)
        self.assertNotIn("await", say)

    def test_falls_back_once_when_loading_or_starting_fails(self):
        body = self.section("async function playAudio", "function finished")
        self.assertIn("START_LIMIT_MS", body)
        self.assertIn("speakWords(words)", body)
        self.assertEqual(body.count("speakWords(words)"), 1)

    def test_a_late_start_after_giving_up_is_stopped(self):
        body = self.section("async function playAudio", "function finished")
        self.assertIn('removeAttribute("src")', body)
        self.assertIn("player.load()", body)
        self.assertIn("gaveUp = true", body)
        self.assertLess(body.index("if (gaveUp)"), body.index("player.src = "))
        self.assertGreaterEqual(body.count("stream.cancel()"), 2)

    def test_unlock_touches_the_audio_element_too(self):
        body = self.section("function unlockSpeech", "function eventWords")
        self.assertIn("player", body)

    def test_mute_stops_both_voices(self):
        body = self.section("function onMute", "// ---- End of announcements")
        self.assertIn("speech.cancel()", body)
        self.assertIn("player.pause()", body)

    def test_acknowledged_only_once_the_audio_is_over(self):
        body = self.section("function acknowledge", "// Keeps the screen on")
        self.assertIn("fetching", body)
        play = self.section("async function playAudio", "function finished")
        tail = play[play.index("} finally {"):]
        self.assertLess(tail.index("fetching.delete(event.id)"), tail.index("acknowledge([event])"))


# ---------------------------------------------------------------- new worktree: repo list

def mkrepo_dir(path, git_dir=True):
    """A folder that looks like a main checkout (a `.git` directory) or a linked worktree (a `.git` file)."""
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    if git_dir:
        (path / ".git").mkdir()
    else:
        (path / ".git").write_text("gitdir: /elsewhere\n")
    return path


class RepoDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(os.path.realpath(self.tmp.name)) / "home"
        self.root = self.home / "code"
        self.root.mkdir(parents=True)

    def names(self, roots=None):
        return sorted(r.name for r in g.find_repos(roots or [str(self.root)], self.home))

    def test_root_children_and_grandchildren(self):
        mkrepo_dir(self.root / "alpha")
        mkrepo_dir(self.root / "group" / "beta")
        mkrepo_dir(self.root / "group" / "deeper" / "gamma")  # Great-grandchild: not looked at.
        (self.root / "plain").mkdir()
        self.assertEqual(self.names(), ["alpha", "beta"])

    def test_the_root_itself_can_be_a_repo_and_is_not_looked_inside(self):
        mkrepo_dir(self.root)
        mkrepo_dir(self.root / "vendored")
        self.assertEqual(self.names(), ["code"])

    def test_a_repo_is_not_looked_inside(self):
        mkrepo_dir(self.root / "alpha")
        mkrepo_dir(self.root / "alpha" / "nested")
        self.assertEqual(self.names(), ["alpha"])

    def test_a_git_file_is_a_linked_worktree_not_a_main_checkout(self):
        mkrepo_dir(self.root / "linked", git_dir=False)
        (self.root / "linked-link").mkdir()
        os.symlink(self.root / "elsewhere", self.root / "linked-link" / ".git")
        self.assertEqual(self.names(), [])

    def test_hidden_folders_and_symlinks_are_skipped(self):
        mkrepo_dir(self.root / ".hidden")
        mkrepo_dir(self.root / ".cache" / "inside")
        real = mkrepo_dir(self.home / "outside" / "real")
        os.symlink(real, self.root / "link")
        os.symlink(self.home / "outside", self.root / "linkdir")
        self.assertEqual(self.names(), [])

    def test_worktrees_and_superset_are_skipped(self):
        mkrepo_dir(self.home / "worktrees" / "alpha" / "fix")
        mkrepo_dir(self.home / ".superset" / "worktrees" / "x")
        mkrepo_dir(self.home / "worktrees-not" / "kept")
        self.assertEqual(self.names([str(self.home)]), ["kept"])
        self.assertEqual(self.names([str(self.home / "worktrees")]), [])
        self.assertEqual(self.names([str(self.home / "worktrees" / "alpha")]), [])
        self.assertEqual(self.names([str(self.home / ".superset" / "worktrees")]), [])

    def test_a_missing_root_is_fine(self):
        self.assertEqual(self.names([str(self.home / "nowhere")]), [])

    def test_counts_are_capped(self):
        for index in range(6):
            mkrepo_dir(self.root / f"repo{index}")
        with unittest.mock.patch.object(g, "MAX_REPOS", 3):
            self.assertEqual(len(g.find_repos([str(self.root)], self.home)), 3)
        for index in range(30):
            (self.root / "many" / f"dir{index:02d}").mkdir(parents=True)
        with unittest.mock.patch.object(g, "MAX_SCANNED_DIRS", 5):
            self.assertLessEqual(len(g.find_repos([str(self.root)], self.home)), 5)

    def test_ids_and_where(self):
        alpha = mkrepo_dir(self.root / "alpha")
        mkrepo_dir(self.root / "group" / "beta")
        found = {r.name: r for r in g.find_repos([str(self.root)], self.home)}
        self.assertEqual(found["alpha"].id, g.repo_id_of(alpha))
        self.assertRegex(found["alpha"].id, r"^[0-9a-f]{12}$")
        self.assertNotEqual(found["alpha"].id, found["beta"].id)
        self.assertEqual(found["alpha"].where, "code")
        self.assertEqual(found["beta"].where, "code/group")
        self.assertEqual(found["alpha"].path, str(alpha))
        self.assertEqual(found["alpha"].view(), {"id": found["alpha"].id, "name": "alpha", "where": "code"})

    def test_the_same_repo_under_two_roots_is_listed_once(self):
        mkrepo_dir(self.root / "alpha")
        self.assertEqual(self.names([str(self.root), str(self.root), str(self.root / "alpha")]), ["alpha"])

    def test_roots_from_the_environment(self):
        self.assertEqual(g.repo_roots_from_env({"HOME": "/h"}), ("/h/work", "/h/personal"))
        self.assertEqual(g.repo_roots_from_env({}), ())
        self.assertEqual(g.repo_roots_from_env({"HOME": "/h", "VOICE_GATEWAY_REPO_ROOTS": ""}), ())
        self.assertEqual(g.repo_roots_from_env({"VOICE_GATEWAY_REPO_ROOTS": "/a:/b::"}), ("/a", "/b"))
        config = g.Config.from_env({"HOME": "/h", "VOICE_GATEWAY_REPO_ROOTS": "/a"})
        self.assertEqual((config.repo_roots, config.home), (("/a",), Path("/h")))

    def test_a_relative_root_refuses_to_start(self):
        data = self.home / "data"
        g.store_passcode(data, g.hash_passcode("long enough", iterations=10))
        out = io.StringIO()
        with redirect_stderr(out):
            code = g.main([], env={"VOICE_GATEWAY_DATA": str(data), "HOME": str(self.home),
                                   "VOICE_GATEWAY_REPO_ROOTS": f"{self.root}:relative/dir"}, runner=FakeTailscale())
        self.assertEqual(code, 1)
        self.assertIn("VOICE_GATEWAY_REPO_ROOTS", out.getvalue())

    def test_the_list_is_cached_briefly(self):
        clock = FakeClock()
        index = g.RepoIndex((str(self.root),), self.home, clock)
        mkrepo_dir(self.root / "alpha")
        self.assertEqual([r.name for r in index.repos()], ["alpha"])
        mkrepo_dir(self.root / "beta")
        self.assertEqual([r.name for r in index.repos()], ["alpha"])
        self.assertEqual([r.name for r in index.repos(fresh=True)], ["alpha", "beta"])
        shutil.rmtree(self.root / "beta")
        clock.t += g.REPO_CACHE_SECONDS + 1
        self.assertEqual([r.name for r in index.repos()], ["alpha"])


# ---------------------------------------------------------------- new worktree: rules

class BranchRuleTests(unittest.TestCase):
    def test_branch_shape(self):
        for branch in ("fix/streaming-timeout", "feat.x_1", "A", "x" * g.MAX_BRANCH_CHARS, "a/b/c"):
            self.assertTrue(g.valid_branch_shape(branch), branch)
        for branch in ("", "-x", "--force", "HEAD", "a b", "é", "x" * (g.MAX_BRANCH_CHARS + 1), "a;b", "$(x)",
                       "a\nb", "a`b", "@{-1}", "a~1", "a^", "a:b", None, 3):
            self.assertFalse(g.valid_branch_shape(branch), branch)

    def test_slug_matches_wt(self):
        self.assertEqual(g.wt_slug("Fix/Streaming_Timeout"), "fix-streaming-timeout")
        self.assertEqual(g.wt_slug("a.b"), "a-b")
        self.assertEqual(g.wt_slug("feat-1"), "feat-1")

    def test_slug_matches_the_real_wt_function(self):
        script = Path(os.path.expanduser("~/.local/bin/wt"))
        if not script.exists():
            self.skipTest("wt is not installed here")
        body = re.search(r"^slug\(\) \{.*\}$", script.read_text(), re.M).group(0)
        for branch in ("Fix/Streaming_Timeout", "a.b..c", "x/y-z_1", "UPPER"):
            done = subprocess.run(["/bin/bash", "-c", f'{body}\nslug "$1"', "-", branch], capture_output=True,
                                  text=True, timeout=10)
            self.assertEqual(done.stdout, g.wt_slug(branch), branch)


class InterpretationTests(unittest.TestCase):
    IDS = ("aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc")

    def parse(self, raw):
        return g.parse_interpretation(raw, self.IDS)

    def bad(self, raw):
        with self.assertRaises(g.InterpretationError):
            self.parse(raw)

    def test_a_plan(self):
        expected = {"repo_id": "aaaaaaaaaaaa", "branch": "fix/x"}
        self.assertEqual(self.parse({"repo_id": "aaaaaaaaaaaa", "branch": "fix/x"}), expected)
        self.assertEqual(self.parse('{"repo_id": "aaaaaaaaaaaa", "branch": "fix/x"}'), expected)
        self.assertEqual(self.parse({"repo_id": "aaaaaaaaaaaa", "branch": "fix/x", "candidates": [],
                                     "question": "", "other": None}), expected)

    def test_a_question(self):
        self.assertEqual(self.parse({"candidates": ["bbbbbbbbbbbb", "aaaaaaaaaaaa"], "question": "Which one?"}),
                         {"candidates": ["bbbbbbbbbbbb", "aaaaaaaaaaaa"], "question": "Which one?"})
        self.assertEqual(self.parse({"candidates": ["aaaaaaaaaaaa"]})["question"], g.DEFAULT_QUESTION)
        cleaned = self.parse({"candidates": ["aaaaaaaaaaaa"], "question": "Which\x1b[31m\n one?" + "x" * 500})
        self.assertTrue(cleaned["question"].startswith("Which[31m one?"))
        self.assertLessEqual(len(cleaned["question"]), g.MAX_QUESTION_CHARS)

    def test_anything_else_is_not_understood(self):
        for raw in ({"repo_id": "aaaaaaaaaaaa"}, {"branch": "x"}, {"repo_id": "zzzzzzzzzzzz", "branch": "x"},
                    {"repo_id": "aaaaaaaaaaaa", "branch": "x", "candidates": ["bbbbbbbbbbbb"]},
                    {"repo_id": "aaaaaaaaaaaa", "branch": "x", "why": "because"},
                    {"repo_id": ["aaaaaaaaaaaa"], "branch": "x"}, {"repo_id": "aaaaaaaaaaaa", "branch": 3},
                    {"candidates": ["zzzzzzzzzzzz"], "question": "q"},
                    {"candidates": ["aaaaaaaaaaaa", "aaaaaaaaaaaa"], "question": "q"},
                    {"candidates": "aaaaaaaaaaaa", "question": "q"}, {"candidates": [1], "question": "q"},
                    {"candidates": ["aaaaaaaaaaaa"], "question": 5}, {}, [], "", "null", "[1]", "{bad",
                    '{"repo_id": "aaaaaaaaaaaa", "branch": NaN}',
                    '{"repo_id": "aaaaaaaaaaaa", "repo_id": "bbbbbbbbbbbb", "branch": "x"}',
                    '```json\n{"repo_id": "aaaaaaaaaaaa", "branch": "x"}\n```', None, 3, b"{}"):
            self.bad(raw)

    def test_candidates_are_capped(self):
        ids = tuple(f"{i:012x}" for i in range(g.MAX_PLAN_CANDIDATES + 1))
        with self.assertRaises(g.InterpretationError):
            g.parse_interpretation({"candidates": list(ids), "question": "q"}, ids)
        self.assertEqual(len(g.parse_interpretation({"candidates": list(ids[:-1])}, ids)["candidates"]),
                         g.MAX_PLAN_CANDIDATES)

    def test_a_plan_with_a_base_as_said(self):
        self.assertEqual(self.parse({"repo_id": "aaaaaaaaaaaa", "branch": "fix/x", "base": "production 4.6.2"}),
                         {"repo_id": "aaaaaaaaaaaa", "branch": "fix/x", "base": "production 4.6.2"})
        cleaned = self.parse({"repo_id": "aaaaaaaaaaaa", "branch": "fix/x", "base": " off\x1b\n uat\x00 "})
        self.assertEqual(cleaned["base"], "off uat")
        for empty in ("", None, " \x07 "):
            self.assertNotIn("base", self.parse({"repo_id": "aaaaaaaaaaaa", "branch": "fix/x", "base": empty}))

    def test_a_bad_base_is_not_understood(self):
        for base in (3, ["dev"], {"x": 1}, "x" * (g.MAX_BRANCH_CHARS + 1)):
            self.bad({"repo_id": "aaaaaaaaaaaa", "branch": "fix/x", "base": base})

    def test_a_base_next_to_candidates_is_dropped(self):
        # A tap on a candidate plans again from the same words, so the base comes back with it then.
        self.assertEqual(self.parse({"candidates": ["aaaaaaaaaaaa"], "question": "Which?", "base": "dev"}),
                         {"candidates": ["aaaaaaaaaaaa"], "question": "Which?"})


class BaseResolutionTests(unittest.TestCase):
    NAMES = ["production-4.6.3", "production-4.6.2", "production-4.6.1", "production-4.6.0", "master", "uat",
             "develop", "feature/login"]

    def resolve(self, said, names=None):
        return g.resolve_base(said, self.NAMES if names is None else names)

    def test_normal_forms(self):
        for said in ("production-4.6.2", "production 4.6.2", "Production 4.6.2", "PRODUCTION-4.6.2",
                     "production four point six point two", "production 4 6 2", "production four six two",
                     "production 4 dot 6 dot 2", "production_4.6.2", "production 4-6-2"):
            self.assertEqual(g.base_key(said), "production-4.6.2", said)
        self.assertEqual(g.base_key("feature/login"), g.base_key("feature login"))
        self.assertEqual(g.base_key("production 4.6"), "production-4.6")
        self.assertEqual(g.base_key("phone one"), "phone-1")  # Number words are whole words only.
        self.assertEqual(g.base_key(""), "")

    def test_exact_and_said_differently(self):
        for said in ("production-4.6.2", "production 4.6.2", "production four point six point two",
                     "production 4 6 2", "Production-4.6.2", "origin/production-4.6.2"):
            self.assertEqual(self.resolve(said), ("exact", "production-4.6.2"), said)
        self.assertEqual(self.resolve("origin/uat"), ("exact", "uat"))
        self.assertEqual(self.resolve("UAT"), ("exact", "uat"))
        self.assertEqual(self.resolve("feature login"), ("exact", "feature/login"))

    def test_a_shared_prefix_offers_taps_and_picks_nothing(self):
        kind, names = self.resolve("production 4.6")
        self.assertEqual(kind, "close")
        self.assertEqual(names, ["production-4.6.3", "production-4.6.2", "production-4.6.1", "production-4.6.0"])
        self.assertEqual(self.resolve("prod")[0], "close")
        self.assertEqual(self.resolve("dev"), ("close", ["develop"]))  # Only "develop" exists: offered, not picked.

    def test_several_equal_once_normalised_are_taps(self):
        names = ["Dev", "dev"]
        self.assertEqual(self.resolve("dev", names), ("exact", "dev"))  # Exactly as said wins.
        self.assertEqual(self.resolve("DEV", names), ("close", ["Dev", "dev"]))

    def test_nothing_close(self):
        self.assertEqual(self.resolve("staging"), ("none", []))
        self.assertEqual(self.resolve("", self.NAMES), ("none", []))
        self.assertEqual(self.resolve("production 4.6.2", []), ("none", []))

    def test_choices(self):
        names = ["uat", "master", "dev", "develop", "main", "prod", "production", "x", "y"]
        self.assertEqual(g.base_choices([], None, "master", names),
                         ["master", "dev", "develop", "uat", "main", "prod", "production"])
        self.assertEqual(g.base_choices(["y", "x"], "uat", "master", names),
                         ["y", "x", "uat", "master", "dev", "develop", "main", "prod"])  # At most 8.
        self.assertEqual(g.base_choices([], "gone", None, ["main"]), ["main"])  # Only what origin has.
        close = [f"production-4.{i}" for i in range(12)]
        self.assertEqual(g.base_choices(close, None, None, close), close[:8])


class FakeInterpreter(g.WorktreeInterpreter if hasattr(g, "WorktreeInterpreter") else object):
    """Answers what it is told: a dict, a JSON text, an exception to raise, or a function of (query, repos)."""
    def __init__(self, answer=None):
        self.answer = answer
        self.calls = []
        self.gate = None

    def plan(self, query, repos):
        self.calls.append((query, [dict(r) for r in repos]))
        if self.gate is not None:
            self.gate.wait(10)
        answer = self.answer(query, repos) if callable(self.answer) else self.answer
        if isinstance(answer, Exception):
            raise answer
        return answer


QUERY = "start a worktree in investor-agent for fixing the streaming timeout"
BRANCH = "fix/streaming-timeout"


class WorktreeCase(unittest.TestCase):
    """Real scratch git repos under a temporary home, a fake runner for wt and herdr, a fake interpreter."""
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(os.path.realpath(self.tmp.name)) / "home"
        self.root = self.home / "code"
        self.repo, self.seed = make_origin(self.home, self.root / "investor-agent", ["main"])
        self.other = make_repo(self.root / "group" / "notes", origin=False, subject="notes start")
        self.runner = FakeRunner()
        self.runner.home = self.home
        self.clock = FakeClock()
        self.interpreter = FakeInterpreter(lambda _q, _r: {"repo_id": g.repo_id_of(self.repo), "branch": BRANCH})
        self.data = self.home / "data"
        self.config = replace(CONFIG, home=self.home, repo_roots=(str(self.root),), data=self.data)
        self.app = self.make()

    def make(self, **overrides):
        config = replace(self.config, **overrides.pop("config", {}))
        return build(self.runner, self.clock, config=config, interpreter=overrides.pop("interpreter",
                                                                                         self.interpreter))

    def tearDown(self):
        self.assertEqual(self.runner.violations, [])

    def refused(self, status, fn, *args):
        with self.assertRaises(g.RequestRejected) as caught:
            fn(*args)
        self.assertEqual(caught.exception.status, status, caught.exception.message)
        return caught.exception.message

    def plan(self, query=QUERY, **extra):
        return self.app.worktree_plan({"query": query, **extra})

    def short_sha(self, ref, repo=None):
        return run_git(["-C", str(repo or self.repo), "rev-parse", "--short", ref]).stdout.strip()

    def target(self, branch=BRANCH, repo="investor-agent"):
        return self.home / "worktrees" / repo / g.wt_slug(branch)


class WorktreeSetupTests(WorktreeCase):
    def test_setup_view(self):
        view = self.app.worktree_setup()
        self.assertEqual((view["enabled"], view["interpreter"], view["repos"]), (True, True, 2))

    def test_off_without_roots_and_says_how(self):
        self.app = self.make(config={"repo_roots": ()})
        view = self.app.worktree_setup()
        self.assertFalse(view["enabled"])
        self.assertIn("VOICE_GATEWAY_REPO_ROOTS", view["message"])
        self.assertIn("VOICE_GATEWAY_REPO_ROOTS", self.refused(409, self.plan))
        self.assertEqual(self.interpreter.calls, [])

    def test_interpreter_not_set_up(self):
        self.app = self.make(interpreter=g.NotConfiguredInterpreter())
        view = self.app.worktree_setup()
        self.assertEqual((view["enabled"], view["interpreter"]), (True, False))
        self.assertIn("not set up", view["message"])
        self.assertIn("not set up", self.refused(409, self.plan))

    def test_the_default_interpreter_is_not_configured(self):
        app = build(self.runner, self.clock, config=self.config)
        self.assertFalse(app.worktree_setup()["interpreter"])


class WorktreePlanTests(WorktreeCase):
    def test_a_new_branch_from_origin_head(self):
        view = self.plan()
        self.assertEqual(view["kind"], "plan")
        self.assertRegex(view["plan_id"], r"^[0-9a-f]{32}$")
        self.assertEqual(view["repo"], {"id": g.repo_id_of(self.repo), "name": "investor-agent", "where": "code"})
        self.assertEqual((view["branch"], view["mode"], view["ready"]), (BRANCH, "new", True))
        base = view["base"]
        self.assertEqual({k: base[k] for k in ("applies", "name", "ref", "how", "sha", "subject", "local")},
                         {"applies": True, "name": "main", "ref": "origin/main", "how": "origin-head",
                          "sha": self.short_sha("origin/main"), "subject": "first commit", "local": True})
        self.assertIsInstance(base["committed_at"], int)
        self.assertIsNone(base["fetched_at"])  # A fresh clone writes no reflog for origin's branches.
        self.assertEqual(base["message"], "")
        self.assertEqual(base["choices"], ["main"])
        self.assertEqual(view["path"], "~/worktrees/investor-agent/fix-streaming-timeout")
        self.assertEqual(self.runner.wt_calls("new"), [])
        self.assertEqual(self.runner.git_calls_of("fetch"), [])  # Planning never fetches.
        self.assertFalse(self.target().exists())

    def test_the_interpreter_gets_the_query_and_names_never_paths(self):
        self.plan()
        query, repos = self.interpreter.calls[0]
        self.assertEqual(query, QUERY)
        self.assertEqual(sorted(r["name"] for r in repos), ["investor-agent", "notes"])
        for repo in repos:
            self.assertEqual(set(repo), {"id", "name", "where"})
            self.assertNotIn(str(self.home), json.dumps(repo))

    def test_without_origin_there_is_no_base_and_never_head(self):
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.other), "branch": "write-more"}
        view = self.plan("a worktree in notes to write more")
        self.assertEqual((view["kind"], view["ready"]), ("plan", False))
        self.assertIsNone(view["base"]["name"])
        self.assertIsNone(view["base"]["sha"])
        self.assertIn("origin", view["base"]["message"])
        self.assertEqual(view["base"]["choices"], [])
        self.assertEqual(view["path"], "~/worktrees/notes/write-more")

    def test_an_existing_branch_is_checked_out(self):
        run_git(["-C", str(self.repo), "branch", BRANCH])
        view = self.plan()
        self.assertEqual((view["mode"], view["ready"]), ("existing", True))
        self.assertEqual(view["base"]["ref"], BRANCH)
        self.assertEqual(view["base"]["sha"], self.short_sha(BRANCH))
        self.assertFalse(view["base"]["applies"])
        self.assertIn("does not apply", view["base"]["message"])

    def test_a_branch_checked_out_elsewhere_is_refused(self):
        run_git(["-C", str(self.repo), "worktree", "add", "-b", BRANCH, str(self.home / "elsewhere")])
        self.assertIn("checked out", self.refused(409, self.plan))

    def test_the_main_checkouts_own_branch_is_refused(self):
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": "main"}
        self.assertIn("checked out", self.refused(409, self.plan))

    def test_an_existing_target_is_refused(self):
        self.target().mkdir(parents=True)
        self.assertIn("already exists", self.refused(409, self.plan))

    def test_bad_branches_are_not_understood(self):
        for branch in ("-x", "HEAD", "a..b", "a b", "x/", "x.lock", ".x", "a/.b", "x//y", "@{-1}", "é", "", 7,
                       "x" * (g.MAX_BRANCH_CHARS + 1)):
            self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": branch}
            view = self.plan()
            self.assertEqual((view["kind"], view["understood"]), ("ask", False), branch)
            self.assertEqual(sorted(c["name"] for c in view["candidates"]), ["investor-agent", "notes"])
        self.assertEqual(self.runner.wt_calls("new"), [])

    def test_check_ref_format_has_the_last_word(self):
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": "a..b"}
        self.assertFalse(self.plan()["understood"])
        self.assertIn(["git", "-C", str(self.repo), "check-ref-format", "--branch", "a..b"], self.runner.git_calls())

    def test_an_unknown_repo_is_not_understood(self):
        self.interpreter.answer = {"repo_id": "000000000000", "branch": BRANCH}
        view = self.plan()
        self.assertEqual((view["kind"], view["understood"]), ("ask", False))
        self.assertTrue(view["question"])

    def test_a_question_with_candidates(self):
        ids = [g.repo_id_of(self.other), g.repo_id_of(self.repo)]
        self.interpreter.answer = {"candidates": ids, "question": "Which repo did you mean?"}
        view = self.plan("start a worktree for the docs")
        self.assertEqual((view["kind"], view["understood"]), ("ask", True))
        self.assertEqual(view["question"], "Which repo did you mean?")
        self.assertEqual([c["name"] for c in view["candidates"]], ["notes", "investor-agent"])
        self.assertNotIn("plan_id", view)

    def test_choosing_a_repo_plans_with_that_repo_only(self):
        chosen = g.repo_id_of(self.other)
        self.interpreter.answer = lambda _q, repos: {"repo_id": repos[0]["id"], "branch": "docs"}
        view = self.plan("start a worktree for the docs", repo_id=chosen)
        self.assertEqual(view["repo"]["name"], "notes")
        self.assertEqual([r["id"] for r in self.interpreter.calls[-1][1]], [chosen])
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": "docs"}  # Not the chosen one.
        self.assertFalse(self.plan("start a worktree for the docs", repo_id=chosen)["understood"])

    def test_an_unknown_chosen_repo(self):
        for bad in ("000000000000", "../x", 5, ""):
            with self.assertRaises(g.RequestRejected) as caught:
                self.plan(repo_id=bad)
            self.assertIn(caught.exception.status, (400, 404), bad)
        self.assertEqual(self.interpreter.calls, [])

    def test_the_query(self):
        for bad in (None, 5, "", " \n\t ", "\x1b\x07", "x" * (g.MAX_QUERY_CHARS + 1)):
            with self.assertRaises(g.RequestRejected) as caught:
                self.app.worktree_plan({"query": bad})
            self.assertEqual(caught.exception.status, 400, bad)
        self.assertEqual(self.interpreter.calls, [])
        self.plan("start  a\nworktree\x1b[31m\tin investor-agent\x00 ")
        self.assertEqual(self.interpreter.calls[-1][0], "start a worktree[31m in investor-agent")

    def test_an_interpreter_failure_is_said_once_and_not_retried(self):
        self.interpreter.answer = g.InterpreterError("timed out")
        view = self.plan()
        self.assertEqual((view["kind"], view["understood"], view["reason"]), ("ask", False, "timed out"))
        self.assertEqual(len(view["candidates"]), 2)
        self.assertEqual(len(self.interpreter.calls), 1)

    def test_an_unexpected_interpreter_crash_is_a_short_reason(self):
        self.interpreter.answer = RuntimeError("secret internals /Users/x")
        view = self.plan()
        self.assertFalse(view["understood"])
        self.assertNotIn("secret", json.dumps(view))

    def test_one_plan_at_a_time(self):
        self.interpreter.gate = threading.Event()
        with g.ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(self.plan)
            self.assertTrue(wait_for(lambda: self.interpreter.calls))
            self.refused(409, self.plan)
            self.interpreter.gate.set()
            self.assertEqual(first.result(5)["kind"], "plan")

    def test_plans_are_capped(self):
        ids = {self.plan()["plan_id"] for _ in range(g.MAX_PLANS + 4)}
        self.assertEqual(len(ids), g.MAX_PLANS + 4)
        self.assertLessEqual(len(self.app.worktrees.plans), g.MAX_PLANS)

    def test_only_read_only_git_runs(self):
        self.plan()
        verbs = {tuple(argv[3:5]) for argv in self.runner.git_calls()}
        self.assertLessEqual(verbs, {("check-ref-format", "--branch"), ("rev-parse", "--path-format=absolute"),
                                     ("show-ref", "--verify"), ("symbolic-ref", "-q"), ("log", "-1"),
                                     ("worktree", "list"),
                                     ("for-each-ref", "--format=%(refname)%09%(committerdate:unix)")})
        for call in self.runner.calls:
            self.assertNotIn(QUERY, " ".join(call["argv"]))


class WorktreeCreateTests(WorktreeCase):
    def create(self, plan_id):
        return self.app.worktree_create({"plan_id": plan_id})

    def test_create_runs_wt_new_in_the_main_checkout(self):
        answer = self.create(self.plan()["plan_id"])
        name = "wt-investor-agent-fix-streaming-timeout"
        sha = self.short_sha("origin/main")
        self.assertEqual(answer, {"ok": True, "session": name, "listed": True,
                                  "path": "~/worktrees/investor-agent/fix-streaming-timeout",
                                  "base": {"ref": "origin/main", "sha": sha, "subject": "first commit",
                                           "fetched": True, "moved": False, "planned_sha": sha},
                                  "launch": None})
        [call] = self.runner.wt_calls("new")
        self.assertEqual(call["argv"], ["wt", "new", BRANCH, "--base", "refs/remotes/origin/main"])
        self.assertEqual(call["cwd"], str(self.repo))
        self.assertTrue(call["detach"])  # Output to files: `wt up` leaves a shell holding stdout open.
        self.assertEqual(call["timeout"], g.WT_NEW_TIMEOUT_SECONDS)
        self.assertIsNone(call["extra_env"])  # WT_EDITOR and everything else as inherited, scrubbed as for restart.
        self.assertTrue(self.target().is_dir())
        self.assertEqual(self.runner.wt_calls("name")[-1]["cwd"], str(self.target()))

    def test_an_existing_branch_is_created_too(self):
        run_git(["-C", str(self.repo), "branch", BRANCH])
        answer = self.create(self.plan()["plan_id"])
        self.assertTrue(answer["listed"])
        self.assertIsNone(answer["base"])
        self.assertEqual(self.runner.wt_calls("new")[0]["argv"], ["wt", "new", BRANCH])  # No base, no fetch.
        self.assertEqual(self.runner.git_calls_of("fetch"), [])

    def test_the_test_account_override_applies_to_wt_new(self):
        self.app = self.make(config={"restart_claude_config_dir": "/test/account"})
        self.create(self.plan()["plan_id"])
        self.assertEqual(self.runner.wt_calls("new")[0]["extra_env"], {"CLAUDE_CONFIG_DIR": "/test/account"})

    def test_a_plan_is_used_once(self):
        plan_id = self.plan()["plan_id"]
        self.create(plan_id)
        self.refused(404, self.create, plan_id)
        self.assertEqual(len(self.runner.wt_calls("new")), 1)

    def test_a_plan_expires(self):
        plan_id = self.plan()["plan_id"]
        self.clock.t += g.PLAN_TTL_SECONDS + 1
        self.assertIn("expired", self.refused(409, self.create, plan_id))
        self.refused(404, self.create, plan_id)
        self.assertEqual(self.runner.wt_calls("new"), [])

    def test_bad_plan_ids(self):
        for bad in (None, 5, "", "../x", "0" * 31, "G" * 32):
            self.refused(400, self.create, bad)
        self.refused(404, self.create, "0" * 32)
        self.assertEqual(self.runner.wt_calls("new"), [])

    def test_everything_is_checked_again_first(self):
        plan_id = self.plan()["plan_id"]
        self.target().mkdir(parents=True)
        self.assertIn("already exists", self.refused(409, self.create, plan_id))
        shutil.rmtree(self.target())
        plan_id = self.plan()["plan_id"]
        run_git(["-C", str(self.repo), "branch", BRANCH])  # Made elsewhere meanwhile: no longer a new branch.
        self.assertIn("changed", self.refused(409, self.create, plan_id))
        run_git(["-C", str(self.repo), "branch", "-D", BRANCH])
        plan_id = self.plan()["plan_id"]
        shutil.rmtree(self.repo)
        self.refused(409, self.create, plan_id)
        self.assertEqual(self.runner.wt_calls("new"), [])
        self.assertEqual(self.runner.git_calls_of("fetch"), [])  # Nothing is fetched for a plan that no longer holds.

    def test_a_base_that_moved_is_not_a_conflict(self):
        plan = self.plan()
        run_git(["-C", str(self.repo), "commit", "-q", "--allow-empty", "-m", "moved on"])
        run_git(["-C", str(self.repo), "update-ref", "refs/remotes/origin/main", "HEAD"])  # The local copy moved.
        answer = self.create(plan["plan_id"])
        self.assertTrue(answer["ok"])
        self.assertEqual(answer["base"]["sha"], plan["base"]["sha"])  # The fetch put origin's commit back.

    def test_one_create_at_a_time(self):
        first = self.plan()["plan_id"]
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": "other-work"}
        second = self.plan("other")["plan_id"]
        gate, entered = threading.Event(), threading.Event()
        self.runner.on_wt_new = lambda: (entered.set(), gate.wait(10))
        with g.ThreadPoolExecutor(max_workers=1) as pool:
            running = pool.submit(self.create, first)
            self.assertTrue(entered.wait(5))
            self.assertIn("Another", self.refused(409, self.create, second))
            gate.set()
            self.assertTrue(running.result(10)["ok"])
        self.assertEqual(len(self.runner.wt_calls("new")), 1)

    def test_wt_new_failing(self):
        self.runner.wt_new_result = Res(1, "", "wt: /x already exists\nmore\n")
        message = self.refused(502, self.create, self.plan()["plan_id"])
        self.assertIn("already exists", message)
        self.assertNotIn("more", message)

    def test_wt_new_timing_out(self):
        self.runner.wt_new_result = None
        self.runner.on_wt_new = unittest.mock.Mock(side_effect=subprocess.TimeoutExpired("wt", 1))
        self.assertIn("in time", self.refused(502, self.create, self.plan()["plan_id"]))

    def test_no_folder_or_no_session_name(self):
        self.runner.wt_new_result = Res(0, "ok\n")
        self.assertIn("no folder", self.refused(502, self.create, self.plan()["plan_id"]))
        self.runner.wt_new_result = None
        real = self.runner._wt_new

        def nameless(branch, cwd, base=None):
            done = real(branch, cwd, base)
            self.runner.wt_names.clear()
            return done
        self.runner._wt_new = nameless
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": "nameless"}
        self.assertIn("wt name", self.refused(502, self.create, self.plan("x")["plan_id"]))

    def test_a_session_that_is_not_listed_is_said(self):
        self.runner.new_sessions_listed = False
        answer = self.create(self.plan()["plan_id"])
        self.assertEqual((answer["ok"], answer["listed"]), (True, False))

    def test_nothing_but_wt_new_and_wt_name_runs_wt(self):
        self.create(self.plan()["plan_id"])
        verbs = {c["argv"][1] for c in self.runner.calls if os.path.basename(c["argv"][0]) == "wt"}
        self.assertEqual(verbs, {"new", "name"})


MF_BRANCH = "fix/otm-copy"


class BaseCase(WorktreeCase):
    """mfcentral-frontend as it is: origin/HEAD is master, and origin has production-4.6.0 to 4.6.3, uat, develop
    and feature/login. A local bare repo is its origin, so fetch and ls-remote are real."""
    def setUp(self):
        super().setUp()
        self.mf, self.mf_seed = make_origin(self.home, self.root / "mfcentral-frontend", MF_BRANCHES, head="master")
        self.said = None
        self.interpreter.answer = self.answer

    def answer(self, _query, repos):
        ids = [r["id"] for r in repos]
        wanted = g.repo_id_of(self.mf)
        found = {"repo_id": wanted if wanted in ids else ids[0], "branch": MF_BRANCH}
        if self.said is not None:
            found["base"] = self.said
        return found

    def plan_from(self, said):
        self.said = said
        return self.plan(f"new worktree in mfcentral-frontend from {said} for the otm copy")

    def replan(self, plan_id, base):
        return self.app.worktree_plan({"plan_id": plan_id, "base": base})

    def create(self, plan_id, **extra):
        return self.app.worktree_create({"plan_id": plan_id, **extra})

    def sha(self, ref, repo=None):
        return run_git(["-C", str(repo or self.mf), "rev-parse", ref]).stdout.strip()

    def worktree_head(self, branch=MF_BRANCH):
        return self.sha("HEAD", self.target(branch, "mfcentral-frontend"))

    def bases_file(self):
        return self.data / "bases.json"

    def remembered(self):
        return json.loads(self.bases_file().read_text())

    def cut_off_origin(self, repo=None):
        run_git(["-C", str(repo or self.mf), "remote", "set-url", "origin", str(self.home / "gone.git")])

    def new_branch_on_origin(self, name, start="master"):
        assert run_git(["-C", str(self.mf_seed), "branch", name, start]).returncode == 0
        return commit_on(self.mf_seed, name, f"on {name}")


class BasePlanTests(BaseCase):
    def test_a_named_base_is_used(self):
        commit_on(self.mf_seed, "production-4.6.2", "fixed on production-4.6.2")
        run_git(["-C", str(self.mf), "fetch", "-q", "origin"])  # Writes the reflog the local copy's date comes from.
        view = self.plan_from("production-4.6.2")
        base = view["base"]
        self.assertEqual((view["kind"], view["ready"], view["mode"]), ("plan", True, "new"))
        self.assertEqual({k: base[k] for k in ("applies", "name", "ref", "how", "subject", "local", "said")},
                         {"applies": True, "name": "production-4.6.2", "ref": "origin/production-4.6.2",
                          "how": "named", "subject": "fixed on production-4.6.2", "local": True,
                          "said": "production-4.6.2"})
        self.assertEqual(base["sha"], self.short_sha("origin/production-4.6.2", self.mf))
        self.assertLess(abs(base["fetched_at"] - time.time()), 600)
        self.assertIsInstance(base["committed_at"], int)
        self.assertEqual(base["message"], "")
        self.assertEqual(base["choices"], ["master", "develop", "uat"])
        self.assertEqual(self.runner.git_calls_of("ls-remote"), [])  # Found locally: origin is not asked.
        self.assertEqual(self.runner.git_calls_of("fetch"), [])

    def test_spoken_forms_resolve_to_exactly_that_branch(self):
        for said in ("production 4.6.2", "production four point six point two", "production 4 6 2",
                     "Production 4.6.2", "origin/production-4.6.2"):
            view = self.plan_from(said)
            self.assertEqual((view["ready"], view["base"]["name"], view["base"]["how"]),
                             (True, "production-4.6.2", "named"), said)
            self.assertEqual(view["base"]["said"], said)

    def test_a_shared_prefix_offers_taps_and_picks_nothing(self):
        view = self.plan_from("production 4.6")
        base = view["base"]
        self.assertEqual((view["kind"], view["ready"], base["name"], base["ref"], base["sha"]),
                         ("plan", False, None, None, None))
        self.assertEqual(sorted(base["choices"][:4]),
                         ["production-4.6.0", "production-4.6.1", "production-4.6.2", "production-4.6.3"])
        self.assertEqual(base["choices"][4:], ["master", "develop", "uat"])
        self.assertIn("production 4.6", base["message"])
        [asked] = self.runner.git_calls_of("ls-remote")  # Origin is asked for the normal form, read only.
        self.assertEqual(asked["argv"][4:], ["--heads", "origin", "refs/heads/production-4.6"])
        self.assertIn("base", self.refused(409, self.create, view["plan_id"]))
        self.assertEqual(self.runner.wt_calls("new"), [])
        self.assertEqual(self.runner.git_calls_of("fetch"), [])

    def test_a_branch_shaped_partial_name_asks_origin_read_only_then_offers_taps(self):
        view = self.plan_from("production-4.6")
        self.assertFalse(view["ready"])
        self.assertEqual(len([c for c in view["base"]["choices"] if c.startswith("production-4.6.")]), 4)
        [call] = self.runner.git_calls_of("ls-remote")
        self.assertEqual(call["argv"], ["git", "-C", str(self.mf), "ls-remote", "--heads", "origin",
                                        "refs/heads/production-4.6"])
        self.assertEqual(call["extra_env"], {"GIT_TERMINAL_PROMPT": "0"})
        self.assertTrue(call["detach"])  # Own process group and output to files: nothing can prompt.
        self.assertEqual(call["timeout"], g.GIT_REMOTE_TIMEOUT_SECONDS)

    def test_a_base_only_on_origin_is_found_there(self):
        remote = self.new_branch_on_origin("production-4.6.4")
        view = self.plan_from("production 4.6.4")
        base = view["base"]
        self.assertEqual((view["ready"], base["name"], base["local"], base["how"]),
                         (True, "production-4.6.4", False, "named"))
        self.assertEqual(base["sha"], remote[:7])
        self.assertIsNone(base["fetched_at"])
        self.assertIsNone(base["committed_at"])
        missing = run_git(["-C", str(self.mf), "show-ref", "--verify", "--quiet", "refs/remotes/origin/production-4.6.4"])
        self.assertEqual(missing.returncode, 1)  # Planning did not download it.
        answer = self.create(view["plan_id"])
        self.assertEqual((answer["base"]["fetched"], answer["base"]["moved"]), (True, False))
        self.assertEqual(self.worktree_head(), remote)

    def test_origin_that_cannot_be_asked_is_said(self):
        for outcome in (Res(128, "", "fatal: Could not read from remote repository. secret words"),
                        subprocess.TimeoutExpired("git", 1)):
            self.runner.git_fail["ls-remote"] = outcome
            view = self.plan_from("production-4.6.9")
            self.assertFalse(view["ready"])
            self.assertIn("could not be asked", view["base"]["message"])
            self.assertNotIn("secret", json.dumps(view))

    def test_nothing_close_says_so(self):
        view = self.plan_from("staging")
        self.assertFalse(view["ready"])
        self.assertIn("staging", view["base"]["message"])
        self.assertEqual(view["base"]["choices"], ["master", "develop", "uat"])

    def test_bad_bases_never_reach_git(self):
        for said in ("-x", "--upload-pack=touch x", "-dev", "a..b", "$(touch x)", "a;b", "HEAD", "@{-1}",
                     "a b/../c", "x" * g.MAX_BRANCH_CHARS + "y", "refs/heads/../x", "é", "uat.lock"):
            before = len(self.runner.calls)
            view = self.plan_from(said)
            self.assertFalse(view.get("ready"), said)  # Too long is "couldn't understand", with no plan at all.
            for call in self.runner.calls[before:]:
                parts = [part for part in call["argv"] if said in part and part != "refs/remotes/origin/HEAD"]
                if parts:  # Only a name of the branch shape is ever given to git, and only to check it.
                    self.assertTrue(g.valid_branch_shape(said), (said, call["argv"]))
                    self.assertEqual(call["argv"][3], "check-ref-format", (said, call["argv"]))
        # The police in FakeRunner records any git argument that starts with "-" and is not one of the gateway's own.

    def test_with_no_base_named_origin_head_is_the_default_not_the_checked_out_branch(self):
        # investor-agent as it is: origin/HEAD is master, the main checkout is on dev.
        repo, _seed = make_origin(self.home, self.root / "ai" / "investor-agent-like", ["master", "dev"],
                                  head="master", checkout="dev")
        self.interpreter.answer = {"repo_id": g.repo_id_of(repo), "branch": "fix/x"}
        view = self.plan("a worktree in investor-agent-like for x")
        self.assertEqual((view["ready"], view["base"]["name"], view["base"]["how"]), (True, "master", "origin-head"))

    def test_with_no_origin_head_the_card_asks_and_never_takes_the_checkout(self):
        repo, _seed = make_origin(self.home, self.root / "ai" / "headless", ["master", "dev"], head="master",
                                  checkout="dev", origin_head=False)
        self.interpreter.answer = {"repo_id": g.repo_id_of(repo), "branch": "fix/x"}
        view = self.plan("a worktree in headless for x")
        self.assertEqual((view["ready"], view["base"]["name"]), (False, None))
        self.assertIn("Pick a base", view["base"]["message"])
        self.assertEqual(view["base"]["choices"], ["dev", "master"])
        again = self.replan(view["plan_id"], "dev")
        self.assertEqual((again["ready"], again["base"]["name"], again["base"]["how"]), (True, "dev", "named"))

    def test_the_last_used_base_comes_first(self):
        self.create(self.plan_from("uat")["plan_id"])
        info = os.lstat(self.bases_file())
        self.assertEqual(stat.S_IMODE(info.st_mode), 0o600)
        self.assertEqual(self.remembered(), {g.repo_id_of(self.mf): "uat"})
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.mf), "branch": "fix/second"}
        view = self.plan("another worktree in mfcentral-frontend")
        self.assertEqual((view["ready"], view["base"]["name"], view["base"]["how"]), (True, "uat", "remembered"))
        self.assertEqual(view["base"]["choices"][:2], ["uat", "master"])

    def test_a_remembered_base_origin_no_longer_has_is_skipped(self):
        self.data.mkdir(parents=True)
        self.bases_file().write_text(json.dumps({g.repo_id_of(self.mf): "gone-branch"}))
        self.bases_file().chmod(0o600)
        view = self.plan("a worktree in mfcentral-frontend")
        self.assertEqual((view["base"]["name"], view["base"]["how"]), ("master", "origin-head"))

    def test_a_bad_remembered_file_is_ignored(self):
        self.data.mkdir(parents=True)
        elsewhere = self.home / "elsewhere.json"
        elsewhere.write_text(json.dumps({g.repo_id_of(self.mf): "uat"}))
        for content in ("{bad", "[]", json.dumps({g.repo_id_of(self.mf): 5}), json.dumps({"x": "uat"}),
                        json.dumps({g.repo_id_of(self.mf): "-uat"}), None):
            if self.bases_file().is_symlink() or self.bases_file().exists():
                self.bases_file().unlink()
            if content is None:
                os.symlink(elsewhere, self.bases_file())
            else:
                self.bases_file().write_text(content)
            self.said = None
            view = self.plan("a worktree in mfcentral-frontend")
            self.assertEqual(view["base"]["how"], "origin-head", content)
        self.create(self.plan_from("develop")["plan_id"])  # Replaces the link itself, never what it points at.
        self.assertFalse(self.bases_file().is_symlink())
        self.assertEqual(json.loads(elsewhere.read_text()), {g.repo_id_of(self.mf): "uat"})
        self.assertEqual(self.remembered(), {g.repo_id_of(self.mf): "develop"})

    def test_remembered_only_after_a_create_that_made_the_folder(self):
        self.runner.wt_new_result = Res(1, "", "wt: failed\n")
        self.refused(502, self.create, self.plan_from("uat")["plan_id"])
        self.runner.wt_new_result = None
        self.cut_off_origin()
        self.refused(502, self.create, self.plan_from("uat")["plan_id"])
        self.assertFalse(self.bases_file().exists())
        run_git(["-C", str(self.mf), "branch", MF_BRANCH, "origin/uat"])
        self.create(self.plan_from("uat")["plan_id"])  # An existing branch has no base to remember.
        self.assertFalse(self.bases_file().exists())

    def test_remembered_entries_are_capped(self):
        self.data.mkdir(parents=True)
        old = {f"{i:012x}": "main" for i in range(g.MAX_REMEMBERED_BASES)}
        self.bases_file().write_text(json.dumps(old))
        self.create(self.plan_from("uat")["plan_id"])
        kept = self.remembered()
        self.assertEqual(len(kept), g.MAX_REMEMBERED_BASES)
        self.assertEqual(kept[g.repo_id_of(self.mf)], "uat")
        self.assertNotIn(f"{0:012x}", kept)  # The oldest went.

    def test_a_base_tap_plans_again_without_the_interpreter(self):
        view = self.plan_from("production 4.6")
        again = self.replan(view["plan_id"], "production-4.6.1")
        self.assertEqual((again["ready"], again["base"]["name"], again["branch"], again["repo"]["name"]),
                         (True, "production-4.6.1", MF_BRANCH, "mfcentral-frontend"))
        self.assertNotEqual(again["plan_id"], view["plan_id"])
        self.assertEqual(len(self.interpreter.calls), 1)
        typed = self.replan(view["plan_id"], "production four point six point three")  # The old plan still works.
        self.assertEqual(typed["base"]["name"], "production-4.6.3")
        self.create(again["plan_id"])
        self.assertEqual(self.worktree_head(), self.sha("refs/remotes/origin/production-4.6.1"))

    def test_bad_base_taps(self):
        view = self.plan_from("production 4.6")
        for bad in (None, 5, "", " \x07 ", "x" * (g.MAX_BRANCH_CHARS + 1), ["uat"]):
            self.refused(400, self.replan, view["plan_id"], bad)
        for bad_id in (None, 5, "../x", "0" * 31):
            self.refused(400, self.replan, bad_id, "uat")
        self.refused(404, self.replan, "0" * 32, "uat")
        self.clock.t += g.PLAN_TTL_SECONDS + 1
        self.assertIn("expired", self.refused(409, self.replan, view["plan_id"], "uat"))
        self.assertEqual(len(self.interpreter.calls), 1)

    def test_an_existing_branch_ignores_the_base(self):
        run_git(["-C", str(self.mf), "branch", MF_BRANCH, "origin/develop"])
        view = self.plan_from("uat")
        self.assertEqual((view["mode"], view["ready"], view["base"]["applies"]), ("existing", True, False))
        self.assertIn("does not apply", view["base"]["message"])
        answer = self.create(view["plan_id"])
        self.assertEqual(self.runner.wt_calls("new")[0]["argv"], ["wt", "new", MF_BRANCH])
        self.assertEqual(self.runner.git_calls_of("fetch"), [])
        self.assertIsNone(answer["base"])


class BaseCreateTests(BaseCase):
    def test_create_fetches_that_one_branch_then_starts_from_it(self):
        view = self.plan_from("production 4.6.2")
        answer = self.create(view["plan_id"])
        [fetch] = self.runner.git_calls_of("fetch")
        self.assertEqual(fetch["argv"], ["git", "-C", str(self.mf), "fetch", "--no-tags", "origin",
                                         "+refs/heads/production-4.6.2:refs/remotes/origin/production-4.6.2"])
        self.assertEqual(fetch["cwd"], str(self.mf))
        self.assertEqual(fetch["extra_env"], {"GIT_TERMINAL_PROMPT": "0"})  # The SSH agent stays as inherited.
        self.assertTrue(fetch["detach"])
        self.assertEqual(fetch["timeout"], g.GIT_FETCH_TIMEOUT_SECONDS)
        [new] = self.runner.wt_calls("new")
        self.assertLess(self.runner.calls.index(fetch), self.runner.calls.index(new))
        self.assertEqual(new["argv"], ["wt", "new", MF_BRANCH, "--base", "refs/remotes/origin/production-4.6.2"])
        self.assertEqual(self.worktree_head(), self.sha("refs/remotes/origin/production-4.6.2"))
        self.assertEqual(answer["base"], {"ref": "origin/production-4.6.2", "sha": view["base"]["sha"],
                                          "subject": "on production-4.6.2", "fetched": True, "moved": False,
                                          "planned_sha": view["base"]["sha"]})

    def test_a_base_that_moved_on_origin_is_reported_not_refused(self):
        view = self.plan_from("production-4.6.2")
        moved = commit_on(self.mf_seed, "production-4.6.2", "hotfix after the plan")
        answer = self.create(view["plan_id"])
        self.assertEqual(self.worktree_head(), moved)
        self.assertEqual((answer["base"]["moved"], answer["base"]["planned_sha"], answer["base"]["subject"]),
                         (True, view["base"]["sha"], "hotfix after the plan"))
        self.assertEqual(answer["base"]["sha"], self.short_sha("refs/remotes/origin/production-4.6.2", self.mf))

    def test_a_failed_fetch_refuses_and_offers_the_downloaded_copy(self):
        view = self.plan_from("production-4.6.2")
        planned = self.sha("refs/remotes/origin/production-4.6.2")
        self.cut_off_origin()
        with self.assertRaises(g.RequestRejected) as caught:
            self.create(view["plan_id"])
        refused = caught.exception
        self.assertEqual(refused.status, 502)
        self.assertTrue(refused.message.startswith("Could not fetch origin/production-4.6.2"), refused.message)
        self.assertIn("Nothing was created", refused.message)
        for word in ("fatal", "gone.git", str(self.home)):
            self.assertNotIn(word, refused.message)
        self.assertEqual(self.runner.wt_calls("new"), [])
        offline = refused.extra["offline"]
        self.assertEqual({k: offline[k] for k in ("ref", "sha", "fetched_at", "committed_at")},
                         {"ref": "origin/production-4.6.2", "sha": view["base"]["sha"],
                          "fetched_at": view["base"]["fetched_at"], "committed_at": view["base"]["committed_at"]})
        self.assertRegex(offline["plan_id"], r"^[0-9a-f]{32}$")
        answer = self.create(offline["plan_id"])
        self.assertEqual(len(self.runner.git_calls_of("fetch")), 1)  # Only the failed one: this tap skips it.
        self.assertEqual(self.runner.wt_calls("new")[0]["argv"],
                         ["wt", "new", MF_BRANCH, "--base", "refs/remotes/origin/production-4.6.2"])
        self.assertEqual(self.worktree_head(), planned)
        self.assertEqual((answer["base"]["fetched"], answer["base"]["moved"]), (False, False))
        self.refused(404, self.create, offline["plan_id"])

    def test_the_downloaded_copy_must_not_have_moved(self):
        view = self.plan_from("production-4.6.2")
        self.cut_off_origin()
        with self.assertRaises(g.RequestRejected) as caught:
            self.create(view["plan_id"])
        run_git(["-C", str(self.mf), "update-ref", "refs/remotes/origin/production-4.6.2", "origin/uat"])
        self.assertIn("changed", self.refused(409, self.create, caught.exception.extra["offline"]["plan_id"]))
        self.assertEqual(self.runner.wt_calls("new"), [])

    def test_a_fetch_that_times_out(self):
        self.runner.git_fail["fetch"] = subprocess.TimeoutExpired("git", 1)
        with self.assertRaises(g.RequestRejected) as caught:
            self.create(self.plan_from("uat")["plan_id"])
        self.assertEqual(caught.exception.status, 502)
        self.assertIn("in time", caught.exception.message)
        self.assertIn("offline", caught.exception.extra)

    def test_no_downloaded_copy_no_offer(self):
        self.new_branch_on_origin("production-4.6.4")
        view = self.plan_from("production-4.6.4")
        self.cut_off_origin()
        with self.assertRaises(g.RequestRejected) as caught:
            self.create(view["plan_id"])
        self.assertEqual(caught.exception.status, 502)
        self.assertNotIn("offline", caught.exception.extra)

    def test_only_the_offered_plan_skips_the_fetch(self):
        self.create(self.plan_from("uat")["plan_id"], fetch=False, offline=True, skip_fetch=True)
        self.assertEqual(len(self.runner.git_calls_of("fetch")), 1)

    def test_fetch_reasons_are_short_and_never_gits_own_words(self):
        cases = {"ssh: Could not resolve hostname gitlab.example.com: nodename nor servname": "reached",
                 "git@host: Permission denied (publickey).\nfatal: Could not read from remote repository.": "login",
                 "fatal: could not read Username for 'https://x': terminal prompts disabled": "login",
                 "fatal: couldn't find remote ref refs/heads/uat": "no branch uat",
                 "fatal: 'origin' does not appear to be a git repository": "origin",
                 "something else entirely with secret words": "exit 128"}
        for stderr, words in cases.items():
            reason = g.fetch_failure_reason("uat", 128, stderr)
            self.assertIn(words, reason, stderr)
            for leaked in ("secret", "gitlab.example.com", "publickey", "https://x", "fatal"):
                self.assertNotIn(leaked, reason)


class HttpWorktreeTests(HttpCase):
    def make_app(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(os.path.realpath(self.tmp.name)) / "home"
        self.repo, self.seed = make_origin(self.home, self.home / "code" / "investor-agent", ["main", "dev"])
        self.runner.home = self.home
        self.interpreter = FakeInterpreter({"repo_id": g.repo_id_of(self.repo), "branch": BRANCH})
        config = replace(CONFIG, home=self.home, repo_roots=(str(self.home / "code"),), data=self.home / "data")
        return build(self.runner, self.clock, config=config, tailscale=self.tailscale, interpreter=self.interpreter)

    def test_routes_need_identity_cookie_and_post_rules(self):
        self.assertEqual(self.request("GET", "/api/worktree/setup")[0], 401)
        self.assertEqual(self.post("/api/worktree/plan", {"query": QUERY})[0], 401)
        self.log_in()
        self.assertEqual(self.post("/api/worktree/plan", {"query": QUERY}, Origin="https://evil.com")[0], 403)
        self.assertEqual(self.post("/api/worktree/plan", {"query": QUERY}, Content_Type="text/plain")[0], 415)
        self.assertEqual(self.post("/api/worktree/plan", {"query": QUERY}, Tailscale_User_Login=None)[0], 403)
        self.assertEqual(self.request("GET", "/api/worktree/plan")[0], 404)
        self.assertEqual(self.interpreter.calls, [])

    def test_plan_then_create(self):
        self.log_in()
        status, _, body, _ = self.request("GET", "/api/worktree/setup")
        self.assertEqual((status, json.loads(body)["enabled"]), (200, True))
        status, _, body, _ = self.post("/api/worktree/plan", {"query": QUERY})
        self.assertEqual(status, 200)
        plan = json.loads(body)
        self.assertNotIn(str(self.home), body.decode())
        status, _, body, _ = self.post("/api/worktree/create", {"plan_id": plan["plan_id"]})
        self.assertEqual(status, 200, body)
        self.assertEqual(json.loads(body)["session"], "wt-investor-agent-fix-streaming-timeout")
        self.assertEqual(self.post("/api/worktree/create", {"plan_id": plan["plan_id"]})[0], 404)
        self.assertNotIn(QUERY, self.log.getvalue())

    def test_a_base_tap_then_a_failed_fetch_offers_the_downloaded_copy(self):
        self.log_in()
        plan = json.loads(self.post("/api/worktree/plan", {"query": QUERY})[2])
        self.assertEqual(plan["base"]["name"], "main")
        status, _, body, _ = self.post("/api/worktree/plan", {"plan_id": plan["plan_id"], "base": "dev"})
        self.assertEqual(status, 200, body)
        again = json.loads(body)
        self.assertEqual((again["base"]["name"], again["base"]["how"], again["ready"]), ("dev", "named", True))
        self.assertEqual(len(self.interpreter.calls), 1)  # A base tap never asks the interpreter again.
        run_git(["-C", str(self.repo), "remote", "set-url", "origin", str(self.home / "gone.git")])
        status, _, body, _ = self.post("/api/worktree/create", {"plan_id": again["plan_id"]})
        self.assertEqual(status, 502, body)
        refused = json.loads(body)
        self.assertIn("Could not fetch origin/dev", refused["error"])
        self.assertNotIn(str(self.home), body.decode())
        offline = refused["offline"]
        self.assertEqual(offline["ref"], "origin/dev")
        status, _, body, _ = self.post("/api/worktree/create", {"plan_id": offline["plan_id"]})
        self.assertEqual(status, 200, body)
        self.assertFalse(json.loads(body)["base"]["fetched"])

    def test_the_reason_is_in_the_body(self):
        self.log_in()
        status, _, body, _ = self.post("/api/worktree/create", {"plan_id": "0" * 32})
        self.assertEqual((status, json.loads(body)), (404, {"error": "No such plan. Plan again.",
                                                            "reason": "plan_gone"}))
        status, _, body, _ = self.post("/api/worktree/plan", {"query": ""})
        self.assertEqual((status, json.loads(body)["reason"]), (400, "bad_query"))

    def test_the_launch_is_in_the_body(self):
        self.log_in()
        self.runner.wt_new_stdout = LAUNCH_LINE + "\n"
        plan = json.loads(self.post("/api/worktree/plan", {"query": QUERY})[2])
        status, _, body, _ = self.post("/api/worktree/create", {"plan_id": plan["plan_id"]})
        self.assertEqual((status, json.loads(body)["launch"]), (200, LAUNCH))


# ---------------------------------------------------------------- new worktree: launch line and refusal reasons

LAUNCH_LINE = "wt-launch: model=claude-opus-5-5 effort=high playbook=yes settings=no trust=written"
LAUNCH = {"model": "claude-opus-5-5", "effort": "high", "playbook": True, "settings": False, "trust": "written"}


class LaunchLineTests(unittest.TestCase):
    def test_a_full_line(self):
        self.assertEqual(g.parse_wt_launch(f"Herdr session x started\n{LAUNCH_LINE}\n/tmp/x\n"), LAUNCH)

    def test_defaults_and_every_value(self):
        self.assertEqual(g.parse_wt_launch("wt-launch: model=default effort=default playbook=no settings=no trust=off"),
                         {"model": "default", "effort": "default", "playbook": False, "settings": False,
                          "trust": "off"})
        for effort in ("low", "medium", "high", "xhigh", "max"):
            for trust in ("written", "already", "skipped", "off"):
                line = f"wt-launch: model=m.1-x effort={effort} playbook=no settings=yes trust={trust}"
                self.assertEqual(g.parse_wt_launch(line),
                                 {"model": "m.1-x", "effort": effort, "playbook": False, "settings": True,
                                  "trust": trust})

    def test_anything_else_is_no_launch_and_never_an_error(self):
        bad = [None, 5, "", "\n\n", "wt-launch: restored", LAUNCH_LINE + " ", " " + LAUNCH_LINE, LAUNCH_LINE + "\r",
               LAUNCH_LINE.replace(" effort", "  effort"), LAUNCH_LINE.replace("claude-opus-5-5", "Claude-Opus"),
               LAUNCH_LINE.replace("claude-opus-5-5", "x" * 65), LAUNCH_LINE.replace("claude-opus-5-5", "a b"),
               LAUNCH_LINE.replace("claude-opus-5-5", ""), LAUNCH_LINE.replace("high", "huge"),
               LAUNCH_LINE.replace("playbook=yes", "playbook=true"), LAUNCH_LINE.replace("written", "done"),
               LAUNCH_LINE.replace(" settings=no", ""), LAUNCH_LINE + " extra=1", "x" + LAUNCH_LINE,
               LAUNCH_LINE.replace("wt-launch:", "wt-launch"), LAUNCH_LINE.replace("model=", "model=claude/x")]
        for text in bad:
            self.assertIsNone(g.parse_wt_launch(text), text)
        self.assertEqual(g.parse_wt_launch("x" * 65 + "\n" + LAUNCH_LINE + "\r\nmore"), None)

    def test_the_last_matching_line_wins(self):
        other = LAUNCH_LINE.replace("trust=written", "trust=already")
        self.assertEqual(g.parse_wt_launch(f"{LAUNCH_LINE}\n{other}\n")["trust"], "already")
        self.assertEqual(g.parse_wt_launch(f"{LAUNCH_LINE}\n{other} garbage\n")["trust"], "written")
        self.assertIsNone(g.parse_wt_launch(f"{LAUNCH_LINE} x\nwt-launch: restored\n"))

    def test_only_the_listed_fields_come_back(self):
        self.assertEqual(set(g.parse_wt_launch(LAUNCH_LINE)), {"model", "effort", "playbook", "settings", "trust"})


class WorktreeLaunchTests(WorktreeCase):
    def create(self):
        return self.app.worktree_create({"plan_id": self.plan()["plan_id"]})

    def test_the_create_answer_carries_the_launch(self):
        self.runner.wt_new_stdout = LAUNCH_LINE + "\n"
        answer = self.create()
        self.assertEqual(answer["launch"], LAUNCH)
        self.assertEqual(set(answer), {"ok", "session", "listed", "path", "base", "launch"})

    def test_no_line_is_null(self):
        self.assertIsNone(self.create()["launch"])

    def test_a_restored_session_or_garbage_is_null(self):
        for stdout in ("wt-launch: restored\n", LAUNCH_LINE + "  \n", "wt-launch: model=$(x) effort=high\n"):
            self.runner.wt_new_stdout = stdout
            self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": f"b{len(self.runner.calls)}"}
            answer = self.create()
            self.assertTrue(answer["ok"])
            self.assertIsNone(answer["launch"], stdout)

    def test_a_line_on_stderr_only_is_ignored(self):
        self.runner.wt_new_stderr = LAUNCH_LINE + "\n"
        self.assertIsNone(self.create()["launch"])

    def test_two_lines_the_last_wins(self):
        self.runner.wt_new_stdout = LAUNCH_LINE + "\n" + LAUNCH_LINE.replace("high", "max") + "\n"
        self.assertEqual(self.create()["launch"]["effort"], "max")

    def test_nothing_else_from_wt_is_passed_on(self):
        self.runner.wt_new_stdout = "secret words from wt\n" + LAUNCH_LINE + "\n"
        self.assertNotIn("secret", json.dumps(self.create()))

    def test_the_child_environment_rules_are_unchanged(self):
        env = {"WT_ORCHESTRATOR_MODEL": "m", "WT_PRETRUST": "1", "WT_EDITOR": "none", "CLAUDE_CODE_X": "1",
               "CLAUDECODE": "1", "HERDR_SESSION": "s", "VOICE_GATEWAY_REPO_ROOTS": "/x", "PATH": "/bin"}
        self.assertEqual(g.child_env(env), {"WT_ORCHESTRATOR_MODEL": "m", "WT_PRETRUST": "1", "WT_EDITOR": "none",
                                            "PATH": "/bin"})


class WorktreeReasonTests(WorktreeCase):
    """Every worktree refusal carries a fixed reason; messages and statuses as before."""
    def reason(self, status, fn, *args):
        with self.assertRaises(g.RequestRejected) as caught:
            fn(*args)
        refused = caught.exception
        self.assertEqual(refused.status, status, refused.message)
        self.assertTrue(refused.message)
        return refused.extra.get("reason")

    def create(self, plan_id):
        return self.app.worktree_create({"plan_id": plan_id})

    def replan(self, plan_id, base):
        return self.app.worktree_plan({"plan_id": plan_id, "base": base})

    def test_off(self):
        self.app = self.make(config={"repo_roots": ()})
        self.assertEqual(self.reason(409, self.plan), "worktrees_off")
        self.assertEqual(self.reason(409, self.create, "0" * 32), "worktrees_off")

    def test_interpreter_off(self):
        self.app = self.make(interpreter=g.NotConfiguredInterpreter())
        self.assertEqual(self.reason(409, self.plan), "interpreter_off")
        self.app = self.make()
        self.interpreter.answer = g.InterpreterNotConfigured("x")
        self.assertEqual(self.reason(409, self.plan), "interpreter_off")

    def test_bad_query_repo_and_gone_repo(self):
        self.assertEqual(self.reason(400, self.app.worktree_plan, {"query": None}), "bad_query")
        self.assertEqual(self.reason(400, self.app.worktree_plan, {"query": "x" * (g.MAX_QUERY_CHARS + 1)}),
                         "bad_query")
        self.assertEqual(self.reason(400, lambda: self.plan(repo_id="../x")), "bad_repo")
        self.assertEqual(self.reason(404, lambda: self.plan(repo_id="000000000000")), "repo_gone")

    def test_no_repos(self):
        empty = self.home / "empty"
        empty.mkdir()
        self.app = self.make(config={"repo_roots": (str(empty),)})
        self.assertEqual(self.reason(409, self.plan), "no_repos")

    def test_plan_busy(self):
        self.interpreter.gate = threading.Event()
        with g.ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(self.plan)
            self.assertTrue(wait_for(lambda: self.interpreter.calls))
            self.assertEqual(self.reason(409, self.plan), "plan_busy")
            self.interpreter.gate.set()
            first.result(5)

    def test_plan_ids_and_bases(self):
        plan_id = self.plan()["plan_id"]
        for bad in (None, 5, "", "../x"):
            self.assertEqual(self.reason(400, self.replan, bad, "main"), "bad_plan")
            self.assertEqual(self.reason(400, self.create, bad), "bad_plan")
        for bad in ("", None, "x" * (g.MAX_BRANCH_CHARS + 1)):
            self.assertEqual(self.reason(400, self.replan, plan_id, bad), "bad_base")
        self.assertEqual(self.reason(404, self.replan, "0" * 32, "main"), "plan_gone")
        self.assertEqual(self.reason(404, self.create, "0" * 32), "plan_gone")
        self.clock.t += g.PLAN_TTL_SECONDS + 1
        self.assertEqual(self.reason(409, self.replan, plan_id, "main"), "plan_expired")
        self.assertEqual(self.reason(409, self.create, plan_id), "plan_expired")
        self.assertEqual(self.reason(404, self.create, plan_id), "plan_gone")  # Used, even though it failed.

    def test_create_busy(self):
        first = self.plan()["plan_id"]
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": "other-work"}
        second = self.plan("other")["plan_id"]
        gate, entered = threading.Event(), threading.Event()
        self.runner.on_wt_new = lambda: (entered.set(), gate.wait(10))
        with g.ThreadPoolExecutor(max_workers=1) as pool:
            running = pool.submit(self.create, first)
            self.assertTrue(entered.wait(5))
            self.assertEqual(self.reason(409, self.create, second), "create_busy")
            gate.set()
            running.result(10)

    def test_repo_unlisted(self):
        plan_id = self.plan()["plan_id"]
        again = self.plan()["plan_id"]
        shutil.rmtree(self.repo)
        self.assertEqual(self.reason(409, self.create, plan_id), "repo_unlisted")
        self.assertEqual(self.reason(409, self.replan, again, "main"), "repo_unlisted")

    def test_checkout_refusals(self):
        self.target().mkdir(parents=True)
        self.assertEqual(self.reason(409, self.plan), "target_exists")
        shutil.rmtree(self.target())
        plan_id = self.plan()["plan_id"]
        self.target().mkdir(parents=True)
        self.assertEqual(self.reason(409, self.create, plan_id), "target_exists")
        shutil.rmtree(self.target())
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": "main"}
        self.assertEqual(self.reason(409, self.plan), "branch_checked_out")
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": BRANCH}
        self.runner.git_fail["rev-parse"] = Res(128, "", "fatal")
        self.assertEqual(self.reason(409, self.plan), "not_main_checkout")
        del self.runner.git_fail["rev-parse"]
        run_git(["-C", str(self.repo), "branch", BRANCH])
        self.runner.git_fail["log"] = Res(128, "", "fatal")
        self.assertEqual(self.reason(409, self.plan), "no_commit")

    def test_git_failed(self):
        for verb, outcome in (("show-ref", subprocess.TimeoutExpired("git", 1)), ("show-ref", OSError("x")),
                              ("show-ref", Res(128, "", "fatal")), ("for-each-ref", Res(128, "", "fatal")),
                              ("worktree", Res(128, "", "fatal"))):
            self.runner.git_fail = {verb: outcome}
            if verb == "worktree":
                run_git(["-C", str(self.repo), "branch", BRANCH])
            self.assertEqual(self.reason(502, self.plan), "git_failed", verb)

    def test_repo_changed(self):
        plan_id = self.plan()["plan_id"]
        run_git(["-C", str(self.repo), "branch", BRANCH])
        self.assertEqual(self.reason(409, self.create, plan_id), "repo_changed")

    def test_branch_and_base_unusable(self):
        plan_id = self.plan()["plan_id"]
        self.runner.git_fail["check-ref-format"] = Res(1, "", "")
        self.assertEqual(self.reason(409, self.create, plan_id), "branch_unusable")
        del self.runner.git_fail["check-ref-format"]
        plan_id = self.plan()["plan_id"]
        real = self.app.worktrees._branch_ok
        self.app.worktrees._branch_ok = lambda path, name: name != "main" and real(path, name)
        self.assertEqual(self.reason(409, self.create, plan_id), "base_unusable")

    def test_not_ready(self):
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.other), "branch": "write-more"}
        self.assertEqual(self.reason(409, self.create, self.plan("notes")["plan_id"]), "not_ready")

    def test_fetch_failed_keeps_offline(self):
        self.runner.git_fail["fetch"] = Res(128, "", "fatal: secret")
        with self.assertRaises(g.RequestRejected) as caught:
            self.create(self.plan()["plan_id"])
        refused = caught.exception
        self.assertEqual((refused.status, refused.extra["reason"]), (502, "fetch_failed"))
        self.assertIn("offline", refused.extra)
        self.assertTrue(refused.message.startswith("Could not fetch origin/main"))

    def test_wt_refusals(self):
        self.app = self.make(config={"wt": None})
        self.assertEqual(self.reason(409, self.create, self.plan()["plan_id"]), "wt_missing")
        self.app = self.make()
        self.runner.on_wt_new = unittest.mock.Mock(side_effect=OSError("x"))
        self.assertEqual(self.reason(409, self.create, self.plan()["plan_id"]), "wt_unrunnable")
        self.runner.on_wt_new = unittest.mock.Mock(side_effect=subprocess.TimeoutExpired("wt", 1))
        self.assertEqual(self.reason(502, self.create, self.plan()["plan_id"]), "wt_timeout")
        self.runner.on_wt_new = None
        self.runner.wt_new_result = Res(1, "", "wt: failed\n")
        self.assertEqual(self.reason(502, self.create, self.plan()["plan_id"]), "wt_failed")
        self.runner.wt_new_result = Res(0, "ok\n")
        self.assertEqual(self.reason(502, self.create, self.plan()["plan_id"]), "no_folder")
        self.runner.wt_new_result = None
        real = self.runner._wt_new

        def nameless(branch, cwd, base=None):
            done = real(branch, cwd, base)
            self.runner.wt_names.clear()
            return done
        self.runner._wt_new = nameless
        self.interpreter.answer = {"repo_id": g.repo_id_of(self.repo), "branch": "nameless"}
        self.assertEqual(self.reason(502, self.create, self.plan("x")["plan_id"]), "no_session_name")

    def test_every_refusal_in_the_worktree_code_names_a_reason(self):
        source = inspect.getsource(g.NewWorktrees)
        calls = re.findall(r"RequestRejected\((?:[^()]|\([^()]*\))*\)", source)
        self.assertGreater(len(calls), 20)
        for call in calls:
            self.assertRegex(call, r"reason=\"[a-z_]+\"", call)

    def test_reasons_are_codes(self):
        source = inspect.getsource(g.NewWorktrees) + inspect.getsource(g.Gateway.worktree_create)
        for code in re.findall(r"reason=\"([^\"]*)\"", source):
            self.assertRegex(code, r"^[a-z][a-z_]+$")


# ---------------------------------------------------------------- new worktree: the claude interpreter

FAKE_MACHO = b"\xcf\xfa\xed\xfe" + b"\x00" * 60
REPOS = [{"id": "aaaaaaaaaaaa", "name": "investor-agent", "where": "work"},
         {"id": "bbbbbbbbbbbb", "name": "notes", "where": "personal"}]


class RecordingRunner:
    """Stands in for the CommandRunner under ClaudeInterpreter, and looks at the folder it is run in."""
    def __init__(self, stdout="", rc=0, error=None):
        self.stdout, self.rc, self.error = stdout, rc, error
        self.calls = []

    def run(self, argv, timeout=15, cwd=None, extra_env=None, detach=False, input_text=None):
        info = os.stat(cwd)
        self.calls.append({"argv": list(argv), "timeout": timeout, "cwd": cwd, "extra_env": extra_env,
                           "detach": detach, "input_text": input_text, "listing": os.listdir(cwd),
                           "mode": stat.S_IMODE(info.st_mode)})
        if self.error:
            raise self.error
        return Res(self.rc, self.stdout, "stderr words")


def envelope(**fields):
    return json.dumps({"type": "result", "subtype": "success", "is_error": False, "result": "", **fields})


class ClaudeInterpreterTests(unittest.TestCase):
    def interpreter(self, runner):
        return g.ClaudeInterpreter("/opt/claude/bin/claude", runner)

    def test_argv_flags_and_stdin(self):
        runner = RecordingRunner(envelope(structured_output={"repo_id": "aaaaaaaaaaaa", "branch": "fix/x"}))
        query = "--dangerously-skip-permissions; start a worktree in investor-agent"
        self.assertEqual(self.interpreter(runner).plan(query, REPOS), {"repo_id": "aaaaaaaaaaaa", "branch": "fix/x"})
        [call] = runner.calls
        argv = call["argv"]
        self.assertEqual(argv[0], "/opt/claude/bin/claude")
        for flag in ("-p", "--safe-mode", "--restricted", "--strict-mcp-config", "--disable-slash-commands",
                     "--no-session-persistence"):
            self.assertIn(flag, argv)
        self.assertEqual(argv[argv.index("--model") + 1], "claude-haiku-5-5")
        self.assertEqual(argv[argv.index("--tools") + 1], "")
        self.assertEqual(argv[argv.index("--output-format") + 1], "json")
        schema = json.loads(argv[argv.index("--json-schema") + 1])
        self.assertEqual(set(schema["properties"]), {"repo_id", "branch", "base", "candidates", "question"})
        self.assertEqual(schema["properties"]["base"], {"type": "string"})
        self.assertNotIn("required", schema)  # The base is optional.
        prompt = argv[argv.index("--system-prompt") + 1]
        for words in ('"base"', "from dev", "off uat", "from production 4.6.2", "as said"):
            self.assertIn(words, prompt)
        self.assertNotIn("--mcp-config", argv)
        self.assertNotIn("--dangerously-skip-permissions", argv)
        for part in argv:
            self.assertNotIn("investor-agent", part)  # Neither the query nor the repo list is in the argv.
        data = json.loads(call["input_text"][call["input_text"].index("{"):])
        self.assertEqual(data, {"request": query, "repos": REPOS})
        self.assertEqual((call["listing"], call["mode"]), ([], 0o700))  # An empty private folder...
        self.assertFalse(os.path.exists(call["cwd"]))  # ...removed afterwards.
        self.assertEqual(call["timeout"], g.INTERPRETER_TIMEOUT_SECONDS)
        self.assertIsNone(call["extra_env"])  # The default account: CLAUDE_CONFIG_DIR is scrubbed, never set.
        self.assertFalse(call["detach"])

    def test_the_result_text_is_parsed_when_there_is_no_structured_output(self):
        runner = RecordingRunner(envelope(result='{"candidates": ["aaaaaaaaaaaa"], "question": "Which?"}'))
        self.assertEqual(self.interpreter(runner).plan("q", REPOS),
                         '{"candidates": ["aaaaaaaaaaaa"], "question": "Which?"}')

    def failure(self, runner):
        with self.assertRaises(g.InterpreterError) as caught:
            self.interpreter(runner).plan("q", REPOS)
        message = str(caught.exception)
        self.assertLessEqual(len(message), 80)
        for word in ("model words", "stderr words", "secret"):
            self.assertNotIn(word, message)
        return message

    def test_failures_give_a_short_reason_only(self):
        self.failure(RecordingRunner("model words", rc=1))
        self.failure(RecordingRunner(envelope(is_error=True, result="secret model words")))
        self.failure(RecordingRunner(envelope(subtype="error_max_turns", result="model words")))
        self.failure(RecordingRunner("not json model words"))
        self.failure(RecordingRunner(json.dumps(["model words"])))
        self.failure(RecordingRunner(envelope(result="x" * (g.MAX_INTERPRETER_OUTPUT + 10))))
        self.assertIn("time", self.failure(RecordingRunner(error=subprocess.TimeoutExpired("claude", 1))))
        self.failure(RecordingRunner(error=OSError("secret")))

    def test_no_claude_means_not_configured(self):
        with tempfile.TemporaryDirectory() as tmp:
            interpreter = g.interpreter_from_env({"PATH": tmp, "HOME": tmp}, RecordingRunner())
        self.assertIsInstance(interpreter, g.NotConfiguredInterpreter)
        self.assertFalse(interpreter.configured)
        with self.assertRaises(g.InterpreterNotConfigured):
            interpreter.plan("q", REPOS)

    def test_the_real_binary_is_found_and_a_wrapper_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(os.path.realpath(tmp))
            versions, bin_dir, local = tmp / "versions", tmp / "bin", tmp / ".local" / "bin"
            for folder in (versions, bin_dir, local):
                folder.mkdir(parents=True)
            real = versions / "2.1.0"
            real.write_bytes(FAKE_MACHO)
            real.chmod(0o755)
            os.symlink(real, bin_dir / "claude")
            self.assertEqual(g.find_claude({"PATH": str(bin_dir), "HOME": str(tmp)}), str(real))
            interpreter = g.interpreter_from_env({"PATH": str(bin_dir), "HOME": str(tmp)}, RecordingRunner())
            self.assertIsInstance(interpreter, g.ClaudeInterpreter)
            self.assertEqual(interpreter.binary, str(real))
            os.unlink(bin_dir / "claude")
            (bin_dir / "claude").write_text("#!/bin/sh\nexec real-claude \"$@\"\n")
            (bin_dir / "claude").chmod(0o755)
            self.assertIsNone(g.find_claude({"PATH": str(bin_dir), "HOME": str(tmp)}))
            os.symlink(real, local / "claude")
            self.assertEqual(g.find_claude({"PATH": "", "HOME": str(tmp)}), str(real))
            elf = versions / "elf"
            elf.write_bytes(b"\x7fELF" + b"\x00" * 60)
            elf.chmod(0o755)
            self.assertTrue(g.native_binary(str(elf)))
            self.assertFalse(g.native_binary(str(tmp / "missing")))

    def test_the_start_up_line_names_any_interpreter(self):
        # Found live: main assumed the claude interpreter and failed to start with another one plugged in.
        self.assertEqual(g.interpreter_summary(g.ClaudeInterpreter("/opt/claude", RecordingRunner())),
                         "claude-haiku-5-5 via /opt/claude")
        self.assertEqual(g.interpreter_summary(FakeInterpreter()), "FakeInterpreter")
        self.assertEqual(g.interpreter_summary(g.NotConfiguredInterpreter()), "not set up")

    def test_the_gateway_uses_it_through_the_plan_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(os.path.realpath(tmp)) / "home"
            repo = make_repo(home / "code" / "investor-agent")
            runner = FakeRunner()
            answer = {"repo_id": g.repo_id_of(repo), "branch": "--force"}
            interpreter = g.ClaudeInterpreter("/opt/claude", RecordingRunner(envelope(structured_output=answer)))
            app = build(runner, FakeClock(), config=replace(CONFIG, home=home, repo_roots=(str(home / "code"),)),
                        interpreter=interpreter)
            view = app.worktree_plan({"query": QUERY})
            self.assertEqual((view["kind"], view["understood"]), ("ask", False))
            self.assertEqual(runner.violations, [])


class WorktreePageTests(unittest.TestCase):
    def page(self, name="app.js"):
        return (ROOT / "static" / name).read_text()

    def section(self, start, end):
        source = self.page()
        return source[source.index(start):source.index(end)]

    def test_plan_and_create_need_their_own_taps(self):
        source = self.page()
        self.assertEqual(source.count('"/api/worktree/plan"'), 1)
        self.assertEqual(source.count('"/api/worktree/create"'), 1)
        create = self.section("async function onCreate", "// ---- Speech only ever fills the text box")
        self.assertNotIn("/api/prompt", create)
        self.assertNotIn("onSend", create)
        speech = self.section("// ---- Speech only ever fills the text box", "// ---- Announcements.")
        for word in ("onPlan", "onCreate", "/api/worktree"):
            self.assertNotIn(word, speech)
        self.assertIn('$("plan").addEventListener("click", () => onPlan(null))', source)
        self.assertIn('$("plan-create").addEventListener("click", onCreate)', source)

    def test_the_client_sends_ids_never_paths(self):
        plan = self.section("async function onPlan", "function showPlan")
        self.assertIn("repoId ? { query, repo_id: repoId } : { query }", plan)
        create = self.section("async function onCreate", "// ---- Speech only ever fills the text box")
        self.assertIn("{ plan_id: state.planId }", create)

    def test_the_request_is_carried_into_the_new_worktrees_box_unsent(self):
        create = self.section("async function onCreate", "// ---- Speech only ever fills the text box")
        self.assertIn("state.carryText = state.planQuery", create)
        body = self.section("function route()", "// ---- Earlier output")
        self.assertIn('$("text").value = state.carryText || ""', body)

    def test_the_new_view_shows_the_text_box(self):
        # Found live: the reset loop in route() hid the text box again after composeFor had shown it.
        body = self.section("function route()", "// ---- Earlier output")
        reset = body.index('for (const id of ["keys", "stop", "compose"')
        self.assertGreater(body.index("composeFor(view)"), reset)

    def test_plan_card_and_candidates_use_text_only(self):
        body = self.section("function showPlan", "async function onCreate")
        self.assertIn("textContent", body)
        self.assertNotIn("innerHTML", body)

    def test_the_card_shows_the_base_and_says_it_is_fetched(self):
        body = self.section("function showPlan", "async function onCreate")
        for words in ("Fetched from origin just before creating", "does not apply", "answer.ready",
                      "base.fetched_at", "base.committed_at", "base.choices", "base.message"):
            self.assertIn(words, body)
        self.assertIn('$("plan-create").disabled = !answer.ready', body)

    def test_base_taps_and_the_typed_base_plan_again_with_the_plan_id(self):
        plan = self.section("async function onPlan", "function showPlan")
        self.assertIn("{ plan_id: state.planId, base }", plan)
        source = self.page()
        self.assertIn('$("plan-base-use").addEventListener("click"', source)

    def test_a_failed_fetch_offers_the_downloaded_copy_and_the_final_sha_is_shown(self):
        create = self.section("async function onCreate", "// ---- Speech only ever fills the text box")
        for words in ("error.data.offline", "Create from the downloaded copy", "answer.base", "state.carryBanner"):
            self.assertIn(words, create)
        html = self.page("index.html")
        for element in ("plan-bases", "plan-base-input", "plan-base-use", "plan-offline", "plan-fetch-note",
                        "plan-base-note"):
            self.assertIn(f'id="{element}"', html)
        self.assertIn('$("plan-offline").addEventListener("click", onCreate)', self.page())

    def test_the_carried_note_survives_an_earlier_poll_error(self):
        # Found live: a poll that failed before Create left the "clear on the next good poll" mark on the banner, so
        # the note saying which commit the worktree started from vanished at once.
        body = self.section("function route()", "// ---- Earlier output")
        self.assertLess(body.index('delete $("banner").dataset.poll'), body.index("showBanner(state.carryBanner"))


# ---------------------------------------------------------------- local speech (Chatterbox and Whisper on NixOS)

SPEECH_TOKEN = "Zr3kq0d9mJx2VbT7wYh4LcN8pFsA1eGu6oIiRtWlKyE"
WEBM = b"\x1aE\xdf\xa3" + b"\x00" * 200  # The EBML magic a webm recording starts with.


class FakeSpeech:
    """A loopback stand-in for the speech service on NixOS: GET /health, POST /tts (chunked audio/mpeg, the first
    sentence first) and POST /stt (the audio as the body, JSON back). Every request needs the Bearer token."""
    def __init__(self, token=SPEECH_TOKEN):
        self.token = token
        self.requests = []
        self.health = {"tts": "ready", "stt": "ready"}
        self.tts_status, self.tts_type, self.chunks, self.gates, self.delay, self.drip = 200, "audio/mpeg", None, {}, 0, 0
        self.cut = False
        self.error_body = b'{"error": "the model said something private"}'
        self.stt_status, self.stt_answer, self.stt_gate = 200, {"text": "new worktree in investor-agent"}, None
        self.lock = threading.Lock()
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def authorised(self):
                return self.headers.get("Authorization") == f"Bearer {fake.token}"

            def record(self, body=b""):
                with fake.lock:
                    fake.requests.append({"method": self.command, "path": self.path, "headers": dict(self.headers),
                                          "body": body})

            def reply(self, status, body, kind="application/json"):
                self.send_response(status)
                self.send_header("Content-Type", kind)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                self.record()
                if not self.authorised():
                    return self.reply(401, b'{"error": "token"}')
                if self.path != "/health":
                    return self.reply(404, b"{}")
                self.reply(200, json.dumps(fake.health).encode())

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                self.record(body)
                if not self.authorised():
                    return self.reply(401, b'{"error": "token"}')
                if self.path == "/tts":
                    return self.tts()
                if self.path == "/stt":
                    if fake.stt_gate is not None:
                        fake.stt_gate.wait(10)
                    if fake.stt_status != 200:
                        return self.reply(fake.stt_status, fake.error_body)
                    answer = fake.stt_answer if isinstance(fake.stt_answer, bytes) else json.dumps(fake.stt_answer)
                    return self.reply(200, answer if isinstance(answer, bytes) else answer.encode())
                self.reply(404, b"{}")

            def tts(self):
                if fake.delay:
                    time.sleep(fake.delay)
                if fake.tts_status != 200:
                    return self.reply(fake.tts_status, fake.error_body)
                chunks = fake.chunks if fake.chunks is not None else [FAKE_MP3]
                self.send_response(200)
                self.send_header("Content-Type", fake.tts_type)
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                for index, chunk in enumerate(chunks):
                    if index in fake.gates:
                        fake.gates[index].wait(10)
                    if index and fake.drip:
                        time.sleep(fake.drip)
                    self.wfile.write(b"%x\r\n%s\r\n" % (len(chunk), chunk))
                    if fake.cut:
                        self.close_connection = True
                        return
                self.wfile.write(b"0\r\n\r\n")

            def log_message(self, *args):
                pass

        class Server(http.server.ThreadingHTTPServer):
            daemon_threads = True

            def handle_error(self, request, client_address):
                pass

        self.server = Server(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def close(self):
        for gate in (*self.gates.values(), *([self.stt_gate] if self.stt_gate else [])):
            gate.set()
        self.server.shutdown()
        self.server.server_close()

    def of(self, path):
        return [r for r in self.requests if r["path"] == path]


class FakeLocal(FakeVoice):
    """Stands in for LocalSpeech inside the gateway: the voice of FakeVoice, plus transcribe() and health()."""
    def __init__(self):
        super().__init__()
        self.url = "http://127.0.0.1:8790"
        self.transcribed = []
        self.transcript = "start a worktree in investor-agent from dev"
        self.transcribe_fail = None
        self.transcribe_gate = None

    def transcribe(self, audio, content_type, vocabulary):
        with self.lock:
            self.transcribed.append((audio, content_type, vocabulary))
        if self.transcribe_gate is not None:
            self.transcribe_gate.wait(10)
        if self.transcribe_fail:
            raise self.transcribe_fail
        return self.transcript

    def health(self):
        return {"tts": "ready", "stt": "ready"}


class SpeechUrlTests(unittest.TestCase):
    def test_tailnet_or_loopback_http_only(self):
        for good in ("http://100.96.188.64:8790", "http://127.0.0.1:8790", "http://100.64.0.1:1",
                     "http://127.0.0.1:8790/"):
            self.assertTrue(g.valid_speech_url(good), good)
        for bad in ("https://100.96.188.64:8790", "http://0.0.0.0:8790", "http://10.0.0.1:8790",
                    "http://localhost:8790", "http://example.com:8790", "http://u@100.96.188.64:8790",
                    "http://u:p@127.0.0.1:8790", "http://100.96.188.64:8790/tts", "http://100.96.188.64:8790?x=1",
                    "http://100.96.188.64:8790#x", "http://100.96.188.64", "http://100.96.188.64:0",
                    "http://100.96.188.64:99999", " http://127.0.0.1:8790", "http://[::1]:8790",
                    "http://100.128.0.1:8790", "http://127.0.0.2:8790", "http://192.168.1.5:8790", "", None,
                    "ftp://127.0.0.1:21", "http://127.0.0.1:8790\\x", "http://100.96.188.64:8790 x"):
            self.assertFalse(g.valid_speech_url(bad), bad)

    def test_config_reads_it(self):
        self.assertEqual(g.Config.from_env({"VOICE_GATEWAY_SPEECH_URL": "http://127.0.0.1:8790"}).speech_url,
                         "http://127.0.0.1:8790")
        self.assertIsNone(g.Config.from_env({}).speech_url)
        self.assertIsNone(g.Config.from_env({"VOICE_GATEWAY_SPEECH_URL": ""}).speech_url)

    def test_local_speech_refuses_a_bad_address(self):
        for bad in ("http://example.com:8790", "https://127.0.0.1:8790"):
            with self.assertRaises(ValueError):
                g.LocalSpeech(bad, SPEECH_TOKEN)


class SpeechTokenTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.data = Path(self.tmp.name) / "data"

    def test_round_trip_is_private(self):
        g.store_speech_token(self.data, SPEECH_TOKEN)
        path = g.speech_token_path(self.data)
        self.assertEqual(path.name, "speech-token")
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(g.load_speech_token(self.data), SPEECH_TOKEN)

    def test_missing_is_none(self):
        self.assertIsNone(g.load_speech_token(self.data))

    def test_unsafe_files_are_refused(self):
        g.store_speech_token(self.data, SPEECH_TOKEN)
        path = g.speech_token_path(self.data)
        path.chmod(0o644)
        with self.assertRaises(g.KeyFileError) as caught:
            g.load_speech_token(self.data)
        self.assertNotIn(SPEECH_TOKEN, str(caught.exception))
        path.unlink()
        target = Path(self.tmp.name) / "elsewhere"
        target.write_text(SPEECH_TOKEN)
        target.chmod(0o600)
        path.symlink_to(target)
        with self.assertRaises(g.KeyFileError):
            g.load_speech_token(self.data)
        path.unlink()
        path.mkdir()
        with self.assertRaises(g.KeyFileError):
            g.load_speech_token(self.data)
        path.rmdir()
        fd = os.open(path, os.O_WRONLY | os.O_CREAT, 0o600)
        os.write(fd, b"short\n")
        os.close(fd)
        with self.assertRaises(g.KeyFileError):
            g.load_speech_token(self.data)

    def test_token_shape(self):
        self.assertTrue(g.valid_speech_token(SPEECH_TOKEN))
        for bad in ("", "short", "a b" * 20, "x" * 513, "tok\nen" * 10, "ü" * 30, None):
            self.assertFalse(g.valid_speech_token(bad), bad)

    def test_set_from_a_pipe_or_a_prompt(self):
        class Pipe(io.StringIO):
            def isatty(self):
                return False

        class Terminal(io.StringIO):
            def isatty(self):
                return True
        out = io.StringIO()
        self.assertEqual(g.set_speech_token(self.data, ask=lambda _p: "unused", stdin=Pipe(SPEECH_TOKEN + "\n"),
                                            out=out), 0)
        self.assertEqual(g.load_speech_token(self.data), SPEECH_TOKEN)
        other = "A" * 43
        asked = []
        self.assertEqual(g.set_speech_token(self.data, ask=lambda p: asked.append(p) or other, stdin=Terminal(""),
                                            out=out), 0)
        self.assertEqual(len(asked), 1)
        self.assertEqual(g.load_speech_token(self.data), other)
        self.assertNotIn(SPEECH_TOKEN, out.getvalue())
        self.assertNotIn(other, out.getvalue())

    def test_a_bad_token_changes_nothing(self):
        class Pipe(io.StringIO):
            def isatty(self):
                return False
        out = io.StringIO()
        self.assertEqual(g.set_speech_token(self.data, stdin=Pipe("not a token\n"), out=out), 1)
        self.assertIsNone(g.load_speech_token(self.data))
        self.assertEqual(g.set_speech_token(self.data, stdin=Pipe(""), out=out), 1)
        self.assertIsNone(g.load_speech_token(self.data))


class SpeechMainTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.data = Path(self.tmp.name) / "data"

    def env(self, **extra):
        return {"VOICE_GATEWAY_DATA": str(self.data), "VOICE_GATEWAY_PORT": "0", **extra}

    def test_set_speech_token_needs_no_tailscale_or_passcode_and_reads_a_pipe(self):
        class NoRunner:
            def run(self, *a, **k):
                raise AssertionError("no command should run")

        class Pipe(io.StringIO):
            def isatty(self):
                return False
        out = io.StringIO()
        with redirect_stderr(out), unittest.mock.patch("sys.stdout", out):
            code = g.main(["--set-speech-token"], env=self.env(), runner=NoRunner(),
                          ask=lambda _p: self.fail("a pipe is read, not asked"), stdin=Pipe(SPEECH_TOKEN))
        self.assertEqual(code, 0)
        self.assertEqual(g.load_speech_token(self.data), SPEECH_TOKEN)
        self.assertNotIn(SPEECH_TOKEN, out.getvalue())
        self.assertFalse(g.passcode_path(self.data).exists())

    def test_start_refuses_a_bad_speech_url(self):
        g.store_passcode(self.data, g.hash_passcode("long enough", iterations=10))
        out = io.StringIO()
        with redirect_stderr(out):
            code = g.main([], env=self.env(VOICE_GATEWAY_SPEECH_URL="http://example.com:8790"),
                          runner=FakeTailscale())
        self.assertEqual(code, 1)
        self.assertIn("VOICE_GATEWAY_SPEECH_URL", out.getvalue())

    def test_start_refuses_a_loose_speech_token(self):
        g.store_passcode(self.data, g.hash_passcode("long enough", iterations=10))
        g.store_speech_token(self.data, SPEECH_TOKEN)
        g.speech_token_path(self.data).chmod(0o644)
        out = io.StringIO()
        with redirect_stderr(out):
            code = g.main([], env=self.env(VOICE_GATEWAY_SPEECH_URL="http://127.0.0.1:8790"), runner=FakeTailscale())
        self.assertEqual(code, 1)
        self.assertIn("speech-token", out.getvalue())

    def config(self, url=None):
        return replace(CONFIG, data=self.data, speech_url=url)

    def test_on_only_with_both(self):
        speech, reason = g.speech_from_config(self.config())
        self.assertIsNone(speech)
        self.assertIn("VOICE_GATEWAY_SPEECH_URL", reason)
        speech, reason = g.speech_from_config(self.config("http://127.0.0.1:8790"))
        self.assertIsNone(speech)
        self.assertIn("--set-speech-token", reason)
        g.store_speech_token(self.data, SPEECH_TOKEN)
        speech, reason = g.speech_from_config(self.config("http://127.0.0.1:8790"))
        self.assertIsInstance(speech, g.LocalSpeech)
        self.assertEqual(reason, "")
        self.assertNotIn(SPEECH_TOKEN, repr(speech))

    def test_start_up_line_says_where_and_whether_it_answered(self):
        fake = FakeSpeech()
        self.addCleanup(fake.close)
        line = g.speech_status_line(g.LocalSpeech(fake.url, SPEECH_TOKEN), "")
        self.assertIn(fake.url, line)
        self.assertIn("reachable", line)
        self.assertNotIn("not reachable", line)
        self.assertIn("tts ready", line)
        url = fake.url
        fake.close()
        line = g.speech_status_line(g.LocalSpeech(url, SPEECH_TOKEN), "")
        self.assertIn("not reachable", line)
        self.assertIn(url, line)
        self.assertIn("off", g.speech_status_line(None, "no token is stored"))
        self.assertIn("no token is stored", g.speech_status_line(None, "no token is stored"))

    def test_a_refused_token_is_not_reachable(self):
        fake = FakeSpeech(token="B" * 43)
        self.addCleanup(fake.close)
        line = g.speech_status_line(g.LocalSpeech(fake.url, SPEECH_TOKEN), "")
        self.assertIn("401", line)


class LocalSpeechVoiceTests(unittest.TestCase):
    def setUp(self):
        self.fake = FakeSpeech()
        self.addCleanup(self.fake.close)

    def speech(self, **limits):
        return g.LocalSpeech(self.fake.url, SPEECH_TOKEN, **limits)

    def failure(self, call):
        with self.assertRaises(g.VoiceError) as caught:
            call()
        self.assertNotIn(SPEECH_TOKEN, caught.exception.message)
        self.assertNotIn("private", caught.exception.message)
        return caught.exception

    def test_request_shape(self):
        self.assertEqual(self.speech().synthesise("It is two."), FAKE_MP3)
        request = self.fake.of("/tts")[-1]
        self.assertEqual(request["method"], "POST")
        self.assertEqual(request["headers"]["Authorization"], f"Bearer {SPEECH_TOKEN}")
        self.assertEqual(request["headers"]["Content-Type"], "application/json")
        self.assertEqual(json.loads(request["body"]), {"text": "It is two."})

    def test_chunks_pass_on_as_they_arrive(self):
        self.fake.chunks = [FAKE_MP3, b"second", b"third"]
        self.fake.gates = {1: threading.Event()}
        threading.Timer(1.0, self.fake.gates[1].set).start()
        started = time.monotonic()
        chunks = self.speech().stream("One. Two.")
        first = next(chunks)
        self.assertLess(time.monotonic() - started, 0.6)
        self.assertEqual(first + b"".join(chunks), FAKE_MP3 + b"secondthird")

    def test_text_limits_hold_before_any_request(self):
        self.failure(lambda: self.speech().synthesise("x" * (g.LOCAL_TTS_MAX_CHARS + 1)))
        self.failure(lambda: self.speech().synthesise("   "))
        self.assertEqual(self.fake.of("/tts"), [])
        self.assertEqual(self.speech().synthesise("x" * g.LOCAL_TTS_MAX_CHARS), FAKE_MP3)

    def test_errors_keep_the_status_only(self):
        for status in (400, 401, 413, 500, 503):
            self.fake.tts_status = status
            self.assertEqual(self.failure(lambda: self.speech().synthesise("Hi.")).status, status)

    def test_answer_must_be_mp3_and_not_empty(self):
        self.fake.tts_type = "text/html"
        self.failure(lambda: self.speech().synthesise("Hi."))
        self.fake.tts_type, self.fake.chunks = "audio/mpeg", [b""]
        self.failure(lambda: self.speech().synthesise("Hi."))

    def test_size_cap(self):
        self.fake.chunks = [b"\xff" * 3000, b"\xff" * 3000]
        with unittest.mock.patch.object(g, "MAX_AUDIO_BYTES", 4000):
            self.failure(lambda: self.speech().synthesise("Hi."))

    def test_first_byte_limit_is_short(self):
        self.fake.delay = 1.5
        started = time.monotonic()
        error = self.failure(lambda: self.speech(first_byte_timeout=0.3, timeout=5).synthesise("Hi."))
        self.assertIsNone(error.status)
        self.assertLess(time.monotonic() - started, 1.2)

    def test_overall_deadline_holds_while_chunks_keep_coming(self):
        self.fake.chunks, self.fake.drip = [FAKE_MP3] * 8, 0.15
        started = time.monotonic()
        self.failure(lambda: list(self.speech(first_byte_timeout=0.5, timeout=0.6).stream("Hi.")))
        self.assertLess(time.monotonic() - started, 1.0)

    def test_cut_short_is_a_failure(self):
        self.fake.chunks, self.fake.cut = [FAKE_MP3, b"rest"], True
        self.failure(lambda: b"".join(self.speech().stream("Hi.")))

    def test_nobody_listening(self):
        url = self.fake.url
        self.fake.close()
        self.assertIsNone(self.failure(lambda: g.LocalSpeech(url, SPEECH_TOKEN).synthesise("Hi.")).status)
        self.assertIsNone(self.failure(lambda: g.LocalSpeech(url, SPEECH_TOKEN).health()).status)
        self.assertIsNone(self.failure(lambda: g.LocalSpeech(url, SPEECH_TOKEN).transcribe(WEBM, "audio/webm", "")).status)

    def test_transcribe_request_shape(self):
        text = self.speech().transcribe(WEBM, "audio/webm", "Branches: dev.")
        self.assertEqual(text, "new worktree in investor-agent")
        request = self.fake.of("/stt")[-1]
        self.assertEqual(request["body"], WEBM)
        self.assertEqual(request["headers"]["Content-Type"], "audio/webm")
        self.assertEqual(request["headers"]["X-Vocabulary"], "Branches: dev.")
        self.assertEqual(request["headers"]["Authorization"], f"Bearer {SPEECH_TOKEN}")

    def test_transcribe_failures(self):
        self.fake.stt_status = 500
        self.assertEqual(self.failure(lambda: self.speech().transcribe(WEBM, "audio/webm", "")).status, 500)
        self.fake.stt_status = 200
        for answer in (b"not json", {"text": 5}, {"words": "x"}, [1]):
            self.fake.stt_answer = answer
            self.failure(lambda: self.speech().transcribe(WEBM, "audio/webm", ""))

    def test_health(self):
        self.assertEqual(self.speech().health(), {"tts": "ready", "stt": "ready"})
        self.assertEqual(self.fake.of("/health")[-1]["headers"]["Authorization"], f"Bearer {SPEECH_TOKEN}")


class VocabularyTests(unittest.TestCase):
    BRANCHES = "Branches: dev, develop, uat, main, master, prod, production."

    def test_branches_alone(self):
        self.assertEqual(g.speech_vocabulary([]), self.BRANCHES)

    def test_branches_then_repos(self):
        self.assertEqual(g.speech_vocabulary(["investor-agent", "sebi-rag"]),
                         self.BRANCHES + " Repositories: investor-agent, sebi-rag.")

    def test_whole_names_within_the_limit(self):
        names = [f"repository-number-{n:03d}" for n in range(60)]
        text = g.speech_vocabulary(names)
        self.assertLessEqual(len(text), g.MAX_VOCABULARY_CHARS)
        listed = text[len(self.BRANCHES + " Repositories: "):-1].split(", ")
        self.assertEqual(listed, names[:len(listed)])
        self.assertGreater(len(listed), 10)
        self.assertTrue(text.endswith("."))

    def test_odd_names_are_left_out(self):
        text = g.speech_vocabulary(["good-one", "bad name", "naïve", "a,b", "x\ny", "v1.2_ok"])
        self.assertTrue(text.endswith("Repositories: good-one, v1.2_ok."))

    def test_most_recently_used_first(self):
        with tempfile.TemporaryDirectory() as tmp:
            repos = []
            for number, name in enumerate(("old", "newest", "middle")):
                path = Path(tmp) / name
                (path / ".git").mkdir(parents=True)
                stamp = {"old": 1000, "newest": 3000, "middle": 2000}[name]
                os.utime(path / ".git", (stamp, stamp))
                repos.append(g.Repo(f"{number:012d}", name, str(path), "work"))
            repos.append(g.Repo("9" * 12, "gone", str(Path(tmp) / "gone"), "work"))
            self.assertEqual([r.name for r in g.recent_first(repos)], ["newest", "middle", "old", "gone"])


class EngineTests(GatewayCase):
    send, nonce, sent_text = AnnouncementTests.send, AnnouncementTests.nonce, AnnouncementTests.sent_text
    summary_event, params = VoiceEndpointTests.summary_event, staticmethod(VoiceEndpointTests.params)

    def setUp(self):
        super().setUp()
        self.deepgram, self.local = FakeVoice(), FakeLocal()
        self.addCleanup(self.deepgram.release)
        self.addCleanup(self.local.release)
        self.app = build(self.runner, self.clock, voice=self.deepgram, speech=self.local)

    def audio(self, event_id, **extra):
        return b"".join(self.app.voice_audio(self.params(event_id, **extra)))

    def test_views_say_which_voices(self):
        view = self.app.sessions_view()
        self.assertEqual((view["voice"], view["voices"]), ("chatterbox", ["chatterbox", "deepgram"]))
        self.assertEqual(view["local"], {"configured": True, "reason": ""})
        self.assertEqual(self.app.session_view(SCRATCH)["voices"], ["chatterbox", "deepgram"])
        only_local = build(self.runner, self.clock, speech=self.local).sessions_view()
        self.assertEqual((only_local["voice"], only_local["voices"]), ("chatterbox", ["chatterbox"]))
        none = build(self.runner, self.clock, speech_reason="No speech token is stored.").sessions_view()
        self.assertEqual((none["voice"], none["voices"]), ("browser", []))
        self.assertEqual(none["local"], {"configured": False, "reason": "No speech token is stored."})
        deepgram_only = build(self.runner, self.clock, voice=self.deepgram).sessions_view()
        self.assertEqual((deepgram_only["voice"], deepgram_only["voices"]), ("deepgram", ["deepgram"]))
        self.assertFalse(deepgram_only["local"]["configured"])
        self.assertTrue(deepgram_only["local"]["reason"])

    def test_engine_picks_the_voice_and_defaults_to_chatterbox(self):
        event = self.summary_event()
        self.audio(event["id"])
        self.assertEqual((self.local.texts, self.deepgram.texts), (["It is two."], []))
        self.audio(event["id"], engine="deepgram")
        self.assertEqual(self.deepgram.texts, ["It is two."])
        self.audio(event["id"], engine="chatterbox", with_name="1")
        self.assertEqual(self.local.texts, ["It is two.", "scratch: It is two."])

    def test_engine_values(self):
        event = self.summary_event()
        for bad in ("phone", "browser", "", "Chatterbox", "chatterbox ", "deepgram,chatterbox"):
            self.refused(400, self.app.voice_audio, self.params(event["id"], engine=bad))
        self.refused(400, self.app.voice_audio, {"id": [event["id"]], "engine": ["chatterbox", "deepgram"]})
        self.assertEqual((self.local.texts, self.deepgram.texts), ([], []))

    def test_an_engine_that_is_not_set_up_is_404(self):
        self.app = build(self.runner, self.clock, voice=self.deepgram)
        event = self.summary_event()
        self.refused(404, lambda: self.audio(event["id"], engine="chatterbox"))
        self.app = build(self.runner, self.clock, speech=self.local)
        event = self.summary_event()
        self.refused(404, lambda: self.audio(event["id"], engine="deepgram"))
        self.assertEqual(self.deepgram.texts, [])

    def test_one_download_per_event_variant_and_engine(self):
        event = self.summary_event()
        first = self.audio(event["id"], engine="chatterbox")
        self.assertEqual(self.audio(event["id"], engine="chatterbox"), first)
        self.audio(event["id"], engine="deepgram")
        self.audio(event["id"], engine="deepgram")
        self.assertEqual((len(self.local.texts), len(self.deepgram.texts)), (1, 1))

    def test_a_chatterbox_failure_is_remembered_for_chatterbox_only(self):
        event = self.summary_event()
        self.local.fail = g.VoiceError(None, "The speech service did not answer in time.")
        self.refused(502, lambda: self.audio(event["id"], engine="chatterbox"))
        self.local.fail = None
        self.refused(502, lambda: self.audio(event["id"], engine="chatterbox"))  # Chrome retrying by itself.
        self.assertEqual(len(self.local.texts), 1)
        self.assertTrue(self.audio(event["id"], engine="deepgram").startswith(FAKE_MP3))
        self.assertEqual(list(self.app.failed), [(event["id"], False, "chatterbox")])
        self.app.acknowledge({"ids": [event["id"]]})
        self.assertEqual(self.app.failed, {})
        self.assertEqual(self.app.audio.size(), 0)

    def test_the_502_names_the_engine(self):
        event = self.summary_event()
        self.local.fail = g.VoiceError(503, "x")
        message = self.refused(502, lambda: self.audio(event["id"], engine="chatterbox"))
        self.assertIn("Chatterbox", message)
        self.assertIn("503", message)

    def test_a_slow_chatterbox_clip_waits_as_long_as_its_own_limit(self):
        self.local.timeout = 30
        event = self.summary_event()
        self.local.gates["It is two."] = threading.Event()
        chunks = self.app.voice_audio(self.params(event["id"], engine="chatterbox"))
        self.assertTrue(next(chunks))
        threading.Timer(0.2, self.local.gates["It is two."].set).start()
        self.assertTrue(b"".join(chunks))


class TranscribeTests(GatewayCase):
    def setUp(self):
        super().setUp()
        self.local = FakeLocal()
        self.addCleanup(self.local.release)
        self.app = build(self.runner, self.clock, speech=self.local)

    def test_the_text_comes_back_and_the_audio_goes_on_as_it_is(self):
        answer = self.app.transcribe(WEBM, "audio/webm;codecs=opus")
        self.assertEqual(answer, {"text": "start a worktree in investor-agent from dev"})
        audio, kind, vocabulary = self.local.transcribed[-1]
        self.assertEqual((audio, kind), (WEBM, "audio/webm"))
        self.assertTrue(vocabulary.startswith("Branches: dev, develop, uat, main, master, prod, production."))

    def test_types(self):
        for kind in ("audio/webm", "audio/webm;codecs=opus", "audio/ogg; codecs=opus", "audio/mp4", "audio/x-m4a",
                     "audio/wav", "audio/x-wav", "audio/wave", "Audio/WebM"):
            self.app.transcribe(WEBM, kind)
            self.clock.t += 10
        for kind in ("application/json", "text/plain", "audio/mpeg", "video/webm", "", None, "audio/webm\r\nX: y"):
            self.refused(415, self.app.transcribe, WEBM, kind)

    def test_sizes(self):
        self.refused(400, self.app.transcribe, b"", "audio/webm")
        self.refused(413, self.app.transcribe, b"\x00" * (g.MAX_TRANSCRIBE_BYTES + 1), "audio/webm")
        self.app.transcribe(b"\x00" * g.MAX_TRANSCRIBE_BYTES, "audio/webm")

    def test_off_without_local_speech(self):
        self.app = build(self.runner, self.clock, speech_reason="No speech token is stored.")
        message = self.refused(404, self.app.transcribe, WEBM, "audio/webm")
        self.assertIn("Local Whisper", message)

    def test_rate_limit(self):
        for _ in range(g.TRANSCRIBE_PER_MINUTE):
            self.app.transcribe(WEBM, "audio/webm")
            self.clock.t += 1
        with self.assertRaises(g.RequestRejected) as caught:
            self.app.transcribe(WEBM, "audio/webm")
        self.assertEqual(caught.exception.status, 429)
        self.assertGreater(caught.exception.retry_after, 0)
        self.assertEqual(len(self.local.transcribed), g.TRANSCRIBE_PER_MINUTE)
        self.clock.t += 60
        self.app.transcribe(WEBM, "audio/webm")

    def test_one_at_a_time(self):
        self.local.transcribe_gate = threading.Event()
        with g.ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(self.app.transcribe, WEBM, "audio/webm")
            self.assertTrue(wait_for(lambda: len(self.local.transcribed) == 1))
            self.refused(429, self.app.transcribe, WEBM, "audio/webm")
            self.local.transcribe_gate.set()
            self.assertIn("text", first.result(5))
        self.assertEqual(len(self.local.transcribed), 1)

    def test_a_failure_is_502_with_a_short_reason(self):
        self.local.transcribe_fail = g.VoiceError(500, "the model said something private")
        message = self.refused(502, self.app.transcribe, WEBM, "audio/webm")
        self.assertIn("500", message)
        self.assertNotIn("private", message)

    def test_the_text_is_cleaned_and_capped(self):
        self.local.transcript = "  new\x00 worktree\x1b[31m   in\tdev ‮" + "x" * (g.MAX_PROMPT_CHARS + 50)
        text = self.app.transcribe(WEBM, "audio/webm")["text"]
        self.assertTrue(text.startswith("new worktree[31m in dev"))
        self.assertLessEqual(len(text), g.MAX_PROMPT_CHARS)
        for ch in text:
            self.assertNotIn(g.unicodedata.category(ch), ("Cc", "Cf"))

    def test_the_vocabulary_has_the_repos_most_recent_first(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        home = Path(tmp.name)
        for name, stamp in (("sebi-rag", 1000), ("investor-agent", 3000)):
            mkrepo_dir(home / "work" / name)
            os.utime(home / "work" / name / ".git", (stamp, stamp))
        config = replace(CONFIG, repo_roots=(str(home / "work"),), home=home)
        self.app = build(self.runner, self.clock, config=config, speech=self.local)
        self.app.transcribe(WEBM, "audio/webm")
        self.assertTrue(self.local.transcribed[-1][2].endswith("Repositories: investor-agent, sebi-rag."))


class HttpSpeechTests(HttpCase):
    """The audio route and the dictation route over HTTP, with the real LocalSpeech against FakeSpeech and the real
    DeepgramVoice against FakeDeepgram."""
    def make_app(self):
        self.speech_fake = FakeSpeech()
        self.addCleanup(self.speech_fake.close)
        self.deepgram_fake = FakeDeepgram()
        self.addCleanup(self.deepgram_fake.close)
        speech = g.LocalSpeech(self.speech_fake.url, SPEECH_TOKEN, first_byte_timeout=2, timeout=5)
        voice = g.DeepgramVoice(KEY, "flux-cole-en", self.deepgram_fake.url, timeout=5)
        return build(self.runner, self.clock, tailscale=self.tailscale, voice=voice, speech=speech)

    def setUp(self):
        super().setUp()
        self.log_in()

    def event(self):
        self.post("/api/prompt", {"session": SCRATCH, "text": "hi"})
        sent = self.runner.named("agent", "prompt")[-1]["argv"][-1]
        nonce = re.search(r"SPOKEN\[([0-9a-f]+)\]", sent).group(1)
        self.runner.sessions[SCRATCH].visible = f"⏺ SPOKEN[{nonce}]: All done.\n"
        listing = json.loads(self.request("GET", "/api/sessions")[2])
        self.clock.t += g.PROMPT_REPEAT_SECONDS + 1
        return listing["events"][-1]

    def test_chatterbox_by_default_and_deepgram_on_request(self):
        event = self.event()
        status, headers, body, _ = self.request("GET", f"/api/audio?id={event['id']}")
        self.assertEqual((status, headers["Content-Type"], headers["Transfer-Encoding"]), (200, "audio/mpeg", "chunked"))
        self.assertEqual(body, FAKE_MP3)
        self.assertEqual(json.loads(self.speech_fake.of("/tts")[-1]["body"]), {"text": "All done."})
        self.assertEqual(self.deepgram_fake.requests, [])
        self.assertEqual(self.request("GET", f"/api/audio?id={event['id']}&engine=deepgram")[0], 200)
        self.assertEqual(len(self.deepgram_fake.requests), 1)
        self.assertEqual(self.request("GET", f"/api/audio?id={event['id']}&engine=phone")[0], 400)

    def test_the_view_says_chatterbox(self):
        listing = json.loads(self.request("GET", "/api/sessions")[2])
        self.assertEqual((listing["voice"], listing["voices"]), ("chatterbox", ["chatterbox", "deepgram"]))

    def test_chatterbox_down_is_a_quick_502_then_deepgram_still_plays(self):
        event = self.event()
        self.speech_fake.close()
        started = time.monotonic()
        status, _, body, _ = self.request("GET", f"/api/audio?id={event['id']}&engine=chatterbox")
        self.assertEqual(status, 502)
        self.assertLess(time.monotonic() - started, 2.5)
        self.assertNotIn(SPEECH_TOKEN.encode(), body)
        self.assertEqual(self.request("GET", f"/api/audio?id={event['id']}&engine=chatterbox")[0], 502)
        self.assertEqual(self.request("GET", f"/api/audio?id={event['id']}&engine=deepgram")[0], 200)

    def test_chatterbox_too_slow_to_start_is_a_502(self):
        event = self.event()
        self.speech_fake.delay = 3
        started = time.monotonic()
        self.assertEqual(self.request("GET", f"/api/audio?id={event['id']}&engine=chatterbox")[0], 502)
        self.assertLess(time.monotonic() - started, 2.8)

    def transcribe(self, data=WEBM, kind="audio/webm;codecs=opus", **overrides):
        return self.post("/api/transcribe", None, raw=data, **{"Content_Type": kind, **overrides})

    def test_transcribe_over_http(self):
        status, headers, body, _ = self.transcribe()
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body), {"text": "new worktree in investor-agent"})
        request = self.speech_fake.of("/stt")[-1]
        self.assertEqual((request["body"], request["headers"]["Content-Type"]), (WEBM, "audio/webm"))
        self.assertTrue(request["headers"]["X-Vocabulary"].startswith("Branches: dev"))
        self.assertIn("Content-Security-Policy", headers)
        self.assertNotIn("investor-agent", self.log.getvalue())
        self.assertIn("POST /api/transcribe 200", self.log.getvalue())

    def test_transcribe_guards(self):
        self.assertEqual(self.transcribe(Origin="https://evil.com")[0], 403)
        self.assertEqual(self.transcribe(Origin=None)[0], 403)
        self.assertEqual(self.transcribe(Host="evil.com")[0], 403)
        self.assertEqual(self.transcribe(Tailscale_User_Login=None)[0], 403)
        self.assertEqual(self.transcribe(kind="application/json")[0], 415)
        self.assertEqual(self.transcribe(kind="text/plain")[0], 415)
        self.assertEqual(self.transcribe(data=b"")[0], 400)
        with unittest.mock.patch.object(g, "MAX_TRANSCRIBE_BYTES", 1000):  # Small, so the refused body fits a buffer.
            self.assertEqual(self.transcribe(data=b"\x00" * 1001)[0], 413)
        self.assertEqual(self.transcribe(Transfer_Encoding="chunked")[0], 411)
        self.token = None
        self.assertEqual(self.transcribe()[0], 401)
        self.assertEqual(self.speech_fake.of("/stt"), [])

    def test_a_service_failure_is_a_502_without_its_words(self):
        self.speech_fake.stt_status = 500
        status, _, body, _ = self.transcribe()
        self.assertEqual(status, 502)
        self.assertNotIn(b"private", body)
        self.assertIn("Local Whisper", json.loads(body)["error"])


class LocalSpeechPageTests(unittest.TestCase):
    page, section = VoicePageTests.page, VoicePageTests.section

    def test_the_settings_area_and_its_switches(self):
        html = self.page("index.html")
        for element in ("settings", "engine-chatterbox", "engine-deepgram", "engine-phone", "dictation-chrome",
                        "dictation-whisper", "local-note", "voice-note"):
            self.assertIn(f'id="{element}"', html)
        self.assertIn('<details id="settings"', html)
        source = self.page()
        self.assertIn('"voice-engine"', source)
        self.assertIn('"dictation"', source)

    def test_the_speaker_falls_back_in_order_once_per_engine(self):
        chain = self.section("function engineChain", "function fallbackNote")
        self.assertIn('["chatterbox", "deepgram"]', chain)
        play = self.section("async function playAudio", "function finished")
        self.assertIn("for (const engine of", play)
        self.assertEqual(play.count("speakWords(words)"), 1)
        self.assertLess(play.index("for (const engine of"), play.index("speakWords(words)"))
        note = self.section("function fallbackNote", "async function playAudio")
        self.assertIn("unavailable, used", note)

    def test_the_fallback_note_is_text_only(self):
        body = self.section("function showVoiceNote", "// ---- Speech only ever fills the text box")
        self.assertIn('$("voice-note").textContent', body)

    def test_whisper_records_and_posts_and_only_fills_the_box(self):
        body = self.section("// ---- Local Whisper", "function onMic")
        for words in ("getUserMedia", "MediaRecorder", "RECORD_LIMIT_MS = 30000", '"/api/transcribe"',
                      '$("text").value', "round !== dictationRound"):
            self.assertIn(words, body)
        for words in ("startListening", "Recognition", "onSend", "/api/prompt", ".click(", "/api/worktree",
                      "innerHTML"):
            self.assertNotIn(words, body)

    def test_whisper_never_falls_back_to_chrome_speech(self):
        body = self.section("function onMic", "// ---- Announcements.")
        whisper = body[body.index('if (settings.dictation === "whisper")'):]
        whisper = whisper[:whisper.index("\n  }\n")]
        self.assertIn("return", whisper)
        self.assertNotIn("startListening", whisper)
        self.assertNotIn("Recognition", whisper)

    def test_leaving_or_sending_discards_a_recording(self):
        body = self.section("function stopListening", "function startListening")
        self.assertIn("discardRecording()", body)


# ---------------------------------------------------------------- Android app: refusal reasons (S1)

REASONS = frozenset({
    "bad_text", "not_listed", "stopped", "no_orchestrator", "pane_changed", "not_settled", "not_blocked",
    "not_working", "draft_present", "screen_changed", "repeat_window", "action_running", "herdr_refused",
    "herdr_timeout_maybe_sent", "herdr_failed", "already_running", "voice_command", "voice_noise", "speech_off",
    "bad_audio_type", "audio_empty", "audio_too_large", "transcribe_busy", "transcribe_rate", "speech_failed",
    "restart_refused", "restart_failed"})


class ReasonCase(GatewayCase):
    def reason(self, code, fn, *args, **extra_fields):
        """The refusal's status and machine-readable reason, plus any extra fields asked for."""
        with self.assertRaises(g.RequestRejected) as caught:
            fn(*args)
        rejected = caught.exception
        self.assertEqual(rejected.status, code, rejected.message)
        reason = rejected.extra.get("reason")
        self.assertIn(reason, REASONS, rejected.message)
        for name, value in extra_fields.items():
            self.assertEqual(rejected.extra.get(name), value, name)
        return reason, rejected


class PromptReasonTests(ReasonCase):
    def send(self, text="hello", session=SCRATCH, **extra):
        return self.app.prompt({"session": session, "text": text, **extra})

    def test_bad_text(self):
        for text in (None, 3, "", "   ", "x" * (g.MAX_PROMPT_CHARS + 1)):
            self.assertEqual(self.reason(400, self.send, text)[0], "bad_text", text)

    def test_not_listed(self):
        for name in ("wt-absent", None, "phone"):
            self.assertEqual(self.reason(404, self.send, "hi", name)[0], "not_listed")

    def test_stopped(self):
        self.runner.sessions[SCRATCH].running = False
        self.assertEqual(self.reason(409, self.send)[0], "stopped")

    def test_no_orchestrator(self):
        self.runner.sessions[SCRATCH].agents = []
        self.assertEqual(self.reason(409, self.send)[0], "no_orchestrator")
        self.runner.sessions[SCRATCH].agents = [agent_row(), agent_row("w1:p2", "w1:t1")]
        self.assertEqual(self.reason(409, self.send)[0], "no_orchestrator")

    def test_pane_changed(self):
        self.runner.sessions[SCRATCH].get_row = agent_row(terminal="term_other")
        self.assertEqual(self.reason(409, self.send)[0], "pane_changed")

    def test_not_settled_carries_the_status(self):
        for status in ("working", "blocked", "unknown"):
            self.set_status(status)
            reason, rejected = self.reason(409, self.send, status=status)
            self.assertEqual(reason, "not_settled")
            self.assertEqual(rejected.message, f"The orchestrator is {status}, so I did not send text.")

    def test_draft_present(self):
        self.runner.sessions[SCRATCH].ansi = TYPED_BOX
        self.assertEqual(self.reason(409, self.send)[0], "draft_present")

    def test_repeat_window(self):
        self.send()
        self.clock.t += 0.1
        self.assertEqual(self.reason(409, self.send)[0], "repeat_window")

    def test_action_running(self):
        entered, release = threading.Event(), threading.Event()

        def hold():
            entered.set()
            release.wait(5)
        self.runner.on_prompt = hold
        first = threading.Thread(target=self.send)
        first.start()
        try:
            self.assertTrue(entered.wait(5))
            self.assertEqual(self.reason(409, self.send)[0], "action_running")
        finally:
            release.set()
            first.join(5)

    def test_herdr_refused(self):
        self.runner.prompt_result = err("agent_blocked", "agent is blocked")
        self.assertEqual(self.reason(409, self.send)[0], "herdr_refused")

    def test_herdr_timeout_maybe_sent(self):
        self.runner.fail[(SCRATCH, ("agent", "prompt"))] = subprocess.TimeoutExpired("herdr", 15)
        self.assertEqual(self.reason(502, self.send)[0], "herdr_timeout_maybe_sent")

    def test_herdr_failed(self):
        self.runner.prompt_result = err("something", "failed")
        self.assertEqual(self.reason(502, self.send)[0], "herdr_failed")
        self.runner.prompt_result = Res(0, '{"id":"cli:agent:prompt","result":{"type":"ok"}}')
        self.runner.fail[(SCRATCH, ("tab", "list"))] = err("server_not_running", "no server")
        self.clock.t += 100
        self.assertEqual(self.reason(502, self.send)[0], "herdr_failed")

    def test_reasons_never_carry_the_text(self):
        self.runner.prompt_result = err("something", "failed")
        _, rejected = self.reason(502, self.send, "secret words")
        self.assertNotIn("secret", json.dumps(rejected.extra))


class KeysStopReasonTests(ReasonCase):
    def press(self, key="1", token=None):
        token = g.screen_token("visible screen") if token is None else token
        return self.app.keys({"session": SCRATCH, "key": key, "screen_token": token})

    def stop(self, session=SCRATCH):
        return self.app.stop({"session": session})

    def test_bad_key(self):
        self.set_status("blocked")
        for key in ("3", None, "ctrl+c"):
            self.assertEqual(self.reason(400, self.press, key)[0], "bad_text")

    def test_not_blocked_carries_the_status(self):
        for status in ("idle", "working"):
            self.set_status(status)
            self.assertEqual(self.reason(409, self.press, status=status)[0], "not_blocked")

    def test_screen_changed_keeps_its_fields(self):
        self.set_status("blocked")
        reason, rejected = self.reason(409, self.press, "1", "old", output="visible screen",
                                       screen_token=g.screen_token("visible screen"))
        self.assertEqual(reason, "screen_changed")

    def test_key_repeat_window_and_foreign_session(self):
        self.set_status("blocked")
        self.press("enter")
        self.clock.t += 0.1
        self.assertEqual(self.reason(409, self.press, "enter")[0], "repeat_window")
        self.runner.sessions["phone"] = FakeSession(agents=[agent_row(status="blocked")])
        self.assertEqual(self.reason(404, self.app.keys, {"session": "phone", "key": "1"})[0], "not_listed")

    def test_key_stopped_and_herdr_refused(self):
        self.set_status("blocked")
        self.runner.fail[(SCRATCH, ("agent", "send-keys"))] = err("agent_not_found", "gone")
        self.assertEqual(self.reason(409, self.press)[0], "herdr_refused")
        self.runner.sessions[SCRATCH].running = False
        self.assertEqual(self.reason(409, self.press)[0], "stopped")

    def test_not_working_carries_the_status(self):
        for status in ("idle", "blocked"):
            self.set_status(status)
            reason, rejected = self.reason(409, self.stop, status=status)
            self.assertEqual(reason, "not_working")
            self.assertEqual(rejected.message, f"The orchestrator is {status}, so I did not send stop.")

    def test_stop_other_paths(self):
        self.set_status("working")
        self.stop()
        self.clock.t += 0.1
        self.assertEqual(self.reason(409, self.stop)[0], "repeat_window")
        self.assertEqual(self.reason(404, self.stop, "default")[0], "not_listed")
        self.clock.t += 100
        self.runner.fail[(SCRATCH, ("agent", "send-keys"))] = err("weird", "x")
        self.assertEqual(self.reason(502, self.stop)[0], "herdr_failed")
        self.clock.t += 100
        self.runner.sessions[SCRATCH].agents = []
        self.assertEqual(self.reason(409, self.stop)[0], "no_orchestrator")
        self.runner.sessions[SCRATCH].running = False
        self.assertEqual(self.reason(409, self.stop)[0], "stopped")


class RestartReasonTests(ReasonCase):
    def setUp(self):
        super().setUp()
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.sessions_dir = Path(self.tmp.name) / "sessions" / SCRATCH
        self.sessions_dir.mkdir(parents=True)
        self.worktree = str(Path(self.tmp.name) / "repo")
        os.mkdir(self.worktree)
        (self.sessions_dir / "session.json").write_text(json.dumps(
            {"version": 3, "workspaces": [{"identity_cwd": self.worktree, "tabs": []}]}))
        self.runner.session_dirs[SCRATCH] = str(self.sessions_dir)
        self.runner.sessions[SCRATCH].running = False
        self.runner.wt_names[self.worktree] = SCRATCH

    def restart(self, session=SCRATCH, app=None):
        return (app or self.app).restart({"session": session})

    def test_already_running(self):
        self.runner.sessions[SCRATCH].running = True
        self.assertEqual(self.reason(409, self.restart)[0], "already_running")

    def test_not_listed(self):
        self.assertEqual(self.reason(404, self.restart, "wt-absent")[0], "not_listed")

    def test_refusals_before_wt_up(self):
        self.runner.wt_names[self.worktree] = "wt-something-else"
        reason, rejected = self.reason(409, self.restart)
        self.assertEqual(reason, "restart_refused")
        self.assertIn("wt-something-else", rejected.message)
        self.assertNotIn(self.worktree, json.dumps(rejected.extra))
        (self.sessions_dir / "session.json").unlink()
        self.assertEqual(self.reason(409, self.restart)[0], "restart_refused")
        (self.sessions_dir / "session.json").write_text(json.dumps(
            {"version": 3, "workspaces": [{"identity_cwd": self.worktree, "tabs": []}]}))
        app = build(self.runner, self.clock, replace(CONFIG, wt=None))
        self.assertEqual(self.reason(409, self.restart, SCRATCH, app)[0], "restart_refused")

    def test_wt_up_failure(self):
        self.runner.wt_up_result = Res(1, "", "wt: session did not come up\n")
        reason, rejected = self.reason(502, self.restart)
        self.assertEqual(reason, "restart_failed")
        self.assertNotIn("did not come up", json.dumps(rejected.extra))

    def test_repeat_window(self):
        self.restart()
        self.clock.t += 0.1
        self.assertEqual(self.reason(409, self.restart)[0], "repeat_window")


class TranscribeReasonTests(ReasonCase):
    def setUp(self):
        super().setUp()
        self.local = FakeLocal()
        self.addCleanup(self.local.release)
        self.app = build(self.runner, self.clock, speech=self.local)

    def test_speech_off(self):
        app = build(self.runner, self.clock, speech_reason="No speech token is stored.")
        self.assertEqual(self.reason(404, app.transcribe, WEBM, "audio/webm")[0], "speech_off")

    def test_bad_audio_type_empty_and_too_large(self):
        self.assertEqual(self.reason(415, self.app.transcribe, WEBM, "text/plain")[0], "bad_audio_type")
        self.assertEqual(self.reason(415, self.app.transcribe, WEBM, None)[0], "bad_audio_type")
        self.assertEqual(self.reason(400, self.app.transcribe, b"", "audio/webm")[0], "audio_empty")
        big = b"\x00" * (g.MAX_TRANSCRIBE_BYTES + 1)
        self.assertEqual(self.reason(413, self.app.transcribe, big, "audio/webm")[0], "audio_too_large")

    def test_busy_and_rate(self):
        self.local.transcribe_gate = threading.Event()
        with g.ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(self.app.transcribe, WEBM, "audio/webm")
            self.assertTrue(wait_for(lambda: len(self.local.transcribed) == 1))
            reason, rejected = self.reason(429, self.app.transcribe, WEBM, "audio/webm")
            self.assertEqual((reason, rejected.retry_after), ("transcribe_busy", 2))
            self.local.transcribe_gate.set()
            first.result(5)
        for _ in range(g.TRANSCRIBE_PER_MINUTE - 1):
            self.app.transcribe(WEBM, "audio/webm")
        reason, rejected = self.reason(429, self.app.transcribe, WEBM, "audio/webm")
        self.assertEqual(reason, "transcribe_rate")
        self.assertGreater(rejected.retry_after, 0)

    def test_speech_failed_without_its_words(self):
        self.local.transcribe_fail = g.VoiceError(500, "the model said something private")
        reason, rejected = self.reason(502, self.app.transcribe, WEBM, "audio/webm")
        self.assertEqual(reason, "speech_failed")
        self.assertNotIn("private", json.dumps(rejected.extra))


class HttpReasonTests(HttpCase):
    def make_app(self):
        self.local = FakeLocal()
        self.addCleanup(self.local.release)
        return build(self.runner, self.clock, tailscale=self.tailscale, speech=self.local)

    def setUp(self):
        super().setUp()
        self.log_in()

    def test_prompt_refusal_body(self):
        self.runner.sessions[SCRATCH].agents[0]["agent_status"] = "working"
        status, _, body, _ = self.post("/api/prompt", {"session": SCRATCH, "text": "hi"})
        self.assertEqual(status, 409)
        self.assertEqual(json.loads(body), {"error": "The orchestrator is working, so I did not send text.",
                                            "reason": "not_settled", "status": "working"})

    def test_keys_stop_restart_bodies(self):
        self.assertEqual(json.loads(self.post("/api/keys", {"session": SCRATCH, "key": "x"})[2])["reason"], "bad_text")
        self.assertEqual(json.loads(self.post("/api/stop", {"session": SCRATCH})[2])["reason"], "not_working")
        self.assertEqual(json.loads(self.post("/api/restart", {"session": SCRATCH})[2])["reason"], "already_running")

    def test_transcribe_bodies(self):
        def transcribe(data=WEBM, kind="audio/webm"):
            return self.post("/api/transcribe", None, raw=data, Content_Type=kind)
        status, _, body, _ = transcribe(kind="text/plain")
        self.assertEqual((status, json.loads(body)["reason"]), (415, "bad_audio_type"))
        status, _, body, _ = transcribe(data=b"")
        self.assertEqual((status, json.loads(body)["reason"]), (400, "audio_empty"))
        with unittest.mock.patch.object(g, "MAX_TRANSCRIBE_BYTES", 1000):
            status, _, body, _ = transcribe(data=b"\x00" * 1001)
        self.assertEqual((status, json.loads(body)), (413, {"error": "body too large", "reason": "audio_too_large"}))

    def test_guards_keep_their_bodies(self):
        self.assertEqual(json.loads(self.post("/api/prompt", {"session": SCRATCH, "text": "hi"},
                                              Origin="https://evil.com")[2]), {"error": "origin not allowed"})
        self.assertEqual(json.loads(self.post("/api/prompt", {"session": SCRATCH, "text": "hi"},
                                              Content_Type="text/plain")[2]),
                         {"error": "content type must be application/json"})
        with unittest.mock.patch.object(g, "MAX_BODY_BYTES", 10):
            self.assertEqual(json.loads(self.post("/api/prompt", {"session": SCRATCH, "text": "hello there"})[2]),
                             {"error": "body too large"})
        self.token = None
        self.assertEqual(json.loads(self.post("/api/prompt", {"session": SCRATCH, "text": "hi"})[2]),
                         {"error": "log in first"})

    def test_voice_noise_over_http_is_not_logged(self):
        status, _, body, _ = self.post("/api/prompt", {"session": SCRATCH, "text": "Thanks for watching!",
                                                       "voice": True})
        self.assertEqual((status, json.loads(body)["reason"]), (422, "voice_noise"))
        self.assertNotIn("watching", self.log.getvalue())
        self.assertNotIn(b"watching", body)


# ---------------------------------------------------------------- Android app: voice prompts (S2)

NOISE_PHRASES = ("thank you", "thanks for watching", "thank you for watching", "you", "bye", "okay", "so",
                 "please subscribe", "subtitles by the amara org community", "i m sorry")
NOISE_AS_HEARD = ("Thank you.", "THANK YOU!", "  thanks for watching  ", "Thank you for watching!", "You", "Bye.",
                  "Okay.", "So...", "Please subscribe.", "Subtitles by the Amara.org community", "I'm sorry.",
                  "I’m sorry", "thank-you", "Thank you . ", "okay?!")
NEAR_MISSES = ("thank you, now run the tests", "okay run the tests", "so what is next", "you can merge it now",
               "bye bye", "thank you very much for the fix", "sorry, run it again")


class VoiceNoiseTests(unittest.TestCase):
    def test_the_fixed_list(self):
        self.assertEqual(tuple(g.VOICE_NOISE_PHRASES), NOISE_PHRASES)

    def test_each_phrase_and_how_whisper_writes_it(self):
        for text in NOISE_PHRASES + NOISE_AS_HEARD:
            self.assertTrue(g.is_voice_noise(text), text)

    def test_near_misses_are_not_noise(self):
        for text in NEAR_MISSES + ("", "thank", "yous", "okay okay"):
            self.assertFalse(g.is_voice_noise(text), text)


class VoicePromptTests(ReasonCase):
    def send(self, text="hello", voice=None, **extra):
        payload = {"session": SCRATCH, "text": text, **extra}
        if voice is not None:
            payload["voice"] = voice
        return self.app.prompt(payload)

    def prompts(self):
        return [c["argv"][-1] for c in self.runner.named("agent", "prompt")]

    def nonce(self):
        return re.search(r"SPOKEN\[([0-9a-f]+)\]", self.prompts()[-1]).group(1)

    def herdr_calls(self):
        return [c for c in self.runner.calls if c["argv"][0] == "herdr"]

    def test_typed_prompts_send_todays_exact_text(self):
        for voice in (None, False):
            self.clock.t += 100
            self.assertEqual(self.send("what is 2", voice), {"ok": True, "draft": "unknown"})
            self.assertEqual(self.prompts()[-1], "what is 2 " + TODAYS_INSTRUCTION.format(nonce=self.nonce()))

    def test_voice_prompts_carry_the_voice_instruction(self):
        self.assertEqual(self.send("what is 2", True), {"ok": True, "draft": "unknown"})
        sent = self.prompts()[-1]
        self.assertEqual(sent, "what is 2 " + voice_instruction(self.nonce()))

    def test_invalid_voice_values(self):
        for value in (None, 1, 0, "true", "yes", [], {}, [True]):
            payload = {"session": SCRATCH, "text": "hello", "voice": value}
            self.assertEqual(self.reason(400, self.app.prompt, payload)[0], "bad_text", value)
        self.assertEqual(self.herdr_calls(), [])

    def test_commands_are_refused_by_voice_without_touching_herdr(self):
        for text in ("/compact", "/help me", "!ls", "! ls", "  /clear", "\x00/x"):
            self.assertEqual(self.reason(400, self.send, text, True)[0], "voice_command", text)
        self.assertEqual(self.herdr_calls(), [])
        self.send("/compact", False)
        self.assertEqual(self.prompts(), ["/compact"])

    def test_noise_is_refused_by_voice_without_touching_herdr(self):
        for text in NOISE_PHRASES + NOISE_AS_HEARD:
            self.assertEqual(self.reason(422, self.send, text, True)[0], "voice_noise", text)
        self.assertEqual(self.herdr_calls(), [])

    def test_noise_is_refused_even_for_a_stopped_or_unlisted_session(self):
        self.runner.sessions[SCRATCH].running = False
        self.assertEqual(self.reason(422, self.send, "Thank you.", True)[0], "voice_noise")
        self.assertEqual(self.reason(422, self.app.prompt, {"session": "wt-absent", "text": "Bye.", "voice": True})[0],
                         "voice_noise")
        self.assertEqual(self.herdr_calls(), [])

    def test_near_misses_are_sent_by_voice(self):
        for text in NEAR_MISSES:
            self.clock.t += 100
            self.send(text, True)
        self.assertEqual([p.split(" (Sent from")[0] for p in self.prompts()], list(NEAR_MISSES))

    def test_typed_noise_is_sent_as_today(self):
        self.send("Thank you.", False)
        self.clock.t += 100
        self.send("okay")
        self.assertEqual(len(self.prompts()), 2)

    def test_the_text_rules_still_come_first(self):
        self.assertEqual(self.reason(400, self.send, "", True)[0], "bad_text")
        self.assertEqual(self.reason(400, self.send, None, True)[0], "bad_text")

    def test_the_other_rules_still_hold_for_voice(self):
        self.set_status("working")
        self.assertEqual(self.reason(409, self.send, "run the tests", True)[0], "not_settled")
        self.assertEqual(self.prompts(), [])

    def test_a_tap_confirmation_carries_the_tap_instruction(self):
        self.assertEqual(self.send("yes, go ahead", True, confirm="tap"), {"ok": True, "draft": "unknown"})
        self.assertEqual(self.prompts()[-1], "yes, go ahead " + tap_instruction(self.nonce()))
        self.assertTrue(self.app.announcer.pending[SCRATCH].voice)  # Its reply is read like any voice reply.
        self.clock.t += 100
        self.send("no, do not do that", True, confirm="tap")
        self.assertEqual(self.prompts()[-1], "no, do not do that " + tap_instruction(self.nonce()))

    def test_invalid_confirm_values(self):
        for value in (None, "", "TAP", "tap ", "voice", True, 1, [], {}, ["tap"]):
            for voice in (True, False):
                payload = {"session": SCRATCH, "text": "yes, go ahead", "voice": voice, "confirm": value}
                self.assertEqual(self.reason(400, self.app.prompt, payload)[0], "bad_text", value)
        self.assertEqual(self.herdr_calls(), [])

    def test_a_tap_mark_without_voice_is_ignored(self):
        for voice in (None, False):
            self.clock.t += 100
            self.send("yes", voice, confirm="tap")
            self.assertEqual(self.prompts()[-1], "yes " + TODAYS_INSTRUCTION.format(nonce=self.nonce()))
            self.assertFalse(self.app.announcer.pending[SCRATCH].voice)

    def test_a_tap_confirmation_keeps_the_voice_text_rules(self):
        for text, status, code in (("/compact", 400, "voice_command"), ("Thank you.", 422, "voice_noise")):
            payload = {"session": SCRATCH, "text": text, "voice": True, "confirm": "tap"}
            self.assertEqual(self.reason(status, self.app.prompt, payload)[0], code)
        self.assertEqual(self.herdr_calls(), [])


# ---------------------------------------------------------------- Android app: clarify by voice (S4)

TODAYS_INSTRUCTION = ("(Sent from my phone. End your reply with one final line of at most two plain sentences, written "
                      "to be read aloud, with no code, paths or markdown, in exactly this form: SPOKEN[{nonce}]: your "
                      "spoken summary)")
VOICE_CLAUSE = (
    "(Sent from my phone by voice, so the words may be misheard. If the request is unclear, ambiguous, risky, or looks "
    "like a speech-recognition mistake, do not act on it: change nothing and run nothing, and instead ask one short "
    "question, writing your spoken line as ASK: followed by the question, ending with a question mark. If the question "
    "is whether to do something destructive or hard to undo (deleting or overwriting files, branches or data, git push "
    "or force push, reset or rebase that drops work, dropping or migrating a database, deploying, or anything that "
    "spends money), write ASK!: instead of ASK:, even if the request itself was clear; such a step is confirmed by a "
    "tap on the phone, never by voice. A spoken reply to an ASK: question approves the proposed step if it is a plain "
    "yes, sure, go ahead or the like, unless the step is destructive or hard to undo: then it approves only if it is "
    "exactly one of: yes go ahead, yes do it, yes please proceed, yes thats right (ignoring case and punctuation), and "
    "a bare yes, yeah, sure or okay is not approval, so ask again with ASK!:. ")
TAP_CLAUSE = (
    "(Sent from my phone by a tap on the Yes or No button shown for your last ASK!: or ASK: question, so this reply "
    "is certain and was not misheard: it is the user's explicit approval or refusal of that step. Ask any new "
    "question with ASK: or ASK!: as before. ")
APPROVAL_PHRASES = ("yes go ahead", "yes do it", "yes please proceed", "yes thats right")
DESTRUCTIVE_WORDS = frozenset(("delete remove rm overwrite force push drop reset rebase wipe purge truncate deploy "
                               "publish pay purchase charge").split())


def voice_instruction(nonce):
    return VOICE_CLAUSE + TODAYS_INSTRUCTION.format(nonce=nonce).split("Sent from my phone. ", 1)[1]


def tap_instruction(nonce):
    return TAP_CLAUSE + TODAYS_INSTRUCTION.format(nonce=nonce).split("Sent from my phone. ", 1)[1]


class VoiceInstructionTests(unittest.TestCase):
    def test_typed_instruction_is_byte_identical_to_today(self):
        self.assertEqual(g.summary_instruction(NONCE), TODAYS_INSTRUCTION.format(nonce=NONCE))
        self.assertEqual(g.summary_instruction(NONCE, voice=False), TODAYS_INSTRUCTION.format(nonce=NONCE))
        self.assertEqual(g.with_summary_instruction("x", NONCE), "x " + TODAYS_INSTRUCTION.format(nonce=NONCE))

    def test_voice_instruction_is_the_approved_text(self):
        self.assertEqual(g.summary_instruction(NONCE, voice=True), voice_instruction(NONCE))
        self.assertEqual(g.with_summary_instruction("x", NONCE, voice=True), "x " + voice_instruction(NONCE))

    def test_voice_instruction_rules(self):
        text = g.summary_instruction(NONCE, voice=True)
        self.assertEqual(len(text.splitlines()), 1)
        for words in ("ASK:", "ASK!:", "question mark", "do not act", "misheard", "tap", "deleting", "force push",
                      "database", "money", "not approval", *g.VOICE_APPROVAL_PHRASES):
            self.assertIn(words, text)
        self.assertEqual(text.count(TAG), 1)
        self.assertTrue(text.endswith(f"{TAG} {g.SPOKEN_PLACEHOLDER})"))

    def test_plain_questions_take_a_plain_yes_and_destructive_ones_the_strict_phrases(self):
        text = g.summary_instruction(NONCE, voice=True)
        plain = text.index("plain yes, sure, go ahead")
        strict = text.index("destructive or hard to undo:")
        self.assertLess(plain, strict)
        self.assertLess(strict, text.index(", ".join(g.VOICE_APPROVAL_PHRASES)))
        self.assertIn("never by voice", text)  # ASK!: stays tap only.
        self.assertNotIn("A spoken reply approves a proposed step only if", text)

    def test_tap_instruction_is_the_approved_text(self):
        self.assertEqual(g.summary_instruction(NONCE, voice=True, tap=True), tap_instruction(NONCE))
        self.assertEqual(g.with_summary_instruction("yes, go ahead", NONCE, voice=True, tap=True),
                         "yes, go ahead " + tap_instruction(NONCE))

    def test_tap_instruction_rules(self):
        text = g.summary_instruction(NONCE, voice=True, tap=True)
        self.assertEqual(len(text.splitlines()), 1)
        for words in ("tap", "Yes or No button", "ASK!:", "certain", "not misheard", "explicit approval or refusal"):
            self.assertIn(words, text)
        for words in ("by voice", "may be misheard", "never by voice", "not approval", *g.VOICE_APPROVAL_PHRASES):
            self.assertNotIn(words, text)
        self.assertEqual(text.count(TAG), 1)
        self.assertTrue(text.endswith(f"{TAG} {g.SPOKEN_PLACEHOLDER})"))

    def test_tap_without_voice_is_todays_text(self):
        self.assertEqual(g.summary_instruction(NONCE, voice=False, tap=True), TODAYS_INSTRUCTION.format(nonce=NONCE))

    def test_approval_phrases(self):
        self.assertEqual(tuple(g.VOICE_APPROVAL_PHRASES), APPROVAL_PHRASES)
        for phrase in g.VOICE_APPROVAL_PHRASES:
            self.assertGreaterEqual(len(phrase.split()), 3, phrase)
            self.assertEqual(g.normalise_phrase(phrase), phrase)

    def test_normalise_phrase(self):
        cases = {"Yes, go ahead!": "yes go ahead", "Yes, that's right.": "yes thats right",
                 "Yes, that’s right": "yes thats right", "  YES   do   it ": "yes do it",
                 "yes... please-proceed?": "yes please proceed", "": "", "!!!": "", "rm -rf build/": "rm rf build"}
        for text, expected in cases.items():
            self.assertEqual(g.normalise_phrase(text), expected, text)

    def test_destructive_words(self):
        self.assertEqual(frozenset(g.VOICE_DESTRUCTIVE_WORDS), DESTRUCTIVE_WORDS)


class SplitAskTests(unittest.TestCase):
    def test_questions(self):
        self.assertEqual(g.split_ask("ASK: Which branch?"), (True, "Which branch?"))
        self.assertEqual(g.split_ask("ask:  which branch"), (True, "which branch?"))
        self.assertEqual(g.split_ask("  Ask : x?")[0], False)  # The colon must follow ASK.
        self.assertEqual(g.split_ask("ASK: Which test file?"), (True, "Which test file?"))
        self.assertEqual(g.split_ask("ASK: Pushing ahead with the plan?"), (True, "Pushing ahead with the plan?"))

    def test_no_line_when_the_question_is_empty(self):
        for text in ("ASK:", "ASK:   ", "ask: ?", "ASK!:", "ASK!:  ", "ASK!: ??"):
            ask, spoken = g.split_ask(text)
            self.assertFalse(ask, text)
            self.assertIsNone(spoken, text)

    def test_not_questions(self):
        for text in ("Asked about it", "Done. ASK: x?", "ASK !: x?", "It is done. Want me to run the tests?",
                     "TASK: done", "ASKED: x"):
            self.assertEqual(g.split_ask(text), (False, text), text)

    def test_confirmations(self):
        self.assertEqual(g.split_ask("ASK!: Force push to main?"), ("confirm", "Force push to main?"))
        self.assertEqual(g.split_ask("ask!: delete wt-old"), ("confirm", "delete wt-old?"))
        self.assertEqual(g.split_ask("  ASK!:   Shall I tidy up?"), ("confirm", "Shall I tidy up?"))

    def test_destructive_words_upgrade_a_question(self):
        self.assertEqual(g.split_ask("ASK: Shall I delete the build folder?"),
                         ("confirm", "Shall I delete the build folder?"))
        for word in sorted(DESTRUCTIVE_WORDS):
            self.assertEqual(g.split_ask(f"ASK: Should I {word.upper()} it?")[0], "confirm", word)
        for text in ("ASK: Which test file?", "ASK: Pushing ahead with the plan?", "ASK: Is the deployment fine?",
                     "ASK: Removed or kept?", "ASK: Is the payment page right?"):
            self.assertIs(g.split_ask(text)[0], True, text)

    def test_long_question_is_cut_and_still_a_question(self):
        ask, spoken = g.split_ask("ASK: " + "word " * 80 + "end?")
        self.assertIs(ask, True)
        self.assertLessEqual(len(spoken), g.MAX_SPOKEN_CHARS)
        self.assertTrue(spoken.endswith("?"))
        self.assertTrue(spoken.startswith("word word"))
        self.assertNotIn("  ", spoken)
        ask, spoken = g.split_ask("ASK!: " + "x" * 400)
        self.assertEqual(ask, "confirm")
        self.assertLessEqual(len(spoken), g.MAX_SPOKEN_CHARS)
        self.assertTrue(spoken.endswith("?"))


class VoiceEchoTests(unittest.TestCase):
    def test_echoed_voice_instruction_never_matches_at_any_width(self):
        echo_text = "fix it " + g.summary_instruction(NONCE, voice=True)
        starts = 0
        for width in range(20, 200):
            screen = wrapped(echo_text, width)
            starts += any(line.strip().startswith(TAG) for line in screen.splitlines())
            self.assertIsNone(g.find_spoken(screen + "\n\n✻ Thinking…\n", NONCE), width)
        self.assertGreater(starts, 0, "no width put the tag at the start of a line, so this tested nothing")

    def test_the_question_is_found_below_a_wrapped_echo(self):
        echo_text = "fix it " + g.summary_instruction(NONCE, voice=True)
        for width in range(20, 200):
            screen = wrapped(echo_text, width) + f"\n\n⏺ {TAG} ASK: Which bug do you mean?\n\n───\n❯ \n───\n"
            self.assertEqual(g.find_spoken(screen, NONCE), "ASK: Which bug do you mean?", width)


class VoiceAnnouncementTests(GatewayCase):
    def setUp(self):
        super().setUp()
        self.session = self.runner.sessions[SCRATCH]

    def send(self, text="fix it", voice=True):
        self.app.prompt({"session": SCRATCH, "text": text, "voice": voice})

    def nonce(self):
        sent = self.runner.named("agent", "prompt")[-1]["argv"][-1]
        return re.search(r"SPOKEN\[([0-9a-f]+)\]", sent).group(1)

    def events(self):
        return self.app.sessions_view()["events"]

    def reply(self, line):
        self.session.visible = f"> fix it\n\n⏺ SPOKEN[{self.nonce()}]: {line}\n\n───\n❯ \n───\n"

    def test_the_record_remembers_voice(self):
        self.send()
        self.assertTrue(self.app.announcer.pending[SCRATCH].voice)
        self.clock.t += 100
        self.send(voice=False)
        self.assertFalse(self.app.announcer.pending[SCRATCH].voice)
        record = g.PendingSend(SCRATCH, NONCE, 0, "w1:p1", "term_1")
        self.assertFalse(record.voice)

    def test_a_question(self):
        self.send()
        self.reply("ASK: Did you mean the main branch?")
        events = self.events()
        self.assertEqual(len(events), 1)
        self.assertEqual((events[0]["kind"], events[0]["text"], events[0]["ask"]),
                         ("summary", "Did you mean the main branch?", True))
        self.assertEqual(self.app.sessions_view()["waiting"], [])

    def test_a_tap_only_confirmation(self):
        self.send()
        self.reply("ASK!: Delete branch wt-old?")
        events = self.events()
        self.assertEqual([(e["kind"], e["text"], e["ask"]) for e in events],
                         [("summary", "Delete branch wt-old?", "confirm")])

    def test_a_destructive_question_is_upgraded(self):
        self.send()
        self.reply("ASK: Shall I delete the build folder?")
        self.assertEqual(self.events()[0]["ask"], "confirm")

    def test_a_plain_voice_summary_has_no_ask(self):
        self.send()
        self.reply("Fixed it. Want me to run the tests?")
        event = self.events()[0]
        self.assertEqual(event["text"], "Fixed it. Want me to run the tests?")
        self.assertNotIn("ask", event)
        self.assertEqual(set(event), {"id", "kind", "session", "worktree", "text"})

    def test_a_typed_prompt_is_never_altered(self):
        self.send("fix it", voice=False)
        self.reply("ASK: Did you mean the main branch?")
        event = self.events()[0]
        self.assertEqual(event["text"], "ASK: Did you mean the main branch?")
        self.assertNotIn("ask", event)
        self.assertEqual(set(event), {"id", "kind", "session", "worktree", "text"})

    def test_an_empty_question_falls_back_to_check_screen(self):
        self.send()
        self.reply("ASK:")
        self.assertEqual(self.events(), [])
        self.clock.t += g.SETTLE_SECONDS + 1
        events = self.events()
        self.assertEqual([e["kind"] for e in events], ["check-screen"])
        self.assertNotIn("ask", events[0])

    def test_the_echo_alone_is_not_a_summary(self):
        self.send()
        sent = self.runner.named("agent", "prompt")[-1]["argv"][-1]
        self.session.visible = wrapped(sent, 60) + "\n"
        self.assertEqual(self.events(), [])

    def test_a_tap_confirmation_is_announced_like_a_voice_prompt(self):
        self.app.prompt({"session": SCRATCH, "text": "yes, go ahead", "voice": True, "confirm": "tap"})
        self.reply("ASK: Shall I also run the tests?")
        self.assertEqual([(e["kind"], e["text"], e["ask"]) for e in self.events()],
                         [("summary", "Shall I also run the tests?", True)])

    def test_needs_you_has_no_ask(self):
        self.send()
        self.set_status("blocked")
        events = self.events()
        self.assertEqual([e["kind"] for e in events], ["needs-you"])
        self.assertNotIn("ask", events[0])


class HttpVoiceAskTests(HttpCase):
    def setUp(self):
        super().setUp()
        self.log_in()

    def test_the_event_carries_ask_over_http(self):
        self.assertEqual(self.post("/api/prompt", {"session": SCRATCH, "text": "fix it", "voice": True})[0], 200)
        sent = self.runner.named("agent", "prompt")[-1]["argv"][-1]
        self.assertIn("ASK!:", sent)
        nonce = re.search(r"SPOKEN\[([0-9a-f]+)\]", sent).group(1)
        self.runner.sessions[SCRATCH].visible = f"⏺ SPOKEN[{nonce}]: ASK: Which bug?\n"
        listing = json.loads(self.request("GET", "/api/sessions")[2])
        self.assertEqual([(e["text"], e["ask"]) for e in listing["events"]], [("Which bug?", True)])
        self.assertNotIn("fix it", self.log.getvalue())

    def test_typed_prompt_over_http_sends_todays_text(self):
        self.assertEqual(self.post("/api/prompt", {"session": SCRATCH, "text": "hi", "voice": False})[0], 200)
        sent = self.runner.named("agent", "prompt")[-1]["argv"][-1]
        nonce = re.search(r"SPOKEN\[([0-9a-f]+)\]", sent).group(1)
        self.assertEqual(sent, "hi " + TODAYS_INSTRUCTION.format(nonce=nonce))

    def test_tap_confirmation_over_http(self):
        payload = {"session": SCRATCH, "text": "yes, go ahead", "voice": True, "confirm": "tap"}
        self.assertEqual(self.post("/api/prompt", payload)[0], 200)
        sent = self.runner.named("agent", "prompt")[-1]["argv"][-1]
        nonce = re.search(r"SPOKEN\[([0-9a-f]+)\]", sent).group(1)
        self.assertEqual(sent, "yes, go ahead " + tap_instruction(nonce))
        status, _, body, _ = self.post("/api/prompt", {**payload, "confirm": "voice"})
        self.assertEqual((status, json.loads(body)["reason"]), (400, "bad_text"))
        self.assertNotIn("go ahead", self.log.getvalue())

    def test_invalid_voice_over_http(self):
        status, _, body, _ = self.post("/api/prompt", {"session": SCRATCH, "text": "hi", "voice": "yes"})
        self.assertEqual((status, json.loads(body)["reason"]), (400, "bad_text"))
        self.assertEqual(self.runner.named("agent", "prompt"), [])


if __name__ == "__main__":
    unittest.main()
