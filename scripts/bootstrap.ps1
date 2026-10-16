$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$wrapperPath = Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.jar'
Invoke-WebRequest 'https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $wrapperPath
Write-Host 'Gradle wrapper downloaded. Use JDK 17, install Android SDK 35, then run .\gradlew.bat assembleDebug testDebugUnitTest lintDebug'
