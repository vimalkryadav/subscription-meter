#!/usr/bin/env python3
"""Opt-in Docker PostgreSQL migration/coordination and pinned Caddy SSE reload tests."""
import argparse
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import uuid
import threading
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import bootstrap_blue_green

ROOT=Path(__file__).resolve().parents[2]
CADDY_IMAGE="caddy:2-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f"


def command(args,check=True,**kwargs):
    return subprocess.run(args,cwd=ROOT,text=True,capture_output=True,check=check,timeout=kwargs.pop("timeout",300),**kwargs)


def port(container,internal):
    mapping=json.loads(command(["docker","inspect",container]).stdout)[0]["NetworkSettings"]["Ports"]
    return int(mapping[str(internal)+"/tcp"][0]["HostPort"])


def wait_database(container,timeout=60):
    # PostgreSQL images use a temporary Unix-socket server while initializing.
    # Wait for final TCP service AND the actual application database before migrations.
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            ready=command(["docker","exec",container,"pg_isready","-h","127.0.0.1","-p","5432","-U","meter","-d","meter","-t","2"],
                          check=False,timeout=min(5,max(.1,deadline-time.monotonic())))
            if ready.returncode==0:
                query=command(["docker","exec","-e","PGCONNECT_TIMEOUT=2",container,"psql","-X","-qAt","-h","127.0.0.1","-p","5432",
                               "-v","ON_ERROR_STOP=1","-U","meter","-d","meter","-c","SELECT 1"],
                              check=False,timeout=min(5,max(.1,deadline-time.monotonic())))
                if query.returncode==0 and query.stdout.strip()=="1":return
        except subprocess.TimeoutExpired:
            pass
        remaining=deadline-time.monotonic()
        if remaining>0:time.sleep(min(1,remaining))
    raise RuntimeError("Isolated PostgreSQL meter database did not become ready over TCP before the deadline")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-only",action="store_true")
    parser.add_argument("--timescale",action="store_true",help="Use the pinned production Timescale image and explicit hypertable migrations")
    args=parser.parse_args()
    prefix="control-usage-deploy-test-"+uuid.uuid4().hex[:10]
    resources=[]
    command(["docker","network","create",prefix])
    try:
        pg=prefix+"-pg"
        resources.append(pg)
        image="timescale/timescaledb:latest-pg18@sha256:0fcff5761363809d1242f2261a3482197b5a5337407f8b97a976fb174206d579" if args.timescale else "postgres:18-alpine"
        command(["docker","run","-d","--name",pg,"--network",prefix,"-p","127.0.0.1::5432",
                 "-e","POSTGRES_HOST_AUTH_METHOD=trust","-e","POSTGRES_USER=meter","-e","POSTGRES_DB=meter",
                 "-e","TS_TUNE_MEMORY=512MB","-e","TS_TUNE_NUM_CPUS=1","-e","TS_TUNE_MAX_CONNS=30",
                 "--memory","768m",image])
        wait_database(pg)
        sql=command(["python3","scripts/migrate.py"]+(["--timescale"] if args.timescale else [])).stdout
        psql=["docker","exec","-i",pg,"psql","-X","-v","ON_ERROR_STOP=1","-U","meter","-d","meter"]
        command(psql,input=sql)
        command(psql,input=sql)
        count=command(psql+["-t","-A"],input="SELECT COUNT(*) FROM control_schema_history;\n").stdout.strip()
        assert int(count)==len(list((ROOT/"deploy/migrations").glob("V[0-9]*__*.sql")))+(1 if args.timescale else 0),count
        if args.timescale:
            hypertables=command(psql+["-t","-A"],input="SELECT count(*) FROM timescaledb_information.hypertables;\n").stdout.strip()
            assert int(hypertables)==4,hypertables
            print("Pinned Timescale hypertable conversion and retention migration replay passed.")
        command(psql,input="UPDATE control_schema_history SET checksum='invalid' WHERE name='migrations/V002__coordination.sql';\n")
        assert command(psql,input=sql,check=False).returncode != 0
        print("PostgreSQL migration adoption, replay and checksum rejection passed.")
        env=os.environ.copy()
        env["CONTROL_USAGE_POSTGRES_TEST_URL"]="jdbc:postgresql://127.0.0.1:"+str(port(pg,5432))+"/meter"
        result=command(["mvn","--batch-mode","--no-transfer-progress","-Dtest=PostgresCoordinationTest","test"],env=env)
        print(result.stdout[-2200:])
        if args.postgres_only:return
        with tempfile.TemporaryDirectory(prefix=prefix) as folder:
            folder=Path(folder)
            backend=folder/"backend.py"
            backend.write_text('''from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import threading,time
class Handler(BaseHTTPRequestHandler):
 def do_GET(self):
  self.send_response(200)
  self.send_header("Content-Type","text/event-stream" if self.path in ("/stream","/longstream","/v1/messages") else "text/plain")
  self.end_headers()
  if self.path in ("/stream","/longstream","/v1/messages"):
   for i in range(70 if self.path=="/longstream" else 12):
    self.wfile.write(("data: %s-%s\\n\\n"%(self.server.server_port,i)).encode());self.wfile.flush();time.sleep(.5)
  else:self.wfile.write(str(self.server.server_port).encode())
 do_POST=do_GET
 def log_message(self,*args):pass
for p in (8001,8002,8180):
 threading.Thread(target=ThreadingHTTPServer(("0.0.0.0",p),Handler).serve_forever,daemon=True).start()
threading.Event().wait()
''')
            upstream=prefix+"-upstream"
            resources.append(upstream)
            command(["docker","run","-d","--name",upstream,"--network",prefix,"--network-alias","test-upstream","--network-alias","app",
                     "--memory","128m","-v",str(backend)+":/backend.py:ro","python:3.12-bookworm","python","/backend.py"])
            config=folder/"Caddyfile"
            def write(target):
                with config.open("w") as output:
                    output.write("{\nadmin off\nauto_https off\n}\n:8182 {\nreverse_proxy test-upstream:"+str(target)+" {\nflush_interval -1\n}\n}\n")
                    output.flush();os.fsync(output.fileno())
            write(8001)
            edge=prefix+"-edge"
            resources.append(edge)
            command(["docker","run","-d","--name",edge,"--network",prefix,"-p","127.0.0.1::8182",
                     "--memory","128m","--read-only","--tmpfs","/config","--tmpfs","/data","-v",str(config)+":/etc/caddy/Caddyfile:ro",
                     CADDY_IMAGE,"caddy","run","--config","/etc/caddy/Caddyfile","--adapter","caddyfile"])
            base="http://127.0.0.1:"+str(port(edge,8182))
            def get(path="/"):
                with urllib.request.urlopen(base+path,timeout=20) as response:return response.read().decode()
            for _ in range(30):
                try:
                    if get()=="8001":break
                except Exception:pass
                time.sleep(.25)
            # Twenty concurrent streams span a switch and rollback; each runs35seconds.
            # Wait until every stream has received its first event before changing routes.
            def long_stream(ready):
                with urllib.request.urlopen(base+"/longstream",timeout=50) as response:
                    first=response.readline()
                    ready.set()
                    return first.decode()+response.read().decode()
            with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
                blue_ready=[threading.Event() for _ in range(10)]
                blue=[pool.submit(long_stream,event) for event in blue_ready]
                assert all(event.wait(10) for event in blue_ready)
                write(8002)
                command(["docker","kill","--signal","SIGUSR1",edge])
                for _ in range(30):
                    if get()=="8002":break
                    time.sleep(.1)
                assert get()=="8002"
                green_ready=[threading.Event() for _ in range(10)]
                green=[pool.submit(long_stream,event) for event in green_ready]
                assert all(event.wait(10) for event in green_ready)
                write(8001)
                command(["docker","kill","--signal","SIGUSR1",edge])
                for _ in range(30):
                    if get()=="8001":break
                    time.sleep(.1)
                assert get()=="8001"
                for response in blue:
                    assert response.result()=="".join(f"data: 8001-{i}\n\n" for i in range(70))
                for response in green:
                    assert response.result()=="".join(f"data: 8002-{i}\n\n" for i in range(70))
            config.write_text("this config is invalid {\n")
            command(["docker","kill","--signal","SIGUSR1",edge])
            time.sleep(.5)
            assert get()=="8001"
            actual=(ROOT/"deploy/public-gateway.Caddyfile").read_text()
            config.write_text(actual)
            command(["docker","kill","--signal","SIGUSR1",edge])
            time.sleep(.4)
            assert get("/gateway/v1/models")=="8180"
            def stream_gateway():
                req=urllib.request.Request(base+"/gateway/v1/messages",data=b"{}",method="POST")
                with urllib.request.urlopen(req,timeout=20) as response:return response.read().decode()
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                active=pool.submit(stream_gateway)
                time.sleep(1)
                config.write_text(bootstrap_blue_green.maintenance_config(actual))
                command(["docker","kill","--signal","SIGUSR1",edge])
                time.sleep(.4)
                for path in ("/portal/api/session","/admin/status","/gateway/v1/models"):
                    try:
                        get(path)
                        raise AssertionError("Admission pause failed for "+path)
                    except urllib.error.HTTPError as response:
                        assert response.code==503 and response.headers.get("Retry-After")=="60"
                assert active.result()=="".join(f"data: 8180-{i}\n\n" for i in range(12))
            config.write_text(actual)
            command(["docker","kill","--signal","SIGUSR1",edge])
            time.sleep(.4)
            assert get("/gateway/v1/models")=="8180"
            print("Pinned Caddy:20 concurrent35-second SSE streams survive admin-off reload+rollback, bad config retains route, real allowlist admission pause preserves existing stream.")
    except subprocess.CalledProcessError as error:
        print(error.stdout[-4000:] if error.stdout else "")
        print(error.stderr[-4000:] if error.stderr else "")
        raise
    finally:
        for resource in reversed(resources):command(["docker","rm","-fv",resource],check=False)
        command(["docker","network","rm",prefix],check=False)


if __name__=="__main__":main()
