import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, chmod, symlink, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { config, session, HttpAdminApi } from '../dist/api.js';
const token='a'.repeat(43), csrf='c'.repeat(64), base='https://control.example';
async function fixture(){const dir=await mkdtemp(join(tmpdir(),'control-usage-mcp-test-'));const path=join(dir,'session');await writeFile(path,JSON.stringify({version:1,origin:base,session:token}),{mode:0o600});return {settings:{origin:base,sessionFile:path,writes:true,content:false},dir,path,close:()=>rm(dir,{recursive:true,force:true})};}
const json=(data,status=200)=>new Response(JSON.stringify(data),{status,headers:{'content-type':'application/json'}});

test('configuration rejects insecure origins, paths and ambiguous flags',()=>{
 for(const url of ['http://control.example','https://u:p@control.example','https://control.example/gateway','https://control.example/?secret=a'])assert.throws(()=>config({CONTROL_USAGE_URL:url,CONTROL_USAGE_MCP_SESSION_FILE:'/private/session'}));
 assert.throws(()=>config({CONTROL_USAGE_URL:base,CONTROL_USAGE_MCP_SESSION_FILE:'relative'}));
 assert.throws(()=>config({CONTROL_USAGE_URL:base,CONTROL_USAGE_MCP_SESSION_FILE:'/private/session',CONTROL_USAGE_MCP_WRITES:'yes'}));
});
test('credentials are private, origin-bound and reloaded; symlinks and unsafe modes fail',async()=>{
 const f=await fixture();try{
  assert.equal(await session(f.settings),token);
  await assert.rejects(session({...f.settings,origin:'https://other.example'}),/Private MCP session/);
  await chmod(f.path,0o644);await assert.rejects(session(f.settings),/Private MCP session/);await chmod(f.path,0o600);
  const link=join(f.dir,'link');await symlink(f.path,link);await assert.rejects(session({...f.settings,sessionFile:link}),/Private MCP session/);
  await writeFile(f.path,JSON.stringify({version:1,origin:base,session:'b'.repeat(43)}));assert.equal(await session(f.settings),'b'.repeat(43));
 }finally{await f.close();}
});
test('writes obtain fresh CSRF and send only one mutation with same credential',async()=>{
 const f=await fixture(),calls=[];try{
  const api=new HttpAdminApi(f.settings,async(url,options)=>{calls.push({url,options});return url.endsWith('/portal/api/session')?json({user:{role:'admin'},csrfToken:csrf}):json({saved:true});});
  assert.deepEqual(await api.request('PATCH','/admin/members/m/limits',{rpm:20}),{saved:true});
  assert.equal(calls.length,2);assert.equal(calls[0].url,base+'/portal/api/session');assert.equal(calls[1].options.headers['X-CSRF-Token'],csrf);
  assert.equal(calls[1].options.headers.Cookie,'meter_session='+token);assert.equal(calls[1].options.redirect,'manual');
  assert.equal(calls[1].options.headers['X-Control-Usage-Client'],'mcp');
  await assert.rejects(new HttpAdminApi({...f.settings,writes:false}).request('DELETE','/admin/members/m'),/disabled/);
 }finally{await f.close();}
});
test('role and expired session rejection prevent actions and do not expose failure bodies',async()=>{
 const f=await fixture();try{
  let count=0;const api=new HttpAdminApi(f.settings,async()=>{count++;return json({user:{role:'member'},csrfToken:csrf});});
  await assert.rejects(api.request('DELETE','/admin/members/m'),/Administrator session/);assert.equal(count,1);
  const failed=new HttpAdminApi(f.settings,async()=>json({error:'PRIVATE_TOKEN'},401));
  await assert.rejects(failed.request('GET','/admin/status'),error=>/expired/.test(error.message)&&!error.message.includes('PRIVATE_TOKEN'));
 }finally{await f.close();}
});
test('redirects, foreign routes and oversized bodies fail without retries',async()=>{
 const f=await fixture();try{
  let count=0;const redirect=new HttpAdminApi(f.settings,async()=>{count++;return new Response(null,{status:302,headers:{location:'https://evil.invalid'}});});
  await assert.rejects(redirect.request('GET','/admin/status'),/Redirect rejected/);assert.equal(count,1);
  await assert.rejects(redirect.request('GET','https://evil.invalid/admin/status'),/Unsupported/);assert.equal(count,1);
  const large=new HttpAdminApi(f.settings,async()=>json({data:'x'.repeat(2*1024*1024)}));await assert.rejects(large.request('GET','/admin/status'),/2 MiB/);
 }finally{await f.close();}
});
test('ambiguous write failure is not automatically retried; parallel writes are refused',async()=>{
 const f=await fixture();try{
  let count=0;const api=new HttpAdminApi(f.settings,async(url)=>{count++;if(url.endsWith('/portal/api/session'))return json({user:{role:'admin'},csrfToken:csrf});throw new Error('secret network details');});
  await assert.rejects(api.request('DELETE','/admin/members/m'),/outcome unknown/);assert.equal(count,2);
  let finish;const pending=new HttpAdminApi(f.settings,()=>new Promise(resolve=>{finish=resolve;}));
  const first=pending.request('DELETE','/admin/members/m');await assert.rejects(pending.request('DELETE','/admin/members/n'),/Another operation/);
  while(!finish)await new Promise(resolve=>setImmediate(resolve));finish(json({},401));await assert.rejects(first,/expired/);
 }finally{await f.close();}
});
