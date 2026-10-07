param([ValidateSet('status','third_person','test_block','stop')][string]$Operation='status',[string]$GuestRoot=(Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskJdk='C:\Users\sow\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon\bin'
$taskClients=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine.Contains((Join-Path $GuestRoot 'mc')) -and $_.CommandLine.Contains('net.fabricmc.devlaunchinjector.Main') })
if($taskClients.Count -ne 1) {throw 'Exactly one project-owned MC development client required.'}
$taskAgentRoot=Join-Path $taskRoot 'build\debug-agent'
New-Item -ItemType Directory -Force -Path $taskAgentRoot | Out-Null
& (Join-Path $taskJdk 'javac.exe') --add-modules jdk.attach -d $taskAgentRoot (Join-Path $PSScriptRoot 'EndCraftDebugAgent.java')
if($LASTEXITCODE) {throw 'Agent compile failed.'}
$taskManifest=Join-Path $taskAgentRoot 'MANIFEST.MF'
[IO.File]::WriteAllText($taskManifest,"Manifest-Version: 1.0`r`nAgent-Class: EndCraftDebugAgent`r`n`r`n")
$taskJar=Join-Path $taskAgentRoot 'endcraft-debug-agent.jar'
if(!(Test-Path -LiteralPath $taskJar)) {
    & (Join-Path $taskJdk 'jar.exe') cfm $taskJar $taskManifest -C $taskAgentRoot EndCraftDebugAgent.class
    if($LASTEXITCODE) {throw 'Agent package failed.'}
}
& (Join-Path $taskJdk 'java.exe') --add-modules jdk.attach -cp $taskJar EndCraftDebugAgent $taskClients[0].ProcessId $taskJar $Operation
if($LASTEXITCODE) {throw 'Project MC debug operation failed.'}
