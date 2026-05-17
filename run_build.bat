@echo off
set "JAVA_HOME=D:\jdk17\jdk-17.0.14+7"
set "TEMP=C:\Windows\Temp"
set "TMP=C:\Windows\Temp"
cd /d "D:\Claude Projects\PokedexBinder"
call "D:\Claude Projects\PokedexBinder\gradlew.bat" %*
