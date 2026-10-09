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
    parser.add_argument('--output', type=Path)
    parser.add_argument('--module', default='endcraft.gameplay33')
    args = parser.parse_args()
    version = (ROOT / 'VERSION').read_text(encoding='utf-8').strip()
    module = args.module
    if not module.startswith('endcraft.gameplay') or not module.removeprefix('endcraft.gameplay').isdigit():
        raise SystemExit('A project gameplay module identity is required.')
    dll = ROOT / f'build/native/{module}.dll'
    jar = ROOT / f'mc/build/libs/endcraft-guest-{version}.jar'
    neo_jar = ROOT / f'mc-neoforge/build/libs/endcraft-neoforge-guest-{version}.jar'
    if not dll.is_file() or not jar.is_file() or not neo_jar.is_file():
        raise SystemExit('Build the native module and both versioned Minecraft JARs (Fabric and NeoForge) first.')
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip():
        raise SystemExit('Commit the release sources before creating the corresponding source archive.')
    output = (args.output or ROOT / f'dist/release-{version}').resolve()
    output.mkdir(parents=True, exist_ok=True)
    probe = ROOT / 'build/native/endcraft.probe.dll'
    if not probe.is_file():
        raise SystemExit('Build the shared-memory module (endcraft.probe) first.')

    def module_package(identity, library, name, configuration):
        # Better-Endfield's manager rejects manifests without an author.
        path = output / f'EndCraft-{identity.removeprefix("endcraft.")}-{version}-win-x64.zip'
        manifest = {
            'format': 1, 'abi': 1, 'id': identity, 'version': version, 'name': name, 'author': 'EndCraft project',
            'libraries': {'windows-x64': f'native/windows-x64/{identity}.dll'},
            'dependencies': [], 'default_configuration': configuration,
        }
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('module.json', json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8'))
            archive.write(library, f'native/windows-x64/{identity}.dll')
            for extra in ('README.md', 'LICENSE', 'THIRD-PARTY-NOTICES.md'):
                archive.write(ROOT / extra, extra)
        return path

    # The gameplay module only opens the shared memory; endcraft.probe creates it, so both are required.
    # auto_enable starts the bridge on entering the game.
    module_zip = module_package(module, dll, 'EndCraft ' + version, {'auto_enable': True})
    probe_zip = module_package('endcraft.probe', probe, 'EndCraft shared memory ' + version, {})
    shutil.copy2(jar, output / jar.name)
    shutil.copy2(neo_jar, output / neo_jar.name)
    source_zip = output / f'EndCraft-{version}-source.zip'
    archive_source(source_zip, version)
    files = [module_zip, probe_zip, output / jar.name, output / neo_jar.name, source_zip]
    checksum = output / 'SHA256SUMS.txt'
    checksum.write_text(''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n'
                               for path in files), encoding='utf-8')
    print(json.dumps({'version': version, 'files': [path.name for path in [*files, checksum]]}))


if __name__ == '__main__':
    main()
