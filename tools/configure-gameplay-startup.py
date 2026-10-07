"""Set local startup for the verified bridge without exposing RPC credentials."""
import argparse
import importlib.util
import json
import os
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def main(automatic,kind='gameplay19'):
    spec=importlib.util.spec_from_file_location('installer',ROOT/'tools/install-probe.py')
    installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)
    original_state=installer.STATE.read_bytes()
    state=json.loads(original_state);installer.validate_owned(state)
    paths=[installer.SETTINGS/'third-party/index.json',ROOT/'.tools/framework/third-party/index.json']
    originals={p:p.read_bytes() for p in paths}
    launcher=ROOT/'tools/start-guest.ps1'
    if not launcher.is_file(): raise RuntimeError('Project guest launcher missing.')
    changes={}
    for path,raw in originals.items():
        data=json.loads(raw)
        matches=[m for m in data['modules'] if m['id']=='endcraft.'+kind]
        if len(matches)!=1: raise RuntimeError('Exactly one registered '+kind+' module required.')
        module=matches[0]
        config=dict(module.get('configuration',{}))
        config['auto_enable']=automatic
        config['guest_launcher']=str(launcher)
        module['configuration']=config
        for entry in data['modules']:
            if entry['id']=='endcraft.canvas': entry['enabled']=False
        changes[path]=json.dumps(data,ensure_ascii=False).encode('utf-8')
    changed=[]
    try:
        for path,raw in changes.items():
            temporary=path.with_name(path.name+'.endcraft-startup')
            with temporary.open('xb') as stream: stream.write(raw)
            try: os.replace(temporary,path)
            finally: temporary.unlink(missing_ok=True)
            changed.append(path)
        for record in state['files']:
            if Path(record['path']) in changed: record['sha256']=installer.sha(Path(record['path']))
        installer.STATE.write_text(json.dumps(state,indent=2))
    except Exception:
        for path in changed: path.write_bytes(originals[path])
        installer.STATE.write_bytes(original_state)
        raise
    print(kind+' automatic startup '+('enabled' if automatic else 'disabled')+' for the next game launch; inactive canvas diagnostic retired.')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    group=parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--auto-start',action='store_true')
    group.add_argument('--manual',action='store_true')
    parser.add_argument('--module',choices=['gameplay10','gameplay11','gameplay12','gameplay13','gameplay14','gameplay15','gameplay16','gameplay17','gameplay18','gameplay19'],default='gameplay19')
    args=parser.parse_args();main(args.auto_start,args.module)
