param()
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskJdk=Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-epsilon\bin'
$taskClients=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine.Contains((Join-Path $taskRoot 'mc')) -and $_.CommandLine.Contains('net.fabricmc.devlaunchinjector.Main')})
if($taskClients.Count -ne 1) {throw 'Exactly one project-owned MC client required.'}
$taskDirectory=Join-Path $taskRoot 'build\flight-trial-controlled-agent'
New-Item -ItemType Directory -Force -Path $taskDirectory | Out-Null
$taskJar=Join-Path $taskDirectory 'flight-trial-controlled-agent.jar'
if(!(Test-Path -LiteralPath $taskJar)) {
    & (Join-Path $taskJdk 'javac.exe') --add-modules jdk.attach -d $taskDirectory (Join-Path $PSScriptRoot 'EndCraftControlledFlightAgent.java')
    if($LASTEXITCODE) {throw 'Trial compile failed.'}
    $taskManifest=Join-Path $taskDirectory 'MANIFEST.MF'
    [IO.File]::WriteAllText($taskManifest,"Manifest-Version: 1.0`r`nAgent-Class: EndCraftControlledFlightAgent`r`n`r`n")
    & (Join-Path $taskJdk 'jar.exe') cfm $taskJar $taskManifest -C $taskDirectory .
    if($LASTEXITCODE) {throw 'Trial package failed.'}
}
& (Join-Path $taskJdk 'java.exe') --add-modules jdk.attach -cp $taskJar EndCraftControlledFlightAgent $taskClients[0].ProcessId $taskJar
if($LASTEXITCODE) {throw 'Guest flight trial failed.'}
