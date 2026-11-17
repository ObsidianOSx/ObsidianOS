// Writes the facts line in every page footer from the release index, so the numbers on the site
// are always the real ones. Run it after the index changes, before deploying docs/.
//
//   node obsidian-os/release/update-facts.mjs [docs]
//
// The line sits between <!-- facts --> and <!-- /facts --> in each page's footer; a page without
// the markers gets them after its licence line.
import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

const docs = process.argv[2] || "docs";
const index = JSON.parse(readFileSync(join(docs, "releases/index.json"), "utf8"));

let releases = 0;
let latest = "";
const numbers = [];
for (const d of index.devices) {
  releases += d.releases.length;
  for (const r of d.releases) if (r.date > latest) latest = r.date;
  const n = d.model.match(/^Pixel (\d+)$/);
  if (n) numbers.push(Number(n[1]));
}
numbers.sort((a, b) => a - b);

const months = ["January", "February", "March", "April", "May", "June", "July", "August",
  "September", "October", "November", "December"];
const [y, m, day] = latest.split("-").map(Number);
const when = `${day} ${months[m - 1]} ${y}`;
const pixels = numbers.length > 1
  ? `${numbers.slice(0, -1).join(", ")} and ${numbers.at(-1)}`
  : String(numbers[0]);

const line = `<p class="facts">Built for the Pixel ${pixels}. ${releases} releases published, the latest on ${when}.</p>`;
const block = `<!-- facts -->${line}<!-- /facts -->`;

for (const f of readdirSync(docs).filter((f) => f.endsWith(".html"))) {
  const path = join(docs, f);
  const html = readFileSync(path, "utf8");
  let out;
  if (/<!-- facts -->[\s\S]*?<!-- \/facts -->/.test(html)) {
    out = html.replace(/<!-- facts -->[\s\S]*?<!-- \/facts -->/, block);
  } else if (html.includes("<p>OBSIDIAN, open source under GPL-3.0</p>")) {
    out = html.replace("<p>OBSIDIAN, open source under GPL-3.0</p>",
      `<p>OBSIDIAN, open source under GPL-3.0</p>\n  ${block}`);
  } else {
    console.log(`skipped ${f}: no footer licence line`);
    continue;
  }
  if (out !== html) { writeFileSync(path, out); console.log(`updated ${f}`); }
}
console.log(line);
