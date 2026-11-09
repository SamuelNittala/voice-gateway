"""Writes the :core test fixtures from the gateway's REAL views (task A3/A4). Kept so they can be made again.

Run from anywhere:  python3 android/tools/make_fixtures.py
Output:            android/core/src/test/resources/fixtures/*.json (overwritten)

Each fixture is one HTTP answer exactly as gateway.py's handler would send it: {"status", "headers", "body"}, where
`headers` keeps only Retry-After and Set-Cookie when present and `body` is the parsed JSON. Every answer comes from
the real GatewayHandler route code (do_GET / do_POST / _rejected / the request guards) driven in-process: no server is
started, no socket or port is opened (outbound connections are blocked below), Herdr, wt, ps and tailscale are the
fake runner and FakeTailscale from test_gateway.py, speech is its FakeLocal, and the data dir is a temporary folder,
so data/ is never read or written. Random ids, nonces and cookie tokens are made deterministic so a rerun gives the
same files. Only /api/audio is left out (task A9).
"""
import http.client
import io
import itertools
import json
import socket
import subprocess
import sys
import tempfile
import threading
import types
import unittest.mock
from dataclasses import replace
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "android" / "core" / "src" / "test" / "resources" / "fixtures"
sys.path.insert(0, str(REPO))


def _no_network(*args, **kwargs):
    raise AssertionError("make_fixtures must not open any connection")


socket.create_connection = _no_network  # Before importing anything that could try.
socket.socket.connect = _no_network

import gateway as g  # noqa: E402
import test_gateway as t  # noqa: E402

HOST = "mac.tail1234.ts.net"  # In test_gateway.ALLOWED; Tailscale Serve sends no port.
ORIGIN = f"https://{HOST}"
SCRATCH = t.SCRATCH

_counter = itertools.count(1)
g.secrets.token_hex = lambda n=16: format(next(_counter), "x").rjust(2 * n, "0")[-2 * n:]
g.secrets.token_urlsafe = lambda n=32: "fixture-cookie-" + format(next(_counter), "04d")


class Handler(g.GatewayHandler):
    """The real handler class with no socket: the request comes from these fields, the answer is captured in _send."""
    def __init__(self, server, method, path, headers, body=b""):  # noqa: deliberately skips the socket setup
        self.server, self.command, self.path = server, method, path
        self.headers = http.client.HTTPMessage()
        for name, value in headers.items():
            self.headers[name] = value
        self.rfile = io.BytesIO(body)
        self.client_address = ("127.0.0.1", 50000)  # Loopback, as behind Tailscale Serve.
        self.answer = None

    def _send(self, status, body, content_type, cache="no-store", headers=None):
        self.answer = (status, body, headers or {})


