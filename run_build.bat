@echo off
REM Gradle passthrough wrapper. Usage:  run_build.bat assembleDebug
REM
REM Uses %~dp0 (this script's own directory) rather than a hardcoded absolute
REM path. The previous version pointed at "D:\Claude Projects\PokedexBinder"
REM - the pre-V2 project, since deleted - so it could never work. Keeping it
REM relative means a rename or move cannot break it the same way again.

set "JAVA_HOME=D:\jdk17\jdk-17.0.14+7"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "TEMP=C:\Windows\Temp"
set "TMP=C:\Windows\Temp"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JAVA_HOME not found at %JAVA_HOME%
    echo This build targets Java 17 - see jvmTarget in app\build.gradle.kts
    exit /b 1
)

cd /d "%~dp0"
call "%~dp0gradlew.bat" %*
