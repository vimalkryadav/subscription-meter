#!/usr/bin/env python3
"""HTTP/1.1 client for the isolated fixed-byte tunnel_probe.py server only."""
import argparse
import concurrent.futures
import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request


def run(base, mode, index):
    start=time.monotonic(); first=None;status=0
    request=urllib.request.Request(base+'/stream?mode='+mode,method='POST',data=b'{"diagnostic":true}',
        headers={'Content-Type':'application/json','Authorization':'Bearer diagnostic-only-not-a-credential',
                 'anthropic-version':'2023-06-01','anthropic-beta':'claude-code-20250219'})
    try:
        with urllib.request.urlopen(request,timeout=45) as response:
            status=response.status;body=b'';chunks=[]
            while True:
                line=response.readline(4096)
                if not line:break
                if first is None:first=time.monotonic()-start
                body+=line;chunks.append(round((time.monotonic()-start)*1000))
                if len(body)>8192:raise ValueError('oversized response')
            data=[json.loads(line[6:] if mode=='sse' else line) for line in body.splitlines() if line and (mode!='sse' or line.startswith(b'data: '))]
            return {'index':index,'status':status,'firstMs':round(first*1000) if first is not None else None,
                    'totalMs':round((time.monotonic()-start)*1000),'sequenceComplete':[x['seq'] for x in data]==list(range(16)),
                    'hash':hashlib.sha256(body).hexdigest(),'deliverySpanMs':max(chunks)-min(chunks) if chunks else None}
    except urllib.error.HTTPError as error:
        error.close()
        return {'index':index,'status':error.code,'error':'HTTPError','totalMs':round((time.monotonic()-start)*1000)}
    except Exception as error:return {'index':index,'status':status,'error':type(error).__name__,'totalMs':round((time.monotonic()-start)*1000)}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('url');parser.add_argument('--mode',choices=['sse','ndjson'],default='sse');parser.add_argument('--count',type=int,default=20)
    args=parser.parse_args();url=urllib.parse.urlsplit(args.url)
    if url.username or url.password or url.path not in ('','/') or url.query or url.fragment or args.count not in (1,20):parser.error('Bare probe origin and count1 or20 required')
    if not ((url.scheme=='https' and (url.hostname or '').endswith('.trycloudflare.com')) or (url.scheme=='http' and url.hostname=='127.0.0.1')):parser.error('Probe origin required')
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.count) as pool:
        rows=list(pool.map(lambda i:run(args.url.rstrip('/'),args.mode,i),range(args.count)))
    values=lambda key:sorted(x[key] for x in rows if x.get(key) is not None)
    def p95(key):
        v=values(key);return v[max(0,int(len(v)*.95+.999)-1)] if v else None
    print(json.dumps({'client':'HTTP/1.1','url':args.url,'mode':args.mode,'requests':len(rows),
        'complete':sum(x.get('sequenceComplete',False) for x in rows),'firstP95Ms':p95('firstMs'),'durationP95Ms':p95('totalMs'),
        'deliverySpanP95Ms':p95('deliverySpanMs'),'rows':rows}))

if __name__=='__main__':main()
