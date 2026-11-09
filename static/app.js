"use strict";

// Everything from the server reaches the DOM through textContent. The page only ever sends a session name, or for a
// new worktree the words typed or said, a repo id and a plan id: never a path.
const $ = (id) => document.getElementById(id);
const LIST_POLL_MS = 5000;
const DETAIL_POLL_MS = 2000;
const STICK_TO_BOTTOM_PX = 40;
const DRAFT_UNKNOWN = "Text already in the box at the Mac would be sent too.";
const DRAFT_REFUSED = "There is unsent text in the box at the Mac, so sending will be refused.";

const state = {
  view: "list",       // "list", "detail" or "new".
  session: null,      // Name of the open worktree, or null for the list.
  stoppedOpen: false, // Whether the stopped group is expanded.
  listText: null,     // Last list rendered, so polling does not rebuild an unchanged page.
  renderedOutput: null,
  actions: null,
  screenToken: null,  // Token of the screen on show. A key press carries it, so it answers the dialog the user saw.
  waiting: false,     // Whether any send from the phone has yet to be announced.
  generation: 0,      // Answers to older requests are dropped after moving between views.
  timer: null,
  busy: false,
  planId: null,       // The plan on show, which the Create tap uses.
  planQuery: null,    // The words it was planned from, carried into the new worktree's text box.
  carryText: null,    // Text for the text box of the next view opened.
  carryBanner: null,  // A note for the next view opened: which commit a new worktree started from.
};

function showBanner(message) {
  $("banner").textContent = message || "";
  $("banner").hidden = !message;
}

