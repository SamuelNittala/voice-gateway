"use strict";

const $ = (id) => document.getElementById(id);

function showError(message) {
  $("error").textContent = message;
  $("error").hidden = !message;
}

async function logIn(event) {
  event.preventDefault();
  $("submit").disabled = true;
  showError("");
  try {
    const response = await fetch("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ passcode: $("passcode").value }),
    });
    if (response.ok) {
      window.location.replace("/");
      return;
    }
    let data = {};
    try { data = await response.json(); } catch (_e) { data = {}; }
    showError(data.error || `Login failed (${response.status}).`);
    $("passcode").value = "";
  } catch (_e) {
    showError("Cannot reach the gateway.");
  } finally {
    $("submit").disabled = false;
  }
}

$("login").addEventListener("submit", logIn);
