"""Fetch the exact publisher files in the NeoForge experiment's hash-verified lockfile."""
from pathlib import Path
import hashlib
import json
import urllib.parse
import urllib.request
import argparse
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--shaders', action='store_true', help='Fetch the optional Iris/Sodium preview stack only')
    args = parser.parse_args()
    lock = json.loads((ROOT / ('mc-neoforge/shaders.lock.json' if args.shaders else 'mc-neoforge/modpack.lock.json')).read_text(encoding='utf-8'))
    destination = ROOT / ('mc-neoforge/libs-shaders' if args.shaders else 'mc-neoforge/libs')
    destination.mkdir(parents=True, exist_ok=True)
    if args.shaders:
        extra = {file.name for file in destination.glob('*.jar')} - {item['filename'] for item in lock}
        if extra:
            raise RuntimeError('Old optional shader artifacts must be moved out: ' + ', '.join(sorted(extra)))
    for item in lock:
        name = item['filename']
        url_text = item.get('url')
        # Disabled publisher artifacts remain available for bytecode audits, but
        # must never enter the directory loaded by NeoForge.
        item_destination = ROOT / 'mc-neoforge/libs-disabled' if item.get('disabled') else destination
        item_destination.mkdir(parents=True, exist_ok=True)
        file = item_destination / name
        if url_text is None:
            # Locked local artifact with no publisher URL: verify the on-disk
            # copy only, and never fetch anything from the network for it.
            if not file.is_file():
                if item.get('optional_local'):
                    print('Optional local artifact not supplied: ' + name, flush=True)
                    continue
                raise RuntimeError('Locked local artifact missing and no publisher URL: ' + name)
            if hashlib.sha512(file.read_bytes()).hexdigest() != item['sha512']:
                raise RuntimeError('Locked local artifact checksum mismatch: ' + name)
            print('Verified locked local ' + name, flush=True)
            continue
        url = urllib.parse.urlparse(url_text)
        if Path(name).name != name or not name.endswith('.jar') or url.scheme != 'https' or url.hostname != 'cdn.modrinth.com':
            raise ValueError('Unexpected publisher artifact path or host')
        if file.is_file() and hashlib.sha512(file.read_bytes()).hexdigest() == item['sha512']:
            print('Verified cached ' + name, flush=True)
            continue
        request = urllib.request.Request(url_text, headers={'User-Agent': 'EndCraft-NeoForge/experimental'})
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = response.read()
        if hashlib.sha512(payload).hexdigest() != item['sha512']:
            raise RuntimeError('Publisher checksum mismatch: ' + name)
        temporary = file.with_suffix('.download')
        temporary.write_bytes(payload)
        temporary.replace(file)
        print('Downloaded and verified ' + name, flush=True)
    if args.shaders:
        # Sodium's distribution is a service-loader wrapper around the real mod.
        # ModDev treats runtimeOnly JARs as mods and selects the wrapper instead
        # of its nested implementation (missing VertexSerializer). For dev runs
        # only, supply the publisher's unchanged nested JARs on the classpath.
        dev = ROOT / 'mc-neoforge/libs-shaders-dev'
        dev.mkdir(parents=True, exist_ok=True)
        sodium = next(item for item in lock if item['project'] == 'sodium')
        with zipfile.ZipFile(destination / sodium['filename']) as archive:
            metadata = json.loads(archive.read('META-INF/jarjar/metadata.json'))
            expected = set()
            for entry in metadata['jars']:
                nested = entry['path']
                if not nested.startswith('META-INF/jarjar/') or not nested.endswith('.jar'):
                    raise ValueError('Unexpected Sodium nested artifact')
                filename = Path(nested).name
                expected.add(filename)
                (dev / filename).write_bytes(archive.read(nested))
            # The outer service wrapper also owns driver/workaround classes used
            # by the nested mod. Keep their original bytecode, without registering
            # the outer wrapper as a second Sodium mod in the ModDev launcher.
            support = 'sodium-wrapper-support.jar'
            expected.add(support)
            with zipfile.ZipFile(dev / support, 'w', zipfile.ZIP_DEFLATED) as output:
                output.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nFMLModType: LIBRARY\r\nAutomatic-Module-Name: sodium_service\r\n\r\n')
                for name in archive.namelist():
                    if name.endswith('.class'):
                        output.writestr(name, archive.read(name))
            extra = {file.name for file in dev.glob('*.jar')} - expected
            if extra:
                raise RuntimeError('Old optional shader dev artifacts must be moved out: ' + ', '.join(sorted(extra)))
        print('Prepared unchanged Sodium nested JARs for ModDev', flush=True)

if __name__ == '__main__':
    main()
