@echo off
REM Activate the project toolchain through mise (.mise.toml: java = "temurin-21")
REM before calling gradlew.
REM
REM Why mise is mandatory here: the ambient JAVA_HOME on this machine is a
REM temurin-17 inherited from WSL, so a plain `call gradlew.bat` runs on 17.
REM Robolectric 4.17 needs **Java 21** to create an SDK 36 sandbox and fails
REM with "Failed to create a Robolectric sandbox: Android SDK 36 requires
REM Java 21 (have Java 17)". mise exec switches PATH and JAVA_HOME together to
REM the version declared in .mise.toml, so a hand-run command and CI share one
REM declaration instead of depending on external environment variables.
REM
REM The extra `cmd /c` wrapper is not redundant: `mise exec -- gradlew.bat`
REM fails with "cannot find binary path" because mise does not resolve .bat
REM files as executable entry points.
REM
REM Keep this file pure ASCII. cmd.exe parses .bat/.cmd in the OEM code page
REM (CP936 here); UTF-8 non-ASCII comments can pair across lines and get
REM executed as commands.
cd /d D:\Programing\Personal\pixfold\android
call mise exec -- cmd /c gradlew.bat %*
