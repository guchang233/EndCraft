"""Install only owned additive probe files; preserve every existing game file."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import secrets
import socket
import subprocess

ROOT=Path(__file__).resolve().parents[1]
STATE=ROOT/'.tools/install-manifest.json'
SETTINGS=Path(os.environ['LOCALAPPDATA'])/'BetterEndfield'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def game_running():
    powershell=Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe'
    result=subprocess.run([str(powershell),'-NoProfile','-Command',
        'if (Get-Process Endfield -ErrorAction SilentlyContinue) {exit 1} else {exit 0}'],capture_output=True)
    return result.returncode!=0

def local_payloads(game):
    settings=(f'[Loader]\r\nload_host=true\r\ninstall_root={ROOT / ".tools/framework"}\r\n'
              f'[Host]\r\nmodules_root={ROOT / ".tools/framework/modules"}\r\n')
    sidecar=settings+f'[Paths]\r\nlocal_app_data={os.environ["LOCALAPPDATA"]}\r\n'
    paths=f'[Paths]\r\nlocal_app_data={os.environ["LOCALAPPDATA"]}\r\n'
    payloads=[(game/'xinput1_4.dll',(ROOT/'build/framework/xinput1_4.dll').read_bytes()),
            (game/'EndCraft-bootstrap.ini',sidecar.encode('utf-16')),
            (SETTINGS/'BetterEndfield.ini',settings.encode('utf-16')),
            (ROOT/'.tools/framework/runtime/BetterEndfield.Host.dll',(ROOT/'build/framework/BetterEndfield.Host.dll').read_bytes()),
            (ROOT/'.tools/framework/endcraft-paths.ini',paths.encode('utf-16'))]
    if (SETTINGS/'third-party/index.json').exists():
        payloads.append((ROOT/'.tools/framework/third-party/index.json',(SETTINGS/'third-party/index.json').read_bytes()))
    return payloads

def owned_paths(game):
    return {game/'xinput1_4.dll',game/'EndCraft-bootstrap.ini',
            game/'EndCraft-xinput1_4.rollback.dll',
            SETTINGS/'BetterEndfield.ini',SETTINGS/'third-party/index.json',
            ROOT/'.tools/framework/runtime/BetterEndfield.Host.dll',ROOT/'.tools/framework/endcraft-paths.ini',
            ROOT/'.tools/framework/third-party/index.json',ROOT/'.tools/framework/runtime/EndCraft-Host.rollback.dll'}

def validate_owned(state):
    game=Path(state['game_dir']).resolve()
    allowed={path.resolve() for path in owned_paths(game)}
    for record in state['files']:
        path=Path(record['path']).resolve()
        if path not in allowed: raise RuntimeError('Invalid ownership record; no files changed.')
        if path.exists() and sha(path)!=record['sha256']: raise RuntimeError(f'Changed file preserved: {path}')
    return game

def stage_update():
    """Rename the owned loaded image, preserving it until the next normal restart."""
    if not game_running(): return update()
    state=json.loads(STATE.read_text(encoding='utf-8'))
    game=validate_owned(state)
    status=game/'BetterEndfield-xinput1_4-host.status'
    if not status.exists() or status.read_text(errors='replace').splitlines()[-1]!='Host file was not found':
        raise RuntimeError('Live staging requires evidence that the old Host was never loaded; close the game for update.')
    payloads=local_payloads(game)
    owned={Path(item['path']).resolve() for item in state['files']}
    legacy_runtime=(ROOT/'.tools/framework/runtime/BetterEndfield.Host.dll').resolve()
    for path,_ in payloads:
        if path.exists() and path.resolve() not in owned and path.resolve()!=legacy_runtime:
            raise RuntimeError(f'Existing file preserved: {path}')
    loader=game/'xinput1_4.dll'
    rollback=game/'EndCraft-xinput1_4.rollback.dll'
    if rollback.exists(): raise RuntimeError('A rollback DLL already exists; preserved. Close the game and use update.')
    backups={path:path.read_bytes() if path.exists() else None for path,_ in payloads if path!=loader}
    # A loaded Windows image can often be renamed but cannot be overwritten.
    # If Windows denies the rename, no configuration or game file is changed.
    loader.rename(rollback)
    changed=[]
    try:
        for path,content in payloads:
            path.parent.mkdir(parents=True,exist_ok=True)
            if path==loader:
                with path.open('xb') as stream: stream.write(content)
            else: path.write_bytes(content)
            changed.append(path)
        records={item['path']:item for item in state['files']}
        for path in changed+[rollback]: records[str(path)]={'path':str(path),'sha256':sha(path)}
        state.update(files=list(records.values()),restart_required=True)
        STATE.write_text(json.dumps(state,indent=2),encoding='utf-8')
    except Exception:
        for path in reversed(changed):
            if path==loader: path.unlink()
            elif backups[path] is None: path.unlink(missing_ok=True)
            else: path.write_bytes(backups[path])
        rollback.rename(loader)
        raise
    print('Fixed loader staged for the next launch. The running process keeps its original DLL; rollback image retained.')

def stage_host():
    """Prepare only owned Host diagnostics and module files for next startup."""
    state=json.loads(STATE.read_text(encoding='utf-8'))
    validate_owned(state)
    runtime=ROOT/'.tools/framework/runtime/BetterEndfield.Host.dll'
    rollback=runtime.with_name('EndCraft-Host.rollback.dll')
    source=ROOT/'build/framework/BetterEndfield.Host.dll'
    local_index=ROOT/'.tools/framework/third-party/index.json'
    if rollback.exists(): raise RuntimeError('Host rollback already exists; retained. Close game before another update.')
    if local_index.exists(): raise RuntimeError('Project-local RPC index already exists; retained.')
    payload=(SETTINGS/'third-party/index.json').read_bytes()
    runtime.rename(rollback)
    written=[]
    try:
        for path,data in [(runtime,source.read_bytes()),(local_index,payload)]:
            path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb') as stream: stream.write(data)
            written.append(path)
        records={item['path']:item for item in state['files']}
        for path in written+[rollback]: records[str(path)]={'path':str(path),'sha256':sha(path)}
        state.update(files=list(records.values()),restart_required=game_running())
        STATE.write_text(json.dumps(state,indent=2),encoding='utf-8')
    except Exception:
        for path in written: path.unlink(missing_ok=True)
        rollback.rename(runtime)
        raise
    print('Host diagnostics staged; the running game keeps the previous Host until normal restart.')

def update():
    if game_running(): raise RuntimeError('Close Endfield normally before replacing its loaded DLL.')
    state=json.loads(STATE.read_text(encoding='utf-8'))
    game=validate_owned(state)
    payloads=local_payloads(game)
    owned={Path(item['path']).resolve() for item in state['files']}
    legacy_runtime=(ROOT/'.tools/framework/runtime/BetterEndfield.Host.dll').resolve()
    for path,_ in payloads:
        if path.exists() and path.resolve() not in owned and path.resolve()!=legacy_runtime:
            raise RuntimeError(f'Existing file preserved: {path}')
    backups={path:path.read_bytes() if path.exists() else None for path,_ in payloads}
    try:
        for path,content in payloads:
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes(content)
        records={item['path']:item for item in state['files']}
        for path,_ in payloads: records[str(path)]={'path':str(path),'sha256':sha(path)}
        state.update(files=list(records.values()),restart_required=False)
        STATE.write_text(json.dumps(state,indent=2),encoding='utf-8')
    except Exception:
        for path,original in backups.items():
            if original is None: path.unlink(missing_ok=True)
            else: path.write_bytes(original)
        raise
    print('Owned loader and Host updated. Start Endfield through its usual launcher.')

def install(game):
    game=game.resolve()
    if not (game/'Endfield.exe').is_file(): raise RuntimeError('Endfield.exe not found')
    restart_required=game_running()
    if STATE.exists(): raise RuntimeError('Probe already installed; use status/probe tools or uninstall first.')
    payloads=local_payloads(game)
    destinations=[path for path,_ in payloads]+[SETTINGS/'third-party/index.json']
    for path in destinations:
        if path.exists(): raise RuntimeError(f'Existing file preserved: {path}')
    (ROOT/'.tools/framework/modules').mkdir(parents=True,exist_ok=True)
    with socket.socket() as reservation:
        reservation.bind(('127.0.0.1',0));port=reservation.getsockname()[1]
    index={'schema':1,'port':port,'token':secrets.token_urlsafe(36),'modules':[
        {'id':'endcraft.probe','directory':str(ROOT/'build/package'),'generation':'endcraft-probe-0.1.0','enabled':True,'configuration':{}}]}
    written=[]
    ROOT.joinpath('.tools').mkdir(exist_ok=True)
    try:
        # Exclusive creation also protects against another installer racing this one.
        payloads.append((SETTINGS/'third-party/index.json',json.dumps(index,ensure_ascii=False).encode('utf-8')))
        payloads.append((ROOT/'.tools/framework/third-party/index.json',json.dumps(index,ensure_ascii=False).encode('utf-8')))
        for path,content in payloads:
            path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb') as stream: stream.write(content)
            written.append(path)
        state={'game_dir':str(game),'restart_required':restart_required,'files':[{'path':str(p),'sha256':sha(p)} for p in written]}
        STATE.write_text(json.dumps(state,indent=2),encoding='utf-8')
    except Exception:
        for path in written: path.unlink(missing_ok=True)
        raise
    print('Probe installed through ordinary XInput loading. No original archive or anti-cheat file changed.')
    if restart_required: print('Endfield is already running. Exit normally and relaunch to load the new DLL.')

def uninstall():
    if game_running(): raise RuntimeError('Close Endfield before uninstalling the bootstrap DLL.')
    state=json.loads(STATE.read_text(encoding='utf-8'))
    validate_owned(state)
    for record in state['files']: Path(record['path']).unlink(missing_ok=True)
    STATE.unlink()
    print('Owned bootstrap/configuration removed; diagnostic logs retained.')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['install','update','stage-update','stage-host','uninstall'])
    parser.add_argument('--game-dir',type=Path,default=Path('D:/Arknights Endfield'))
    args=parser.parse_args()
    try:
        if args.action=='install': install(args.game_dir)
        elif args.action=='update': update()
        elif args.action=='stage-update': stage_update()
        elif args.action=='stage-host': stage_host()
        else: uninstall()
    except (RuntimeError,OSError) as error: parser.exit(1,str(error)+'\n')
