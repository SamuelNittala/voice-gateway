# Tasks

Voice gateway v2: a thin web remote over the user's existing Herdr `wt-*` sessions, for an Android phone.
Python standard library only, Python 3.9+, portable to Linux. No persistence beyond the passcode file, no auto-start.

The previous version (own agents in a `phone` session, jobs, result files, voice routing) and its task history are in
`archive-v1/`. Port 8765 on 127.0.0.1 belongs to another service on this Mac: never bind it. The default port is 8766.

## Spec

Worktree list (`GET /api/sessions`)
- Built from `herdr session list --json`, names starting `wt-` only.
- Groups: attached, running, stopped. Attached = a process `herdr session attach <name>` in `ps -axo args=`.
- Running sessions list every agent with name or kind, pane label, state. Only the orchestrator can be acted on.
- No Herdr command against a stopped session except Restart. Never bare `herdr`. No focus commands.

Worktree detail (`GET /api/session?name=`)
- Recent output of the orchestrator only: `agent read <pane> --source recent-unwrapped --lines N`, N no more than the
  pane's visible rows, because reading further makes Herdr scroll the agent's screen and redraws attached clients.
  "Earlier output" reads further on a tap, only when `idle` or `done`.

Actions (`POST /api/prompt`, `/api/keys`, `/api/stop`, `/api/restart`), JSON `{"session": ..., ...}`. Never a pane ID.
- Session must be in a fresh `herdr session list` and start `wt-`.
- Orchestrator is derived fresh: the one agent in a tab labelled exactly `orchestrator`. None or several: refuse.
- Prompt: only when a re-check right before sending shows `idle` or `done`. Text capped, control characters except
  newline stripped, cannot be parsed as a flag. Per-session lock plus a short repeat window, so a double tap sends once.
  Refused for Claude when text is waiting in its input box.
- Keys: fixed allowlist (1, 2, up, down, enter, esc), only when `blocked`, and only with the token of the screen on show.
- Stop: per-kind stop keys, only when `working`.
- Restart: stopped sessions only. Path from the session's saved `session.json`, confirmed with `wt name` in that path
  and compared to the session name, then `wt up` there. Otherwise refuse and say why.
- Never used anywhere: `wt down`, `wt rm`, `herdr session stop/delete`, `herdr server stop`.
- `wt up` child environment has no CLAUDE_CONFIG_DIR, HERDR_* or VOICE_GATEWAY_*, because the Herdr server it starts
  passes its environment to the user's agents. `VOICE_GATEWAY_RESTART_CLAUDE_CONFIG_DIR` is an optional test-only
  override for that one child, never a default.

Security
- Every request: Host allowlist and fail-closed Tailscale identity. POST: JSON content type and allowed Origin. CSP.
- Passcode required: `python3 gateway.py --set-passcode` stores a salted PBKDF2 hash in a 0600 file in the data dir
  (scrypt is missing from the macOS system Python). No passcode, no start.
- Login sets an HttpOnly, SameSite=Strict random cookie, Secure over HTTPS, held in memory, 7 days.
  Lockout after 5 failures with increasing delay, per client and globally.
- All API routes need identity and cookie. Only the login page and its static files need identity alone.

Page
- List, detail, login. Output through textContent only. Speech only fills the text box; Send is always a separate tap.
  This is the web page's rule and stays so; the Android app (below) sends spoken text by itself.
- Keys shown only when `blocked`, Stop only when `working`, Restart with an in-page confirm on stopped sessions.
- `VOICE_GATEWAY_OTHER_URL` (https on a ts.net host) shows a link to the other machine's page.

Spoken summaries (approved 2026-11-07)
- A prompt sent from the phone gets a random nonce and an appended instruction: end the reply with one line of at most
  two plain sentences, no code, paths or markdown, in the form `SPOKEN[<nonce>]: ...`. Text beginning with `/` or `!` is
  sent unchanged, gets no nonce, and ends in the check-screen case.
- One pending record per session in server memory only: nonce, sent time, announced. A new send replaces it.
- When the orchestrator settles, the visible screen is searched for a line that starts with that exact tag, allowing
  the agent's leading bullet and whitespace. An echoed prompt never counts, even where wrapping puts the tag first on a
  line. `idle` and `done` can appear between steps, so "no line" is only decided after a settling period.
- One final event per send (see "Finish after a dialog" for `needs-you`), unique id, text capped near 300 characters,
  control characters removed. Kinds: `summary`, `needs-you` (became blocked; never reads the dialog), `check-screen`
  (settled with no tag, or stopped or failed).
- `GET /api/sessions` and `GET /api/session` return unacknowledged `events` and the `waiting` session names.
  `POST /api/announced {"ids": [...]}` acknowledges. Raw terminal output is never spoken. Work started elsewhere makes no
  events, and sessions with no pending send are not read by the list.
- Page: speechSynthesis with the phone's own voice, same text shown through textContent, each id spoken once then
  acknowledged. Unlocked during the Send tap. Screen wake lock while anything is waiting. One mute toggle.

Keys match the screen (approved 2026-11-07)
- When `blocked`, the dialog shown and `screen_token` (hash of normalised visible rows) come from one read.
- `/api/keys` must carry that token. The server re-reads right before sending and answers 409 with the new screen and
  token if it differs, or if the token is missing. The stop key while `working` keeps its rule and takes no token.
- A tiny window remains between that read and the key arriving.

Half-typed text (approved 2026-11-07)
- Claude: a styled visible read shows its placeholder as dim and typed text with no style, so a send over typed text is
  refused with 409 and the text is left alone. A box in shell mode counts as typed.
- Any other kind, or a Claude box that is not as expected: `draft` is `unknown`, the send goes, and a short warning by
  Send says text already in the box would be sent too.

`wt` (approved 2026-11-07, Mac only, not in this folder): `session_busy` counts `blocked` as busy; `cmd_up` removes that
session's reap marker when it really starts the session.

Finish after a dialog (approved 2026-11-08; replaces "one event per send")
- The pending record survives a `needs-you` event. Each new move into `blocked`, seen on a poll after one that was not
  blocked, gives one `needs-you`. When the send settles it still gives its one `summary` or `check-screen`, and only
  then stops waiting. A move in and out of a dialog between two polls is not seen.
- Every event has its own id and is spoken once. A new send replaces the record, blocked or not: the old one makes no
  more events, and events it already made stay until acknowledged.

Phone send resets the reap timer (approved 2026-11-08)
- After a prompt that Herdr accepted (not a refusal, not a timeout, not keys, stop or restart), the gateway removes
  `<reap dir>/<session>`, so `wt reap` starts its 10 minutes again.
- Reap dir: `VOICE_GATEWAY_REAP_DIR` (absolute) or `$HOME/.config/herdr/wt-reap`. A missing file or directory is fine.
- The name is the validated session name and must have no path separator. The directory is opened without following
  a symlink, the entry is `lstat`ed through it, and only a regular file is removed. Nothing else there is touched.
  A failure never fails the send.

Deepgram voice (approved 2026-11-08)
- API, from developers.deepgram.com: `POST https://api.deepgram.com/v1/speak?model=<voice>&encoding=mp3`,
  `Authorization: Token <key>`, `Content-Type: application/json`, body `{"text": ...}`, answer `audio/mpeg`. Text up to
  2000 characters (413 past that). Errors 400, 401, 402, 403, 413, 429, 5xx with a JSON body.
- Server side only. `http.client` over HTTPS with the default verified context, a timeout, no proxy and no redirects.
  Answers must be 200, `audio/mpeg`, non-empty and under a size cap. Errors keep the HTTP status only.
- Key: `python3 gateway.py --set-deepgram-key` (getpass) stores it in `deepgram-key` in the data dir, 0600, like the
  passcode. `--clear-deepgram-key` removes it. A key file that others can read, or that is not a regular file, stops
  the gateway from starting. No key file: the browser voice, as before.
- `VOICE_GATEWAY_DEEPGRAM_VOICE`: `aura-[2-]<name>-<lang>`, default `aura-2-thalia-en`.
- `VOICE_GATEWAY_DEEPGRAM_TEST_URL`: test only, loopback http or https, nothing else.
- `python3 gateway.py --test-voice`: one fixed sentence, audio saved to a temp file, prints success or a short error
  with the HTTP status, never the key.
- `POST /api/voice {"id": ..., "with_name": bool}` (identity, cookie, POST rules) answers the audio of one
  unacknowledged event. Only the event's own text, or `<worktree>: <text>` for a summary with `with_name`, is sent.
  404 for an unknown id or with no key. 502 if Deepgram fails. Audio is cached per event in memory (few entries, a
  byte cap, a time limit) and dropped on acknowledge or when the event is gone.
- Views carry `voice`: `deepgram` or `browser`.
- Page: one audio element plays the fetched blob. Unlocked in the Send tap and any tap, like speech. A failed fetch or
  play falls back to speechSynthesis for that event only. Each event is spoken once, and acknowledged only once its
  audio has been fetched or given up. Events play one after another. Mute stops both. CSP adds
  `media-src 'self' blob:` and nothing else.

Streamed voice (2026-11-08; replaces `POST /api/voice` and the blob above)
- Measured cause: a new TLS connection (0.6-0.8 s), then the whole MP3 (3-4.4 s for 120 characters) before the phone
  got a byte, then a blob, behind one lock for all events. Deepgram's first byte comes 0.3-0.4 s after the request.
