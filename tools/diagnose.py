"""Read-only local inventory. Game version is a content fingerprint, not Unity's version."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
from datetime import datetime

ROOT=Path(__file__).resolve().parents[1]

def main(game,output):
    files={}
    for name in ('Endfield.exe','UnityPlayer.dll','GameAssembly.dll'):
        path=game/name
        if path.exists():
            digest=hashlib.sha256()
            with path.open('rb') as stream:
                for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
            files[name]={'bytes':path.stat().st_size,'sha256':digest.hexdigest()}
    log=Path(os.environ['USERPROFILE'])/'AppData/LocalLow/Hypergryph/Endfield/Player.log'
    engine=None;api=None
    if log.exists():
        text=log.read_text(encoding='utf-8',errors='replace')
        match=re.search(r'Initialize engine version: ([^\r\n]+)',text)
        engine=match.group(1) if match else None
        if 'vulkan instance extension:' in text:api='Vulkan'
        elif 'Direct3D11' in text:api='D3D11'
        elif 'Direct3D12' in text:api='D3D12'
    proxy=game/'BetterEndfield-xinput1_4-host.status'
    result={
        'local_time':datetime.now().astimezone().isoformat(),
        'game_directory':str(game.resolve()),'game_build_files':files,
        'game_release_version':'not_exposed_by_inspected_metadata',
        'unity_engine_version':engine,'last_log_graphics_api':api,
        'last_log_modified_time':datetime.fromtimestamp(log.stat().st_mtime).astimezone().isoformat() if log.exists() else None,
        'anti_cheat_directory_present':(game/'AntiCheatExpert').is_dir(),
        'probe_bootstrap_present':(game/'xinput1_4.dll').is_file(),
        'bootstrap_load_status':proxy.read_text(errors='replace') if proxy.exists() else 'not_observed',
        'capabilities':{name:'unverified' for name in ('camera','movement_authority','host_depth','terrain_collision','npc_collision','combat')},
        'upstream':{'SkyCraft':'bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32','Better-Endfield':'35216279f716b9a7b90bf565ec7e25e8999705b9'},
    }
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({key:result[key] for key in ('unity_engine_version','last_log_graphics_api','probe_bootstrap_present','bootstrap_load_status')},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--game-dir',type=Path,default=Path('D:/Arknights Endfield'))
    parser.add_argument('--output',type=Path,default=ROOT/'reports/environment.json');args=parser.parse_args()
    main(args.game_dir,args.output)
