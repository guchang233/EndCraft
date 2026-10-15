param([ValidateSet('status','open_world','render_test_world','loading_probe','abort_test_load','continue_world','render_test_ship','plot_transform_audit','atlas_request','atlas_poll','mesh_benchmark','shader_mesh','shader_preview','particle_seed','particle_audit','capture_frame','inventory','fixture','ship','stop','third_person','first_person','equip_gun','gun_refit','compat_audit','maid_walk','render_audit','overlay_audit','stage_probe','pick_probe','rendertype_probe','keys_probe','inject_quote','inject_f1','close_screen','key_watch')][string]$Operation='status', [string]$GameDirectory)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskJdk=Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-epsilon\bin'
if ($GameDirectory) {
    $taskGameDirectory=[IO.Path]::GetFullPath($GameDirectory).TrimEnd('\')
    if (!(Test-Path -LiteralPath $taskGameDirectory -PathType Container) -or
        !(Get-ChildItem -LiteralPath (Join-Path $taskGameDirectory 'mods') -Filter 'endcraft-neoforge-guest-*.jar' -ErrorAction SilentlyContinue)) {
        throw 'The selected NeoForge instance must contain this project mod.'
    }
}
$taskClients=@(Get-CimInstance Win32_Process | Where-Object {
    if ($_.Name -notin @('java.exe','javaw.exe') -or !$_.CommandLine) {return $false}
    if (!$GameDirectory) {return $_.CommandLine.Contains('net.neoforged.devlaunch.Main') -and $_.CommandLine.Contains((Join-Path $taskRoot 'mc-neoforge'))}
    $taskMatch=[regex]::Match($_.CommandLine,'(?:^|\s)--gameDir\s+(?:"([^"]+)"|(\S+))')
    if (!$taskMatch.Success -or $_.CommandLine -notlike '*neoforge*') {return $false}
    $taskCandidate=if($taskMatch.Groups[1].Success){$taskMatch.Groups[1].Value}else{$taskMatch.Groups[2].Value}
    return [IO.Path]::GetFullPath($taskCandidate).TrimEnd('\') -eq $taskGameDirectory
})
if($taskClients.Count -ne 1){throw 'Exactly one matching EndCraft NeoForge client required.'}
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
