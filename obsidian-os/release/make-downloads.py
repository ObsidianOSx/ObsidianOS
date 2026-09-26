#!/usr/bin/env python3
"""Writes the website's Downloads page from the list of published releases.

    make-downloads.py --index releases/index.json --out downloads.html

The index is the record of everything ever published, newest first within each phone. The page it
writes is plain HTML with no scripts, so it works in any browser and can be read without trusting
anything. Run this again after publishing a release; nothing else needs editing.
"""
import argparse
import json
import sys

# Kept here so the page carries the same header and footer as the rest of the site.
NAV = """<header class="top">
  <a class="brand" href="index.html" aria-label="OBSIDIAN home">
    <svg class="mark" viewBox="0 0 32 32" aria-hidden="true"><ellipse cx="16" cy="16" rx="8" ry="10" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>
    <span>OBSIDIAN</span>
  </a>
  <nav aria-label="Primary">
    <a href="about.html">About</a>
    <a href="index.html#phone">The phone</a>
    <a href="index.html#messaging">Messaging</a>
    <a href="index.html#transparency">Transparency</a>
    <a href="devices.html">Phones</a>
    <a href="install.html">Install</a>
    <a href="downloads.html" aria-current="page">Downloads</a>
    <a href="donate.html">Donate</a>
    <a class="ghost" href="https://github.com/ObsidianOSx/ObsidianOS">Source</a>
  </nav>
</header>"""

FOOT = """<footer>
  <p>OBSIDIAN, open source under GPL-3.0</p>
  <p class="fineprint">This page loads no fonts, scripts, analytics or cookies from anywhere. Nothing here counts your visit.</p>
</footer>"""

FAVICON = ("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'>"
           "<rect width='32' height='32' fill='%23000'/><ellipse cx='16' cy='16' rx='8' ry='10' "
           "fill='none' stroke='%23d8d8d8' stroke-width='1.4'/></svg>")


def gb(size):
    return "%.2f GB" % (size / 1e9)


def release_item(rel, newest):
    """One release: what it is, how big, what it should hash to, and where to get it."""
    chip = ' <span class="chip now">Newest</span>' if newest else ""
    keys = rel.get("keys", "test")
    signing = ("Signed with test keys, so the bootloader stays unlocked."
               if keys == "test" else "Signed with the OBSIDIAN release keys.")
    return """      <article class="card">
        <h3>{version}{chip}</h3>
        <p class="meta">Released {date}. {size} ({bytes:,} bytes). {signing}</p>
        <p class="code sha">SHA-256 {sha}</p>
        <p class="row">
          <a class="button" href="{file}">Download the zip</a>
          <a class="button quiet" href="{file}.sha256">Checksum file</a>
        </p>
      </article>""".format(version=rel["version"], chip=chip, date=rel["date"], size=gb(rel["size"]),
                           bytes=rel["size"], signing=signing, sha=rel["sha256"], file=rel["file"])


def device_section(dev, alt):
    items = "\n".join(release_item(rel, i == 0) for i, rel in enumerate(dev["releases"]))
    note = dev.get("note", "")
    note_html = '\n    <p class="section-lede">%s</p>' % note if note else ""
    return """  <section class="band{alt}" id="{device}">
    <h2>Google {model}</h2>{note}
    <div class="cards two">
{items}
    </div>
  </section>""".format(alt=" alt" if alt else "", device=dev["device"], model=dev["model"],
                       note=note_html, items=items)


def pending_section(pending):
    """Phones on the way, named rather than hidden, each with how far along it is."""
    if not pending:
        return ""
    items = "\n".join("""      <div class="item">
        <h3>{model} <span class="chip next">{state}</span></h3>
        <p>{note}</p>
      </div>""".format(model=p["model"], state=p.get("state", "In testing"), note=p["note"])
                      for p in pending)
    return """

  <section class="band">
    <h2>Not published yet</h2>
    <p class="section-lede">
      A phone appears above as soon as its build is finished and its install has been checked. These
      are the ones still being built. The <a href="devices.html">phones page</a> says where each one
      stands.
    </p>
    <div class="matrix">
{items}
    </div>
  </section>""".format(items=items)


def page(index):
    devices = index["devices"]
    sections = "\n\n".join(device_section(d, i % 2 == 1) for i, d in enumerate(devices))
    return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Download OBSIDIAN</title>
