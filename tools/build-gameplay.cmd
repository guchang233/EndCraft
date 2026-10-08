@echo off
setlocal
cd /d "%~dp0.."
for /f "usebackq tokens=*" %%I in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "TASK_VS=%%I"
if not defined TASK_VS exit /b 2
call "%TASK_VS%\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul
if errorlevel 1 exit /b 2
if not exist build\native mkdir build\native
pushd build\native
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /DENDCRAFT_GAMEPLAY /DENDCRAFT_ACTOR_MODULE_ID=\"endcraft.gameplay33\" /LD ..\..\native\actor_reader.cpp /Fe:endcraft.gameplay33.dll /link user32.lib /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT ..\..\native\input_mode_test.cpp /Fe:input-mode-test.exe
if errorlevel 1 exit /b 1
input-mode-test.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT ..\..\native\mesh_layers_test.cpp /Fe:mesh-layers-test.exe
if errorlevel 1 exit /b 1
mesh-layers-test.exe
exit /b %errorlevel%
