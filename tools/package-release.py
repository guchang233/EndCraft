"""Package the tagged prototype without copying local configuration or saved games."""
from pathlib import Path
import argparse
import hashlib
import io
import json
import shutil
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM = {
    'third_party/Better-Endfield': '35216279f716b9a7b90bf565ec7e25e8999705b9',
    'third_party/SkyCraft': 'bfcaf178524b92c2cdeb88e4ce0f13ef9ded6f32',
}


def archive_source(output, version):
    prefix = f'EndCraft-{version}/'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as target:
        for directory, revision in [('.', 'HEAD'), *UPSTREAM.items()]:
            repository = ROOT / directory
            archive = subprocess.check_output(['git', '-C', str(repository), 'archive', revision])
            with tarfile.open(fileobj=io.BytesIO(archive)) as source:
                for member in source:
                    if not member.isfile():
                        continue
                    relative = member.name if directory == '.' else directory + '/' + member.name
                    stream = source.extractfile(member)
                    target.writestr(prefix + relative, stream.read())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'dist/release-1.0.0')
    args = parser.parse_args()
    version = (ROOT / 'VERSION').read_text(encoding='utf-8').strip()
    module = 'endcraft.gameplay15'
    dll = ROOT / f'build/native/{module}.dll'
    jar = ROOT / f'mc/build/libs/endcraft-guest-{version}.jar'
    if not dll.is_file() or not jar.is_file():
        raise SystemExit('Build the native module and the versioned Minecraft JAR first.')
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip():
        raise SystemExit('Commit the release sources before creating the corresponding source archive.')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    module_zip = output / f'EndCraft-gameplay15-{version}-win-x64.zip'
    manifest = {
        'format': 1, 'abi': 1, 'id': module, 'version': version,
        'name': 'EndCraft prototype ' + version,
        'libraries': {'windows-x64': f'native/windows-x64/{module}.dll'},
        'dependencies': [], 'default_configuration': {},
    }
    with zipfile.ZipFile(module_zip, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.write(dll, f'native/windows-x64/{module}.dll')
        archive.writestr('module.json', json.dumps(manifest, indent=2))
        for name in ('LICENSE', 'THIRD-PARTY-NOTICES.md', 'docs/RELEASE-1.0.0.md'):
            archive.write(ROOT / name, name)
    shutil.copy2(jar, output / jar.name)
    source_zip = output / f'EndCraft-{version}-source.zip'
    archive_source(source_zip, version)
    files = [module_zip, output / jar.name, source_zip]
    checksum = output / 'SHA256SUMS.txt'
    checksum.write_text(''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n'
                               for path in files), encoding='utf-8')
    print(json.dumps({'version': version, 'files': [path.name for path in [*files, checksum]]}))


if __name__ == '__main__':
    main()
