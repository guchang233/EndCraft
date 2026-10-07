$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskLog=Join-Path $taskRoot 'reports\guest-startup.log'
try {
    $taskClients=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine.Contains('net.fabricmc.devlaunchinjector.Main') -and $_.CommandLine.Contains('MC x ENDFIELD')})
    if($taskClients.Count) {
        if($taskClients.Count -eq 1 -and $taskClients[0].CommandLine.Contains((Join-Path $taskRoot 'mc'))) {
            'Project guest is already running.' | Set-Content -LiteralPath $taskLog
            exit 0
        }
        throw 'Another EndCraft guest is running. Preserve it and avoid two writers to shared memory.'
    }
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
        & (Join-Path $PSScriptRoot 'build-mc.ps1') -Run -Offline -SkipAssets *> $taskLog
    } else {
        & (Join-Path $PSScriptRoot 'build-mc.ps1') -Run *> $taskLog
    }
    if($LASTEXITCODE) {throw "Guest exited with code $LASTEXITCODE"}
} catch {
    $_.Exception.Message | Add-Content -LiteralPath $taskLog
    exit 1
}