async function api(method, path, body) {
  const options = { method, headers: {} };
  if (body !== undefined) {
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  if (response.status === 401) {
    window.location.replace("/login");
    throw new Error("Log in first.");
  }
  let data = {};
  try { data = await response.json(); } catch (_e) { data = {}; }
  if (!response.ok) {
    const error = new Error(data.error || `Request failed (${response.status}).`);
    error.status = response.status;
    error.data = data;
    throw error;
  }
  return data;
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

// ---- Agents

function agentLine(agent) {
  const item = element("li", agent.orchestrator ? "agent orchestrator" : "agent");
  item.append(element("span", "agent-name", agent.name));
  if (agent.pane_label && agent.pane_label !== agent.name) {
    item.append(element("span", "agent-label", agent.pane_label));
  }
  item.append(element("span", `status status-${agent.status}`, agent.status));
  return item;
}

function agentList(agents, className) {
  const list = element("ul", className);
  for (const agent of agents) list.append(agentLine(agent));
  return list;
}

// ---- List

function sessionCard(session) {
  const button = element("button", "card");
  button.type = "button";
  const top = element("span", "card-top");
  top.append(element("span", "name", session.name));
  top.append(element("span", `tag tag-${session.state}`, session.state));
  button.append(top);
  if (session.error) button.append(element("span", "error", session.error));
  if (session.agents.length) button.append(agentList(session.agents, "agents"));
  else if (session.state !== "stopped" && !session.error) button.append(element("span", "hint", "No agents"));
  button.addEventListener("click", () => { window.location.hash = encodeURIComponent(session.name); });
  return button;
}

function group(title, sessions) {
  const box = element("div", "group");
  box.append(element("h2", "", `${title} (${sessions.length})`));
  for (const session of sessions) box.append(sessionCard(session));
  return box;
}

function stoppedGroup(sessions) {
  const box = element("details", "group");
  box.open = state.stoppedOpen;
  box.addEventListener("toggle", () => { state.stoppedOpen = box.open; });
  box.append(element("summary", "", `Stopped (${sessions.length})`));
  for (const session of sessions) box.append(sessionCard(session));
  return box;
}

function renderList(view) {
  const text = JSON.stringify([view.attached, view.running, view.stopped, view.other_url]);
  if (text === state.listText) return;
  state.listText = text;
  const box = $("groups");
  box.textContent = "";
  if (view.attached.length) box.append(group("Attached", view.attached));
  if (view.running.length) box.append(group("Running", view.running));
  if (view.stopped.length) box.append(stoppedGroup(view.stopped));
  if (!view.attached.length && !view.running.length && !view.stopped.length) {
    box.append(element("p", "hint", "No worktree sessions."));
  }
  $("other").hidden = !view.other_url;
  if (view.other_url) $("other").href = view.other_url;
}

// ---- Detail

function renderOutput(text) {
  const pre = $("output");
  pre.hidden = !text;
  if (text === state.renderedOutput) return;
  const atBottom = pre.scrollHeight - pre.scrollTop - pre.clientHeight < STICK_TO_BOTTOM_PX;
  pre.textContent = text;
  state.renderedOutput = text;
  if (atBottom) pre.scrollTop = pre.scrollHeight;
}

function stateLine(view) {
  if (view.state === "stopped") return "stopped";
  const status = view.orchestrator ? `orchestrator: ${view.orchestrator.status}` : "no orchestrator";
  return `${view.state}, ${status}`;
}

function sendNote(view) {
  if (view.actions.prompt || !view.orchestrator) return "";
  return `Sending is off while the orchestrator is ${view.orchestrator.status}.`;
}

function renderDetail(view) {
  state.actions = view.actions;
  state.screenToken = view.screen_token || null;
  $("detail-name").textContent = view.name;
  $("detail-state").textContent = stateLine(view);
  $("problem").textContent = view.problem;
  $("problem").hidden = !view.problem;
  const agents = $("detail-agents");
  agents.textContent = "";
  for (const agent of view.agents) agents.append(agentLine(agent));
  renderOutput(view.output);
  $("keys").hidden = view.actions.keys.length === 0;
  $("stop").hidden = !view.actions.stop;
  $("compose").hidden = !view.orchestrator;
  $("send").disabled = state.busy || !view.actions.prompt;
  const note = sendNote(view);
  $("send-note").textContent = note;
  $("send-note").hidden = !note;
  $("draft-warning").hidden = !view.actions.prompt || view.draft === "clear";
  $("draft-warning").textContent = view.draft === "present" ? DRAFT_REFUSED : DRAFT_UNKNOWN;
  $("earlier-toggle").hidden = !view.actions.prompt && $("earlier").hidden;
  $("restart-box").hidden = !view.actions.restart;
  if (!view.actions.restart) $("restart-ask").hidden = true;
}

// ---- Polling, only while the page is visible

function schedule() {
  clearTimeout(state.timer);
  state.timer = null;
  if (document.hidden) return;
  state.timer = setTimeout(refresh, state.session ? DETAIL_POLL_MS : LIST_POLL_MS);
}

async function refresh() {
  clearTimeout(state.timer);
  const generation = ++state.generation;
  const session = state.session;
  try {
    const path = session ? `/api/session?name=${encodeURIComponent(session)}` : "/api/sessions";
    const view = await api("GET", path);
    if (generation !== state.generation) return;
    if (session) renderDetail(view);
    else if (state.view === "list") renderList(view);
    announce(view);
    if ($("banner").dataset.poll) { showBanner(""); delete $("banner").dataset.poll; }
  } catch (error) {
    if (generation !== state.generation) return;
    showBanner(error.status ? error.message : "Cannot reach the gateway. Retrying.");
    $("banner").dataset.poll = "1";
  }
  schedule();
}

function route() {
  const name = decodeURIComponent(window.location.hash.slice(1));
  const view = name === "new" ? "new" : name ? "detail" : "list";  // Session names all start with "wt-".
  const changed = view !== state.view || (view === "detail" ? name : null) !== state.session;
  state.view = view;
  state.session = view === "detail" ? name : null;
  $("list-view").hidden = view !== "list";
  $("detail-view").hidden = view !== "detail";
  $("new-view").hidden = view !== "new";
  if (changed) {
    stopListening();
    delete $("banner").dataset.poll;  // An older poll error must not clear the note carried into this view.
    showBanner(state.carryBanner || "");
    state.carryBanner = null;
    state.renderedOutput = null;
    state.actions = null;
    state.screenToken = null;
    $("text").value = state.carryText || "";  // Only ever filled, never sent: Send is its own tap.
    state.carryText = null;
    hidePlan();
    $("output").textContent = "";
    $("output").hidden = true;
    $("restart-ask").hidden = true;
    showEarlier("");
    for (const id of ["keys", "stop", "compose", "restart-box", "problem", "earlier-toggle"]) $(id).hidden = true;
    composeFor(view);
    $("detail-name").textContent = state.session || "";
    $("detail-state").textContent = "";
    $("detail-agents").textContent = "";
    window.scrollTo(0, 0);
  }
  if (changed && view === "new") loadSetup();
  refresh();
}

// The one text box: Send in a worktree's detail view, Plan in the new worktree view.
function composeFor(view) {
  const making = view === "new";
  $("send").hidden = making;
  $("plan").hidden = !making;
  $("draft-warning").hidden = true;
  $("send-note").hidden = true;
  $("compose").hidden = !making;  // In the detail view, renderDetail shows it once there is an orchestrator.
  const label = making ? "Which repo, and what the worktree is for" : "Message for the orchestrator";
  $("text").placeholder = label;
  $("text-label").textContent = label;
}

// ---- Earlier output: only on a tap, because reading past the screen redraws it for whoever is attached.

function showEarlier(text) {
  $("earlier").textContent = text;
  $("earlier").hidden = !text;
  $("earlier-toggle").textContent = text ? "Hide earlier output" : "Earlier output";
}

async function onEarlier() {
  if (!$("earlier").hidden) {
    showEarlier("");
    return;
  }
  const session = state.session;
  try {
    const view = await api("GET", `/api/session?name=${encodeURIComponent(session)}&earlier=1`);
    if (session === state.session) showEarlier(view.output);
  } catch (error) {
    showBanner(error.message);
  }
}

// ---- Actions. The server decides again on every one of these; the page only hides what would be refused.

async function act(path, body) {
  if (state.busy) return false;
  state.busy = true;
  showBanner("");
  let done = false;
  try {
    const answer = await api("POST", path, { session: state.session, ...body });
    done = true;
    if (answer.draft && answer.draft !== "clear" && $("draft-warning").hidden) showBanner(DRAFT_UNKNOWN);
  } catch (error) {
    showBanner(error.message);
    if (error.data && error.data.screen_token !== undefined) {  // The dialog moved on: show the one to answer now.
      renderOutput(error.data.output);
      state.screenToken = error.data.screen_token;
    }
  }
  state.busy = false;
  refresh();
  return done;
}

async function onSend() {
  const text = $("text").value;
  if (!text.trim()) {
    $("text").focus();
    return;
  }
  unlockSpeech();
  $("announce").hidden = true;
  stopListening();
  $("send").disabled = true;
  if (await act("/api/prompt", { text })) $("text").value = "";
}

function askRestart(show) {
  $("restart-ask").hidden = !show;
  $("restart").hidden = show;
}

async function onRestartConfirm() {
  askRestart(false);
  $("restart").disabled = true;
  showBanner("Restarting...");
  await act("/api/restart", {});
  $("restart").disabled = false;
}

// ---- New worktree. The gateway works out the repo and the branch; nothing is created without the Create tap.

async function loadSetup() {
  try {
    const setup = await api("GET", "/api/worktree/setup");
    if (state.view !== "new") return;
    $("new-note").textContent = setup.message || "";
    $("new-note").hidden = !setup.message;
    $("plan").disabled = !setup.enabled || !setup.interpreter;
  } catch (error) {
    showBanner(error.message);
  }
}

function hidePlan() {
  state.planId = null;
  $("plan-card").hidden = true;
  $("candidates").hidden = true;
  $("candidate-list").textContent = "";
  $("plan-bases").textContent = "";
  $("plan-base-input").value = "";
  $("plan-offline").hidden = true;
  $("plan-create").hidden = false;
}

async function onPlan(repoId) {
  const query = $("text").value.trim();
  if (!query) {
    $("text").focus();
    return;
  }
  await askForPlan(repoId ? { query, repo_id: repoId } : { query }, query);
}

// A base tap, or a base typed on the card: the same repo and branch planned again with that base. No model call.
async function onBase(base) {
  if (!state.planId || !base) return;
  await askForPlan({ plan_id: state.planId, base }, state.planQuery);
}

async function askForPlan(body, query) {
  if (state.busy) return;
  state.busy = true;
  stopListening();
  showBanner("");
  if (body.plan_id === undefined) hidePlan();  // A base tap keeps the card on show until the new plan is in.
  $("plan").disabled = true;
  $("plan").textContent = "Working it out...";
  try {
    const answer = await api("POST", "/api/worktree/plan", body);
    if (state.view === "new") {
      hidePlan();
      showPlan(answer, query);
    }
  } catch (error) {
    showBanner(error.message);
  }
  state.busy = false;
  $("plan").disabled = false;
  $("plan").textContent = "Plan";
}

const BASE_HOW = { remembered: " (last used here)", "origin-head": " (origin/HEAD)" };

function showPlan(answer, query) {
  if (answer.kind === "plan") {
    const base = answer.base;
    state.planId = answer.plan_id;
    state.planQuery = query;
    $("plan-repo").textContent = `${answer.repo.name} (${answer.repo.where})`;
    $("plan-branch").textContent = `Branch: ${answer.branch}`;
    if (answer.mode === "existing") {
      $("plan-mode").textContent = "Existing branch, checked out as it is";
      $("plan-base").textContent = `${base.sha} ${base.subject}`;
      showBaseNote("The base does not apply: the existing branch is checked out as it is.");
      $("plan-fetch-note").hidden = true;
      $("plan-base-other").hidden = true;
      showChoices([], null);
    } else {
      $("plan-mode").textContent = base.ref
        ? `New branch from ${base.ref}${BASE_HOW[base.how] || ""}`
        : "New branch: pick a base";
      $("plan-base").textContent = base.ref ? baseLine(base) : "";
      showBaseNote(base.message);
      $("plan-fetch-note").textContent = "Fetched from origin just before creating.";
      $("plan-fetch-note").hidden = !base.ref;
      $("plan-base-other").hidden = false;
      showChoices(base.choices, base.name);
    }
    $("plan-path").textContent = answer.path;
    $("plan-create").disabled = !answer.ready;
    $("plan-card").hidden = false;
    return;
  }
  // Not sure, or not understood: the repos as taps. A tap plans again with that repo and the same words.
  $("candidates-question").textContent = answer.reason ? `${answer.question} (${answer.reason})` : answer.question;
  const list = $("candidate-list");
  list.textContent = "";
  for (const repo of answer.candidates) {
    const button = element("button", "wide", `${repo.name} (${repo.where})`);
    button.type = "button";
    button.addEventListener("click", () => onPlan(repo.id));
    list.append(button);
  }
  $("candidates").hidden = false;
}

function dateText(seconds) {
  return seconds ? new Date(seconds * 1000).toLocaleString([], { dateStyle: "medium", timeStyle: "short" }) : "";
}

// The local copy's date: when it was last downloaded, or else when its commit was made.
function copyDate(base) {
  if (base.fetched_at) return `downloaded ${dateText(base.fetched_at)}`;
  if (base.committed_at) return `commit of ${dateText(base.committed_at)}`;
  return "";
}

function baseLine(base) {
  if (!base.local) return `${base.sha} on origin, not downloaded here yet`;
  const date = copyDate(base);
  return `${base.sha} ${base.subject}${date ? `; local copy: ${date}` : ""}`;
}

function showBaseNote(message) {
  $("plan-base-note").textContent = message || "";
  $("plan-base-note").hidden = !message;
}

function showChoices(names, current) {
  const box = $("plan-bases");
  box.textContent = "";
  for (const name of names) {
    const button = element("button", "", `origin/${name}`);
    button.type = "button";
    button.setAttribute("aria-pressed", String(name === current));
    button.disabled = name === current;
    button.addEventListener("click", () => onBase(name));
    box.append(button);
  }
  box.hidden = names.length === 0;
}

async function onCreate() {
  if (state.busy || !state.planId) return;
  state.busy = true;
  showBanner("Creating the worktree...");
  $("plan-create").disabled = true;
  $("plan-offline").disabled = true;
  try {
    const answer = await api("POST", "/api/worktree/create", { plan_id: state.planId });
    state.planId = null;
    const started = startedFrom(answer.base);
    if (answer.listed) {
      // The new orchestrator waits on Claude's folder-trust dialog: the keys answer it. The request goes into the
      // text box unsent; sending it is the usual separate Send tap.
      state.carryText = state.planQuery;
      state.carryBanner = started;
      showBanner("");
      window.location.hash = encodeURIComponent(answer.session);
    } else {
      showBanner(`Created ${answer.path}, but its session ${answer.session} is not listed yet. ${started}`);
    }
  } catch (error) {
    showBanner(error.message);
    const offline = error.data && error.data.offline;
    if (offline) {
      // The fetch failed. Creating from the copy already here takes this tap of its own; nothing else skips the fetch.
      state.planId = offline.plan_id;
      const dated = dateText(offline.fetched_at || offline.committed_at) || offline.sha;
      $("plan-offline").textContent = `Create from the downloaded copy (dated ${dated})`;
      $("plan-offline").hidden = false;
      $("plan-create").hidden = true;
      $("plan-fetch-note").hidden = true;
    } else {
      hidePlan();  // A plan is used once, even when creating failed: plan again.
    }
  }
  state.busy = false;
  $("plan-create").disabled = false;
  $("plan-offline").disabled = false;
}

// Which commit the new worktree started from. The fetch may have moved it on since the plan: that is expected.
function startedFrom(base) {
  if (!base) return "";
  const how = !base.fetched ? "the downloaded copy, not fetched"
    : base.moved ? `fetched just now; the plan showed ${base.planned_sha}` : "fetched just now";
  return `Started from ${base.ref} at ${base.sha} ${base.subject} (${how}).`;
}

// ---- Settings: who speaks, and what turns the mic into text. Kept per browser; the gateway says what is set up.

const SERVER_ENGINES = ["chatterbox", "deepgram"];  // Voices the gateway fetches. "phone" is the phone's own.
const ENGINES = [...SERVER_ENGINES, "phone"];
const DICTATIONS = ["chrome", "whisper"];
const ENGINE_NAMES = { chatterbox: "Chatterbox", deepgram: "Deepgram", phone: "the phone's voice" };
const settings = {
  engine: readSetting("voice-engine", ENGINES),                // null: the gateway's default.
  dictation: readSetting("dictation", DICTATIONS) || "chrome",
};
let serverVoices = [];   // What the gateway has set up, from its last answer.
let defaultEngine = "phone";
let local = { configured: false, reason: "" };

function readSetting(name, allowed) {
  try { const value = localStorage.getItem(name); return allowed.includes(value) ? value : null; } catch (_e) { return null; }
}

function writeSetting(name, value) {
  try { localStorage.setItem(name, value); } catch (_e) { /* Kept for this page only. */ }
}

function currentEngine() {
  return settings.engine || defaultEngine;
}

function showSettings(view) {
  serverVoices = (view.voices || []).filter((engine) => SERVER_ENGINES.includes(engine));
  defaultEngine = SERVER_ENGINES.includes(view.voice) ? view.voice : "phone";
  if (view.local) local = { configured: view.local.configured === true, reason: String(view.local.reason || "") };
  for (const engine of ENGINES) {
    $(`engine-${engine}`).checked = engine === currentEngine();
    $(`engine-${engine}`).disabled = engine !== "phone" && !serverVoices.includes(engine);
  }
  for (const way of DICTATIONS) $(`dictation-${way}`).checked = way === settings.dictation;
  $("dictation-whisper").disabled = !local.configured;
  const notes = [];
  if (!local.configured) notes.push(`Chatterbox and Local Whisper are off: ${local.reason}`);
  if (!serverVoices.includes("deepgram")) notes.push("Deepgram has no key on the gateway.");
  $("local-note").textContent = notes.join(" ");
  $("local-note").hidden = notes.length === 0;
}

function onEngine(event) {
  if (!ENGINES.includes(event.target.value)) return;
  settings.engine = event.target.value;
  writeSetting("voice-engine", settings.engine);
  showVoiceNote("");
}

function onDictation(event) {
  if (!DICTATIONS.includes(event.target.value)) return;
  stopListening();
  settings.dictation = event.target.value;
  writeSetting("dictation", settings.dictation);
  showMicIdle();
}

// Says which voice was heard when it was not the chosen one. Text only.
function showVoiceNote(text) {
  $("voice-note").textContent = text;
  $("voice-note").hidden = !text;
}

function showMicIdle() {
  $("mic").classList.remove("listening", "recording");
  $("mic").textContent = settings.dictation === "whisper" ? "Speak (Local Whisper)" : "Speak";
}

// ---- Speech only ever fills the text box. Sending always needs the separate Send tap.

const Recognition = window.isSecureContext ? (window.SpeechRecognition || window.webkitSpeechRecognition) : null;
let listening = null;

function stopListening() {
  if (listening) listening.stop();
  discardRecording();
}

function startListening() {
  const recognition = new Recognition();
  const before = $("text").value;
  recognition.interimResults = true;
  recognition.continuous = false;
  recognition.onresult = (event) => {
    let heard = "";
    for (const result of event.results) heard += result[0].transcript;
    $("text").value = (before ? `${before} ` : "") + heard;
  };
  recognition.onerror = () => { $("hint").hidden = false; };
  recognition.onend = () => {
    listening = null;
    $("mic").classList.remove("listening");
    $("mic").textContent = "Speak";
  };
  listening = recognition;
  $("mic").classList.add("listening");
  $("mic").textContent = "Listening, tap to finish";
  recognition.start();
}

// ---- Local Whisper: the mic records on this page, and the gateway passes the recording to the speech service on the
// user's own machine. It never falls back to Chrome's recogniser, which would send the audio to Google: a failure
// says so and leaves the box alone, and switching is the user's own tap in Voice settings.

const RECORD_LIMIT_MS = 30000;
const RECORD_TYPES = ["audio/webm;codecs=opus", "audio/ogg;codecs=opus", "audio/mp4"];
let recording = null;    // The recording under way: { recorder, round, started, timer, ticker }.
let dictationRound = 0;  // Bumped to drop a recording, or text still on its way, that is no longer wanted.

function recordType() {
  return RECORD_TYPES.find((type) => MediaRecorder.isTypeSupported(type)) || "";
}

function showRecording() {
  if (!recording) {
    showMicIdle();
    return;
  }
  const seconds = Math.floor((Date.now() - recording.started) / 1000);
  $("mic").classList.add("recording");
  $("mic").textContent = `Recording ${seconds} s of ${RECORD_LIMIT_MS / 1000}, tap to stop`;
}

async function startRecording() {
  if (!window.MediaRecorder || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    showBanner("This browser cannot record on this page. Nothing was changed.");
    return;
  }
  const round = ++dictationRound;
  let stream = null;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (_e) {
    showBanner("The microphone is not allowed for this page. Allow it, then tap Speak again.");
    return;
  }
  if (round !== dictationRound) {  // Left the view, or sent, while the browser asked about the mic.
    for (const track of stream.getTracks()) track.stop();
    return;
  }
  const type = recordType();
  const recorder = type ? new MediaRecorder(stream, { mimeType: type }) : new MediaRecorder(stream);
  const chunks = [];
  const current = { recorder, round, started: Date.now(), timer: null, ticker: null };
  recorder.addEventListener("dataavailable", (event) => { if (event.data && event.data.size) chunks.push(event.data); });
  recorder.addEventListener("stop", () => {
    for (const track of stream.getTracks()) track.stop();
    clearTimeout(current.timer);
    clearInterval(current.ticker);
    if (recording === current) recording = null;
    showRecording();
    if (round === dictationRound) transcribe(new Blob(chunks, { type: recorder.mimeType || type }), round);
  });
  recording = current;
  current.timer = setTimeout(stopRecording, RECORD_LIMIT_MS);
  current.ticker = setInterval(showRecording, 500);
  recorder.start();
  showRecording();
}

function stopRecording() {
  if (recording && recording.recorder.state !== "inactive") recording.recorder.stop();
}

// Leaving the view, Send and Plan drop a recording and any text still on its way, so it cannot land in another box.
function discardRecording() {
  dictationRound += 1;
  stopRecording();
}

async function transcribe(blob, round) {
  if (!blob.size) {
    showBanner("Nothing was recorded. The text box was left as it was.");
    return;
  }
  $("mic").disabled = true;
  $("mic").textContent = "Turning it into text...";
  try {
    const response = await fetch("/api/transcribe", { method: "POST", headers: { "Content-Type": blob.type }, body: blob });
    if (response.status === 401) {
      window.location.replace("/login");
      return;
    }
    let data = {};
    try { data = await response.json(); } catch (_e) { data = {}; }
    if (!response.ok) throw new Error(data.error || `Local Whisper failed (${response.status}).`);
    if (round !== dictationRound) return;
    const heard = typeof data.text === "string" ? data.text.trim() : "";
    if (!heard) {
      showBanner("Local Whisper heard no words. The text box was left as it was.");
      return;
    }
    $("text").value = ($("text").value ? `${$("text").value} ` : "") + heard;
  } catch (error) {
    if (round === dictationRound) showBanner(`${error.message} The text box was left as it was.`);
  } finally {
    $("mic").disabled = false;
    showRecording();
  }
}

function onMic() {
  if (settings.dictation === "whisper") {
    if (!local.configured) {
      showBanner(`Local Whisper is off: ${local.reason} Chrome speech is used only if you pick it in Voice settings.`);
      return;
    }
    if (recording) stopRecording();
    else startRecording();
    return;
  }
  if (!Recognition) {
    $("hint").hidden = false;
    $("text").focus();
    return;
  }
  if (listening) stopListening();
  else startListening();
}

// ---- Announcements. Only the text of an event from the server is ever spoken. It is never the terminal's output.

const speech = window.speechSynthesis || null;
// Chatterbox or Deepgram audio, when the gateway has them. The page asks by event id and engine only; the gateway
// picks the words. It plays while it downloads. Chrome starts a plain src only once 32 KB are in, most of a short clip, so where it
// can the page feeds the element through MediaSource, which plays from the first frames. Elsewhere: the plain src.
const player = new Audio();
const STREAMABLE = Boolean(window.MediaSource) && MediaSource.isTypeSupported("audio/mpeg");
const PLAY_LIMIT_MS = 60000;  // Whatever happens to one announcement, the next one is not held up longer than this.
const START_LIMIT_MS = 15000; // Audio that has not started playing by then is given up for the phone's own voice.
const announced = new Set();  // Event ids already spoken or shown, so a late acknowledgement cannot repeat one.
const fetching = new Set();   // Event ids whose audio is still to play or be given up. The gateway must keep those.
let playing = Promise.resolve();  // Announcements from the gateway's voices play one after another.
let speechUnlocked = false;
let wakeLock = null;
let wakeLockAsked = false;
let muted = false;
try { muted = localStorage.getItem("voice-muted") === "1"; } catch (_e) { muted = false; }

// Chrome on Android refuses speech and sound until the page has had a user gesture, so this runs during a tap.
function unlockSpeech() {
  if (muted || speechUnlocked) return;
  speechUnlocked = true;
  player.play().catch(() => {});  // Nothing to play yet: this only lets the element play later.
  if (!speech) return;
  const silent = new SpeechSynthesisUtterance("");
  silent.volume = 0;
  speech.speak(silent);
}

function withName(event) {
  return event.kind === "summary" && event.session !== state.session;
}

function eventWords(event) {
  return withName(event) ? `${event.worktree}: ${event.text}` : event.text;
}

function announce(view) {
  showSettings(view);
  const canSpeak = Boolean(speech) || engineChain().length > 0;
  for (const event of view.events || []) {
    if (event.kind === "progress") continue;  // The app's working updates: neither said nor acknowledged here.
    if (announced.has(event.id)) continue;
    const words = eventWords(event);
    $("announce").textContent = words;
    $("announce").hidden = false;
    if (canSpeak && !muted && !speechUnlocked) continue;  // After a reload: held until a tap lets the page speak.
    announced.add(event.id);
    if (muted) continue;
    if (currentEngine() === "phone") speakWords(words);
    else sayWithServer(event, words);
  }
  acknowledge(view.events || []);
  state.waiting = (view.waiting || []).length > 0;
  updateWakeLock();
}

// The phone's own voice: the fallback for one event whose audio could not be fetched or played.
function speakWords(words) {
  return new Promise((resolve) => {
    if (!speech || muted) { resolve(); return; }
    const utterance = new SpeechSynthesisUtterance(words);
    utterance.onend = resolve;
    utterance.onerror = resolve;
    setTimeout(resolve, PLAY_LIMIT_MS);
    speech.speak(utterance);
  });
}

function audioUrl(event, engine) {
  return `/api/audio?id=${encodeURIComponent(event.id)}&with_name=${withName(event) ? 1 : 0}&engine=${engine}`;
}

function sayWithServer(event, words) {
  fetching.add(event.id);
  playing = playing.then(() => playAudio(event, words));
}

// The audio as it comes in, for the element to play through a MediaSource. Throws if there is no audio to play.
async function openStream(event, engine) {
  const controller = new AbortController();
  const response = await fetch(audioUrl(event, engine), { signal: controller.signal });
  const kind = response.headers.get("Content-Type") || "";
  if (!response.ok || !kind.startsWith("audio/mpeg") || !response.body) {
    controller.abort();
    throw new Error("No audio.");
  }
  const media = new MediaSource();
  const url = URL.createObjectURL(media);
  feed(media, response.body.getReader(), controller.signal).finally(() => URL.revokeObjectURL(url));
  return { url, cancel: () => controller.abort() };
}

async function feed(media, reader, signal) {
  try {
    await new Promise((resolve, reject) => {
      media.addEventListener("sourceopen", resolve, { once: true });
      signal.addEventListener("abort", reject, { once: true });
    });
    const buffer = media.addSourceBuffer("audio/mpeg");
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer.appendBuffer(value);
      await new Promise((resolve) => buffer.addEventListener("updateend", resolve, { once: true }));
    }
    media.endOfStream();
  } catch (_e) {
    reader.cancel().catch(() => {});
    if (media.readyState === "open") media.endOfStream("network");  // Plays what came, then ends with an error.
  }
}

// The voices to try for one event, in order: the choice, and Deepgram after Chatterbox. Only voices the gateway has.
function engineChain() {
  const choice = currentEngine();
  const order = choice === "chatterbox" ? ["chatterbox", "deepgram"] : choice === "deepgram" ? ["deepgram"] : [];
  return order.filter((engine) => SERVER_ENGINES.includes(engine) && serverVoices.includes(engine));
}

// "Chatterbox unavailable, used Deepgram", or nothing when the chosen voice was heard.
function fallbackNote(failed, used) {
  if (!failed.length) return "";
  return `${failed.map((engine) => ENGINE_NAMES[engine]).join(" and ")} unavailable, used ${ENGINE_NAMES[used]}`;
}

// Each event is heard once: from the first voice in the chain that starts playing, otherwise the phone's voice.
// Each voice is tried at most once per event, and the gateway asks each at most once too.
async function playAudio(event, words) {
  try {
    if (muted) return;
    const chain = engineChain();
    const choice = currentEngine();
    const failed = choice === "phone" || chain.includes(choice) ? [] : [choice];  // Chosen, but not set up.
    for (const engine of chain) {
      if (await startAudio(event, engine)) {
        showVoiceNote(fallbackNote(failed, engine));
        await finished(player);
        return;
      }
      failed.push(engine);
      if (muted) return;
    }
    showVoiceNote(fallbackNote(failed, "phone"));
    await speakWords(words);
  } finally {
    fetching.delete(event.id);
    acknowledge([event]);
  }
}

// One voice's try: true once its audio plays. A failed load, a refused play() or no start in time is false, and
// whatever would still start late is stopped, so it cannot play over the next voice.
async function startAudio(event, engine) {
  let timer = null;
  let stream = null;
  let gaveUp = false;
  const tooSlow = new Promise((_resolve, reject) => {
    timer = setTimeout(() => reject(new Error("Audio did not start.")), START_LIMIT_MS);
  });
  const start = async () => {
    stream = STREAMABLE ? await openStream(event, engine) : null;
    if (gaveUp) {
      if (stream) stream.cancel();
      throw new Error("Too late.");
    }
    player.src = stream ? stream.url : audioUrl(event, engine);
    await player.play();  // Refused if the audio fails to load or may not play.
  };
  try {
    await Promise.race([start(), tooSlow]);
    return true;
  } catch (_e) {
    gaveUp = true;
    if (stream) stream.cancel();
    player.pause();
    player.removeAttribute("src");
    player.load();
    return false;
  } finally {
    clearTimeout(timer);
  }
}

function finished(element) {
  return new Promise((resolve) => {
    if (element.ended || element.paused) { resolve(); return; }  // A very short clip may be over already.
    const names = ["ended", "error", "pause"];
    let timer = null;
    const done = () => {
      for (const name of names) element.removeEventListener(name, done);
      clearTimeout(timer);
      resolve();
    };
    for (const name of names) element.addEventListener(name, done);
    timer = setTimeout(done, PLAY_LIMIT_MS);
  });
}

function acknowledge(events) {
  // Not while its audio is still to play: playAudio acknowledges it once that is over or given up.
  const ids = events.filter((event) => announced.has(event.id) && !fetching.has(event.id)).map((event) => event.id);
  if (ids.length) api("POST", "/api/announced", { ids }).catch(() => {});  // The next poll tries again.
}

// Keeps the screen on while something is waiting, because a hidden page neither polls nor speaks. HTTPS only.
async function updateWakeLock() {
  if (!state.waiting) {
    if (wakeLock) wakeLock.release().catch(() => {});
    wakeLock = null;
    return;
  }
  if (wakeLock || wakeLockAsked || document.hidden || !window.isSecureContext || !navigator.wakeLock) return;
  wakeLockAsked = true;
  try {
    const held = await navigator.wakeLock.request("screen");
    held.addEventListener("release", () => { if (wakeLock === held) wakeLock = null; });
    wakeLock = held;
    if (!state.waiting) updateWakeLock();
  } catch (_e) {
    wakeLock = null;  // Refused, for example on low battery. The next poll asks again.
  }
  wakeLockAsked = false;
}

function showMute() {
  $("mute").textContent = muted ? "Voice off" : "Voice on";
  $("mute").setAttribute("aria-pressed", String(muted));
}

function onMute() {
  muted = !muted;
  try { localStorage.setItem("voice-muted", muted ? "1" : "0"); } catch (_e) { /* Remembered for this page only. */ }
  if (muted && speech) speech.cancel();
  if (muted) player.pause();
  unlockSpeech();
  showMute();
}

// ---- End of announcements

// ---- Start

$("back").addEventListener("click", () => { window.location.hash = ""; });
$("new-back").addEventListener("click", () => { window.location.hash = ""; });
$("new-worktree").addEventListener("click", () => { window.location.hash = "new"; });
$("plan").addEventListener("click", () => onPlan(null));
$("plan-create").addEventListener("click", onCreate);
$("plan-offline").addEventListener("click", onCreate);
$("plan-base-use").addEventListener("click", () => onBase($("plan-base-input").value.trim()));
$("plan-cancel").addEventListener("click", hidePlan);
$("mute").addEventListener("click", onMute);
document.addEventListener("click", unlockSpeech, true);
$("mic").addEventListener("click", onMic);
for (const input of document.querySelectorAll('input[name="engine"]')) input.addEventListener("change", onEngine);
for (const input of document.querySelectorAll('input[name="dictation"]')) input.addEventListener("change", onDictation);
$("send").addEventListener("click", onSend);
$("stop").addEventListener("click", () => act("/api/stop", {}));
$("earlier-toggle").addEventListener("click", onEarlier);
$("restart").addEventListener("click", () => askRestart(true));
$("restart-cancel").addEventListener("click", () => askRestart(false));
$("restart-confirm").addEventListener("click", onRestartConfirm);
for (const button of document.querySelectorAll("[data-key]")) {
  button.addEventListener("click", () => act("/api/keys", { key: button.dataset.key, screen_token: state.screenToken }));
}
window.addEventListener("hashchange", route);
document.addEventListener("visibilitychange", () => {
  if (document.hidden) clearTimeout(state.timer);
  else refresh();
  updateWakeLock();
});
if (!Recognition) $("hint").hidden = false;
showMute();
showMicIdle();
route();
