$env:JAVA_HOME = "D:\jdk17\jdk-17.0.14+7"
Set-Location "D:\Claude Projects\PokedexBinderV2"
& ".\gradlew.bat" testDebugUnitTest --tests "com.skyler.pokedexbinder.domain.GeminiCardScannerTest" --rerun-tasks
exit $LASTEXITCODE
