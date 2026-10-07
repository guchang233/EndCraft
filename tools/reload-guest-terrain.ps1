$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskJdk=Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-epsilon\bin'
$taskClients=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {$_.CommandLine.Contains((Join-Path $taskRoot 'mc')) -and $_.CommandLine.Contains('net.fabricmc.devlaunchinjector.Main')})
if($taskClients.Count -ne 1){throw 'Exactly one project-owned development guest required.'}
$taskDirectory=Join-Path $taskRoot 'build\terrain-reload-agent'
New-Item -ItemType Directory -Force -Path $taskDirectory | Out-Null
& (Join-Path $taskJdk 'javac.exe') --add-modules jdk.attach -d $taskDirectory (Join-Path $PSScriptRoot 'EndCraftTerrainReloadAgent.java')
if($LASTEXITCODE){throw 'Compile failed.'}
$taskManifest=Join-Path $taskDirectory 'MANIFEST.MF'
[IO.File]::WriteAllText($taskManifest,"Manifest-Version: 1.0`r`nAgent-Class: EndCraftTerrainReloadAgent`r`nCan-Redefine-Classes: true`r`n`r`n")
$taskJar=Join-Path $taskDirectory 'terrain-reload.jar'
& (Join-Path $taskJdk 'jar.exe') cfm $taskJar $taskManifest -C $taskDirectory EndCraftTerrainReloadAgent.class
if($LASTEXITCODE){throw 'Package failed.'}
& (Join-Path $taskJdk 'java.exe') --add-modules jdk.attach -cp $taskJar EndCraftTerrainReloadAgent $taskClients[0].ProcessId $taskJar (Join-Path $taskRoot 'mc\build\classes\java\main\dev\skycraft\world\SmoothTerrainCollision.class')
if($LASTEXITCODE){throw 'Terrain solver reload failed.'}
'Project guest terrain solver reloaded.'
