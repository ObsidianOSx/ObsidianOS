@ECHO OFF
:: Installs OBSIDIAN on a Google Pixel 8 that is in bootloader mode, and erases everything on it.
::
:: Needs fastboot from Google's Android SDK Platform-Tools, on your PATH or in this folder:
::   https://developer.android.com/tools/releases/platform-tools
:: The bootloader must already be unlocked: fastboot flashing unlock, then confirm on the phone.
::
:: This is a test build, signed with test keys. Leave the bootloader unlocked afterwards: a phone
:: locked to an operating system it cannot verify refuses to start.
cd /d "%~dp0"

fastboot getvar product 2>&1 | findstr /r /c:"^product: *shiba" >nul
if errorlevel 1 (
  echo No Pixel 8 found in bootloader mode. Nothing was changed.
  pause
  exit /b 1
)

fastboot flash bootloader @BOOTLOADER@ || goto failed
fastboot reboot-bootloader
ping -n 6 127.0.0.1 >nul
fastboot flash radio @RADIO@ || goto failed
fastboot reboot-bootloader
ping -n 6 127.0.0.1 >nul
fastboot -w update @IMAGE@ || goto failed

echo Done. The phone is starting OBSIDIAN. The first start takes a few minutes.
pause
exit /b 0

:failed
echo Flashing stopped with an error. Put the phone back in bootloader mode and run this again.
pause
exit /b 1
