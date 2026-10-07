param([ValidateSet('status','boat','minecart','cleanup','terrain','tnt','player_exit')][string]$Operation='status')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskJdk=Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-epsilon\bin'
$taskClients=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine.Contains((Join-Path $taskRoot 'mc')) -and $_.CommandLine.Contains('net.fabricmc.devlaunchinjector.Main')})
if($taskClients.Count -ne 1){throw 'Exactly one project-owned MC client required.'}
$taskDirectory=Join-Path $taskRoot 'build\vehicle-check-v6-agent'
New-Item -ItemType Directory -Force -Path $taskDirectory | Out-Null
$taskJar=Join-Path $taskDirectory 'vehicle-check.jar'
if(!(Test-Path -LiteralPath $taskJar)){
 & (Join-Path $taskJdk 'javac.exe') --add-modules jdk.attach -d $taskDirectory (Join-Path $PSScriptRoot 'EndCraftVehicleCheckAgentV6.java')
 if($LASTEXITCODE){throw 'Compile failed.'}
 $taskManifest=Join-Path $taskDirectory 'MANIFEST.MF'
 [IO.File]::WriteAllText($taskManifest,"Manifest-Version: 1.0`r`nAgent-Class: EndCraftVehicleCheckAgentV6`r`n`r`n")
 & (Join-Path $taskJdk 'jar.exe') cfm $taskJar $taskManifest -C $taskDirectory .
 if($LASTEXITCODE){throw 'Package failed.'}
}
& (Join-Path $taskJdk 'java.exe') --add-modules jdk.attach -cp $taskJar EndCraftVehicleCheckAgentV6 $taskClients[0].ProcessId $taskJar $Operation
if($LASTEXITCODE){throw 'Interaction check failed.'}
