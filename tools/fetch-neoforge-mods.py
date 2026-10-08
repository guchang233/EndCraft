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
        url = urllib.parse.urlparse(item['url'])
        if Path(name).name != name or not name.endswith('.jar') or url.scheme != 'https' or url.hostname != 'cdn.modrinth.com':
            raise ValueError('Unexpected publisher artifact path or host')
        file = destination / name
        if file.is_file() and hashlib.sha512(file.read_bytes()).hexdigest() == item['sha512']:
            print('Verified cached ' + name, flush=True)
            continue
        request = urllib.request.Request(item['url'], headers={'User-Agent': 'EndCraft-NeoForge/experimental'})
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
