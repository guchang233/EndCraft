param([ValidateSet('status','open_world','fixture','ship','stop','third_person','render_audit','overlay_audit','stage_probe','pick_probe','rendertype_probe')][string]$Operation='status')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskJdk=Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-epsilon\bin'
$taskClients=@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $_.CommandLine -and $_.CommandLine.Contains('net.neoforged.devlaunch.Main') -and $_.CommandLine.Contains((Join-Path $taskRoot 'mc-neoforge'))
})
if($taskClients.Count -ne 1){throw 'Exactly one project NeoForge development client required.'}
$taskAgentRoot=Join-Path $taskRoot 'build\neo-test-agent'
New-Item -ItemType Directory -Force -Path $taskAgentRoot | Out-Null
$taskSource=Join-Path $PSScriptRoot 'EndCraftNeoTestAgent.java'
$taskHash=(Get-FileHash -Algorithm SHA256 -LiteralPath $taskSource).Hash.Substring(0,12)
$taskJar=Join-Path $taskAgentRoot ("endcraft-neo-test-agent-$taskHash.jar")
if (!(Test-Path -LiteralPath $taskJar)) {
# An attached JVM keeps the first agent class it loaded; name each source revision uniquely.
$taskClass="EndCraftNeoTestAgent_$taskHash"
$taskVersioned=Join-Path $taskAgentRoot "$taskClass.java"
[IO.File]::WriteAllText($taskVersioned,[IO.File]::ReadAllText($taskSource).Replace('EndCraftNeoTestAgent',$taskClass))
& (Join-Path $taskJdk 'javac.exe') --add-modules jdk.attach -d $taskAgentRoot $taskVersioned
if($LASTEXITCODE){throw 'Neo test agent compile failed.'}
$taskManifest=Join-Path $taskAgentRoot 'MANIFEST.MF'
[IO.File]::WriteAllText($taskManifest,"Manifest-Version: 1.0`r`nAgent-Class: $taskClass`r`n`r`n")
& (Join-Path $taskJdk 'jar.exe') cfm $taskJar $taskManifest -C $taskAgentRoot "$taskClass.class"
if($LASTEXITCODE){throw 'Neo test agent packaging failed.'}
}
& (Join-Path $taskJdk 'java.exe') --add-modules jdk.attach -cp $taskJar "EndCraftNeoTestAgent_$taskHash" $taskClients[0].ProcessId $taskJar $Operation
if($LASTEXITCODE){throw 'Neo runtime diagnostic attach failed.'}
