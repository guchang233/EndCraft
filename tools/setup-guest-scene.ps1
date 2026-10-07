param([string]$GuestRoot=(Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskJdk='C:\Users\sow\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon\bin'
$taskClients=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine.Contains((Join-Path $GuestRoot 'mc')) -and $_.CommandLine.Contains('net.fabricmc.devlaunchinjector.Main')})
if($taskClients.Count -ne 1) {throw 'Exactly one project-owned MC client required.'}
$taskDirectory=Join-Path $taskRoot 'build\scene-agent'
New-Item -ItemType Directory -Force -Path $taskDirectory | Out-Null
$taskJar=Join-Path $taskDirectory 'scene-agent.jar'
if(!(Test-Path -LiteralPath $taskJar)) {
    & (Join-Path $taskJdk 'javac.exe') --add-modules jdk.attach -d $taskDirectory (Join-Path $PSScriptRoot 'EndCraftSceneAgent.java')
    if($LASTEXITCODE) {throw 'Diagnostic compile failed.'}
    $taskManifest=Join-Path $taskDirectory 'MANIFEST.MF'
    [IO.File]::WriteAllText($taskManifest,"Manifest-Version: 1.0`r`nAgent-Class: EndCraftSceneAgent`r`n`r`n")
    & (Join-Path $taskJdk 'jar.exe') cfm $taskJar $taskManifest -C $taskDirectory EndCraftSceneAgent.class
    if($LASTEXITCODE) {throw 'Diagnostic package failed.'}
}
& (Join-Path $taskJdk 'java.exe') --add-modules jdk.attach -cp $taskJar EndCraftSceneAgent $taskClients[0].ProcessId $taskJar
if($LASTEXITCODE) {throw 'Guest diagnostic failed.'}
