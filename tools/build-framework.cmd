@echo off
setlocal
cd /d "%~dp0.."
for /f "usebackq tokens=*" %%I in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "TASK_VS=%%I"
if not defined TASK_VS exit /b 2
call "%TASK_VS%\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul
if errorlevel 1 exit /b 2
if not exist build\framework mkdir build\framework
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File tools\prepare-framework.ps1
if errorlevel 1 exit /b 1
pushd build\framework
set "TASK_UPSTREAM=..\..\third_party\Better-Endfield\native"
cl /nologo /std:c++20 /EHsc /W3 /O2 /MT /utf-8 /DUNICODE /D_UNICODE /DWIN32_LEAN_AND_MEAN /DNOMINMAX /I%TASK_UPSTREAM%\shared\host /I%TASK_UPSTREAM%\shared\include /I%TASK_UPSTREAM%\shared\third_party\minhook\include /LD %TASK_UPSTREAM%\shared\host\bootstrap.cpp %TASK_UPSTREAM%\shared\host\pose_lease.cpp %TASK_UPSTREAM%\shared\host\dynamic_resolver.cpp %TASK_UPSTREAM%\shared\host\hook_broker.cpp %TASK_UPSTREAM%\shared\host\hook_diagnostics.cpp ..\generated\framework_runtime.cpp ..\generated\framework_logging.cpp %TASK_UPSTREAM%\shared\host\module_manager.cpp ..\..\native\framework_settings.cpp %TASK_UPSTREAM%\shared\third_party_modules\third_party_host.cpp %TASK_UPSTREAM%\shared\third_party\minhook\src\buffer.c %TASK_UPSTREAM%\shared\third_party\minhook\src\hook.c %TASK_UPSTREAM%\shared\third_party\minhook\src\trampoline.c %TASK_UPSTREAM%\shared\third_party\minhook\src\hde\hde64.c /Fe:BetterEndfield.Host.dll /link ws2_32.lib /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W3 /O2 /MT /utf-8 /LD ..\..\native\bootstrap_wrapper.cpp /Fe:xinput1_4.dll /link /DYNAMICBASE /NXCOMPAT
exit /b %errorlevel%
