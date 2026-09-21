// OBSIDIAN browser installer.
//
// Talks to a Pixel 8 in bootloader mode over WebUSB, using fastboot.js (MIT, served from this site
// with two small changes described at the top of fastboot.mjs). It downloads the release from this
// same site, checks it against the published SHA-256 while it arrives, and then flashes it the way
// AOSP's own fastboot-info.txt describes. Nothing is sent anywhere, and nothing is loaded from any
// other site.
//
// Test builds are signed with test keys, so this installer never locks the bootloader: a phone
// locked to an operating system it cannot verify refuses to start.

import * as fastboot from "./fastboot.mjs";
import { Sha256 } from "./sha256.mjs";

const RELEASE_INFO = "releases/latest.json";
const SUPPORTED_PRODUCT = "shiba"; // how the Pixel 8's bootloader names itself
const BLOB_PART = 64 * 1024 * 1024; // hand the download to the browser's storage in pieces this size

const device = new fastboot.FastbootDevice();
fastboot.setDebugLevel(1); // log to the browser console, useful when someone reports a problem

let supported = false;
let release = null;
let image = null; // the finished, verified download
let connected = false;
let unlocked = false;
let busy = false;

const el = (id) => document.getElementById(id);
const gb = (bytes) => `${(bytes / 1e9).toFixed(2)} GB`;

function say(id, text, kind = "") {
  const node = el(id);
  node.textContent = text;
  node.dataset.kind = kind; // "", "ok", "warn" or "bad": colours come from style.css
}

function setButtons() {
  el("connect").disabled = busy || !supported;
  el("unlock").disabled = busy || !connected || unlocked;
  el("download").disabled = busy || !release || image !== null;
  el("install").disabled = busy || !connected || !unlocked || image === null;
}

// Turn what WebUSB and the bootloader throw into something a person can act on.
function explain(error) {
  const name = error?.name || "";
  const message = error?.message || String(error);
  if (name === "NotFoundError") {
    return "No phone was chosen. Press the button again and pick the Pixel 8 from the list.";
  }
  if (name === "SecurityError" || /access denied|claim|unable to open/i.test(message)) {
    return "The computer would not let this page use the phone. Close any other program that might be using it, " +
      "such as fastboot in a terminal or Android Studio, unplug the phone, plug it back in and try again.";
  }
  if (name === "NetworkError" || /disconnect|transfer|device unavailable/i.test(message)) {
    return "The connection to the phone dropped. Check the cable goes straight into the computer, not a hub, then connect again.";
  }
  if (error instanceof fastboot.FastbootError) {
    return `The phone refused: ${message}`;
  }
  return message;
}

// One action at a time, and every failure explained where it happened.
async function run(statusId, work) {
  if (busy) return;
  busy = true;
  setButtons();
  try {
    await work();
  } catch (error) {
    console.error(error);
    say(statusId, explain(error), "bad");
  } finally {
    busy = false;
    setButtons();
  }
}

function checkSupport() {
  if (!window.isSecureContext) {
    say("support", "This page has to be opened over a secure https connection before the browser will let it use USB.", "bad");
  } else if (!("usb" in navigator)) {
    say("support", "This browser cannot reach a phone over USB. Open this page in Chrome, Edge or Brave on a computer. " +
      "Firefox and Safari do not support it.", "bad");
  } else {
    supported = true;
    say("support", "This browser can install OBSIDIAN.", "ok");
  }
}

async function loadRelease() {
  try {
    const response = await fetch(RELEASE_INFO, { cache: "no-store" });
    if (!response.ok) throw new Error(String(response.status));
    release = await response.json();
    say("download-status", `${release.name}, ${gb(release.size)}. Not downloaded yet.`);
  } catch {
    say("download-status", "There is no release to download yet.", "warn");
  }
}

async function connect() {
  say("connect-status", "Choose the phone in the box your browser shows. It may be listed by its serial number.");
  await device.connect();
  const product = await device.getVariable("product");
  if (product !== SUPPORTED_PRODUCT) {
    connected = false;
    say("connect-status", `This phone calls itself "${product}". OBSIDIAN only runs on the Pixel 8, whose bootloader ` +
      `calls itself ${SUPPORTED_PRODUCT}. Nothing has been changed on it.`, "bad");
    return;
  }
  connected = true;
  unlocked = (await device.getVariable("unlocked")) === "yes";
  say("connect-status", "Connected to a Pixel 8.", "ok");
  say("unlock-status", unlocked ? "Already unlocked. Go on to the next step." : "Locked. Unlock it to continue.",
    unlocked ? "ok" : "");
}