class World:
    """One gateway with the fake runner, logged in through the real /api/login route."""
    def __init__(self, speech=None, speech_reason=None, config=None, interpreter=None, home=None):
        self.tmp = tempfile.TemporaryDirectory()
        self.runner, self.clock = t.FakeRunner(), t.FakeClock()
        self.runner.home = home
        config = replace(config or t.CONFIG, data=Path(self.tmp.name) / "data")
        self.app = t.build(self.runner, self.clock, config=config, speech=speech, speech_reason=speech_reason,
                           interpreter=interpreter)
        self.server = types.SimpleNamespace(config=self.app.config, app=self.app, log_stream=io.StringIO())
        self.session = self.runner.sessions[SCRATCH]
        self.cookie = None
        self.login_answer = self.post("/api/login", {"passcode": t.PASSCODE})
        set_cookie = self.login_answer[2]["Set-Cookie"]
        self.cookie = set_cookie.split(";", 1)[0]

    def request(self, method, path, body=b"", **headers):
        base = {"Host": HOST, "Tailscale-User-Login": t.LOGIN}
        if self.cookie:
            base["Cookie"] = self.cookie
        if method == "POST":
            base.update({"Origin": ORIGIN, "Content-Length": str(len(body))})
        base.update({k.replace("_", "-"): v for k, v in headers.items()})
        handler = Handler(self.server, method, path, {k: v for k, v in base.items() if v is not None}, body)
        getattr(handler, f"do_{method}")()
        return handler.answer

    def get(self, path, **headers):
        return self.request("GET", path, **headers)

    def post(self, path, payload, **headers):
        return self.request("POST", path, json.dumps(payload).encode(), **{"Content_Type": "application/json",
                                                                          **headers})

    def prompt(self, text="what is 2", voice=None, session=SCRATCH, confirm=None):
        payload = {"session": session, "text": text}
        if voice is not None:
            payload["voice"] = voice
        if confirm is not None:
            payload["confirm"] = confirm
        return self.post("/api/prompt", payload)

    def nonce(self):
        sent = self.runner.named("agent", "prompt")[-1]["argv"][-1]
        return g.re.search(r"SPOKEN\[([0-9a-f]+)\]", sent).group(1)

    def reply(self, line, echo="what is 2"):
        self.session.visible = f"> {echo}\n\n⏺ SPOKEN[{self.nonce()}]: {line}\n\n───\n❯ \n───\n"

    def progress(self, lines, echo="fix it"):
        """The agent is still working and has written these progress lines below the echo."""
        self.session.agents[0]["agent_status"] = "working"
        written = "\n\n".join(f"⏺ PROGRESS[{self.nonce()}]: {line}" for line in lines)
        self.session.visible = f"> {echo}\n\n{written}\n\n✻ Working…\n"

    def view(self):
        return self.get(f"/api/session?name={SCRATCH}")

    def close(self):
        assert self.runner.violations == [], self.runner.violations
        self.tmp.cleanup()


