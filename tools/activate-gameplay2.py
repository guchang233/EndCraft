"""Switch to a new native module identity while retaining the pinned old DLL."""
import contextlib
import importlib.util
import io
import json
import os
import time
import copy
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def main(old_kind='gameplay',new_kind='gameplay2'):
    source=ROOT/f'build/native/endcraft.{new_kind}.dll'
    if not source.is_file(): raise RuntimeError('Build the new bridge successfully before retiring the old one.')
    installer=load('endcraft_installer',ROOT/'tools/install-probe.py')
    state=json.loads(installer.STATE.read_text())
    # An earlier run enabled auto-start only in the project index. Accept exactly
    # that known local edit; all other externally changed content remains protected.
    project_index=ROOT/'.tools/framework/third-party/index.json'
    user_index=installer.SETTINGS/'third-party/index.json'
    project=json.loads(project_index.read_text());reference=json.loads(user_index.read_text())
    normalized=copy.deepcopy(project)
    for record in normalized['modules']:
        if record['id']=='endcraft.gameplay' and record.get('configuration')=={'auto_enable':True}:
            record['configuration']={}
    if normalized==reference:
        for record in state['files']:
            if Path(record['path'])==project_index: record['sha256']=installer.sha(project_index)
    installer.validate_owned(state)
    installer.STATE.write_text(json.dumps(state,indent=2))
    report=load('endcraft_report',ROOT/'tools/runtime-report.py')
    old_enabled=any(r['id']=='endcraft.'+old_kind and r.get('enabled',False) for r in project['modules'])
    if old_enabled:
      with contextlib.redirect_stdout(io.StringIO()):
        report.collect('status',ROOT/'reports/gameplay-disable-old.json','endcraft.'+old_kind,{'action':'disable'})
        for attempt in range(20):
            report.collect('status',ROOT/'reports/gameplay-retire-old.json','endcraft.'+old_kind)
            body=json.loads((ROOT/'reports/gameplay-retire-old.json').read_text())['body']['gameplay']
            if not body['active'] and not body.get('character_hidden',False) and not body.get('host_input_mask',{}).get('acquired',False): break
            time.sleep(.2)
        else: raise RuntimeError('Old bridge has not restored the character; preserve the current modules.')
    indexes=[installer.SETTINGS/'third-party/index.json',ROOT/'.tools/framework/third-party/index.json']
    originals={p:p.read_bytes() for p in indexes}
    changed=[]
    try:
        for path,raw in originals.items():
            data=json.loads(raw)
            for record in data['modules']:
                if record['id']=='endcraft.'+old_kind: record['enabled']=False
            temporary=path.with_name(path.name+'.endcraft-retire')
            with temporary.open('xb') as stream: stream.write(json.dumps(data,ensure_ascii=False).encode())
            os.replace(temporary,path);changed.append(path)
        for record in state['files']:
            if Path(record['path']) in changed: record['sha256']=installer.sha(Path(record['path']))
        installer.STATE.write_text(json.dumps(state,indent=2))
    except Exception:
        for path in changed: path.write_bytes(originals[path])
        raise
    register=load('endcraft_registration',ROOT/'tools/register-inspector.py')
    register.main(new_kind)
    print('Old bridge retired and new bridge registered. Its configuration determines whether gameplay starts automatically.')

if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser()
    parser.add_argument('--old',choices=['gameplay','gameplay2','gameplay3','gameplay4','gameplay5','gameplay6','gameplay7','gameplay8','gameplay9','gameplay10','gameplay11','gameplay12','gameplay13','gameplay14','gameplay15'],default='gameplay')
    parser.add_argument('--new',choices=['gameplay2','gameplay3','gameplay4','gameplay5','gameplay6','gameplay7','gameplay8','gameplay9','gameplay10','gameplay11','gameplay12','gameplay13','gameplay14','gameplay15'],default='gameplay2')
    args=parser.parse_args();main(args.old,args.new)
