"""Load the exact Host in this test process, never inject it into another process."""
from pathlib import Path
import ctypes
import json
import os
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
index=json.loads((Path(os.environ['LOCALAPPDATA'])/'BetterEndfield/third-party/index.json').read_text())
library=ctypes.WinDLL(str(ROOT/'.tools/framework/runtime/BetterEndfield.Host.dll'))
deadline=time.monotonic()+8
while time.monotonic()<deadline:
    try:
        request=urllib.request.Request(f'http://127.0.0.1:{index["port"]}/status',headers={'Authorization':'Bearer '+index['token']})
        with urllib.request.urlopen(request,timeout=1) as response: result=json.load(response)
        print(json.dumps({'test_process_pid':os.getpid(),'scope':'independent_host_process','host_status':result},ensure_ascii=False))
        break
    except OSError: time.sleep(.2)
else: raise SystemExit('Independent Host RPC did not start within 8 seconds.')
# Windows owns teardown of the pinned process-lifetime Host.
os._exit(0)
