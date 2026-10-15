"""EFML profile routing checks; never connects to or modifies a real game."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

TOOLS=Path(__file__).resolve().parent
sys.path.insert(0,str(TOOLS))
import efml_support


def load(name,file):
    spec=importlib.util.spec_from_file_location(name,TOOLS/file)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class EfmlToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.local=Path(self.temp.name)
        self.profile=self.local/'custom profile'
        self.env=patch.dict(os.environ,{'LOCALAPPDATA':str(self.local),'EFML_HOME':str(self.profile)})
        self.env.start()
        self.addCleanup(self.env.stop)
        index=self.profile/'modules/index.json'
        index.parent.mkdir(parents=True)
        index.write_text(json.dumps({'modules':[]}))

    def test_rpc_prefers_efml_profile_even_when_legacy_index_exists(self):
        old=self.local/'BetterEndfield/third-party/index.json'
        old.parent.mkdir(parents=True)
        old.write_text('{}')
        rpc=load('routing_rpc','runtime-report.py')
        self.assertEqual(rpc.INDEX,self.profile/'modules/index.json')

    def test_startup_preserves_selected_launcher_and_anchor(self):
        config={'guest_launcher':'C:/chosen/start-guest.ps1','initial_host_anchor':[1,2,3],'custom':42}
        (self.profile/'modules/index.json').write_text(json.dumps({'modules':[
            {'id':'endcraft.gameplay35','configuration':config}]}))
        startup=load('efml_startup','configure-gameplay-startup.py')
        with patch.object(efml_support,'command',return_value='saved') as command:
            with contextlib.redirect_stdout(io.StringIO()):startup.main(True,kind='gameplay35')
        args=command.call_args.args
        self.assertEqual(args[:2],('config','endcraft.gameplay35'))
        self.assertEqual(json.loads(args[2]),dict(config,auto_enable=True))

    def test_registration_refuses_to_overwrite_registered_dll(self):
        (self.profile/'modules/index.json').write_text(json.dumps({'modules':[
            {'id':'endcraft.gameplay35'}]}))
        registration=load('efml_registration','register-inspector.py')
        with patch.object(registration.shutil,'copy2') as copy:
            with self.assertRaisesRegex(RuntimeError,'already registered'):
                registration.main('gameplay35')
        copy.assert_not_called()

    def test_cli_failure_is_reported_without_printing_arguments(self):
        binary=self.profile/'runtime/1.0.0/efml.exe'
        binary.parent.mkdir(parents=True)
        binary.write_bytes(b'fixture')
        with patch.object(efml_support.subprocess,'run') as run:
            run.return_value.returncode=1
            run.return_value.stderr='registration failed'
            with self.assertRaisesRegex(RuntimeError,'registration failed'):
                efml_support.command('enable','endcraft.gameplay35')


if __name__=='__main__':unittest.main()