async function unlock() {
  say("unlock-status", "Now look at the phone. Use the volume buttons to choose Unlock the bootloader, then press Power. " +
    "This erases everything on it.", "warn");
  await device.runCommand("flashing unlock");
  try {
    unlocked = (await device.getVariable("unlocked")) === "yes";
  } catch {
    // Some phones restart into bootloader mode after erasing themselves. Ask for the connection again.
    connected = false;
    say("unlock-status", "The phone restarted. Press Connect again, then carry on.", "warn");
    return;
  }
  say("unlock-status", unlocked ? "Unlocked." : "The phone is still locked. If you chose not to unlock, press Unlock to try again.",
    unlocked ? "ok" : "warn");
}

async function download() {
  const response = await fetch(release.file);
  if (!response.ok || !response.body) throw new Error(`The download failed (${response.status}). Try again.`);
  el("download-bar").hidden = false;
  const reader = response.body.getReader();
  const hash = new Sha256();
  const parts = [];
  let pending = [];
  let pendingBytes = 0;
  let received = 0;
  let shownAt = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    hash.update(value);
    pending.push(value);
    pendingBytes += value.length;
    received += value.length;
    // Hand the data to the browser's own blob storage as it arrives, so the whole image never sits in page memory.
    if (pendingBytes >= BLOB_PART) {
      parts.push(new Blob(pending));
      pending = [];
      pendingBytes = 0;
    }
    const now = performance.now();
    if (now - shownAt > 250) {
      shownAt = now;
      el("download-bar").value = received / release.size;
      say("download-status", `Downloading and checking: ${gb(received)} of ${gb(release.size)}`);
    }
  }
  parts.push(new Blob(pending));
  el("download-bar").value = received / release.size;
  if (received !== release.size) {
    throw new Error(`The download stopped early, at ${gb(received)} of ${gb(release.size)}. Try again.`);
  }
  const digest = hash.hex();
  if (digest !== release.sha256) {
    throw new Error("The download does not match the published checksum, so it will not be used. Try downloading it again.");
  }
  image = new Blob(parts, { type: "application/zip" });
  say("download-status", `Downloaded and checked: its SHA-256 matches the published ${digest.slice(0, 16)}...`, "ok");
}

function askToReconnect() {
  el("reconnect").hidden = false;
  say("install-status", "The browser lost touch with the phone while it restarted. Check the cable is plugged in firmly, " +
    "then press Reconnect and choose the phone.", "warn");
}

async function reconnect() {
  el("reconnect").hidden = true;
  try {
    await device.connect();
    say("install-status", "Reconnected. Carrying on.", "warn");
  } catch (error) {
    el("reconnect").hidden = false;
    say("install-status", explain(error), "bad");
  }
}

async function install() {
  const leaving = (event) => {
    event.preventDefault();
    event.returnValue = "";
  };
  window.addEventListener("beforeunload", leaving); // closing the page now would stop halfway through
  el("reconnect").hidden = true;
  // On a computer the library reconnects by itself when the phone comes back from a restart, and only
  // asks for help on Android. If the phone has not come back a minute after a restart began, it was
  // probably not seen again, so offer the same Reconnect button. Normal restarts take seconds.
  let restartingSince = 0;
  const watchdog = setInterval(() => {
    if (restartingSince && performance.now() - restartingSince > 60_000 && el("reconnect").hidden) {
      askToReconnect();
    }
  }, 5_000);
  try {
    el("install-bar").hidden = false;
    say("install-status", "Starting. Keep the phone plugged in and leave it alone until this says it has finished.", "warn");
    await device.flashFactoryZip(image, true, askToReconnect, (action, item, progress) => {
      if (action === "reboot") {
        if (!restartingSince) restartingSince = performance.now();
      } else {
        restartingSince = 0;
        el("reconnect").hidden = true; // any other step means the phone is reachable again
      }
      const verb = fastboot.USER_ACTION_MAP[action] || action;
      say("install-status", `${verb} ${item}${progress > 0 ? `, ${Math.round(progress * 100)}%` : ""}`, "warn");
      el("install-bar").value = progress;
    });
    el("install-bar").value = 1;
    say("install-status", "OBSIDIAN is installed. The phone is starting it now.", "ok");
    await device.reboot();
  } finally {
    clearInterval(watchdog);
    window.removeEventListener("beforeunload", leaving);
    el("reconnect").hidden = true;
  }
}

el("connect").addEventListener("click", () => run("connect-status", connect));
el("unlock").addEventListener("click", () => run("unlock-status", unlock));
el("download").addEventListener("click", () => run("download-status", download));
el("install").addEventListener("click", () => run("install-status", install));
el("reconnect").addEventListener("click", reconnect); // must work while install is running

checkSupport();
setButtons();
await loadRelease();
setButtons();
