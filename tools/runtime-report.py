"""Authenticated local Better-Endfield RPC. Never logs its authentication token."""
from pathlib import Path
import argparse
import json
import os
import time
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]
INDEX=Path(os.environ['LOCALAPPDATA'])/'BetterEndfield/third-party/index.json'
if not INDEX.is_file():
    # Store Python can virtualize LocalAppData; the installed host keeps the same
    # authenticated module index in the project runtime directory.
    INDEX=ROOT/'.tools/framework/third-party/index.json'

def collect(action,output,module='endcraft.probe',request_body=None):
    index=json.loads(INDEX.read_text(encoding='utf-8-sig'))
    endpoint=f'http://127.0.0.1:{index["port"]}'
    def call(path,body=None):
        request=urllib.request.Request(endpoint+path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'Authorization':'Bearer '+index['token'],'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=3) as response: return json.load(response)
    if action=='host-status': result=call('/status')
    else:
        request_id=uuid.uuid4().hex
        accepted=call('/send',{'module_id':module,'request_id':request_id,'body':request_body or {'action':action}})
        if not accepted.get('accepted'): raise RuntimeError('Host did not accept request')
        deadline=time.monotonic()+15
        while True:
            events=call('/poll',{'module_id':module})
            replies=[item for item in events.get('messages',[]) if item.get('kind')=='reply' and item.get('request_id')==request_id]
            if replies:
                reply=replies[0]
                result={'result':reply['result'],'body':reply['body']}
                break
            if time.monotonic()>=deadline: raise TimeoutError('Accepted request did not produce a reply')
            time.sleep(.15)
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
    print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['host-status','probe','observe','status','inspect'])
    parser.add_argument('--output',type=Path,default=ROOT/'reports/runtime-report.json')
    parser.add_argument('--module',default='endcraft.probe');parser.add_argument('--request-file',type=Path);args=parser.parse_args()
    try: collect(args.action,args.output,args.module,json.loads(args.request_file.read_text(encoding='utf-8')) if args.request_file else None)
    except (OSError,ValueError,RuntimeError,TimeoutError) as error: parser.exit(1,f'Runtime report unavailable: {error}\n')
