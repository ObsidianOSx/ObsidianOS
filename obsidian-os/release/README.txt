OBSIDIAN @VERSION@ for the Google Pixel 8

This is a test build. It is signed with test keys, so the bootloader stays unlocked and the phone
shows a warning each time it starts. It is for evaluation, not yet for protecting anyone.

Installing it erases everything on the phone.

The easy way
  Use the browser installer on the OBSIDIAN website, in Chrome, Edge or Brave on a computer.

From a computer, with Google's platform tools installed
  1. On the phone, turn on OEM unlocking in Settings, System, Developer options.
  2. Start the phone in bootloader mode: hold Volume Down and press Power. Connect it.
  3. Run  fastboot flashing unlock  and confirm on the phone.
  4. Run flash-all.bat on Windows, or flash-all.sh on macOS and Linux.
  Do not lock the bootloader afterwards.

Checking your download
  Its SHA-256 is published beside it. Compare the two before installing.

Source code
  https://github.com/ObsidianOSx/ObsidianOS
  The phone image also contains open source components under their own licences, listed on the
  phone in Settings, About phone, Legal information. Source code for the components licensed under
  the GPL is available on request: open an issue at the address above.
