@echo off
REM Convenience wrapper for the D1 real-device debug session (called from WSL
REM via cmd.exe). Keep ASCII-only: cmd.exe reads .bat in the local ANSI
REM codepage, so UTF-8 CJK comments can be mangled and executed as commands.
REM
REM Default device = the user's phone fixed wireless-debug address.
REM Override with the ADB_SERIAL environment variable.
REM
REM Usage: adb.bat shell input tap 632 210
REM        adb.bat devices
REM
REM ---------------------------------------------------------------------------
REM GUARD (added 2026-09-21, second offence): never let a uiautomator dump land
REM in user-visible storage.
REM
REM   `uiautomator dump /sdcard/x.xml` writes into the device's USER STORAGE
REM   ROOT -- visible in file managers and over USB. HANGOFF 12.5 has forbidden
REM   this since 2026-09-17 (one walkthrough once left 59 stray xml files), and
REM   `dumpui.bat` exists precisely to route dumps to /data/local/tmp.
REM
REM   The rule was already written down and still got broken, because a plain
REM   `adb.bat shell ...` passthrough makes the mistake the path of least
REM   resistance. So the wrapper REFUSES the dangerous command outright instead
REM   of relying on memory. Use:  dumpui.bat NAME   (writes /data/local/tmp).
REM
REM   Implementation note: keep this FLAT (one-line condition + goto). Wrapping
REM   `echo %* | findstr ...` inside `if ... ( ... )` breaks under cmd.exe --
REM   the pipe inside a parenthesised block gets parsed as findstr arguments.
REM ---------------------------------------------------------------------------
setlocal
set SDK_ADB=C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe
if "%ADB_SERIAL%"=="" set ADB_SERIAL=192.168.43.1:4444

echo %* | findstr /I /C:"uiautomator" >nul || goto run
echo %* | findstr /I /C:"/sdcard" >nul && goto block
echo %* | findstr /I /C:"/storage/emulated" >nul && goto block
goto run

:block
echo.
echo ERROR: refused a uiautomator dump into user-visible storage.
echo        /sdcard and /storage/emulated are visible in file managers and
echo        over USB, so dumps there pollute the user's device.
echo        Use instead:  dumpui.bat NAME   (writes /data/local/tmp)
echo.
endlocal
exit /b 3

:run
"%SDK_ADB%" -s %ADB_SERIAL% %*
endlocal
