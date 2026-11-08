#!/usr/bin/env python3
"""Opt-in packaged application smoke, using only compose.audit-test dependencies."""
import json
import os
import re
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from migrate import migration_sql

def main():
    container=subprocess.check_output(['docker','compose','-p','control-usage-test','-f','compose.audit-test.yaml','ps','-q','postgres'],cwd=ROOT,text=True).strip()
    if not container:raise RuntimeError('Start the isolated control-usage-test dependencies first')
    schema='packaged_'+uuid.uuid4().hex
    psql=['docker','exec','-i',container,'psql','-X','-v','ON_ERROR_STOP=1','-U','audit_test','-d','audit_test']
    def sql(value):subprocess.run(psql,input=value,text=True,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    sql('CREATE SCHEMA '+schema+'; SET search_path TO '+schema+';\n'+migration_sql())
    process=None
    try:
        with tempfile.TemporaryDirectory(prefix='control-usage-packaged-') as directory:
            directory=Path(directory);env=os.environ.copy();tokens={}
            for name in ('admin','metrics','upstream','deploy'):
                tokens[name]=secrets.token_hex(32)
                path=directory/name;path.write_text(tokens[name]);path.chmod(0o600)
                env[name.upper()+'_TOKEN_FILE']=str(path)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
            env.update(DATABASE_URL='jdbc:postgresql://127.0.0.1:25432/audit_test?currentSchema='+schema,
                DATABASE_USER='audit_test',DATABASE_PASSWORD='local-integration-only',PORT=str(port),
                SQL_INIT_MODE='never',UPSTREAM_MODE='subscription',METER_AUDIT_TRANSPORT='jdbc',
                PORTAL_SECURE_COOKIE='false',RELEASE_ID='isolated-packaged-test',TIMESCALE_ENABLED='false')
            java=str(Path(env['JAVA_HOME'])/'bin/java') if 'JAVA_HOME' in env else 'java'
            def request(path,token=None):
                req=urllib.request.Request('http://127.0.0.1:'+str(port)+path,headers={'Authorization':'Bearer '+token} if token else {})
                try:
                    with urllib.request.urlopen(req,timeout=5) as response:return response.status,response.read()
                except urllib.error.HTTPError as error:return error.code,error.read()
            with (directory/'app.log').open('w') as log:
                process=subprocess.Popen([java,'-Xmx512m','-jar',str(ROOT/'target/subscription-meter-0.1.0.jar'),'--server.address=127.0.0.1'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
                for attempt in range(90):
                    if process.poll() is not None:raise RuntimeError('Packaged application exited before readiness')
                    try:
                        code,body=request('/internal/deployment',tokens['deploy'])
                        if code==200 and json.loads(body)['ready']:break
                    except (OSError,ValueError):pass
                    time.sleep(.5)
                else:raise RuntimeError('Packaged application never became ready')
                code,html=request('/')
                assert code==200 and b'id="root"' in html
                assets=re.findall(rb'(?:src|href)="(/assets/react/[^"]+)"',html)
                assert len(assets)>=2,'React script and CSS must be packaged'
                for asset in assets:
                    assert request(asset.decode())[0]==200,asset.decode()
                assert request('/manage.html')[0]==404
                assert request('/actuator/health')[0]==200
                assert request('/internal/deployment')[0]==404
                assert request('/admin/request-search')[0]==401
                assert request('/admin/accounting')[0]==401
                assert request('/admin/quota-usage')[0]==401
                assert request('/admin/accounts')[0]==401
                for endpoint in ('/admin/request-search','/admin/accounting','/admin/quota-usage','/admin/accounts','/admin/status'):
                    assert request(endpoint,tokens['admin'])[0]==200,endpoint
                # Exercise the window aggregation on PostgreSQL, in this disposable schema only.
                sql("SET search_path TO "+schema+"; " + """
                    INSERT INTO members(id,name,key_hash,key_prefix,rpm,concurrency_limit)
                    VALUES('quota-alice','Alice','quota-hash-a','sm_a',60,4),('quota-bob','Bob','quota-hash-b','sm_b',60,4);
                    INSERT INTO requests(id,member_id,started_at,path,model,state,usage_seen,usage_complete,input_tokens)
                    VALUES('quota-a','quota-alice',CURRENT_TIMESTAMP-INTERVAL '1 minute','/v1/messages','test','complete',TRUE,TRUE,100),
                          ('quota-b','quota-bob',CURRENT_TIMESTAMP-INTERVAL '1 minute','/v1/messages','test','complete',TRUE,TRUE,300);
                    INSERT INTO provider_quota_snapshots(id,provider,upstream_account,bucket,used_percentage,observed_at,resets_at,source,trusted)
                    VALUES('quota-week','anthropic','primary','seven_day',40,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 day','upstream_headers_v1',TRUE);
                    """)
                code,body=request('/admin/quota-usage',tokens['admin'])
                assert code==200
                quota=json.loads(body);window=quota['windows'][0]
                assert quota['outsideUsage']=='unknown' and window['requests']==2 and window['reportedTokens']==400
                assert [member['gatewayTokenSharePercent'] for member in window['members']]==[75,25]
                assert all(member['allowanceUsedPercent'] is None for member in window['members'])
                assert request('/admin/quota-usage?provider=invalid',tokens['admin'])[0]==400
                assert request('/openai/v1/models')[0]==404
                print('Packaged application: migrated PostgreSQL startup, lease/audit readiness, protected admin APIs, bootstrap reporting and disabled Codex route passed. No inference sent.')
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:process.wait(timeout=20)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        sql('DROP SCHEMA '+schema+' CASCADE;')

if __name__=='__main__':main()
