"""Print this machine's bridge world anchor, for LAN players to share (configure-gameplay-startup.py --anchor)."""
import contextlib
import importlib.util
import io
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    spec = importlib.util.spec_from_file_location('runtime_report', ROOT / 'tools/runtime-report.py')
    report = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(report)
    with tempfile.TemporaryDirectory() as directory:
        output = Path(directory) / 'status.json'
        with contextlib.redirect_stdout(io.StringIO()):
            report.collect('status', output, 'endcraft.gameplay33')
        gameplay = json.loads(output.read_text(encoding='utf-8'))['body']['gameplay']
    anchor = gameplay.get('origin_raw_units')
    if not anchor or not gameplay.get('initialized'):
        sys.exit('The bridge has no world anchor yet: enter the game world first.')
    print('Anchor: {:.3f} {:.3f} {:.3f}'.format(*anchor))
    print('Players joining this world run, then restart the game:')
    print('  python tools/configure-gameplay-startup.py --auto-start --anchor {:.3f} {:.3f} {:.3f}'.format(*anchor))


if __name__ == '__main__':
    main()
