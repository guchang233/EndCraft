from pathlib import Path
import hashlib
import json
import shutil
import zipfile
import argparse

ROOT=Path(__file__).resolve().parents[1]
PACKAGE=ROOT/'build/package'

def main(staging_only=False):
    staging=ROOT/'build/distribution-staging' if staging_only else PACKAGE
    files={
        'native/windows-x64/endcraft.probe.dll': ROOT/'build/native/endcraft.probe.dll',
        'module.json': ROOT/'native/module.json',
        'ui/index.html': ROOT/'native/ui/index.html',
        'LICENSE': ROOT/'LICENSE',
        'THIRD-PARTY-NOTICES.md': ROOT/'THIRD-PARTY-NOTICES.md',
    }
    for relative,source in files.items():
        target=staging/relative
        target.parent.mkdir(parents=True,exist_ok=True)
        if not target.exists() or source.read_bytes()!=target.read_bytes(): shutil.copy2(source,target)
    (ROOT/'dist').mkdir(exist_ok=True)
    with zipfile.ZipFile(ROOT/'dist/EndCraft-Probe-0.1.0-win-x64.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for relative in files: archive.write(staging/relative,relative)
    for kind in ('inspect2','actor','telemetry','motion','teleport','targets','gameplay15'):
        module_id='endcraft.'+kind
        source=ROOT/f'build/native/{module_id}.dll'
        manifest={'format':1,'abi':1,'id':module_id,'version':'0.1.0','name':'EndCraft '+kind,
                  'libraries':{'windows-x64':f'native/windows-x64/{module_id}.dll'},
                  'dependencies':[],'default_configuration':{}}
        with zipfile.ZipFile(ROOT/f'dist/EndCraft-{kind}-0.1.0-win-x64.zip','w',zipfile.ZIP_DEFLATED) as archive:
            archive.write(source,f'native/windows-x64/{module_id}.dll')
            archive.writestr('module.json',json.dumps(manifest))
            archive.write(ROOT/'LICENSE','LICENSE')
            archive.write(ROOT/'THIRD-PARTY-NOTICES.md','THIRD-PARTY-NOTICES.md')
    jars=list((ROOT/'mc/build/libs').glob('*.jar'))
    for jar in jars:
        if 'sources' not in jar.name: shutil.copy2(jar,ROOT/'dist'/jar.name)
    report={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'dist').iterdir() if p.is_file() and p.name!='SHA256SUMS.json'}
    (ROOT/'dist/SHA256SUMS.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('Packaged diagnostic module and MC guest; no gameplay capability is declared complete.')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--staging-only',action='store_true',help='Package fresh builds without modifying the installed probe package.')
    main(parser.parse_args().staging_only)
