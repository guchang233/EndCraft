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
# The user authorized starting the client. Launch the original executable;
# its own SDK remains responsible for authentication and launcher requirements.
Start-Process -FilePath $taskGameExe -WorkingDirectory $taskGameRoot
Write-Output 'Original Endfield client launch requested.'
Start-Sleep -Seconds 8
if (!(Get-Process Endfield -ErrorAction SilentlyContinue)) {
    $taskShortcut='C:\Users\Public\Desktop\明日方舟：终末地.lnk'
    if (Test-Path -LiteralPath $taskShortcut) {
        Start-Process -FilePath $taskShortcut
        Write-Output 'Client did not remain running; opened the official launcher shortcut.'
    } else { throw 'Client did not remain running and official shortcut was not found.' }
}
