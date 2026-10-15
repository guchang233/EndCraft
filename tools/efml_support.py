"""Locate the active EFML profile and use its CLI for module mutations."""
from pathlib import Path
import json
import os
import subprocess


def data_root():
    return Path(os.environ.get('EFML_HOME') or Path(os.environ['LOCALAPPDATA']) / 'EFML')


def index_path():
    return data_root() / 'modules/index.json'


def available():
    return index_path().is_file()


def command(*args):
    # Prefer the installed runtime over unrelated source-tree builds.
    candidates = sorted((data_root() / 'runtime').glob('*/efml.exe'),
                        key=lambda p: p.stat().st_mtime, reverse=True)
    if not candidates:
        raise RuntimeError('EFML module index exists but its installed CLI is missing.')
    result = subprocess.run([str(candidates[0]), *map(str, args)],
                            capture_output=True, encoding='utf-8', errors='replace')
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or 'EFML command failed.')
    return result.stdout.strip()


def modules():
    return json.loads(index_path().read_text(encoding='utf-8-sig'))['modules']
