param(
    [string]$JavaHome = $(if ($env:ENDCRAFT_JAVA_HOME) { $env:ENDCRAFT_JAVA_HOME } else { Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-epsilon' }),
    [ValidateSet('fabric','neoforge')]
    [string]$Loader = $(if ($env:ENDCRAFT_GUEST_LOADER) {$env:ENDCRAFT_GUEST_LOADER} else {'fabric'})
)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
if ($Loader -eq 'neoforge') {
    New-Item -ItemType Directory -Path (Join-Path $taskRoot 'reports') -Force | Out-Null
    & (Join-Path $PSScriptRoot 'start-neoforge.ps1') -BridgeHost -JavaHome $JavaHome *> (Join-Path $taskRoot 'reports\neoforge-startup.log')
    exit $LASTEXITCODE
}
$taskLog=Join-Path $taskRoot 'reports\guest-startup.log'
try {
    $taskNeoClients = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains('-Dendcraft.allowHostConnection=true')
    })
    if ($taskNeoClients.Count) {throw 'A NeoForge EndCraft guest is running. Save and quit it normally before switching to Fabric.'}
    $taskClients=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine -and $_.CommandLine.Contains('net.fabricmc.devlaunchinjector.Main') -and ($_.CommandLine.Contains((Join-Path $taskRoot 'mc')) -or $_.CommandLine.Contains('-Dskycraft.startHidden=true'))})
    if($taskClients.Count) {
        if($taskClients.Count -eq 1 -and $taskClients[0].CommandLine.Contains((Join-Path $taskRoot 'mc'))) {
            Write-Output 'Project guest is already running.'
            exit 0
        }
        throw 'Another EndCraft guest is running. Preserve it and avoid two writers to shared memory.'
    }
    . (Join-Path $PSScriptRoot 'guest-process.ps1')
    if (Test-EndCraftGuestRunning) {throw 'An EndCraft guest already owns the bridge (possibly elevated). Save and quit it normally before switching.'}
    New-Item -ItemType Directory -Path (Split-Path -Parent $taskLog) -Force | Out-Null
    # Reuse a complete versioned asset cache; avoid revalidating all assets online on every launch.
    $taskProperties=[IO.File]::ReadAllText((Join-Path $taskRoot 'mc\gradle.properties'))
    $taskVersion=[regex]::Match($taskProperties,'(?m)^minecraft_version=(.+)$').Groups[1].Value.Trim()
    $taskAssets=Join-Path $env:USERPROFILE '.gradle\caches\fabric-loom\assets'
    $taskIndex=Get-ChildItem -LiteralPath (Join-Path $taskAssets 'indexes') -Filter "$taskVersion-*.json" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $taskAssetsReady=$false
    if($taskIndex) {
        $taskObjects=@(([IO.File]::ReadAllText($taskIndex.FullName) | ConvertFrom-Json).objects.PSObject.Properties)
        $taskAssetsReady=$taskObjects.Count -gt 0
        foreach($taskEntry in $taskObjects) {
            $taskHash=$taskEntry.Value.hash
            if($taskHash -notmatch '^[0-9a-f]{40}$') {$taskAssetsReady=$false;break}
            $taskAsset=Get-Item -LiteralPath (Join-Path $taskAssets ('objects\'+$taskHash.Substring(0,2)+'\'+$taskHash)) -ErrorAction SilentlyContinue
            if(!$taskAsset -or $taskAsset.Length -ne $taskEntry.Value.size) {$taskAssetsReady=$false;break}
        }
    }
    if($taskAssetsReady) {
        & (Join-Path $PSScriptRoot 'build-mc.ps1') -Run -Offline -SkipAssets -JavaHome $JavaHome *> $taskLog
    } else {
        & (Join-Path $PSScriptRoot 'build-mc.ps1') -Run -JavaHome $JavaHome *> $taskLog
    }
    if($LASTEXITCODE) {throw "Guest exited with code $LASTEXITCODE"}
} catch {
    try { $_.Exception.Message | Add-Content -LiteralPath $taskLog } catch { [Console]::Error.WriteLine('Guest startup failed; the existing startup log is in use. Read the original launch output.') }
    exit 1
}
