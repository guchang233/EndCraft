param([string]$JavaHome = (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-epsilon'), [switch]$Run, [switch]$Offline, [switch]$SkipAssets)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) { throw 'Provide a JDK 25 path with -JavaHome.' }
$env:JAVA_HOME = $JavaHome
Push-Location (Join-Path $taskRoot 'mc')
try {
    $taskAction = if ($Run) { 'runClient' } else { 'build' }
    $taskArguments=@("-Dorg.gradle.java.home=$JavaHome",$taskAction,'--console=plain','--no-configuration-cache')
    if($Offline) {$taskArguments+='--offline'}
    if($Run -and $SkipAssets) {$taskArguments+=@('-x','downloadAssets')}
    & .\gradlew.bat @taskArguments
    if ($LASTEXITCODE -ne 0) { throw "MC $taskAction failed ($LASTEXITCODE)." }
} finally { Pop-Location }
