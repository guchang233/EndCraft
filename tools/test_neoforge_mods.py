import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fetch_mods', Path(__file__).with_name('fetch-neoforge-mods.py'))
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)


class DependencyFetchTest(unittest.TestCase):
    def run_local(self, *, optional=False, payload=None):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            folder = root / 'mc-neoforge'
            folder.mkdir()
            item = {'filename': 'local.jar', 'url': None, 'sha512': hashlib.sha512(b'expected').hexdigest(),
                    'optional_local': optional}
            (folder / 'modpack.lock.json').write_text(json.dumps([item]), encoding='utf-8')
            if payload is not None:
                (folder / 'libs').mkdir()
                (folder / 'libs/local.jar').write_bytes(payload)
            with patch.object(fetch, 'ROOT', root), patch('sys.argv', ['fetch']), contextlib.redirect_stdout(io.StringIO()):
                fetch.main()

    def test_optional_local_file_is_not_required_on_clean_runner(self):
        self.run_local(optional=True)

    def test_required_local_file_still_fails_when_missing(self):
        with self.assertRaisesRegex(RuntimeError, 'missing'):
            self.run_local()

    def test_optional_local_file_is_still_hash_verified_when_supplied(self):
        self.run_local(optional=True, payload=b'expected')
        with self.assertRaisesRegex(RuntimeError, 'checksum mismatch'):
            self.run_local(optional=True, payload=b'wrong')


if __name__ == '__main__':
    unittest.main()
