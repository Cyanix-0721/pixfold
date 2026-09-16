@echo off
REM D1 真机调试便捷脚本(WSL 侧经 cmd.exe 调用)。
REM 默认设备 = 用户手机的固定无线调试地址;可用环境变量 ADB_SERIAL 覆盖。
REM 用法: adb.bat shell input tap 632 210
setlocal
set SDK_ADB=C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe
if "%ADB_SERIAL%"=="" set ADB_SERIAL=192.168.43.1:4444
"%SDK_ADB%" -s %ADB_SERIAL% %*
endlocal