<meta name="description" content="Every OBSIDIAN release, with its date, size and SHA-256 checksum. Older releases are kept for good.">
<meta name="color-scheme" content="dark">
<link rel="stylesheet" href="style.css">
<link rel="icon" href="{favicon}">
</head>
<body>

<a class="skip" href="#main">Skip to content</a>

{nav}

<main id="main">

  <section class="hero compact">
    <p class="badge">Every release</p>
    <h1>Downloads</h1>
    <p class="lede">
      Every OBSIDIAN release is kept here, and older ones are never taken away. The browser installer
      finds the newest release for your phone by itself, so this page is for anyone who would rather
      have the file: to install from a computer, to check it before installing, or to keep a copy.
    </p>
    <p class="cta">
      <a class="button" href="web-install.html">Install from your browser instead</a>
      <a class="button quiet" href="install.html">How to install</a>
    </p>
  </section>

{sections}{pending}

  <section class="band alt">
    <h2>Check what you downloaded</h2>
    <p class="section-lede">
      Every release has its SHA-256 published above. Work the same number out on your own computer and
      compare the two, character for character. If they differ, delete the file and download it again.
      Do not install it. Put the name of the file you downloaded where FILENAME is.
    </p>
    <div class="cards three">
      <article class="card">
        <h3>Windows</h3>
        <p>In Command Prompt, in the folder you downloaded to:</p>
        <p class="code">certutil -hashfile FILENAME.zip SHA256</p>
      </article>
      <article class="card">
        <h3>macOS</h3>
        <p>In Terminal:</p>
        <p class="code">shasum -a 256 FILENAME.zip</p>
      </article>
      <article class="card">
        <h3>Linux</h3>
        <p>In a terminal:</p>
        <p class="code">sha256sum FILENAME.zip</p>
      </article>
    </div>
  </section>

  <section class="band">
    <h2>What is inside</h2>
    <p class="section-lede">
      The zip holds the whole operating system and the firmware that belongs with it, laid out the way
      Google's own factory images are, plus two scripts that install it for you.
    </p>
    <div class="matrix">
      <div class="item">
        <h3>flash-all.bat and flash-all.sh</h3>
        <p>Run the first on Windows, the second on macOS or Linux. Each checks it is talking to the
        right phone, then installs everything in the right order. You need Google's platform tools and
        an unlocked bootloader first, and the install page covers both.</p>
      </div>
      <div class="item">
        <h3>bootloader and radio images</h3>
        <p>The phone's own firmware, at the versions this build expects. They go on before the system,
        because a mismatch makes the phone refuse the rest halfway through.</p>
      </div>
      <div class="item">
        <h3>image zip</h3>
        <p>OBSIDIAN itself: the system, its apps and the settings that cannot be changed.</p>
      </div>
      <div class="item">
        <h3>README.txt</h3>
        <p>The short version of the install steps, so the file still makes sense on its own long after
        you downloaded it.</p>
      </div>
    </div>
  </section>

  <section class="band alt closing">
    <h2>These are test builds</h2>
    <p class="section-lede">
      Every release here is signed with test keys. The phone starts and everything works, but it shows
      a warning each time it starts and the bootloader has to stay unlocked, so this is for trying
      OBSIDIAN out rather than for protecting anyone yet. Do not lock the bootloader after installing
      one: a phone locked to an operating system it cannot verify refuses to start.
    </p>
    <p class="cta"><a class="button" href="web-install.html">Install from your browser</a></p>
  </section>

</main>

{foot}

</body>
</html>
""".format(favicon=FAVICON, nav=NAV, sections=sections, pending=pending_section(index.get("pending", [])),
           foot=FOOT)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--index", required=True, help="releases/index.json")
    parser.add_argument("--out", required=True, help="downloads.html to write")
    args = parser.parse_args()
    index = json.load(open(args.index))
    for dev in index["devices"]:
        for rel in dev["releases"]:
            for field in ("version", "date", "file", "size", "sha256"):
                if field not in rel:
                    sys.exit("%s %s has no %s" % (dev["device"], rel.get("version", "?"), field))
            if len(rel["sha256"]) != 64:
                sys.exit("%s %s has a malformed sha256" % (dev["device"], rel["version"]))
    with open(args.out, "w", newline="\n") as out:
        out.write(page(index))
    total = sum(len(d["releases"]) for d in index["devices"])
    print("%s: %d release(s) across %d phone(s)" % (args.out, total, len(index["devices"])))


main()