- `GET /api/audio?id=<event id>&with_name=0|1` (Host, identity, login cookie; SameSite=Strict keeps other sites out).
  `with_name` absent means 0; anything else, or a repeated parameter, is 400. Same text rules as before: only the
  event's own text, or `<worktree>: <text>` for a summary with `with_name`. 404 for an unknown id or no key. Other
  parameters are ignored and never spoken. `POST /api/voice` is gone.
- The answer is `200 audio/mpeg`, `Transfer-Encoding: chunked`, `no-store`, sent once Deepgram's first byte is in, and
  each later chunk is passed on as it arrives. A failure before the first byte is 502 with the HTTP status only. A
  failure after it ends the connection without the closing chunk, so the browser sees a failed load.
- Caps kept: 2 MiB, the 10 s overall deadline (each read's timeout is what remains of it), MP3 only, empty is a failure.
- One Deepgram call per event and variant, ever: the download runs on its own thread and any number of requests read
  along from the same buffer, so a second request mid-stream never calls Deepgram again, and a phone that hangs up
  does not stop it. A fully received clip goes to the existing cache (unless the event is gone). A failed one is
  remembered until its event goes and answered 502 at once, because Chrome retries a failed media load by itself
  (seen live: five retries, each would have been a new call). Different events never wait for each other.
- A `Range` other than `bytes=0-` is 416 `bytes */*`: only whole clips are sent, and Chrome's retries ask for the
  rest from part way.
- One keep-alive connection to Deepgram, verified HTTPS, no proxy, no redirects, reused when idle; a second clip at the
  same time opens its own. Opened ahead while any phone send waits or any event is unacknowledged. Deepgram answers
  408 and closes a connection with no request about 5.2 s after its handshake (measured twice), so it is looked at
  every 0.5 s, renewed when 2.5 s old (the old one stays usable meanwhile), never used past 4 s, replaced at once if
  Deepgram closed it, and closed 15 s after nothing has waited. A reused connection that turns out closed gets one
  retry on a fresh connection, only when no byte came back (the send failed, or the answer was empty). Never after a
  timeout, never on a fresh connection.
- Page: plays while it downloads. Chrome starts a plain src only once 32 KB are in (measured: 8 KB/s started at 3 s,
  4 KB/s at 7 s), which is most of a 120-character clip, so where `MediaSource.isTypeSupported("audio/mpeg")` the page
  fetches the URL and appends each chunk to a MediaSource on the same audio element; elsewhere the element's src is
  the URL itself. A failed load, a refused play() or no start within 15 s falls back to the phone's voice for that
  event, once, and a start after giving up is stopped. A download that fails part way ends the stream: what came is
  played, with no second voice. Events still play one after another, mute stops both, and each event is acknowledged
  only once its audio is over or given up. The unlock during taps is unchanged. CSP stays `media-src 'self' blob:`,
  now for the MediaSource.

Flux voice (2026-11-08; adds to "Deepgram voice" and "Streamed voice", which still hold unless said here)
- Docs (read 2026-11-08): developers.deepgram.com/docs/flux-tts/batch.md, /docs/flux-tts/voices.md,
  /docs/flux-tts/overview.md, /docs/flux-tts/migrating.md, /reference/speak/v-2/audio/generate.md.
  `POST https://api.deepgram.com/v2/speak?model=flux-<voice>-<lang>`, same Token header, JSON body and error shape as
  v1. Encoding defaults to mp3. Aura names stay on `/v1/speak` only ("no aliasing, redirect, or deprecation"), and
  v2 takes only Flux names. Not in the docs: streaming, Content-Type, a text limit, error statuses beyond 400.
- Measured on v2 with the real key (flux-cole-en, the 118-character text): `200 audio/mpeg`, `Transfer-Encoding:
  chunked`, first byte 0.39 s after the request and the rest over 4.8 s (it streams, although the docs say batch
  sends the whole clip), 40176 bytes, MPEG-2 layer III 48 kbps 24 kHz, no ID3 tag. 2000 characters: 200.
- The voice name decides the endpoint. `flux-[a-z]{2,20}-[a-z]{2,3}` is Flux, on `/v2/speak`; the Aura pattern as
  before is on `/v1/speak`; anything else, or over 40 characters, refuses to start, naming both forms. Only
  `model` and `encoding=mp3` are sent, for both.
- Default `flux-cole-en`. `VOICE_GATEWAY_DEEPGRAM_VOICE` still overrides it, Aura names included.
- Every guarantee above holds for both endpoints, tested on both: verified HTTPS, no proxy, no redirects, `audio/mpeg`
  only (v2 sends the same type, so the check is unchanged), size cap, deadline, status-only errors, event text
  only, one download per event, no repeat call after a failure, 416, fallback to the phone's voice.
- Text limit per endpoint: v1 2000 (documented, 413 past it), v2 2000 (not documented; 2000 measured accepted, more
  not tried). The gateway sends at most about 370 characters, so neither is ever reached.
- The kept connection's timings stay (renew at 2.5 s, never used past 4 s). Measured again on 2026-11-08: a
  connection with no request gets 408 and is closed 5.0 s after its handshake (three times, now on TLS 1.2), and
  after a v2 answer Deepgram closes it 5.0 s after the answer ended. Both are past 4 s.
- `VOICE_GATEWAY_DEEPGRAM_TEST_URL` is now loopback scheme, host and port with no path, since the path comes from the
  voice. One with a path refuses to start.
- `--test-voice` uses the configured voice and prints its name and endpoint (`/v1/speak` or `/v2/speak`) with
  success or the short error.

No kept Deepgram connection (approved 2026-11-08; replaces the kept-connection paragraph of "Streamed voice" and
the kept connection's timings in "Flux voice")
- The user chose about 0.5-1 s more before audio starts over a reconnect every few seconds while an agent works.
- Each clip opens its own verified HTTPS connection (no proxy, no redirects, the same timeout and deadline) and closes
  it when the clip ends. Nothing is opened ahead, nothing is kept, and there is no retry: a failed clip is a failure.
- Everything else in "Streamed voice" and "Flux voice" holds: `GET /api/audio`, MediaSource on the page, one download
  per event and variant, no repeat call after a failure, 416 for partial ranges, caps, both endpoints, the fallback.

New worktree from the phone (approved 2026-11-08)
- Repo list. Roots: `VOICE_GATEWAY_REPO_ROOTS`, colon-separated absolute paths; unset means `~/work:~/personal`; set
  but empty means off, and the page says how to turn it on. A relative entry refuses to start. A repo is a main
  checkout: a directory whose `.git` is a real directory (lstat), found at the root itself, its children and
  grandchildren. A repo is not looked inside. Hidden names and symlinks are skipped, and so is anything under
  `~/worktrees` or `~/.superset`. At most 200 repos and 5000 folders looked at; cached 30 s. Each repo has an id
  (a hash of its path); the client only ever sends ids.
- Interpreter: `plan(query, repos)` where repos is `[{"id", "name", "where"}]` (`where` is the folder it sits in,
  relative to home, never a full path), returning a dict or a JSON text: `{"repo_id", "branch"}` or
  `{"candidates": [ids], "question"}`. Keys that are null, "" or [] are dropped first; then exactly one of the two
  shapes, every id in the list given, at most 8 unique candidates, a question cleaned and capped at 200. Anything
  else is "couldn't understand": the page says so and shows every repo as a tap, with no silent retry.
  `InterpreterNotConfigured` makes the page say the interpreter is not set up.
- Real interpreter: `claude -p --model claude-haiku-5-5` on the default account (CLAUDE* removed as for every
  child), the request and repo list as JSON on stdin, never in argv. All tools off, no MCP, safe mode (no CLAUDE.md,
  hooks, plugins, skills), no slash commands, no session saved, JSON output with a schema. cwd is a new empty private
  temp dir, removed after. The binary is `claude` on PATH or `~/.local/bin`, resolved, and must be a Mach-O or ELF
  file, not a script. 60 s timeout. Errors say a short reason only; the model's words are never shown or logged.
  Not found: the not-configured one.
- Plan (`POST /api/worktree/plan {"query", "repo_id"?}`, identity, cookie, POST rules): query cleaned of control
  characters, whitespace collapsed, 1-500 characters. With `repo_id` (a tap on a candidate) only that repo is given to
  the interpreter. The branch: 1-100 characters of `A-Za-z0-9._/-`, not starting with `-`, not `HEAD`, and
  `git check-ref-format --branch` must print it back unchanged. Then, as `wt new` does: main checkout from
  `git rev-parse --path-format=absolute --git-common-dir`; an existing `refs/heads/<branch>` is checked out, and
  refused if a worktree already has it; otherwise a new branch from `origin/HEAD` (`symbolic-ref`), else `HEAD`.
  Shown: short sha and subject of `git log -1`. Target `~/worktrees/<repo>/<slug>` (wt's slug: lowercase, anything
  but `a-z0-9-` becomes `-`), refused if it exists. The answer is a plan record in memory: id, 5 minutes, used once,
  at most 16 kept.
- Create (`POST /api/worktree/create {"plan_id"}`): one at a time. The plan is taken (used even if create fails),
  then everything is worked out again and must match. `wt new <branch>` in the main checkout, output to files, own
  process group, 180 s timeout, the child environment as for restart (WT_EDITOR kept as inherited, the restart test
  override for CLAUDE_CONFIG_DIR applies here too). Then `wt name` in the new folder, and `herdr session list` must
  show it. The answer has the session name. The gateway never runs `wt rm`, `wt down` or any delete.
- Page: "New worktree" on the list. The same mic and text box, then Plan. The card shows repo, branch, new or
  existing, base commit and path, with Create and Cancel. Candidates are taps that plan again with that repo. After
  Create the new worktree's detail view opens with the request in the text box, unsent. Speech only fills the box.

Base branch for new worktrees (2026-11-08; replaces "from `origin/HEAD`, else `HEAD`" above)
- Found: "a worktree in example-frontend from production-4.6.2" showed origin/master, because the schema had no base
  and the plan always took origin/HEAD, else HEAD. Across the 37 repos origin/HEAD is often not the working base
  (example-agent: origin/HEAD is master, its main checkout is on dev) and 5 repos have none at all.
- The base is always the remote copy, `origin/<base>`, never a local branch and never the checked-out branch.
- Interpreter: an optional `base` in the schema and prompt, the base branch the request names ("from dev", "off uat",
  "from production 4.6.2") as said, or absent. Text, one line, at most 100 characters, else "couldn't understand".
  Ignored next to candidates. No branch list is ever sent to the model; resolving is the gateway's.
- Resolving a named base against `refs/remotes/origin/*` (for-each-ref, `HEAD` left out, names of the branch shape
  only), in order: the name as said (a leading `origin/` dropped); then the one name equal once normalised (lowercase,
  number words zero to twelve to digits, "point" or "dot" to `.`, numbers with only spaces, dots or dashes between them
  joined by `.`, words joined by `-`: "Production four point six point two", "production 4 6 2" and
  "production 4.6.2" all become `production-4.6.2`). Several equal once normalised, or none but some starting with
  it ("production 4.6"): those are offered as taps, newest commit first, and nothing is picked.
- Not found locally: the plan step may ask origin, read only, for the name as said, or for its normal form when the
  name as said is not of the branch shape (a spoken name never is): `git ls-remote --heads origin refs/heads/<name>`,
  own process group, output to files, 15 s, GIT_TERMINAL_PROMPT=0. Found there: used, shown as on origin and not
  downloaded yet. A fresh clone has no reflog for origin's branches, so the local copy's date can be missing; the
  card then shows the commit's date.
- The base is untrusted like the branch: the branch's character rules, not `HEAD`, never starting with `-`, and
  `git check-ref-format --branch` must print it back, before it reaches any git argv. It is only ever passed inside a
  full ref (`refs/heads/<name>`, `+refs/heads/<name>:refs/remotes/origin/<name>`, `refs/remotes/origin/<name>`).
- No base named: the base last used for that repo, if origin still has it here; else origin/HEAD's target; else none,
  and the card asks for one. Remembered in `bases.json` in the data dir: 0600, written by a private temporary file
  moved into place, repo id to base name, at most 256 entries, written only after `wt new` made the folder. A file
  that is not a regular file or not a JSON object of names is ignored.
- Plan answer: `ready`, and `base` with `applies` (false for an existing branch, which is checked out as it is),
  `name`, `ref` (`origin/<name>`), `how` (`named`, `remembered`, `origin-head`), short `sha`, `subject`,
  `committed_at`, `fetched_at` (the local copy's reflog time, when there is one), `local`, `said`, `message` and
  `choices`: close matches first, then the remembered base, origin/HEAD's target, and whichever of dev, develop, uat,
  main, master, prod, production origin has, at most 8. `POST /api/worktree/plan {"plan_id", "base"}` plans the same
  repo and branch again with that base, without the interpreter; the old plan stays as it was.
- Card: `origin/<name>`, sha, subject and the local copy's date, "Fetched from origin just before creating", the
  choices as taps and a box to type another base. Create only when ready. For an existing branch it says the base
  does not apply.
- Create, after everything but the base's commit is checked again as before: in the main checkout,
  `git fetch --no-tags origin +refs/heads/<name>:refs/remotes/origin/<name>` (argv only, GIT_TERMINAL_PROMPT=0, the
  inherited SSH agent kept, own process group so no terminal can prompt, output to files, 60 s), then
  `wt new <branch> --base refs/remotes/origin/<name>`. The commit may have moved: expected, not a 409. The answer and
  the page give the final sha, and say whether it moved.
- Fetch failed: refused (502) with a short reason, never git's own words, nothing created, and when a local copy
  exists a new plan that creates from it without fetching, offered as "Create from the downloaded copy (dated ...)".
  Only that plan skips the fetch, and its commit must not have moved.

Local speech on NixOS (approved 2026-11-08; adds to the voice sections above, which still hold unless said here)
- Measured before (~/voice-models/bench on the speech host, RTX 4060 Ti 8 GB): Chatterbox Turbo has no streaming, about
  0.25x real time (1.2 s for 80 characters, 5.3 s for 300), first `generate()` 8.2 s, a Perth watermark on every clip,
  2.8-4 GB of GPU memory. faster-whisper large-v3-turbo float16: about 2.1 GB, 0.22 s a clip, but 0.35 s when
  chatterbox is imported in the same process, so the two run as separate processes. A vocabulary `initial_prompt`
  raised exact repo and branch names from 6 to 13 of 16. Whisper keeps only the last 223 tokens of a prompt; the
  37 repo names here plus the branches are 305 tokens (737 characters).
- Speech service, `~/voice-models/speech/` on the speech host (not in this folder). `start.sh` in the foreground, Ctrl+C stops
  every process it started. No systemd, no auto-start, no sudo, no system config.
  - One stdlib HTTP front (no torch) and two worker processes it starts and watches: TTS (chatterbox) and STT
    (faster-whisper). Each warms up before it is ready. Frames over pipes; the workers' own stdout goes to stderr so
    no library print can break a frame. A worker that dies or overruns its limit is killed and started again.
  - Binds <speech-host-ip> (the tailnet address) on 8790 by default, or 127.0.0.1 for an SSH tunnel; nothing else,
    never 0.0.0.0. Clients other than <gateway-host-ip> (the Mac) and 127.0.0.1 are refused before anything is read.
    Measured 2026-11-08: the Mac reaches <speech-host-ip>:8790 with the firewall as it is, so no tunnel is needed.
  - `Authorization: Bearer <token>` on every request, compared in constant time. The token is made on first start in
    `speech/token`, 0600; a token file that is not a 0600 regular file stops the start.
  - `GET /health`: each worker's state (`starting`, `ready`, `busy`, `restarting`, `down`).
  - `POST /tts` JSON `{"text"}`: 1-300 characters after control characters are stripped. Split into sentences; the
    first is generated, encoded and sent first, then the rest in order, through one MP3 encoder per request (ffmpeg,
    24 kHz mono, 64 kbps CBR, no Xing or ID3 header, 0.15 s of silence between sentences), so the parts are one
    stream. `200 audio/mpeg`, chunked, only once the first MP3 bytes exist; a failure before them is a JSON error.
  - `POST /stt`: the audio as the body, at most 2 MiB, `Content-Type` webm, ogg, mp4/m4a or wav, checked against the
    file's own magic bytes; optional `X-Vocabulary` (printable ASCII, at most 1000 characters) as `initial_prompt`,
    trimmed by whole comma-separated items from the end to 223 tokens; language English; audio over 35 s refused.
    Answers `{"text"}`.
  - One request at a time per worker, at most 2 more waiting (else 503), waits and runs time-limited. Request bodies
    and headers read with a short socket timeout. No path or file name is ever taken from a request. The log has
    method, path and status, never text or audio.
- Gateway config. `VOICE_GATEWAY_SPEECH_URL`: `http://<IPv4>:<port>` where the address is a tailnet one (100.64.0.0/10)
  or 127.0.0.1, with no user, path, query or fragment; anything else refuses to start. `--set-speech-token` stores the
  token in `speech-token` in the data dir, 0600, like the Deepgram key, read with getpass, or from stdin when stdin is
  not a terminal (`ssh <speech-host> cat ~/voice-models/speech/token | python3 gateway.py --set-speech-token`). A token
  file that is loose or not a file stops the start. Local speech is on only with both; otherwise the page says it is
  off and why. The start-up line shows the URL and whether `/health` answered.
- Speaker switch (page): Chatterbox, Deepgram, Phone voice, kept per browser in localStorage (guarded). Default:
  Chatterbox when local speech is on, else Deepgram with a key, else Phone. Views carry `voice` (the default:
  `chatterbox`, `deepgram` or `browser`), `voices` (the server engines that are set up, in fallback order) and `local`
  (`{"configured", "reason"}`).
- `GET /api/audio?id=&with_name=&engine=chatterbox|deepgram`. `engine` absent means the default engine; anything
  else, or repeated, is 400; one that is not set up is 404. The cache, the running downloads and the remembered
  failures are keyed by event, variant and engine, so each engine is asked at most once per event and variant. The
  Chatterbox clip has a 10 s limit for its first byte and 30 s overall; Deepgram keeps its 10 s. Text over 300
  characters (a long summary with its worktree name) is a Chatterbox failure before any audio, so it falls back.
- Fallback on the page: Chatterbox fails before playing (load failed, refused, not started in time) -> Deepgram if
  the gateway has a key -> the phone's voice. Deepgram fails -> the phone's voice. One attempt per engine per event,
  each heard once, a late start after giving up is stopped, mute stops all. A small note says when a fallback was used
  ("Chatterbox unavailable, used Deepgram"). Everything else in "Streamed voice" holds.
- Dictation switch (page): Chrome speech or Local Whisper, kept in localStorage. Default Chrome speech, as before.
  Local Whisper: the mic records with getUserMedia and MediaRecorder (tap to start, tap to stop, stops itself at
  30 s), with a recording indicator and timer; then `POST /api/transcribe` with the recording as the body. The text
  fills the box as Chrome speech does, in both views. A failure shows an error and leaves the box alone; it never
  falls back to Chrome speech, which would send the audio to Google. Leaving the view or Send discards a recording.
- `POST /api/transcribe` (identity, cookie, allowed Origin): body is the audio, `Content-Type` one of the STT types,
  `Content-Length` 1 byte to 2 MiB, no Transfer-Encoding. One at a time (429 while one runs) and at most 12 a
  minute (429 with Retry-After). 404 when local speech is off. Forwards to `/stt` with the vocabulary: the branches
  dev, develop, uat, main, master, prod, production, then the discovered repo names most recently used first (by
  their `.git` time), whole names only, within 480 characters. Answers `{"text"}` with control characters removed,
  capped at the prompt limit. Failures are 502 with a short reason, never the service's words.
- CSP unchanged unless the browser shows a need: the mic is not under CSP, `/api/transcribe` is 'self', and MediaSource
  already has `media-src 'self' blob:`.

Android app (2026-11-08; plan in ~/.claude/plans/android-voice-app.md; adds to the sections above, which still hold
unless said here)
- A native Kotlin app at `android/` drives one `wt-*` worktree by voice. Hands-free loop: tap once; the app listens,
  detects end of speech on the phone, transcribes with Local Whisper through `POST /api/transcribe`, sends the text
  with `POST /api/prompt` at once, speaks the agent's `summary` event, then listens again. Tap while speaking stops
  the audio and starts listening; the mic is off while speaking.
- Auto-send (user's decision): no cancel window and no confirm step of any kind; the user does not look at the screen.
  A short "sent" earcon plays on every send. A wrong request can go through; the backstops are the guards below, the
  agent asking instead of acting (S4), Claude's own tap-only permission dialogs, and Stop.
- Transport: HTTPS through Tailscale Serve, no cleartext in release builds. The app sends `Origin` of its configured
  base URL on every POST, never sets a `Tailscale-*` header, and keeps only `vg_login`. No change to the Host,
  identity, Origin, JSON-content-type or cookie rules, and no new route.
- Guards. Any guard rejecting means nothing is sent, "Didn't catch that", listen again; three in a row pause the loop.
  - G1 (phone, mandatory) energy gate: RMS above -55 dBFS and peak above -40 dBFS, before upload.
    Measured on 2026-11-08 on the Nothing Phone (1) (speech RMS -48 / peak -29.5, room -58.6), on the speech span only.
  - G2 (phone, mandatory) Silero gate: at least 0.8 s of voiced frames (prob >= 0.6), voiced at least 40% of the
    utterance, before upload.
  - G3 (phone, mandatory) transcript filter: empty or punctuation only; a known-hallucination phrase; fewer than 2
    words (except answer mode); more than 4 words per voiced second; a phrase repeated 3+ times; only non-Latin
    script when the phone's language is English.
  - G4 (phone and server S2, mandatory) never send text starting with `/` or `!` by voice.
  - G5 (server S2, mandatory) the server refuses voice prompts matching the noise list.
  - G6 (speech service S3, pending the user) Whisper's own VAD and no-speech filter.
  - G7 (server, mandatory, already there) the existing prompt refusals, unchanged.
  - G8 (phone, mandatory) send only to the worktree current when the utterance started.
  - G9 (server S4 and phone, mandatory) every voice prompt carries the clarify instruction; destructive steps are
    asked with `ASK!:` and answered only by tap.
  - G10 (phone, mandatory backstop) no unsend; a large Stop (`POST /api/stop`) and a headset long-press end the turn.
- Clarification loop: a `summary` event with `ask: true` is played, then the mic reopens with no tap ("your turn"
  earcon, 20 s no-speech timeout, one re-say, then paused with the question kept; nothing is ever sent on silence).
  The answer is a new ordinary voice prompt; the agent has the earlier turn in context. Answer mode relaxes G3's
  2-word minimum to one word from `yes`, `no`, `yeah`, `nope`, `cancel`, `stop`, `one`, `two`, `three`; "okay" is
  refused everywhere.
- Approval phrases (user's decision, relaxed 2026-11-09): a plain `ASK:` question (`ask: true`) is approved by a
  plain yes, sure, go ahead or the like. A destructive or hard-to-undo step needs a whole utterance equal, once
  normalised, to one of `yes go ahead`, `yes do it`, `yes please proceed`, `yes thats right`; `ASK!:` steps stay
  tap only. The app never decides anything was approved; it sends the user's words, and the agent applies the rule
  from its instruction (`ApprovalPhrase.approves` mirrors it for labels and tests only). G3's answer-mode word list
  is unchanged, so a lone "sure" or "yep" is still dropped as "didn't catch that".
- Tap-only confirmation: a `summary` event with `ask: "confirm"` (the agent wrote `ASK!:`, or the server upgraded an
  `ASK:` with a destructive word) is played, then the mic stays off and the screen shows Yes and No. Yes sends
  "yes, go ahead", No sends "no, do not do that", both as voice prompts with `"confirm": "tap"` (S5), so the agent
  is told the answer came from a tap and was not misheard. A headset press only re-says it.
- `needs-you` dialogs are answered by tap only in v1; the mic stays off while blocked.
- S1 Refusal reasons. Every JSON error body raised by `/api/prompt`, `/api/keys`, `/api/stop`, `/api/restart` and
  `/api/transcribe` carries `reason`, one of: `bad_text`, `not_listed`, `stopped`, `no_orchestrator`,
  `pane_changed`, `not_settled` (with `status`), `not_blocked` (with `status`), `not_working` (with `status`),
  `draft_present`, `screen_changed` (with `output`, `screen_token` as today), `repeat_window`, `action_running`,
  `herdr_refused`, `herdr_timeout_maybe_sent`, `herdr_failed`, `already_running`, `voice_command`, `voice_noise`,
  `speech_off`, `bad_audio_type`, `audio_empty`, `audio_too_large`, `transcribe_busy`, `transcribe_rate`,
  `speech_failed`, and for restart only `restart_refused` (409) and `restart_failed` (502). Never holds prompt text,
  paths or a service's words. `error` messages and statuses unchanged. The request guards before the route (401,
  403, 411, 415 for JSON, a bad length or bad JSON, 413 for a JSON body) keep today's bodies with no `reason`; the
  client goes by status there. A recording over the cap is 413 `audio_too_large` from the guard as well.
- S2 Voice prompts. `POST /api/prompt` takes an optional `"voice": true`. Absent means false; `true` and `false` are
  accepted; any other value (null, numbers, strings) is 400 `bad_text`. With it: text beginning with `/` or `!`
  (after control characters and leading whitespace are removed) is refused (400, `voice_command`); text that,
  lowercased, with every run of punctuation or whitespace made one space and trimmed, equals an entry of
  `VOICE_NOISE_PHRASES` (`thank you`, `thanks for watching`, `thank you for watching`, `you`, `bye`, `okay`, `so`,
  `please subscribe`, `subtitles by the amara org community`, `i m sorry`) is refused (422, `voice_noise`). Both are
  checked before the session is looked up or locked, so nothing is read from Herdr. Without `voice` the rules are
  unchanged. Near misses ("thank you, now run the tests") are sent.
- S3 (speech service, sam-nix, pending the user) Whisper silence filter: `vad_filter=True`,
  `condition_on_previous_text=False`; segments with `no_speech_prob > 0.6` and `avg_logprob < -1.0` dropped;
  `{"text": ""}` when nothing is left. The gateway already answers that as empty text. Not in this folder.
- S4 Clarify by voice (used only by prompts sent with `"voice": true`, so the page never sees it).
  - `summary_instruction(nonce, voice=True)` puts a clarify clause before the existing text, so the line still ends
    in the placeholder, the tag still appears exactly once, and it stays one line: "(Sent from my phone by voice, so
    the words may be misheard. If the request is unclear, ambiguous, risky, or looks like a speech-recognition
    mistake, do not act on it: change nothing and run nothing, and instead ask one short question, writing your
    spoken line as ASK: followed by the question, ending with a question mark. If the question is whether to do
    something destructive or hard to undo (deleting or overwriting files, branches or data, git push or force push,
    reset or rebase that drops work, dropping or migrating a database, deploying, or anything that spends money),
    write ASK!: instead of ASK:, even if the request itself was clear; such a step is confirmed by a tap on the
    phone, never by voice. A spoken reply to an ASK: question approves the proposed step if it is a plain yes, sure,
    go ahead or the like, unless the step is destructive or hard to undo: then it approves only if it is exactly one
    of: yes go ahead, yes do it, yes please proceed, yes thats right (ignoring case and punctuation), and a bare yes,
    yeah, sure or okay is not approval, so ask again with ASK!:. End your reply with one final line of at most two
    plain sentences, written to be read aloud, with no code, paths or markdown, in exactly this form:
    SPOKEN[nonce]: your spoken summary)" (approval sentence relaxed 2026-11-09). The phrase
    list is built from `VOICE_APPROVAL_PHRASES`, so it reads in its normalised form. With `voice` false (typed
    prompts, the page) the text is byte-identical to today.
  - `PendingSend` gets `voice` (default false), set from the prompt's flag in `arm`.
  - Pure `split_ask(text) -> (ask, text)` with `ask` one of `False`, `True`, `"confirm"`: if the cleaned spoken text
    starts with `ASK!:` (case-insensitive, no space between `ASK` and `!`, surrounding spaces allowed) it returns
    `("confirm", rest)`; else if it starts with `ASK:` it returns `(True, rest)`, upgraded to `"confirm"` when the
    normalised question contains a whole word from `VOICE_DESTRUCTIVE_WORDS` (`delete`, `remove`, `rm`,
    `overwrite`, `force`, `push`, `drop`, `reset`, `rebase`, `wipe`, `purge`, `truncate`, `deploy`, `publish`, `pay`,
    `purchase`, `charge`), a fail-safe backstop (a false positive only costs a tap). In both cases the marker is
    removed, the rest cut to `MAX_SPOKEN_CHARS - 1` at a word, and `?` appended if missing; an empty rest (or only
    question marks) returns no spoken line, `(False, None)`, so the existing `check-screen` fallback applies.
    Anything else returns `(False, text)` unchanged. Applied only for voice records; a typed prompt's spoken line is
    never altered.
  - Pure `normalise_phrase(text)`: lowercase; apostrophes (`'` and `’`) removed (`that's` -> `thats`); every other
    run of characters that are not letters or digits becomes one space; trimmed. Used by the destructive-word check
    and pinned by tests against `VOICE_APPROVAL_PHRASES` (every entry at least 3 words, already normalised).
  - The event stays kind `summary` (the page's name-prefix rule, `/api/audio` keying and acknowledge are
    unchanged); it gains `"ask": true` for a question or `"ask": "confirm"` for a tap-only confirmation. The field
    is absent otherwise. The marker is never spoken; the question mark is.
- S5 Tap confirmation (2026-11-09). `POST /api/prompt` takes an optional `"confirm"`; the only accepted value is
  `"tap"`, anything else (null, other strings, other types) is 400 `bad_text`, checked before Herdr is read. Honoured
  only with `"voice": true` (typed text with it is sent as today). With it, `summary_instruction(nonce, voice=True,
  tap=True)` uses, instead of the clarify clause: "(Sent from my phone by a tap on the Yes or No button shown for
  your last ASK!: or ASK: question, so this reply is certain and was not misheard: it is the user's explicit approval
  or refusal of that step. Ask any new question with ASK: or ASK!: as before. End your reply ... SPOKEN[nonce]: your
  spoken summary)". Nonce, record (`voice` true), voice text checks (S2) and `split_ask` are unchanged.
- Unchanged for the web page: the Host, identity, Origin and JSON-content-type rules; the cookie name, flags and
  lifetime; every route, query parameter and response field (S1 only adds `reason`, S4 only adds `ask`);
  `GET /api/audio` semantics, 416 rule and engine fallback; the transcribe caps; event ids, kinds and acknowledge;
  the page's "speech only fills the box" rule; CSP; `static/` untouched.

Wire format for the Android client (final, as implemented)
- Prompt request: `POST /api/prompt` `{"session": "wt-...", "text": "...", "voice": true}`; typed text sends
  `"voice": false` or leaves it out. The Yes or No tap of a confirmation adds `"confirm": "tap"` (S5). Success is unchanged: `{"ok": true, "draft": "clear"|"present"|"unknown"}`.
- Error body: `{"error": "<message for people>", "reason": "<code>", ...}`. Extra fields by reason: `status` (the
  orchestrator status string, e.g. `"working"`) with `not_settled`, `not_blocked`, `not_working`; `output` and
  `screen_token` with `screen_changed`; `retry_after` (integer seconds, also the `Retry-After` header) with
  `transcribe_busy` (2) and `transcribe_rate`. Statuses by reason: 400 `bad_text`, `voice_command`, `audio_empty`;
  404 `not_listed`, `speech_off`; 409 `stopped`, `no_orchestrator`, `pane_changed`, `not_settled`, `not_blocked`,
  `not_working`, `draft_present`, `screen_changed`, `repeat_window`, `action_running`, `herdr_refused`,
  `already_running`, `restart_refused`; 413 `audio_too_large`; 415 `bad_audio_type`; 422 `voice_noise`; 429
  `transcribe_busy`, `transcribe_rate`; 502 `herdr_timeout_maybe_sent`, `herdr_failed`, `speech_failed`,
  `restart_failed`. A body with no `reason` comes from a request guard (401 log in again, 403, 411, 415, 400, 413).
- Event (in `events` of `GET /api/session` and `GET /api/sessions`): `{"id", "kind", "session", "worktree", "text"}`
  as today, plus `"ask": true` or `"ask": "confirm"` only on a `summary` from a voice prompt whose spoken line was
  a question. No `ask` key otherwise; any other value must be treated as `"confirm"`.

Orchestrator launch and new worktree from the app (approved 2026-11-08; plan in
~/.claude/plans/android-new-worktree.md; adds to "New worktree from the phone" and "Base branch for new worktrees",
which still hold unless said here). This part is the server and `wt`; the Android client is a later task.
- Create answer gains `launch`. The gateway reads the stdout of `wt new` (already captured to a file) and takes the
  **last** line that fully matches
  `wt-launch: model=([a-z0-9.-]{1,64}|default) effort=(low|medium|high|xhigh|max|default) playbook=(yes|no) settings=(yes|no) trust=(written|already|skipped|off)`
  (single spaces, no leading or trailing space, no `\r`). It answers
  `"launch": {"model": "<id>"|"default", "effort": "<level>"|"default", "playbook": bool, "settings": bool, "trust": "written"|"already"|"skipped"|"off"}`,
  or `"launch": null` when no line matches (an older `wt`, no orchestrator config, `wt-launch: restored`, garbage, or
  a line on stderr only). Nothing else from wt's output is passed on, and a bad line is never an error. The page
  ignores the key; `static/` is unchanged.
- Worktree refusals gain `reason` (S1 style, beside today's `error`; messages and statuses unchanged; the request
  guards before the route keep their bodies with no `reason`). Every `RequestRejected` raised by plan, re-plan or
  create carries one:
  400 `bad_query`, `bad_repo` (repo id not usable), `bad_plan` (plan id missing or malformed), `bad_base` (typed base
  empty or too long);
  404 `repo_gone` (the tapped repo is not listed), `plan_gone` (no such plan, or already used);
  409 `worktrees_off`, `interpreter_off`, `no_repos`, `plan_busy`, `plan_expired`, `create_busy`, `repo_unlisted`
  (the planned repo left the list), `not_main_checkout`, `branch_checked_out`, `no_commit` (an existing branch with
  no commit), `target_exists`, `repo_changed` ("changed since the plan"), `branch_unusable`, `base_unusable`,
  `not_ready` (no base picked), `wt_missing` (no wt command), `wt_unrunnable` (wt could not be started);
  502 `git_failed` (local git timed out, could not run, or could not list), `fetch_failed` (with `offline` when there
  is a local copy, exactly as today), `wt_failed`, `wt_timeout`, `no_folder`, `no_session_name`.
  Restart's own reasons (`restart_refused`, `restart_failed`) are unchanged: the wt reasons are set only on the
  create path.
- No change to the child environment: `WT_*` names pass through, `CLAUDE*`, `HERDR_*` and `VOICE_GATEWAY_*` are still
  dropped. No new route, no session name in the plan answer.
- `wt` (Mac only, not in this folder; user-approved edit of `~/.local/bin/wt`, backup at
  `~/.local/bin/wt.pre-orchestrator`). Simplified 2026-11-08 at the user's request: the config file, playbook, guard
  hook, `--settings`, `--effort` and the `wt-launch:` line were removed. Only two differences from the backup remain:
  - A brand-new orchestrator runs `claude --model "${WT_ORCHESTRATOR_MODEL-claude-opus-5-5}"`. Unset: Opus 5.5. Set
    to another name: that model. Set but empty: plain `claude` (opt-out). A value not matching
    `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`: plain `claude` and one `wt:` warning on stderr; `wt new` never fails because
    of it. A restored session types nothing. The user directs agents themselves.
  - Pre-trust: `wt new` (only) runs `~/.local/share/wt/pretrust.py <path>` after `git worktree add`, before the
    session starts; its word is discarded and its result never fails `wt new`.
  - `wt` prints no `wt-launch:` line, so `launch` in the create answer stays optional and is normally `null`.
- Pre-trust (`pretrust.py <path>`): sets `projects["<realpath>"].hasTrustDialogAccepted = true` in `$HOME/.claude.json`
  for that one path. The path must be a directory exactly two levels below `realpath($HOME/worktrees)` and a linked
  git worktree, else `refused` (exit 2). `$HOME/.claude.json` must exist and be a regular file owned by the user,
  else `skipped` (never created). Lock: `mkdir $HOME/.claude.json.lock`, retried for up to 5 s, else `skipped`; a
  lock it did not make is never removed. Already trusted (that path or its main checkout): `already`. Otherwise only
  that key is added, via a private temp file, atomic replace and re-read; a failure leaves the original. Never prints
  a value from the file. No backup copy is made.

## Current

- [x] A0 Spec: the "Android app" section above (auto-send with no cancel window or confirm, guards, the clarification
      loop, the approval-phrase rule, tap-only confirmation for `ASK!:`, S1-S4) and these tasks.
- [x] A1 Server tests first with the fake runner, FakeLocal and the HTTP server: S1 reasons (one test per refusal
      path of prompt, keys, stop, restart, transcribe, plus HTTP bodies and guards keeping theirs), S2 (`voice`
      true/false/invalid, `/`, `!`, each noise phrase and how Whisper writes it, near misses that must pass, nothing
      read from Herdr) and S4 (exact instruction text, typed text byte-identical, `normalise_phrase`, approval list,
      destructive words, `split_ask`, wrapped echo, events with `ask`, typed prompts unchanged). 64 of 73 new tests
      failed first on 3.14 (two of them also had a test bug, fixed: a helper slice and a keyword clash).
- [x] A2 Server: S1, S2 and S4 implemented in `gateway.py`; `static/` unchanged. 767 green on Python 3.14 and the
      macOS system 3.9 (694 before plus 73 new).
- [ ] A3 Android skeleton: wrapper, `settings.gradle.kts`, catalog with pinned versions, `:core` and `:app` build,
      empty Compose screen installs over adb. Not started.
      - [x] Fixtures generated from the gateway's real views: `android/tools/make_fixtures.py` drives the real
            handler in-process with the fake runner (no server, port, Herdr or data/), 55 files in
            `android/core/src/test/resources/fixtures/`, the same on 3.14 and 3.9.
- [x] A4 `:core` models and `HttpGatewayApi` with MockWebServer tests first. 43 of 50 new tests failed first (the
      7 that passed check fixture coverage only); 50 green.
- [x] A5 Pure speech helpers (`Wav`, `UtteranceDetector`, `TranscriptGuard`, `ApprovalPhrase`, `RateBudget`,
      `EngineChain`), tests first. `RateBudget` and `EngineChain` last: 20 of 25 new tests failed first; 25 green.
- [x] A6 `VoiceLoop.step` with the transition table as the test spec first (`core/loop`: 15 phases, 31 inputs,
      20 effects, 7 timers). 104 of 122 new tests failed first (the 18 that passed assert "this input changes
      nothing" or sample coverage); 124 green, including random walks of 80,000 steps over the mic, timer, poll and
      no-auto-send rules.
- [ ] A7 `SecretStore` and `PersistentCookieJar`, then Login and Settings screens. Not started.
- [ ] A8 Audio: `AudioRecordCapture` with Silero, `AudioFocusGate`. Not started.
- [x] A9 Playback: `ExoSpeaker`, `PhoneTtsSpeaker`, `ChainSpeaker`. Not started.
- [x] A10 `LoopController`, `Poller`, `VoiceLoopService` with JVM scenario tests against a fake gateway. Not started.
- [x] A11 UI: `VoiceScreen` states, picker, details sheet, Compose UI tests. Not started.
- [x] W0 Spec: "Orchestrator launch and new worktree from the app" above (server and `wt` part).
- [x] W1 `wt` tests first: `test_wt.py` (48 tests; temp HOME under /tmp, fake herdr, fake claude and zed that
      record any run, real git on scratch repos, the real `~/.claude.json` proven untouched with an audit hook).
      Before the change every test failed except the 3 that pin the default behaviour (no config, byte for byte
      against `wt.pre-orchestrator`, restored with no config), which passed as they must; the 4 installed-file
      tests skipped.
- [x] W2 `~/.local/bin/wt`, `~/.local/share/wt/orchestrator.py`, `~/.local/share/wt/pretrust.py`, the guard and
      settings in `~/.config/wt/` (0700 folder, 0600 files). 48 green on Python 3.14 and the macOS system 3.9.
      `~/.config/wt/orchestrator.conf` not created: the feature is off.
- [x] W3 Gateway tests first (`launch` parsing and the create answer, every worktree `reason`, a source scan that
      every `RequestRejected` in `NewWorktrees` names a reason, HTTP bodies): 29 of 34 (33 new, 1 updated for
      `launch: null`) failed first on 3.14.
- [x] W4 Gateway: `parse_wt_launch`, `launch` in the create answer, reasons on every worktree refusal, create-path
      wt reasons via `_wt_for_create` (restart's reasons untouched). 798 green on Python 3.14 and the macOS
      system 3.9; `static/` unchanged.
- [x] W8 Examples in `examples/`: the playbook as reviewed, `wt-orchestrator.conf.example`, `orchestrator-guard.py`,
      `orchestrator-settings.example.json`; a test checks them for personal names and hostnames.
- [x] W5 Fixtures: `make_fixtures.py` `worktrees()` drives the real handler on scratch git (local bare origin, fixed
      commit dates, repo ids hashed below the scratch home): 27 new files (82 in all), identical on 3.14 and 3.9.
- [x] W6 Android `:core` tests first (models, refusals with `offline`, HTTP with plan 90 s / create 300 s, `CardPhrase`,
      `CardSpeech`, the flow's rows in `VoiceLoopWorktreeTest`, property walks, mapper): 73 failed first; then
      `model/Worktrees.kt`, `worktree/` (flow, rules, phrases, speech), `Phase.NewWorktree`, mapper. 427 green.
- [x] W7 Android `:app`: scenario tests first (9 failed first), then `LoopController` effects, `AppActions`,
      `NewWorktreeCard`, the "New worktree" button and picker entry, previews; Compose tests written and compiled,
      not run (no device). 200 green; debug APK built.
- [x] W9 Reverted W2/W8 to the simplified `wt` (model default Opus 5.5, env override, empty opt-out, pre-trust kept);
      guard, playbook, settings, `orchestrator.py` and `examples/` deleted. `test_wt.py` trimmed to 25 tests: 7
      failed first against the old wt, 25 green on Python 3.14 and the macOS system 3.9.
- [x] W10 Android mic permission: one gate (`core/permission`, `MicPermissionGate`) for every mic-opening action
      (asks, runs the remembered action after Allow, persistent "Microphone permission is needed to listen" with
      Open settings when denied for good); a mic failure keeps the new-worktree flow with an on-screen message; the
      service no longer sends AppStopped when it never reached the foreground (that discarded the flow). Tests first.
- [x] W11 No input after Create (user, 2026-11-08): `HANDOFF_FIRST_PROMPT` false; Create switches to the new
      worktree and stops there idle; no prompt, no Held, no hand-off wait or 120 s limit (hand-off kept behind the
      flag, tested both ways). Tests first.
- [x] C1 Tap confirmation is not a voice command (bug, 2026-11-09): Yes and No of AwaitingTap went out with the
      misheard-voice clause, so the agent refused them. Server S5 (`"confirm": "tap"`, own instruction, validated)
      and app (`Effect.Send.confirmTap`, kept through retry, Held and Send now; `GatewayApi.prompt(confirmTap)`;
      fixtures `prompt_ok_tap`, `prompt_bad_confirm` from `make_fixtures.py`, same on 3.14 and 3.9, other fixtures
      unchanged). Tests first (red, then green); mic still off in AwaitingTap.
- [x] C2 Relaxed approval for plain `ASK:` (2026-11-09): strict phrases only for destructive steps; plain yes for
      plain questions. Gateway instruction text, `ApprovalPhrase.PLAIN` and `approves(text, AskKind)`, mirror test
      on the server text. Tests first.
- [ ] C3 On the phone: an `ASK!:` Yes tap is acted on, and a plain `ASK:` answered "yes" is acted on (pending the
      user; not verified on a device).

## wt prune (2026-11-08)

Spec. `wt prune [--dry-run] [--days N]` (Mac only, user-approved edit of `~/.local/bin/wt`, backup at
`~/.local/bin/wt.pre-prune`; helper `~/.local/share/wt/prune.py`, run with `python3 -I`). Deletes stale Herdr session
RECORDS only; never a worktree, folder or branch.
- Candidates: names `wt-[A-Za-z0-9._-]{0,61}` that `herdr session list` shows not running and that have no
  `herdr session attach <name>` process (wt's `session_attached`). Other names are never passed to herdr.
- Deleted when any holds, checked in this order: (a) folder missing: `workspaces[0].identity_cwd` of the saved
  `session.json`, absolute, and stat says it does not exist; (b) idle more than N days (default 7, `--days` 1-3650):
  idle age = now minus the newest mtime of the regular files directly in `~/.config/herdr/sessions/<name>/`
  (herdr-server.log, herdr-client.log, session.json, any backups) and `<name>.server.log`; no such file = rule off;
  (c) fully pushed: `git branch -r --contains HEAD` non-empty (read-only git, 10 s timeout per call, no fetch,
  `--no-optional-locks`); no remote = not pushed. Uncommitted changes do not block; their count is shown.
- Mutations (real run only): `herdr session delete <name>` after a fresh list shows it still stopped (else
  skipped), removing `<name>.server.log`, one line per deleted record appended to `~/.local/state/wt/prune.log`
  (dir 0700, file 0600; date, name, folder, branch, full sha), lock dir `~/.local/state/wt/prune.lock` (never
  broken). `--dry-run` takes no lock, writes nothing and runs only `herdr session list`, ps and read-only git.
- Report: one line per wt-* session (name, action, reason, branch, short sha, uncommitted count), then totals. No
  paths, no file contents. Exit 0 ok, 1 error (lock held, herdr list failed, any delete failed; the rest still
  run), 2 usage.

- [x] P0 Spec (above).
- [x] P1 Failing tests first in `test_wt.py` (fake herdr that fails on anything but `session list` and
      `session delete <name>`, fake ps, git wrapper that logs subcommands, scratch repos with local bare remotes):
      24 of 26 new tests failed first on 3.14 and 3.9 (the 2 that passed: usage exit 2, which old wt also gave,
      and the diff against the backup, trivially true before the change).
- [x] P2 `prune.py` (pure `decide` plus thin effects) and the `prune` case in wt. 51 green on Python 3.14 and the
      macOS system 3.9.
- [x] P3 Real `wt prune --dry-run` (2026-11-08, read-only, nothing created): 38 wt-* sessions, would delete 33
      (4 folder missing, 19 idle, 10 fully pushed), kept 5 (3 running, 2 recent and not pushed / not git).
- [ ] P4 The real `wt prune` is the user's to run.

### wt prune --worktrees (2026-11-08)

Spec. `wt prune --worktrees [--dry-run] [--days N]` (user-approved; backup `~/.local/bin/wt.pre-worktrees`). Runs the
record pass above unchanged, then a folder pass in `~/.local/share/wt/prune_worktrees.py` (loaded by `prune.py` by
path only when the flag is given, so `prune.py` itself keeps no worktree command). Without the flag nothing changes.
- Candidates: every directory entry exactly at realpath(`~/worktrees`)/<repo>/<name>; plain files are ignored.
- Removed only when ALL hold, else kept with the first failing reason, checked in this order: not a symlink; a
  linked worktree (`.git` regular file starting `gitdir:`; a `.git` folder = "main checkout"); its main checkout
  (dirname of commondir) is a normal repo; listed by `git -C <main> worktree list --porcelain`, not its first entry,
  not locked, not prunable; no `.gitmodules` / gitdir `modules`; no rebase, merge, cherry-pick, revert or bisect in
  progress; HEAD readable; no running and no attached Herdr session whose saved folder is the folder (or inside it)
  or whose name is the one wt derives for it (any session name, not only wt-*); `git status --porcelain
  --untracked-files=all` empty (dirty N files); every `--ignored=matching` path under a regenerable folder
  (node_modules .next .nuxt dist build out target .gradle .turbo .cache coverage __pycache__ .venv venv .pytest_cache
  .mypy_cache .dart_tool .angular), else "ignored outside safe list: <first name> (+N)"; a remote exists; HEAD in
  `git branch -r --contains HEAD` (detached counts, reported); no process cwd inside it per one
  `lsof -a -d cwd -Fn -w` (15 s timeout; lsof missing, failing or timing out = kept "in use (lsof unavailable)").
  Git reads: `--no-optional-locks`, `core.fsmonitor=false`, `core.hooksPath=/dev/null`, no GIT_* env, timeouts.
- Removal (real run, under the existing prune lock): re-gather and re-decide that one folder right before
  (skip "changed since check" if anything differs: decision, HEAD or branch), then only
  `git -C <main> worktree remove <path>` (never --force); on failure "failed: git worktree remove refused", exit 1,
  continue. After a removal: wt-* records for that folder that a fresh list shows stopped are deleted as the record
  rules do (`herdr session delete`, server log, record log line). Never branch delete, `worktree prune`, rm -rf,
  `wt rm/down`, `session stop`, ~/.claude, or the ~/worktrees/<repo> parent.
- Log: one tab-separated line per removal in `prune.log`: ISO date, `worktree`, path, main, branch, full sha,
  `git -C <main> worktree add <path> <branch|sha>` (shell-quoted).
- Report: second table WORKTREE (<repo>/<name>), ACTION (removed / would remove / kept / failed), REASON, BRANCH,
  HEAD; totals. `--dry-run`: no lock, nothing created or removed.

- [x] W0 Spec (above). Backups: `~/.local/bin/wt.pre-worktrees`, scratchpad copy of `prune.py`.
- [x] W1 Failing tests first (real scratch worktrees under a temp HOME's ~/worktrees, git shim recording argv, fake
      lsof): 33 of 34 new tests failed on 3.14 and 3.9 (the one passing: "without the flag nothing runs").
- [x] W2 `prune_worktrees.py`, `--worktrees` in `prune.py`, help lines in wt. 85 green on 3.14 and 3.9.
- [x] W3 Real `wt prune --worktrees --dry-run` (2026-11-08, read-only, nothing created): 28 folders, would remove 5,
      kept 23 (12 ignored outside safe list, 5 dirty, 3 main checkout not found, 2 not a git folder).
- [ ] W4 The real `wt prune --worktrees` is the user's to run.

## Not done, pending the user

- [ ] Orchestrator launch live checks (scratch repo under a scratch root, local bare origin, at most 4 real prompts):
      - [ ] L1 A new `wt new` session shows Opus 5.5 (`/model` in the orchestrator tab).
      - [ ] L2 `WT_ORCHESTRATOR_MODEL= wt new ...` types plain `claude`.
      - [ ] L3 Pre-trust: no trust dialog on the new worktree; `~/.claude.json` still 0600 and parses (keys only,
            never values).
      - [ ] L5 Restore: stop the scratch session, `wt attach`, then `/model` again. Record what Herdr kept.
      - [ ] L7-L10 Phone flow, near misses, fetch failure, create time over Serve (after the Android task).
      - [ ] Clean-up: `wt down` and `wt rm` per scratch worktree, scratch repos removed.

- [ ] Local speech: start `~/voice-models/speech/start.sh` on the speech host; store its token on the Mac with
      `ssh <speech-host> cat ~/voice-models/speech/token | python3 gateway.py --set-speech-token`; restart the gateway on
      8766 with `VOICE_GATEWAY_SPEECH_URL=http://<speech-host-ip>:8790`. Then try Chatterbox and Local Whisper on the
      real phone: the phone's mic and speaker, Android Chrome's MediaRecorder type, the mic permission over the
      tailnet HTTPS page.
- [ ] Headless Chrome check that the web page is unchanged with the new server (plan A2). Not run: no gateway was
      started for this work. The tests pin typed prompts to today's exact instruction text.
- [ ] Approve the Android app's server wording: S1 reasons, S2 noise list, and S4's instruction text in particular
      (the action-type list, the approval phrases, `VOICE_DESTRUCTIVE_WORDS`).
- [ ] Decide S3 (replaces "Whisper's habit of inventing text"): the Whisper silence filter in
      `~/voice-models/speech/stt_worker.py` on sam-nix, only with the user's go-ahead. Until then G1-G5 carry the load.
- [ ] A12 Live on the real phone over the tailnet (one scratch worktree, a small capped number of real prompts):
      login over Serve, a full hands-free turn screen-off, silence and noise produce no send (zero `/api/prompt` in
      the gateway log), a vague request ("fix it") gets a spoken question and the spoken answer completes the turn,
      silence after a question pauses without a send, deleting a scratch file gets an `ASK!:` confirmation that keeps
      the mic off until a Yes tap (count the agent's own `ASK!:` against the destructive-word upgrade), a lone "yes"
      to an approval question gets the question again, tap-to-interrupt, a dialog answered by tap after the
      look-at-phone cue, Chatterbox -> Deepgram -> phone voice fallback, gateway restart -> re-login, Tailscale off/on
      mid-turn, battery over 30 minutes.
- [ ] Set a real passcode: `python3 gateway.py --set-passcode`.
- [ ] Restart the gateway on 8766 to pick up the streamed voice, the Flux default (flux-cole-en), no kept Deepgram
      connection and new worktrees (repos under ~/work and ~/personal, `claude -p` with claude-haiku-5-5). The page
      files already changed: until the restart, `GET /api/audio` and the New worktree routes answer 404 from the old
      server. For Aura instead, start it with `VOICE_GATEWAY_DEEPGRAM_VOICE=aura-2-thalia-en`.
- [ ] Try "New worktree" on the real phone, including a spoken request and the trust dialog on the new session.
- [ ] Restart the gateway on 8766 for the base branch. First real Create: the fetch over the real remote (SSH agent
      from the gateway's own environment) has not been tried; only ls-remote was, from a terminal.
- [ ] Hear it on the real phone: Deepgram audio, the fallback, the unlock during Send, the wake lock with the screen off,
      and that Android Chrome plays MP3 through MediaSource over the tailnet as headless Chrome did on the Mac.
- [ ] Try it on the real phone.
- [ ] Copy to NixOS when wanted (not part of this work).
- [ ] `wt reap` still stops unattached idle sessions after 10 minutes, and watching from the phone does not count as
      attached. A session waiting on a dialog no longer counts as idle.

## Completed

- [x] T0 Archived the v1 gateway.py, test_gateway.py, static/ and TASKS.md to archive-v1/.
- [x] T1 Probed the real CLI on a scratch session. `--` is taken as the text itself, so it must not be used; text that
      begins with `-` gets a leading space instead.
- [x] T2 Failing tests with a fake runner: 209 of 213 failed against an empty interface skeleton.
- [x] T3 New gateway.py, green on Python 3.14 and the macOS system 3.9.
- [x] T4 New page: login, list, detail.
- [x] T5 Live tests on the scratch session, port 8767. Each fix below had a failing test first:
      - `wt up` leaves a shell holding stdout, so its output now goes to files instead of pipes.
      - CLAUDE_CODE_* markers leaked into a restarted server and turned off transcripts, so all CLAUDE* names are removed.
      - Reading past the screen redraws attached clients, so polling stays within the screen.
      - Herdr says idle for over a second after a restore, with a shell in the foreground, so counter 0 means unknown.
- [x] T6 Cleaned up: no process left, scratch session and repo removed, no Serve config.
- [x] T7 Edited `wt`. A scratch session on a dialog, unattached, survived 4 real sweeps over 17 minutes with no marker;
      once idle it got a marker on the next sweep and was stopped 10 minutes later. A real restart removed the marker.
- [x] T8 Probed real dialogs and input boxes. Tokens stayed byte-identical while nothing changed. Styled reads separate
      the placeholder from typed text in every case tried: empty, one line, multi-line, long, pasted, words identical to
      the placeholder.
- [x] T9 Failing tests with the fake runner: 98 of 313 for summaries and tokens, then 22 of 335 for half-typed text.
- [x] T10 Implemented server and page, green on Python 3.14 and the macOS system 3.9.
- [x] T11 Live on port 8767 and headless Chrome at phone size, 6 of 6 Claude prompts used, then cleaned up.
- [x] T12 Spec for the finish after a dialog, the reap marker and the Deepgram voice (2026-11-08).
- [x] T13 Failing tests with fakes: 94 of 434 failed before implementing (fake runner, temp reap dirs, a local fake
      Deepgram server, and a guard that lets tests connect to loopback only).
- [x] T14 Implemented server and page. 434 green on Python 3.14 and the macOS system 3.9.
- [x] T15 Live on 8767 with headless Chrome at phone size, `--autoplay-policy=user-gesture-required`, Deepgram
      replaced by a loopback fake, 4 of 4 Claude prompts behind a herdr guard. A real reap sweep wrote the marker and
      a Send removed it 0.39 s later. A dialog gave `needs-you`, then its finish gave a `summary`. Deepgram audio
      played 30 s after the last tap. Failed fetch, unplayable audio and mute all behaved. No CSP violations. Cleaned
      up.
- [x] T16 Spec for the streamed voice, the kept connection and the page (2026-11-08).
- [x] T17 Failing tests with fakes (chunked loopback Deepgram with gates and misbehaviour, gated fake voice, the HTTP
      route): 56 of 467 failed first. Each later finding had failing tests first: renewal and age (2), failure memory
      and Range (5), MediaSource page (5), TLS 1.3 session tickets taken for a close (2 on 3.14, 1 on 3.9).
- [x] T18 Implemented server and page. 479 green on Python 3.14 and the macOS system 3.9.
- [x] T19 Live on 127.0.0.1:8767, fake herdr, the real key read in place, 9 of 10 counted Deepgram calls, headless
      Chrome 157 at 390x844 with `--autoplay-policy=user-gesture-required`, a 118-character summary. From the text
      appearing to the audio playing: old code 3.08 s and 4.61 s; new, cold connection 0.98 s; new, warm 0.48 s and
      0.47 s; new, warm, plain src (no MediaSource) 2.87 s. A 299-character summary started at 0.40 s and was all in
      after 5.5 s, inside the 10 s deadline. Found on the way: Deepgram's 408 at 5.2 s, Chrome's 32 KB start, Chrome's
      own retries of a failed clip, and TLS 1.3 session tickets.
- [x] T20 Spec for the Flux voice (above).
- [x] T21 Failing tests with fakes: the loopback Deepgram fake answers `/v1/speak` for Aura names and `/v2/speak` for
      Flux names only; the voice, stream, keeper and audio-route suites run once per endpoint. 85 of 533 failed
      first, the same on 3.14 and 3.9.
- [x] T22 Implemented in gateway.py. 533 green on Python 3.14 and the macOS system 3.9.
- [x] T23 Live on 127.0.0.1:8767, fake herdr, the real key read in place, 8 of 8 counted Deepgram requests, headless
      Chrome 157 at 390x844 with `--autoplay-policy=user-gesture-required` and a real tap, the 118-character
      summary. From the text appearing to `playing`: flux-cole-en warm 0.69 s and 0.59 s, cold 1.54 s; Aura
      (aura-2-thalia-en) warm the same day 0.39 s (0.47-0.48 s and cold 0.98 s on the earlier day). Deepgram's first
      byte after the request: Flux 0.44-0.52 s, Aura 0.32 s. TLS handshakes were 0.7-0.97 s on TLS 1.2 this time.
      `--test-voice` worked for flux-cole-en on /v2/speak and for aura-2-thalia-en on /v1/speak.
      Not seen this time, for either voice: the media clock stayed at 0 s after `playing`, so `ended` never came and
      the events were never acknowledged (the same with an Aura clip in a dry run, with the page unchanged; the Mac
      kept dropping into maintenance sleep). The phone is the check for whole-clip playback.
- [x] T24 Spec for no kept Deepgram connection and new worktrees from the phone (2026-11-08).
- [x] T25 Failing tests with fakes (real git on scratch repos behind the fake runner, a fake `wt new`, fake and
      recording interpreters): 96 of 588 failed first on 3.14. Later findings each had a failing test first: main's
      start-up line assumed the claude interpreter (1), and the page hid the text box in the new view (1).
- [x] T26 Implemented server, interpreter and page. 590 green on Python 3.14 and the macOS system 3.9.
- [x] T27 Live. Discovery: 37 repos under ~/work and ~/personal. `claude -p` (5 of 6 allowed calls, plan step only,
      nothing created): 3.3-4.1 s each; a clear request planned, an ambiguous one first guessed and then, with the
      prompt told not to guess on a shared prefix, asked between the two; no repo named gave 8 candidates and a
      question. End to end on 127.0.0.1:8767 with a scratch root, the fake interpreter, a herdr guard (scratch
      sessions only, at most 2 prompts, 1 used), headless Chrome at 390x844: Plan, Create (`wt new` in about 1 s,
      WT_EDITOR=none), detail view with the request in the box unsent, trust dialog answered with Down and Enter,
      a plugin's setup question dismissed with Esc, one prompt sent and its summary spoken. Cleaned up.
- [x] T28 Spec for the base branch (2026-11-08).
- [x] T29 Failing tests with fakes and real scratch git (a local bare repo as origin with example-frontend-like
      branches, real fetch and ls-remote against it): 45 of 630 failed first on 3.14. Later each had a failing test
      first: the carried "started from" note was wiped by an earlier poll error (1, found live).
- [x] T30 Implemented server, interpreter and page. 631 green on Python 3.14 and the macOS system 3.9.
- [x] T31 Live on the real repo list, plan step only, 4 of 4 `claude -p` calls (2.98-4.22 s): "from production-4.6.2"
      and "from production four point six point two" both gave origin/production-4.6.2 c1c08c02; "from production
      4.6" gave taps for 4.6.0 to 4.6.3 and picked nothing (one read-only ls-remote to the real origin); "off dev" in
      example-agent gave origin/dev. Only read-only git ran; no fetch on any real repo.
- [x] T32 Scratch end to end on 127.0.0.1:8767 (local bare origin, real `wt new`, WT_EDITOR=none, fake interpreter,
      headless Chrome 157 at 390x844): a spoken base, a commit pushed after the plan taken by the fetch and reported
      as moved; the remembered base as the next default; close-match taps; a failed fetch refused with the
      downloaded-copy tap, which created from the old copy. Sessions stopped with `wt down`, worktrees removed,
      scratch repos deleted.
- [x] T33 Spec for local speech (above).
- [x] T34 Failing tests with fakes (a loopback fake speech service with token check, chunked TTS, STT and health; a
      fake local voice with transcribe; the Deepgram fake; the fake runner): 70 of 694 failed first on 3.14.
- [x] T35 Speech service in ~/voice-models/speech on the speech host: server.py (front), tts_worker.py, stt_worker.py,
      protocol.py, start.sh, and test_server.py with fake_worker.py: 27 green (Python 3.11 of the uv env). These
      tests were written next to the front rather than strictly before it.
- [x] T36 Gateway and page. 694 green on Python 3.14 and the macOS system 3.9.
- [x] T37 Live, 2026-11-08. Reach: the Mac reached <speech-host-ip>:8790 with the firewall as it is (no tunnel). Warm-up:
      Whisper 4.0-4.6 s, Chatterbox 12.6-12.7 s; GPU 2.2 GB (STT) + 3.1 GB (TTS). TTS straight to the service, 3 runs
      each, first byte / whole: 79 characters 0.26-0.34 / 1.20-1.26 s, 120 characters 1.15-1.22 / 1.97-2.04 s, 294
      characters 0.48-0.60 / 4.78-4.93 s; every answer decoded with no error, one MP3 stream. Page (headless Chrome
      157, 390x844, `--autoplay-policy=user-gesture-required`, real taps, fake herdr on 127.0.0.1:8767): text shown
      to `playing` 0.39 s (79), 1.20, 1.22, 1.60 s (120), 0.60 s (294); each clip played to its end with no stall.
      Local Whisper with Chrome's fake mic (4 Chatterbox-made request clips): stop tap to text 0.42-0.52 s, service
      0.27-0.36 s. Fallbacks with the service stopped: Chatterbox 502 then Deepgram played (2 of 4 allowed Deepgram
      calls used); Deepgram failing or off then the phone's voice, each with its note; Whisper down gave an error and
      left the box alone. Ctrl+C (SIGINT to the group) and a dropped SSH session both left no process and freed the
      GPU.
