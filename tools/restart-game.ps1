param([string]$GameDirectory='D:\Arknights Endfield', [int]$WaitSeconds=600)
$ErrorActionPreference='Stop'
$taskGameRoot=[IO.Path]::GetFullPath($GameDirectory)
$taskGameExe=Join-Path $taskGameRoot 'Endfield.exe'
if (!(Test-Path -LiteralPath $taskGameExe -PathType Leaf)) { throw 'Endfield.exe not found.' }
if ($WaitSeconds -lt 1 -or $WaitSeconds -gt 600) { throw 'WaitSeconds must be 1 to 600.' }
$deadline=[DateTime]::UtcNow.AddSeconds($WaitSeconds)
Write-Output 'Waiting for normal Endfield exit; this helper never terminates the game.'
while (Get-Process Endfield -ErrorAction SilentlyContinue) {
    if ([DateTime]::UtcNow -ge $deadline) { throw 'Game remained open; no new process started.' }
    Start-Sleep -Seconds 1
}
# Endfield 1.6.3 rejects direct executable launches. Use the official launcher
# shortcut so its login and launch-token flow remains intact.
$taskShell=New-Object -ComObject WScript.Shell
$taskLauncher=$null
$taskLauncherShortcut=$null
foreach ($taskShortcut in Get-ChildItem -LiteralPath 'C:\Users\Public\Desktop' -Filter '*.lnk' -File -ErrorAction SilentlyContinue) {
    $taskLink=$taskShell.CreateShortcut($taskShortcut.FullName)
    if ($taskLink.TargetPath -like '*Hypergryph Launcher*Launcher.exe' -and $taskLink.Arguments -like '*--game=endfield*') {
        $taskLauncher=$taskLink
        $taskLauncherShortcut=$taskShortcut.FullName
        break
    }
}
if ($null -eq $taskLauncher) { throw 'Official Endfield launcher shortcut was not found.' }
# Let the Windows shell execute the .lnk exactly as the official installer created it.
Start-Process -FilePath $taskLauncherShortcut
Write-Output 'Official Endfield launcher requested.'
