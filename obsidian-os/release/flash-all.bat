@ECHO OFF
:: Installs OBSIDIAN on a Google @MODEL@ that is in bootloader mode, and erases everything on it.
::
:: Needs fastboot from Google's Android SDK Platform-Tools, on your PATH or in this folder:
::   https://developer.android.com/tools/releases/platform-tools
:: The bootloader must already be unlocked: fastboot flashing unlock, then confirm on the phone.
::
:: This is a test build, signed with test keys. Leave the bootloader unlocked afterwards: a phone
:: locked to an operating system it cannot verify refuses to start.
cd /d "%~dp0"

fastboot getvar product 2>&1 | findstr /r /c:"^product: *@DEVICE@" >nul
if errorlevel 1 (
  echo No @MODEL@ found in bootloader mode. Nothing was changed.
  pause
  exit /b 1
)

:: The firmware goes onto both slots, not just the one in use. A phone keeps two copies of the
:: system and can fall back to the other one, but only if that copy's firmware matches. Writing one
:: slot leaves the other holding firmware from whatever was on the phone before, and a phone that
:: then tries the old slot fails to start and marks it dead.
echo Firmware, both slots
fastboot flash --slot=other bootloader @BOOTLOADER@ || goto failed
fastboot --set-active=other
fastboot reboot-bootloader
ping -n 6 127.0.0.1 >nul
fastboot flash --slot=other bootloader @BOOTLOADER@ || goto failed
fastboot --set-active=other
fastboot reboot-bootloader
ping -n 6 127.0.0.1 >nul
fastboot flash radio @RADIO@ || goto failed
fastboot reboot-bootloader
ping -n 6 127.0.0.1 >nul

:: Three partitions hold firmware signed separately from the system, and they keep whatever the
:: previous operating system put there. Left alone they can disagree with the system being installed
:: and stop the phone starting, so they are cleared and the phone rebuilds them. Each is allowed to
:: fail, because which of them exists differs between phones.
echo Clearing firmware left by the previous system
fastboot erase avb_custom_key
fastboot oem uart disable
fastboot erase fips
fastboot erase dpm_a
fastboot erase dpm_b

:: The system itself. This is the long part, and the one that goes wrong: it sends about two
:: gigabytes in chunks, and on Windows the USB connection to this phone sometimes drops partway
:: through. A drop leaves the phone with half a system and nothing to start, which looks alarming
:: and is not: writing it again from the beginning fixes it, so that is what happens here.
echo The system, about two gigabytes
fastboot -w update @IMAGE@ && goto done

echo.
echo That transfer failed partway through, which is usually the USB connection rather than the
echo phone. Attempt 2 of 3, starting again from the beginning.
echo If it keeps failing: use the cable that came with the phone, plug straight into the
echo computer rather than a hub or a monitor, and try a different port.
echo.
fastboot reboot-bootloader
ping -n 6 127.0.0.1 >nul
fastboot -w update @IMAGE@ && goto done

echo.
echo Attempt 3 of 3.
echo.
fastboot reboot-bootloader
ping -n 6 127.0.0.1 >nul
fastboot -w update @IMAGE@ && goto done

echo.
echo The system could not be written after three attempts, so the phone has no working system on
echo it at the moment. It is not broken and nothing is permanent. Hold the power button for ten
echo seconds to turn it off, then hold volume down and press power to reach Fastboot Mode again,
echo and run this file once more. A different USB cable or port fixes this most of the time.
pause
exit /b 1

:done
echo Done. The phone is starting OBSIDIAN. The first start takes a few minutes.
pause
exit /b 0

:failed
echo Flashing stopped with an error. Put the phone back in bootloader mode and run this again.
pause
exit /b 1
