@echo off
rem EndCraft: restart the NeoForge bridge client while the host game is still running
rem (for example after Minecraft crashed). It reconnects to the host game and reopens the bridge world.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start-neoforge.ps1" -BridgeHost -Offline
if errorlevel 1 pause
