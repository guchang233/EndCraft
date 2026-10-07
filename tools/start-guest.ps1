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
    & (Join-Path $PSScriptRoot 'build-mc.ps1') -Run *> $taskLog
    if($LASTEXITCODE) {throw "Guest exited with code $LASTEXITCODE"}
} catch {
    $_.Exception.Message | Add-Content -LiteralPath $taskLog
    exit 1
}
