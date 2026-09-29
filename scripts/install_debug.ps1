$env:JAVA_HOME = "D:\jdk17\jdk-17.0.14+7"
Set-Location "D:\Claude Projects\PokedexBinderV2"
& ".\gradlew.bat" installDebug --rerun-tasks
exit $LASTEXITCODE
