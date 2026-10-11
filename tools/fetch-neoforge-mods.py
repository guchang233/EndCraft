"""Fetch the exact publisher files in the NeoForge experiment's hash-verified lockfile."""
from pathlib import Path
import hashlib
import json
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def main():
    lock = json.loads((ROOT / 'mc-neoforge/modpack.lock.json').read_text(encoding='utf-8'))
    destination = ROOT / 'mc-neoforge/libs'
    destination.mkdir(parents=True, exist_ok=True)
    for item in lock:
        name = item['filename']
        if item.get('disabled'):
            print('Skipped disabled entry ' + name + ': ' + str(item.get('disabled_reason', '')), flush=True)
            continue
        url_text = item.get('url')
        file = destination / name
        if url_text is None:
            # Locked local artifact with no publisher URL: verify the on-disk
            # copy only, and never fetch anything from the network for it.
            if not file.is_file():
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

if __name__ == '__main__':
    main()
