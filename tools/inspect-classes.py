"""Read selected runtime class contracts; retain full metadata in local reports."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('runtime_report',ROOT/'tools/runtime-report.py')
report=importlib.util.module_from_spec(spec);spec.loader.exec_module(report)

def collect(assembly,namespace,name):
    output=ROOT/'reports'/f'{name}-metadata.json'
    with contextlib.redirect_stdout(io.StringIO()):
        report.collect('inspect',output,'endcraft.inspect',{'action':'inspect','assembly':assembly,'namespace':namespace,'class':name})
    result=json.loads(output.read_text())
    body=result.get('body',{})
    print(json.dumps({'class':name,'result':result.get('result'),'state':body.get('state'),
                      'error':body.get('error'),'method_count':sum(len(c['methods']) for c in body.get('hierarchy',[])),
                      'field_count':sum(len(c['fields']) for c in body.get('hierarchy',[]))},ensure_ascii=False))

if __name__=='__main__':
    for name in ('MovementComponent','Entity','PlayerController'):
        collect('Gameplay.Beyond.dll','Beyond.Gameplay.Core',name)
    collect('UnityEngine.PhysicsModule.dll','UnityEngine','CharacterController')
