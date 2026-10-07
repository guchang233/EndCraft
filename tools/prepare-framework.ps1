$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$upstream=Join-Path $taskRoot 'third_party\Better-Endfield\native\shared\host'
$generated=Join-Path $taskRoot 'build\generated'
[IO.Directory]::CreateDirectory($generated) | Out-Null
$source=[IO.File]::ReadAllText((Join-Path $upstream 'host_runtime.cpp'))
$old='third_party_->Start(settings_->Paths().settings_root/"third-party"/"index.json","windows-x64",'
$new=@'
const auto original_index=settings_->Paths().settings_root/"third-party"/"index.json";
    const auto local_index=settings_->Paths().install_root/"third-party"/"index.json";
    const auto index_path=std::filesystem::is_regular_file(local_index)?local_index:original_index;
    const auto attributes=GetFileAttributesW(index_path.c_str());
    const auto path_error=attributes==INVALID_FILE_ATTRIBUTES?GetLastError():0;
    logger_->Write("third-party", "original_index="+original_index.string()+
        " selected_index="+index_path.string()+" attributes="+std::to_string(attributes)+
        " path_error="+std::to_string(path_error));
    const bool third_party_ready=third_party_->Start(index_path,"windows-x64",
'@
if (!$source.Contains($old)) { throw 'Pinned Host runtime context changed.' }
$source=$source.Replace($old,$new)
$oldEnd='[this](const std::string& module,const std::string& message){logger_->Write(module,message);},nullptr,hook_service_ready?hooks_->ChainApi():nullptr);'
$newEnd=$oldEnd+"`n    logger_->Write(`"third-party`", third_party_ready?`"RPC listener started`":`"RPC listener did not start`" );"
if (!$source.Contains($oldEnd)) { throw 'Pinned Host RPC callback context changed.' }
$source=$source.Replace($oldEnd,$newEnd)
[IO.File]::WriteAllText((Join-Path $generated 'framework_runtime.cpp'),$source,(New-Object Text.UTF8Encoding $false))
$logging=[IO.File]::ReadAllText((Join-Path $upstream 'logging.cpp'))
$logging=$logging.Replace('#include "logging.h"',"#include `"logging.h`"`n#include <Windows.h>")
$logging=$logging.Replace('<< " [" << source << "] "', '<< " [pid=" << GetCurrentProcessId() << "][" << source << "] "')
[IO.File]::WriteAllText((Join-Path $generated 'framework_logging.cpp'),$logging,(New-Object Text.UTF8Encoding $false))
