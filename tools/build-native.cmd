@echo off
setlocal
cd /d "%~dp0.."
for /f "usebackq tokens=*" %%I in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "TASK_VS=%%I"
if not defined TASK_VS exit /b 2
call "%TASK_VS%\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul
if errorlevel 1 exit /b 2
if not exist build\native mkdir build\native
pushd build\native
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /LD ..\..\native\module.cpp /Fe:endcraft.probe.dll /link advapi32.lib /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include ..\..\native\standin.cpp /Fe:endcraft-standin.exe /link /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
endcraft-standin.exe endcraft.probe.dll --test
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /LD ..\..\native\inspector.cpp /Fe:endcraft.inspect.dll /link /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include ..\..\native\inspector_test.cpp /Fe:inspector-test.exe /link /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
inspector-test.exe endcraft.inspect.dll
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /LD ..\..\native\actor_reader.cpp /Fe:endcraft.actor.dll /link /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
inspector-test.exe endcraft.actor.dll
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /DENDCRAFT_ACTOR_TELEMETRY /DENDCRAFT_ACTOR_MODULE_ID=\"endcraft.telemetry\" /LD ..\..\native\actor_reader.cpp /Fe:endcraft.telemetry.dll /link /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
inspector-test.exe endcraft.telemetry.dll
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /DENDCRAFT_MOTION_PROBE /DENDCRAFT_ACTOR_MODULE_ID=\"endcraft.motion\" /LD ..\..\native\actor_reader.cpp /Fe:endcraft.motion.dll /link /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
inspector-test.exe endcraft.motion.dll
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /DENDCRAFT_MOTION_PROBE /DENDCRAFT_TELEPORT_PROBE /DENDCRAFT_ACTOR_MODULE_ID=\"endcraft.teleport\" /LD ..\..\native\actor_reader.cpp /Fe:endcraft.teleport.dll /link /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
inspector-test.exe endcraft.teleport.dll
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include ..\..\native\motion_test.cpp /Fe:motion-test.exe /link /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
motion-test.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /DENDCRAFT_GAMEPLAY /DENDCRAFT_ACTOR_MODULE_ID=\"endcraft.gameplay\" /LD ..\..\native\actor_reader.cpp /Fe:endcraft.gameplay.dll /link user32.lib /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
inspector-test.exe endcraft.gameplay.dll
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /DENDCRAFT_GAMEPLAY /DENDCRAFT_ACTOR_MODULE_ID=\"endcraft.gameplay2\" /LD ..\..\native\actor_reader.cpp /Fe:endcraft.gameplay2.dll /link user32.lib /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
inspector-test.exe endcraft.gameplay2.dll
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /DENDCRAFT_GAMEPLAY /DENDCRAFT_ACTOR_MODULE_ID=\"endcraft.gameplay27\" /LD ..\..\native\actor_reader.cpp /Fe:endcraft.gameplay27.dll /link user32.lib /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
inspector-test.exe endcraft.gameplay27.dll
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /DENDCRAFT_INSPECTOR_ID=\"endcraft.inspect2\" /LD ..\..\native\inspector.cpp /Fe:endcraft.inspect2.dll /link /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT /I..\..\native\include /DENDCRAFT_VISUAL_PROBE /DENDCRAFT_TARGETS_PROBE /DENDCRAFT_ACTOR_MODULE_ID=\"endcraft.targets\" /LD ..\..\native\actor_reader.cpp /Fe:endcraft.targets.dll /link user32.lib /DYNAMICBASE /NXCOMPAT
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT ..\..\native\host_authority_test.cpp /Fe:host-authority-test.exe
if errorlevel 1 exit /b 1
host-authority-test.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT ..\..\native\coordinate_map_test.cpp /Fe:coordinate-map-test.exe
if errorlevel 1 exit /b 1
coordinate-map-test.exe
if errorlevel 1 exit /b 1
cl /nologo /std:c++20 /EHsc /W4 /O2 /MT ..\..\native\bridge_memory_test.cpp /Fe:bridge-memory-test.exe
if errorlevel 1 exit /b 1
bridge-memory-test.exe
exit /b %errorlevel%
