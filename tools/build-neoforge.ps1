param(
    [string]$JavaHome = $(if ($env:ENDCRAFT_JAVA_HOME) {$env:ENDCRAFT_JAVA_HOME} else {Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-epsilon'}),
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {throw 'Provide a JDK 25 path with -JavaHome.'}
$env:JAVA_HOME = $JavaHome
Push-Location (Join-Path $taskRoot 'mc-neoforge')
try {
    $taskArguments = @("-Dorg.gradle.java.home=$JavaHome",'build','--console=plain')
    if ($Offline) {$taskArguments += '--offline'}
    & .\gradlew.bat @taskArguments
    if ($LASTEXITCODE) {throw "NeoForge build failed ($LASTEXITCODE)."}
} finally {Pop-Location}
