@echo off
REM UI dump helper for the D1 prototype (invoked from WSL via cmd.exe).
REM
REM NOTE: keep this file ASCII-only. cmd.exe reads .bat in the local ANSI
REM codepage (GBK here), so UTF-8 CJK comments get mangled and their broken
REM bytes are executed as commands. Rationale lives in HANGOFF 12.5.
REM
REM WHY this script exists (lesson 2026-09-17): calling
REM   uiautomator dump /sdcard/x.xml
REM directly writes debug scratch files into the user's internal storage root,
REM where they show up in file managers and over USB. That pollutes the device.
REM This script always dumps to /data/local/tmp (the Android debug scratch dir),
REM which is not user-visible and is not media-scanned.
REM
REM Usage: dumpui.bat NAME
REM   dumpui.bat t1  ->  /data/local/tmp/t1.xml, pulled to repo\artifacts\t1.xml
setlocal
set SDK_ADB=C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe
if "%ADB_SERIAL%"=="" set ADB_SERIAL=192.168.43.1:4444
if "%~1"=="" goto usage

set NAME=%~1
set REMOTE=/data/local/tmp/%NAME%.xml
set LOCAL=%~dp0artifacts\%NAME%.xml

"%SDK_ADB%" -s %ADB_SERIAL% shell uiautomator dump %REMOTE% >nul 2>&1
if errorlevel 1 goto failed
"%SDK_ADB%" -s %ADB_SERIAL% pull %REMOTE% "%LOCAL%" >nul 2>&1
if errorlevel 1 goto failed
echo dumped: %LOCAL%
endlocal
exit /b 0

:usage
echo usage: dumpui.bat NAME
endlocal
exit /b 2

:failed
echo dump failed: %REMOTE%
endlocal
exit /b 1
