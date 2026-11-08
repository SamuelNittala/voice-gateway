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

## Current

Nothing in progress.

## Not done, pending the user

- [ ] Local speech: start `~/voice-models/speech/start.sh` on the speech host; store its token on the Mac with
      `ssh <speech-host> cat ~/voice-models/speech/token | python3 gateway.py --set-speech-token`; restart the gateway on
      8766 with `VOICE_GATEWAY_SPEECH_URL=http://<speech-host-ip>:8790`. Then try Chatterbox and Local Whisper on the
      real phone: the phone's mic and speaker, Android Chrome's MediaRecorder type, the mic permission over the
      tailnet HTTPS page.
- [ ] Decide on Whisper's habit of inventing text ("Thank you.") for a silent recording: seen live with silent input;
      it only fills the box, but a VAD filter in stt_worker.py would make it empty. Not changed.
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
