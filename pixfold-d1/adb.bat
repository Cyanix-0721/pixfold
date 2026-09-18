@echo off
REM Convenience wrapper for the D1 real-device debug session (called from WSL
REM via cmd.exe). Keep ASCII-only: cmd.exe reads .bat in the local ANSI
REM codepage, so UTF-8 CJK comments can be mangled and executed as commands.
REM
REM Default device = the user's phone fixed wireless-debug address.
REM Override with the ADB_SERIAL environment variable.
REM
REM Usage: adb.bat shell input tap 632 210
setlocal
set SDK_ADB=C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe
if "%ADB_SERIAL%"=="" set ADB_SERIAL=192.168.43.1:4444
"%SDK_ADB%" -s %ADB_SERIAL% %*
endlocal
