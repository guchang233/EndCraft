"""Register this project's read-only inspector without restarting or replacing a loaded DLL."""
from pathlib import Path
import importlib.util
import json
import os
import shutil

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('installer',ROOT/'tools/install-probe.py')
installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)

def main(kind='inspect'):
    if kind not in ('inspect','inspect2','inspect3','actor','visual','targets','targets2','canvas','telemetry','motion','teleport','gameplay','gameplay2','gameplay3','gameplay4','gameplay5','gameplay6','gameplay7','gameplay8','gameplay9','gameplay10','gameplay11','gameplay12','gameplay13','gameplay14','gameplay15','gameplay16','gameplay17','gameplay18','gameplay19','gameplay20','gameplay21','gameplay22','gameplay23','gameplay24','gameplay25','gameplay26','gameplay27','gameplay28','gameplay29','gameplay30','gameplay31','gameplay32','gameplay33'): raise RuntimeError('Unknown project module.')
    module_id='endcraft.'+kind
    state=json.loads(installer.STATE.read_text());installer.validate_owned(state)
    package=ROOT/'build'/f'{kind}-package'
    configuration={'auto_enable':True,'guest_launcher':str(ROOT/'tools/start-guest.ps1')} if kind in ('gameplay2','gameplay3') else {}
    manifest={'format':1,'abi':1,'id':module_id,'version':'0.1.0','name':'EndCraft '+kind,
              'libraries':{'windows-x64':f'native/windows-x64/{module_id}.dll'},'dependencies':[],'default_configuration':configuration}
    destination=package/f'native/windows-x64/{module_id}.dll'
    if destination.exists(): raise RuntimeError('Inspector package already exists; preserve the potentially loaded DLL.')
    destination.parent.mkdir(parents=True,exist_ok=True)
    shutil.copy2(ROOT/f'build/native/{module_id}.dll',destination)
    (package/'module.json').write_text(json.dumps(manifest),encoding='utf-8')
    shutil.copy2(ROOT/'LICENSE',package/'LICENSE')
    indexes=[installer.SETTINGS/'third-party/index.json',ROOT/'.tools/framework/third-party/index.json']
    originals={path:path.read_bytes() for path in indexes}
    payloads={}
    for path,data in originals.items():
        index=json.loads(data)
        if any(record['id']==module_id for record in index['modules']): raise RuntimeError('Module already registered.')
        index['modules'].append({'id':module_id,'directory':str(package),'generation':f'endcraft-{kind}-0.1.0',
                                 'enabled':True,'configuration':configuration})
        payloads[path]=json.dumps(index,ensure_ascii=False).encode('utf-8')
    changed=[]
    try:
        for path,data in payloads.items():
            temporary=path.with_name(path.name+'.endcraft-new')
            with temporary.open('xb') as stream: stream.write(data)
            try: os.replace(temporary,path)
            finally: temporary.unlink(missing_ok=True)
            changed.append(path)
        for record in state['files']:
            if Path(record['path']) in changed: record['sha256']=installer.sha(Path(record['path']))
        installer.STATE.write_text(json.dumps(state,indent=2))
    except Exception:
        for path in changed: path.write_bytes(originals[path])
        raise
    print(f'{module_id} registered without replacing a loaded DLL; no gameplay command has been issued.')

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser();parser.add_argument('kind',nargs='?',choices=['inspect','inspect2','inspect3','actor','visual','targets','targets2','canvas','telemetry','motion','teleport','gameplay','gameplay2','gameplay3','gameplay4','gameplay5','gameplay6','gameplay7','gameplay8','gameplay9','gameplay10','gameplay11','gameplay12','gameplay13','gameplay14','gameplay15','gameplay16','gameplay17','gameplay18','gameplay19','gameplay20','gameplay21','gameplay22','gameplay23','gameplay24','gameplay25','gameplay26','gameplay27','gameplay28','gameplay29','gameplay30','gameplay31','gameplay32','gameplay33'],default='inspect')
    main(parser.parse_args().kind)
