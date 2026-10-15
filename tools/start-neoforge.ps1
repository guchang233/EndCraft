param(
    [string]$JavaHome = $(if ($env:ENDCRAFT_JAVA_HOME) {$env:ENDCRAFT_JAVA_HOME} else {Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-epsilon'}),
    [switch]$BridgeHost,
    [switch]$ShaderPreview,
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
if ($ShaderPreview) {
    python (Join-Path $PSScriptRoot 'fetch-neoforge-mods.py') --shaders
    if ($LASTEXITCODE) {throw 'Could not verify the optional shader dependencies.'}
    $taskShaderSource=Join-Path $taskRoot 'mc-neoforge\SHADER.zip'
    if (!(Test-Path -LiteralPath $taskShaderSource -PathType Leaf)) {throw 'Put your shader pack at mc-neoforge\SHADER.zip first.'}
    $taskPackRoot=Join-Path $taskRoot 'mc-neoforge\run\shaderpacks'
    New-Item -ItemType Directory -Force -Path $taskPackRoot | Out-Null
    Copy-Item -LiteralPath $taskShaderSource -Destination (Join-Path $taskPackRoot 'SHADER.zip')
    $taskConfig=Join-Path $taskRoot 'mc-neoforge\run\config\iris.properties'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $taskConfig) | Out-Null
    if ((Test-Path -LiteralPath $taskConfig) -and !(Test-Path -LiteralPath "$taskConfig.endcraft-backup")) {
        Copy-Item -LiteralPath $taskConfig -Destination "$taskConfig.endcraft-backup"
    }
    $taskLines=@(if (Test-Path -LiteralPath $taskConfig) {Get-Content -LiteralPath $taskConfig})
    $taskLines=@($taskLines | Where-Object {$_ -notmatch '^\s*(enableShaders|shaderPack)\s*='})
    $taskLines += 'enableShaders=true','shaderPack=SHADER.zip'
    [IO.File]::WriteAllLines($taskConfig,$taskLines,[Text.UTF8Encoding]::new($false))
}
$env:JAVA_HOME = $JavaHome
Push-Location (Join-Path $taskRoot 'mc-neoforge')
try {
    $taskArguments = @("-Dorg.gradle.java.home=$JavaHome",'runClient','--console=plain')
    if ($BridgeHost) {$taskArguments += '-PbridgeHost=true'}
    if ($ShaderPreview) {$taskArguments += '-PshaderPreview=true'}
    if ($Offline) {$taskArguments += '--offline'}
    & .\gradlew.bat @taskArguments
    if ($LASTEXITCODE) {throw "NeoForge client exited ($LASTEXITCODE)."}
} finally {Pop-Location}
