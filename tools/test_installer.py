"""Ownership/rollback checks in temporary directories; never touches the game."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('installer',Path(__file__).with_name('install-probe.py'))
installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)

class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='EndCraft installer test ')
        installer.ROOT=Path(self.temp.name)/'project with spaces'
        installer.SETTINGS=Path(self.temp.name)/'settings'
        installer.STATE=installer.ROOT/'.tools/install-manifest.json'
        installer.game_running=lambda:False
        self.game=Path(self.temp.name)/'game'
        self.game.mkdir();(self.game/'Endfield.exe').write_bytes(b'fixture')
        framework=installer.ROOT/'build/framework';framework.mkdir(parents=True)
        (framework/'xinput1_4.dll').write_bytes(b'loader v1')
        (framework/'BetterEndfield.Host.dll').write_bytes(b'host v1')
    def tearDown(self): self.temp.cleanup()
    def test_install_update_uninstall_preserves_originals(self):
        original=self.game/'original.dat';original.write_bytes(b'untouched')
        installer.install(self.game)
        self.assertIn('install_root=',(self.game/'EndCraft-bootstrap.ini').read_text(encoding='utf-16'))
        (installer.ROOT/'build/framework/xinput1_4.dll').write_bytes(b'loader v2')
        installer.update()
        self.assertEqual((self.game/'xinput1_4.dll').read_bytes(),b'loader v2')
        # Uninstall works even after deleting build products.
        for path in (installer.ROOT/'build/framework').iterdir(): path.unlink()
        installer.uninstall()
        self.assertEqual(original.read_bytes(),b'untouched')
        self.assertFalse((self.game/'xinput1_4.dll').exists())
    def test_existing_loader_is_preserved(self):
        loader=self.game/'xinput1_4.dll';loader.write_bytes(b'other mod')
        with self.assertRaises(RuntimeError): installer.install(self.game)
        self.assertEqual(loader.read_bytes(),b'other mod')
    def test_modified_owned_file_is_preserved(self):
        installer.install(self.game)
        loader=self.game/'xinput1_4.dll';loader.write_bytes(b'changed')
        with self.assertRaises(RuntimeError): installer.update()
        with self.assertRaises(RuntimeError): installer.uninstall()
        self.assertEqual(loader.read_bytes(),b'changed')
    def test_live_game_prevents_update_and_uninstall(self):
        installer.install(self.game);installer.game_running=lambda:True
        with self.assertRaises(RuntimeError): installer.update()
        with self.assertRaises(RuntimeError): installer.uninstall()
    def test_manifest_cannot_target_unrelated_file(self):
        installer.install(self.game)
        other=Path(self.temp.name)/'other.txt';other.write_bytes(b'keep')
        state=json.loads(installer.STATE.read_text());state['files'].append({'path':str(other),'sha256':installer.sha(other)})
        installer.STATE.write_text(json.dumps(state))
        with self.assertRaises(RuntimeError): installer.uninstall()
        self.assertEqual(other.read_bytes(),b'keep')
    def test_staging_retains_owned_loader_and_requires_missing_host(self):
        installer.install(self.game);installer.game_running=lambda:True
        with self.assertRaises(RuntimeError): installer.stage_update()
        (self.game/'BetterEndfield-xinput1_4-host.status').write_text('worker entered\nHost file was not found\n')
        (installer.ROOT/'build/framework/xinput1_4.dll').write_bytes(b'fixed loader')
        installer.stage_update()
        self.assertEqual((self.game/'xinput1_4.dll').read_bytes(),b'fixed loader')
        self.assertEqual((self.game/'EndCraft-xinput1_4.rollback.dll').read_bytes(),b'loader v1')
        state=json.loads(installer.STATE.read_text());self.assertTrue(state['restart_required'])
        installer.game_running=lambda:False;installer.uninstall()
        self.assertFalse((self.game/'EndCraft-xinput1_4.rollback.dll').exists())
    def test_legacy_host_staging_records_rollback_and_local_index(self):
        installer.install(self.game)
        # Reproduce the first install, before the project-local index existed.
        local=installer.ROOT/'.tools/framework/third-party/index.json';local.unlink()
        state=json.loads(installer.STATE.read_text())
        state['files']=[item for item in state['files'] if Path(item['path'])!=local]
        installer.STATE.write_text(json.dumps(state))
        installer.game_running=lambda:True
        (installer.ROOT/'build/framework/BetterEndfield.Host.dll').write_bytes(b'host v2')
        installer.stage_host()
        runtime=installer.ROOT/'.tools/framework/runtime'
        self.assertEqual((runtime/'BetterEndfield.Host.dll').read_bytes(),b'host v2')
        self.assertEqual((runtime/'EndCraft-Host.rollback.dll').read_bytes(),b'host v1')
        self.assertEqual(local.read_bytes(),(installer.SETTINGS/'third-party/index.json').read_bytes())
        installer.game_running=lambda:False;installer.uninstall()
        self.assertFalse(local.exists())

if __name__=='__main__': unittest.main()
