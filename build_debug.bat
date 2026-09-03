@echo off
REM Build + install the debug APK to a connected device.
REM
REM Previously pointed at "D:\Claude Projects\PokedexBinder" - the pre-V2
REM project, since deleted - so it could never run. Now script-relative.
REM
REM Also previously used the Android Studio JBR (Java 21) while run_build.bat
REM used JDK 17; both now use JDK 17, which matches jvmTarget in
REM app\build.gradle.kts.

call "%~dp0run_build.bat" installDebug
