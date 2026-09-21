Checks the browser installer's flashing plan without a phone.

Runs the site's own docs/js/fastboot.mjs against a real release zip, with a stand-in that answers like
a Pixel 8 in bootloader and fastbootd mode, and prints every command it would send: which partitions,
which slot, in which mode, and how much data. Compare the result with the image's fastboot-info.txt
(unzip -p image-*.zip fastboot-info.txt). Run it before publishing a release.

  node --max-old-space-size=12000 emulate.mjs obsidian-shiba-<version>.zip

Needs Node 20 or newer and about 10 GB of free memory for a 2 GB release. shim.mjs supplies the
browser pieces the library expects; Node's own Blob overflows its stack on gigabyte reads.
