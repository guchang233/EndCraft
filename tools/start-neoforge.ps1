param(
    [string]$JavaHome = $(if ($env:ENDCRAFT_JAVA_HOME) {$env:ENDCRAFT_JAVA_HOME} else {Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-epsilon'}),
    [switch]$BridgeHost,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {throw 'Provide a JDK 25 path with -JavaHome.'}
if ($BridgeHost) {
    . (Join-Path $PSScriptRoot 'guest-process.ps1')
    if (Test-EndCraftGuestRunning) {throw 'An EndCraft guest already owns the bridge (possibly elevated). Save and quit it normally before switching.'}
    $taskClients = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and ($_.CommandLine.Contains('-Dskycraft.startHidden=true') -or $_.CommandLine.Contains('-Dendcraft.allowHostConnection=true'))
    })
    if ($taskClients.Count) {throw 'An EndCraft guest already owns the bridge. Save and quit it normally before switching.'}
}
python (Join-Path $PSScriptRoot 'fetch-neoforge-mods.py')
if ($LASTEXITCODE) {throw 'Could not verify the locked Create/Aeronautics dependencies.'}
$env:JAVA_HOME = $JavaHome
Push-Location (Join-Path $taskRoot 'mc-neoforge')
try {
    $taskArguments = @("-Dorg.gradle.java.home=$JavaHome",'runClient','--console=plain')
    if ($BridgeHost) {$taskArguments += '-PbridgeHost=true'}
    if ($Offline) {$taskArguments += '--offline'}
    & .\gradlew.bat @taskArguments
    if ($LASTEXITCODE) {throw "NeoForge client exited ($LASTEXITCODE)."}
} finally {Pop-Location}
