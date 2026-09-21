// Runs the browser installer's exact flashing code against the real release, with a stand-in for the
// phone that answers like a Pixel 8 and records every command. Nothing here touches a real device.
import "./shim.mjs";
import { readFileSync } from "node:fs";
const fastboot = await import("../../../docs/js/fastboot.mjs");
fastboot.configureZip({ useWebWorkers: false });

const release = process.argv[2];
const SLOTTED = new Set(["boot", "init_boot", "dtbo", "vendor_kernel_boot", "pvmfw", "vendor_boot", "vbmeta",
  "bootloader", "radio", "system", "system_dlkm", "system_ext", "product", "vendor", "vendor_dlkm"]);
const LOGICAL = new Set(["system", "system_dlkm", "system_ext", "product", "vendor", "vendor_dlkm", "odm", "odm_dlkm"]);
let mode = "bootloader";
let slot = "b";
const log = [];
const t0s = Date.now();
const note = (s) => { const l = `[${mode.padEnd(10)}] ${s}`; log.push(l); console.error(`${((Date.now() - t0s) / 1000).toFixed(0).padStart(5)}s ${l}`); };

const dev = new fastboot.FastbootDevice();
dev.getVariable = async (v) => {
  let r = null;
  const base = (p) => p.replace(/_(a|b)$/, "");
  if (v.startsWith("has-slot:")) r = SLOTTED.has(v.slice(9)) ? "yes" : "no";
  else if (v.startsWith("is-logical:")) r = mode === "fastbootd" && LOGICAL.has(base(v.slice(11))) ? "yes" : "no";
  else r = ({ product: "shiba", "current-slot": slot, "is-userspace": mode === "fastbootd" ? "yes" : "no",
    "max-download-size": "0x10000000", "super-partition-name": "super", "snapshot-update-status": "none",
    unlocked: "yes", "version-bootloader": "ripcurrent-17.0-15199481",
    "version-baseband": "g5300i-260317-260505-B-15346003" })[v] ?? null;
  if (!/^(has-slot|is-logical|max-download-size|current-slot)/.test(v)) note(`getvar ${v} = ${r}`);
  return r;
};
dev.runCommand = async (c) => {
  if (c.startsWith("set_active:")) slot = c.split(":")[1];
  if (!c.startsWith("resize-logical-partition")) note(c);
  return { text: "" };
};
const uploaded = {};
dev.upload = async (p, data) => { uploaded[p] = (uploaded[p] || 0) + data.byteLength; };
dev.waitForConnect = async () => {};
dev.reboot = async (target = "", wait = false) => {
  note(`reboot ${target || "(system)"}`);
  mode = target === "fastboot" ? "fastbootd" : target === "bootloader" ? "bootloader" : "system";
};
// flash:X is only recorded once per partition, with the total size sent to it
const realRun = dev.runCommand;
dev.runCommand = async (c) => {
  if (c.startsWith("flash:")) { const p = c.slice(6); note(`flash ${p}  (${((uploaded[p] || 0) / 1e6).toFixed(1)} MB sent so far)`); return { text: "" }; }
  return realRun(c);
};

const t0 = Date.now();
await dev.flashFactoryZip(new Blob([readFileSync(release)]), true, () => note("ASKED USER TO RECONNECT"), () => {});
console.log(log.join("\n"));
console.log(`\nfinished in ${((Date.now() - t0) / 1000).toFixed(0)} s, final slot ${slot}`);