def write(name, answer):
    status, body, headers = answer
    kept = {k: v for k, v in headers.items() if k in ("Retry-After", "Set-Cookie")}
    fixture = {"status": status, "headers": kept, "body": json.loads(body)}
    (OUT / f"{name}.json").write_text(json.dumps(fixture, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return fixture


def expect(answer, status, reason=None):
    body = json.loads(answer[1])
    assert answer[0] == status, (answer[0], body)
    assert body.get("reason") == reason, body
    return answer


# ---------------------------------------------------------------- views and successful answers

def views():
    w = World()
    write("login_ok", w.login_answer)
    write("session_idle", w.view())
    w.runner.sessions["wt-old"] = t.FakeSession(running=False)
    w.runner.sessions["wt-api"] = t.FakeSession(agents=[t.agent_row(status="working", name="builder")])
    w.runner.ps_output = "/usr/local/bin/herdr session attach wt-api\n"
    w.prompt("what is 2")  # Leaves a summary event on the list as well.
    w.reply("It is two.")
    write("sessions", w.get("/api/sessions"))
    w.close()

    w = World()
    w.session.agents[0]["agent_status"] = "working"
    w.session.output = "Reading files...\nRunning the tests"
    write("session_working", w.view())
    w.session.agents[0]["agent_status"] = "blocked"
    w.session.visible = "Do you want to make this edit?\n❯ 1. Yes\n  2. No\n"
    write("session_blocked", w.view())
    w.session.running = False
    write("session_stopped", w.view())
    w.close()

    w = World()
    write("prompt_ok", expect(w.prompt("what is 2", voice=False), 200))
    w.clock.t += 100
    w.session.agents[0]["agent_status"] = "blocked"
    w.session.visible = "Do you want to make this edit?\n❯ 1. Yes\n  2. No\n"
    write("keys_ok", expect(w.post("/api/keys", {"session": SCRATCH, "key": "1",
                                                 "screen_token": g.screen_token(w.session.visible)}), 200))
    w.clock.t += 100
    w.session.agents[0]["agent_status"] = "working"
    write("stop_ok", expect(w.post("/api/stop", {"session": SCRATCH}), 200))
    w.close()

    with restart_world() as w:
        write("restart_ok", expect(w.post("/api/restart", {"session": SCRATCH}), 200))

    local = t.FakeLocal()
    local.transcript = "run the tests in the api worktree"
    w = World(speech=local)
    write("transcribe_ok", expect(w.request("POST", "/api/transcribe", WAV, Content_Type="audio/wav"),
                                  200))
    w.close()


WAV = b"RIFF" + (36 + 320).to_bytes(4, "little") + b"WAVEfmt " + bytes(16) + b"data" + (320).to_bytes(4, "little") + \
    bytes(320)


# ---------------------------------------------------------------- events

def events():
    w = World()  # A typed prompt's summary.
    w.prompt("what is 2")
    w.reply("It is two.")
    write("event_summary", w.view())
    ids = [e["id"] for e in json.loads(w.view()[1])["events"]]
    write("announced_ok", expect(w.post("/api/announced", {"ids": ids}), 200))
    w.close()

    w = World()  # A dialog after a send.
    w.prompt("what is 2")
    w.session.agents[0]["agent_status"] = "blocked"
    write("event_needs_you", w.view())
    w.close()

    w = World()  # Settled with no spoken line for SETTLE_SECONDS.
    w.prompt("what is 2")
    w.session.visible = "no line"
    w.view()
    w.clock.t += g.SETTLE_SECONDS + 1
    write("event_check_screen", w.view())
    w.close()

    for name, line in (("event_ask_true", "ASK: Did you mean wt-api or wt-web?"),
                       ("event_ask_confirm", "ASK!: Delete branch wt-old?"),
                       ("event_voice_summary", "Fixed it. Want me to run the tests?")):
        w = World()
        w.prompt("fix it", voice=True)
        w.reply(line, echo="fix it")
        write(name, w.view())
        w.close()


# ---------------------------------------------------------------- refusals (S1), one per reason

class restart_world:
    """A stopped wt-scratch whose saved folder `wt name` confirms, as in RestartReasonTests."""
    def __init__(self, config=None):
        self.config = config

    def __enter__(self):
        self.w = World(config=self.config)
        root = Path(self.w.tmp.name)
        sessions_dir = root / "sessions" / SCRATCH
        sessions_dir.mkdir(parents=True)
        worktree = root / "repo"
        worktree.mkdir()
        (sessions_dir / "session.json").write_text(json.dumps(
            {"version": 3, "workspaces": [{"identity_cwd": str(worktree), "tabs": []}]}))
        self.w.runner.session_dirs[SCRATCH] = str(sessions_dir)
        self.w.session.running = False
        self.w.runner.wt_names[str(worktree)] = SCRATCH
        return self.w

    def __exit__(self, *exc):
        if exc[0] is None:
            self.w.close()


def prompt_refusals():
    def simple(setup, call=lambda w: w.prompt("hello")):
        w = World()
        setup(w)
        answer = call(w)
        w.close()
        return answer

    cases = {
        "bad_text": (400, lambda: simple(lambda w: None, lambda w: w.prompt("   "))),
        "not_listed": (404, lambda: simple(lambda w: None, lambda w: w.prompt("hi", session="wt-absent"))),
        "stopped": (409, lambda: simple(lambda w: setattr(w.session, "running", False))),
        "no_orchestrator": (409, lambda: simple(lambda w: setattr(w.session, "agents", []))),
        "pane_changed": (409, lambda: simple(lambda w: setattr(w.session, "get_row", t.agent_row(terminal="x")))),
        "not_settled": (409, lambda: simple(lambda w: w.session.agents[0].update(agent_status="working"))),
        "draft_present": (409, lambda: simple(lambda w: setattr(w.session, "ansi", t.TYPED_BOX))),
        "repeat_window": (409, lambda: simple(lambda w: w.prompt("first"))),
        "herdr_refused": (409, lambda: simple(lambda w: setattr(w.runner, "prompt_result", t.err("agent_blocked")))),
        "herdr_timeout_maybe_sent": (502, lambda: simple(lambda w: w.runner.fail.__setitem__(
            (SCRATCH, ("agent", "prompt")), subprocess.TimeoutExpired("herdr", 15)))),
        "herdr_failed": (502, lambda: simple(lambda w: setattr(w.runner, "prompt_result", t.err("something")))),
        "voice_command": (400, lambda: simple(lambda w: None, lambda w: w.prompt("/compact", voice=True))),
        "voice_noise": (422, lambda: simple(lambda w: None, lambda w: w.prompt("Thank you.", voice=True))),
        "action_running": (409, action_running),
    }
    for reason, (status, make) in cases.items():
        write(f"refusal_{reason}", expect(make(), status, reason))


def action_running():
    w = World()
    entered, release = threading.Event(), threading.Event()

    def hold():
        entered.set()
        release.wait(5)
    w.runner.on_prompt = hold
    first = threading.Thread(target=w.prompt)
    first.start()
    try:
        assert entered.wait(5)
        answer = w.prompt("second")
    finally:
        release.set()
        first.join(5)
    w.close()
    return answer


def keys_stop_refusals():
    w = World()
    w.session.agents[0]["agent_status"] = "idle"
    write("refusal_not_blocked", expect(w.post("/api/keys", {"session": SCRATCH, "key": "1", "screen_token": "x"}),
                                        409, "not_blocked"))
    write("refusal_not_working", expect(w.post("/api/stop", {"session": SCRATCH}), 409, "not_working"))
    w.session.agents[0]["agent_status"] = "blocked"
    w.session.visible = "Do you want to make this edit?\n❯ 1. Yes\n  2. No\n"
    write("refusal_screen_changed", expect(w.post("/api/keys", {"session": SCRATCH, "key": "1",
                                                                "screen_token": "old"}), 409, "screen_changed"))
    w.close()

    with restart_world() as w:
        w.session.running = True
        write("refusal_already_running", expect(w.post("/api/restart", {"session": SCRATCH}), 409,
                                                "already_running"))
    with restart_world() as w:
        # The "no saved state" refusal, whose message holds no temporary path.
        Path(w.runner.session_dirs[SCRATCH], "session.json").unlink()
        write("refusal_restart_refused", expect(w.post("/api/restart", {"session": SCRATCH}), 409,
                                                "restart_refused"))
    with restart_world() as w:
        w.runner.wt_up_result = t.Res(1, "", "wt: session did not come up\n")
        write("refusal_restart_failed", expect(w.post("/api/restart", {"session": SCRATCH}), 502,
                                               "restart_failed"))


def transcribe_refusals():
    def send(w, data=WAV, kind="audio/wav", **headers):
        return w.request("POST", "/api/transcribe", data, Content_Type=kind, **headers)

    w = World(speech_reason="No speech token is stored.")
    write("refusal_speech_off", expect(send(w), 404, "speech_off"))
    w.close()

    local = t.FakeLocal()
    w = World(speech=local)
    write("refusal_bad_audio_type", expect(send(w, kind="text/plain"), 415, "bad_audio_type"))
    write("refusal_audio_empty", expect(send(w, data=b""), 400, "audio_empty"))
    # The guard answers from Content-Length alone, before reading: no 2 MiB body is needed.
    write("refusal_audio_too_large", expect(send(w, data=b"", Content_Length=str(g.MAX_TRANSCRIBE_BYTES + 1)),
                                            413, "audio_too_large"))
    local.transcribe_fail = g.VoiceError(500, "private words")
    write("refusal_speech_failed", expect(send(w), 502, "speech_failed"))
    local.transcribe_fail = None
    local.transcribe_gate = threading.Event()
    before = len(local.transcribed)
    first = threading.Thread(target=send, args=(w,))
    first.start()
    try:
        assert t.wait_for(lambda: len(local.transcribed) == before + 1)
        write("refusal_transcribe_busy", expect(send(w), 429, "transcribe_busy"))
    finally:
        local.transcribe_gate.set()
        first.join(5)
    local.transcribe_gate = None
    w.clock.t += 61
    for _ in range(g.TRANSCRIBE_PER_MINUTE):
        expect(send(w), 200)
    w.clock.t += 10
    write("refusal_transcribe_rate", expect(send(w), 429, "transcribe_rate"))
    w.close()


# ---------------------------------------------------------------- request guards and login answers (no reason)

def guards():
    w = World()
    cookie, w.cookie = w.cookie, None
    write("guard_401_logged_out", expect(w.view(), 401))
    write("guard_401_logged_out_post", expect(w.prompt(), 401))
    w.cookie = cookie
    write("guard_403_origin", expect(w.post("/api/prompt", {"session": SCRATCH, "text": "hi"},
                                            Origin="https://evil.example.com"), 403))
    write("guard_403_host", expect(w.post("/api/prompt", {"session": SCRATCH, "text": "hi"}, Host="evil.example.com"),
                                   403))
    write("guard_415_content_type", expect(w.post("/api/prompt", {"session": SCRATCH, "text": "hi"},
                                                  Content_Type="text/plain"), 415))
    write("guard_411_chunked", expect(w.post("/api/prompt", {}, Transfer_Encoding="chunked"), 411))
    write("guard_400_bad_json", expect(w.request("POST", "/api/prompt", b"{", Content_Type="application/json"), 400))
    write("guard_413_body", expect(w.request("POST", "/api/prompt", b"", Content_Type="application/json",
                                             Content_Length=str(g.MAX_BODY_BYTES + 1)), 413))
    w.close()

    w = World()
    w.cookie = None
    write("login_wrong", expect(w.post("/api/login", {"passcode": "wrong"}), 401))
    for _ in range(10):
        answer = w.post("/api/login", {"passcode": "wrong"})
        if answer[0] == 429:
            break
    write("login_locked", expect(answer, 429))
    w.close()


# ---------------------------------------------------------------- new worktrees (plan, re-plan, create)

# Every commit and reflog entry made by the scratch git gets the same dates, so the shas are the same on every run.
FIXED_DATE = "2026-11-01T09:00:00+00:00"
t.GIT_ENV.update({"GIT_AUTHOR_DATE": FIXED_DATE, "GIT_COMMITTER_DATE": FIXED_DATE})

WEB_BRANCHES = ("main", "production-4.6.0", "production-4.6.1", "production-4.6.2", "production-4.6.3", "uat",
                "develop")
WEB_BRANCH = "fix/login-timeout"
LAUNCH_LINE = "wt-launch: model=claude-opus-5-5 effort=high playbook=yes settings=no trust=written"


class WtWorld(World):
    """A scratch home with real git: `web-app` (a local bare origin with WEB_BRANCHES, origin/HEAD main) and `notes`
    (no origin) under the root `code`, the fake `wt new` and a fake interpreter, behind the real handler. Repo ids are
    hashed from the path under the scratch home, so they do not depend on the temporary folder's name."""
    def __init__(self, roots=True, interpreter=True):
        scratch = tempfile.TemporaryDirectory()
        self.scratch = scratch
        self.home = Path(g.os.path.realpath(scratch.name)) / "home"
        root = self.home / "code"
        self.web, self.seed = t.make_origin(self.home, root / "web-app", WEB_BRANCHES, head="main")
        self.notes = t.make_repo(root / "group" / "notes", origin=False, subject="notes start")
        self.said = None
        self.branch = WEB_BRANCH
        self.answer = None  # A fixed interpreter answer instead of web-app and self.branch.
        fake = t.FakeInterpreter(self._interpret) if interpreter else g.NotConfiguredInterpreter()
        config = replace(t.CONFIG, home=self.home, repo_roots=(str(root),) if roots else ())
        super().__init__(config=config, interpreter=fake, home=self.home)

    def _interpret(self, _query, repos):
        if self.answer is not None:
            return self.answer
        found = {"repo_id": repo_id(self.web), "branch": self.branch}
        if self.said is not None:
            found["base"] = self.said
        return found

    def plan(self, said=None, query="a worktree in web app for fixing the login timeout", **extra):
        self.said = said
        return self.post("/api/worktree/plan", {"query": query, **extra})

    def plan_id(self, answer):
        return json.loads(answer[1])["plan_id"]

    def create(self, plan_id):
        return self.post("/api/worktree/create", {"plan_id": plan_id})

    def git(self, *args, repo=None):
        done = t.run_git(["-C", str(repo or self.web), *args])
        assert done.returncode == 0, (args, done.stderr)
        return done

    def close(self):
        super().close()
        self.scratch.cleanup()


_real_repo_id_of = g.repo_id_of


def repo_id(path):
    """gateway.repo_id_of on the path below the scratch home: the same id on every run (see WtWorld)."""
    real = g.os.path.realpath(path)
    marker = f"{g.os.sep}home{g.os.sep}code{g.os.sep}"
    return _real_repo_id_of("/fixture/code/" + real.split(marker, 1)[1]) if marker in real else _real_repo_id_of(real)


def worktrees():
    g.repo_id_of = repo_id

    w = WtWorld()
    write("worktree_setup", expect(w.get("/api/worktree/setup"), 200))
    w.close()
    w = WtWorld(roots=False)
    write("worktree_setup_off", expect(w.get("/api/worktree/setup"), 200))
    write("refusal_worktrees_off", expect(w.plan(), 409, "worktrees_off"))
    w.close()
    w = WtWorld(interpreter=False)
    write("refusal_interpreter_off", expect(w.plan(), 409, "interpreter_off"))
    w.close()

    w = WtWorld()  # A named base with a downloaded copy, then created from it (fetched, not moved).
    t.commit_on(w.seed, "production-4.6.2", "fix the session timeout")
    w.git("fetch", "-q", "origin")  # Writes the reflog entry the local copy's date comes from.
    ready = write("plan_new_ready", expect(w.plan("production 4.6.2"), 200))
    write("create_ok", expect(w.create(ready["body"]["plan_id"]), 200))
    w.close()

    w = WtWorld()  # Moved on origin between the plan and the create.
    plan_id = w.plan_id(expect(w.plan("uat"), 200))
    t.commit_on(w.seed, "uat", "hotfix after the plan")
    write("create_ok_moved", expect(w.create(plan_id), 200))
    w.close()

    w = WtWorld()  # An existing branch: the base does not apply.
    w.git("branch", WEB_BRANCH, "origin/develop")
    write("plan_existing", expect(w.plan("uat"), 200))
    write("create_ok_existing", expect(w.create(w.plan_id(w.plan())), 200))
    w.close()

    w = WtWorld()  # wt printed its launch line; then wt printed `wt-launch: restored` (no launch).
    w.runner.wt_new_stdout = LAUNCH_LINE + "\n"
    write("create_ok_launch", expect(w.create(w.plan_id(w.plan())), 200))
    w.runner.wt_new_stdout = "wt-launch: restored\n"
    w.branch = "fix/second"
    write("create_ok_no_launch", expect(w.create(w.plan_id(w.plan())), 200))
    w.close()

    w = WtWorld()  # No origin/HEAD and nothing remembered: the card asks for a base, then create refuses.
    w.git("remote", "set-head", "origin", "-d")
    needs = write("plan_needs_base", expect(w.plan(), 200))
    write("refusal_not_ready", expect(w.create(needs["body"]["plan_id"]), 409, "not_ready"))
    w.close()

    w = WtWorld()  # A spoken base shared by several branches: close matches as taps.
    write("plan_close_matches", expect(w.plan("production 4.6"), 200))
    write("refusal_bad_base", expect(w.post("/api/worktree/plan", {"plan_id": "0" * 32, "base": " "}), 400,
                                     "bad_base"))
    write("refusal_bad_plan", expect(w.create("../x"), 400, "bad_plan"))
    write("refusal_bad_query", expect(w.post("/api/worktree/plan", {"query": "  "}), 400, "bad_query"))
    w.close()

    w = WtWorld()  # A base only on origin, not downloaded; then origin goes away: no downloaded copy to offer.
    assert t.run_git(["-C", str(w.seed), "branch", "production-4.6.4", "main"]).returncode == 0
    t.commit_on(w.seed, "production-4.6.4", "on production-4.6.4")
    on_origin = write("plan_on_origin", expect(w.plan("production 4.6.4"), 200))
    w.git("remote", "set-url", "origin", str(w.home / "gone.git"))
    write("refusal_fetch_failed", expect(w.create(on_origin["body"]["plan_id"]), 502, "fetch_failed"))
    w.close()

    w = WtWorld()  # Origin goes away after the plan: the downloaded copy is offered.
    plan_id = w.plan_id(expect(w.plan("production 4.6.1"), 200))
    w.git("remote", "set-url", "origin", str(w.home / "gone.git"))
    write("refusal_fetch_failed_offline", expect(w.create(plan_id), 502, "fetch_failed"))
    w.close()

    w = WtWorld()  # The interpreter is unsure, then cannot be understood.
    w.answer = {"candidates": [repo_id(w.web), repo_id(w.notes)], "question": "Which repo did you mean?"}
    write("plan_ask_candidates", expect(w.plan(), 200))
    w.answer = "not json at all"
    understood = w.plan()  # Its body has a `reason` of its own (why not), so it is checked by hand.
    assert understood[0] == 200 and json.loads(understood[1])["understood"] is False, understood
    write("plan_not_understood", understood)
    w.close()

    w = WtWorld()  # Used, expired, changed, wt failed.
    write("refusal_plan_gone", expect(w.create("0" * 32), 404, "plan_gone"))
    plan_id = w.plan_id(w.plan())
    w.clock.t += g.PLAN_TTL_SECONDS + 1
    write("refusal_plan_expired", expect(w.create(plan_id), 409, "plan_expired"))
    plan_id = w.plan_id(w.plan())
    w.git("branch", WEB_BRANCH, "origin/develop")  # Now an existing branch: not what the plan said.
    write("refusal_repo_changed", expect(w.create(plan_id), 409, "repo_changed"))
    w.branch = "fix/third"
    w.runner.wt_new_result = t.Res(1, "", "wt: failed to start the session\n")
    write("refusal_wt_failed", expect(w.create(w.plan_id(w.plan())), 502, "wt_failed"))
    w.runner.wt_new_result = None
    w.close()

    w = WtWorld()  # A second create while the first is still in `wt new`.
    first_id = w.plan_id(w.plan())
    w.branch = "fix/fourth"
    second_id = w.plan_id(w.plan())
    entered, release = threading.Event(), threading.Event()

    def hold():
        entered.set()
        release.wait(5)
    w.runner.on_wt_new = hold
    first = threading.Thread(target=w.create, args=(first_id,))
    first.start()
    try:
        assert entered.wait(5)
        write("refusal_create_busy", expect(w.create(second_id), 409, "create_busy"))
    finally:
        release.set()
        first.join(10)
    w.close()

    g.repo_id_of = _real_repo_id_of


# ---------------------------------------------------------------- tap confirmation (last, so earlier ids stay put)

def tap_confirmation():
    w = World()
    write("prompt_ok_tap", expect(w.prompt("yes, go ahead", voice=True, confirm="tap"), 200))
    assert w.runner.named("agent", "prompt")[-1]["argv"][-1].startswith("yes, go ahead (Sent from my phone by a tap")
    w.clock.t += 100
    write("prompt_bad_confirm", expect(w.prompt("yes", voice=True, confirm="voice"), 400, "bad_text"))
    w.close()


# ---------------------------------------------------------------- progress updates (last, so earlier ids stay put)

def progress():
    w = World()  # A voice prompt still working, with one progress line on screen.
    w.prompt("fix it", voice=True)
    w.progress(["Reading the failing test"])
    answer = w.view()
    assert [e["kind"] for e in json.loads(answer[1])["events"]] == ["progress"], answer
    write("event_progress", answer)
    w.session.agents[0]["agent_status"] = "idle"  # Then the reply: the unheard progress line is dropped.
    w.session.visible += f"\n⏺ SPOKEN[{w.nonce()}]: Fixed the test. Want me to run the rest?\n"
    answer = w.view()
    assert [e["kind"] for e in json.loads(answer[1])["events"]] == ["summary"], answer
    write("event_progress_then_summary", answer)
    w.close()


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.json"):
        old.unlink()
    views()
    events()
    prompt_refusals()
    keys_stop_refusals()
    transcribe_refusals()
    guards()
    worktrees()
    tap_confirmation()
    progress()
    names = sorted(p.name for p in OUT.glob("*.json"))
    print(f"{len(names)} fixtures in {OUT.relative_to(REPO)}:")
    for name in names:
        print(" ", name)


if __name__ == "__main__":
    main()
