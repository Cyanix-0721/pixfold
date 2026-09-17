@echo off
REM 真机 UI dump 便捷脚本(WSL 侧经 cmd.exe 调用)。
REM
REM 为什么需要它(教训 2026-09-17):直接用 `uiautomator dump /sdcard/x.xml`
REM 会把调试中间文件写进**用户内部存储根目录**,在文件管理器/USB 里可见 ——
REM 属污染用户设备。本脚本统一写到 /data/local/tmp(Android 标准调试临时目录,
REM 不进用户可见空间、不被媒体扫描)。
REM
REM 用法: dumpui.bat <输出名,不含扩展名>
REM   dumpui.bat t1   ->  /data/local/tmp/t1.xml 拉到 <repo>\artifacts\t1.xml
setlocal
set SDK_ADB=C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe
if "%ADB_SERIAL%"=="" set ADB_SERIAL=192.168.43.1:4444
if "%~1"=="" (
  echo 用法: dumpui.bat ^<输出名^>
  exit /b 2
)
set NAME=%~1
set REMOTE=/data/local/tmp/%NAME%.xml
set LOCAL=%~dp0artifacts\%NAME%.xml

"%SDK_ADB%" -s %ADB_SERIAL% shell uiautomator dump %REMOTE% >nul
"%SDK_ADB%" -s %ADB_SERIAL% pull %REMOTE% "%LOCAL%" >nul
if errorlevel 1 (
  echo dump 失败: %REMOTE%
  exit /b 1
)
echo dumped: %LOCAL%
endlocal
