import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Client, InMemoryTransport } from '@modelcontextprotocol/client';
import { StdioClientTransport } from '@modelcontextprotocol/client/stdio';
import { createServer } from '../dist/server.js';
import { fileURLToPath } from 'node:url';

async function fixture(settings={writes:true,content:false}, responder=()=>({ok:true})) {
  const calls=[];
  const server=createServer({request:async(...args)=>{calls.push(args);return responder(...args);}},settings);
  const client=new Client({name:'isolated-test',version:'1.0.0'});
  const [a,b]=InMemoryTransport.createLinkedPair();await server.connect(a);await client.connect(b);
  return {client,calls,close:async()=>{await client.close();await server.close();}};
}
const call=(client,name,args={})=>client.callTool({name,arguments:args});

test('read-only registry excludes actions, content, generic execution and credential tools',async()=>{
 const f=await fixture({writes:false,content:false});try{
  const list=await f.client.listTools();assert.equal(list.tools.length,15);
  assert.ok(list.tools.every(t=>t.annotations.readOnlyHint));
  assert.ok(!list.tools.some(t=>/revoke|decide|classify|capture|events|shell|execute|credential|token$/.test(t.name)));
  await call(f.client,'get_quota_usage');assert.deepEqual(f.calls,[['GET','/admin/quota-usage?provider=']]);
 }finally{await f.close();}
});

test('actions require explicit confirmation and enforce argument limits before sending writes',async()=>{
 const f=await fixture();try{
  const args={memberId:'member-1',rpm:20,concurrency:2,expectedRpm:60,expectedConcurrency:4};
  assert.equal((await call(f.client,'update_member_limits',args)).isError,true);
  assert.equal((await call(f.client,'update_member_limits',{...args,rpm:601,confirm:true})).isError,true);
  assert.equal((await call(f.client,'update_member_limits',{...args,confirm:true,url:'https://evil.invalid'})).isError,true);
  assert.equal(f.calls.length,0);
  assert.equal((await call(f.client,'update_member_limits',{...args,confirm:true})).isError,undefined);
  assert.deepEqual(f.calls,[['PATCH','/admin/members/member-1/limits',{rpm:20,concurrency:2,expectedRpm:60,expectedConcurrency:4}]]);
  const actions=(await f.client.listTools()).tools.filter(t=>!t.annotations.readOnlyHint);assert.equal(actions.length,7);
  assert.ok(actions.every(t=>t.annotations.destructiveHint&&!t.annotations.idempotentHint));
 }finally{await f.close();}
});

test('approval, revocation, classification and expense tools map to fixed API contracts',async()=>{
 const f=await fixture();try{
  await call(f.client,'decide_key_request',{keyRequestId:'request-1',approve:true,rpm:60,concurrency:4,confirm:true});
  await call(f.client,'revoke_key_request',{keyRequestId:'request-1',confirm:true});
  await call(f.client,'revoke_member',{memberId:'member-1',confirm:true});
  await call(f.client,'revoke_account_sessions',{accountId:'account-1',sessionRef:'a'.repeat(64),confirm:true});
  await call(f.client,'classify_request',{requestId:'request-1',category:'debugging',confirm:true});
  const invoice={provider:'anthropic',upstreamAccount:'primary',periodStart:'2026-11-01T00:00:00Z',periodEnd:'2026-12-01T00:00:00Z',amount:25.12,currency:'USD',gatewayFraction:0.4,allocationBasis:'reported_tokens',provenance:'manual_invoice',confirm:true};
  assert.equal((await call(f.client,'record_subscription_expense',invoice)).isError,undefined);
  assert.deepEqual(f.calls.map(c=>c.slice(0,2)),[
   ['POST','/admin/key-requests/request-1/decision'],['POST','/admin/key-requests/request-1/revoke'],['DELETE','/admin/members/member-1'],
   ['DELETE','/admin/accounts/account-1/sessions/'+'a'.repeat(64)],['PATCH','/admin/requests/request-1/classification'],['POST','/admin/subscription-invoices']]);
  assert.equal('confirm' in f.calls.at(-1)[2],false);
  assert.equal((await call(f.client,'record_subscription_expense',{...invoice,periodEnd:invoice.periodStart})).isError,true);
  assert.equal(f.calls.length,6);
 }finally{await f.close();}
});

test('search validates scope and returns allowlisted metadata without captured content',async()=>{
 const f=await fixture(undefined,()=>({items:[{id:'r',name:'User',request_body:'PRIVATE PROMPT',response_headers:'SECRET HEADER',new_sensitive_field:'secret',input_tokens:100}],nextCursor:'next'}));try{
  assert.equal((await call(f.client,'search_requests',{from:'2026-11-02T00:00:00Z',to:'2026-11-01T00:00:00Z'})).isError,true);
  assert.equal((await call(f.client,'search_requests',{requestId:'../secret'})).isError,true);assert.equal(f.calls.length,0);
  const result=await call(f.client,'search_requests',{limit:5,provider:'anthropic'});
  assert.deepEqual(result.structuredContent.data.items,[{id:'r',name:'User',input_tokens:100}]);
  assert.doesNotMatch(JSON.stringify(result),/PRIVATE PROMPT|SECRET HEADER|new_sensitive_field/);
 }finally{await f.close();}
});

test('sensitive capture tool is opt-in, bounded and carries completeness metadata',async()=>{
 const f=await fixture({writes:false,content:true},()=>({request_body:'0123456789',capture_status:'incomplete'}));try{
  const result=await call(f.client,'read_request_capture',{requestId:'r',field:'request_body',offset:2,length:4});
  assert.equal(result.structuredContent.data.text,'2345');assert.equal(result.structuredContent.data.nextOffset,6);
  assert.equal(result.structuredContent.data.captureStatus,'incomplete');
  assert.equal((await call(f.client,'read_request_capture',{requestId:'r',field:'provider_credential'})).isError,true);
  assert.equal((await call(f.client,'read_request_events',{requestId:'r',limit:26})).isError,true);
 }finally{await f.close();}
});

test('unexpected failures do not echo raw secrets and oversized results fail explicitly',async()=>{
 const f=await fixture(undefined,()=>{throw new Error('PRIVATE_TOKEN');});try{
  const result=await call(f.client,'get_status');assert.equal(result.isError,true);assert.doesNotMatch(JSON.stringify(result),/PRIVATE_TOKEN/);
 }finally{await f.close();}
 const large=await fixture(undefined,()=>({payload:'x'.repeat(140000)}));try{
  const result=await call(large.client,'get_status');assert.equal(result.isError,true);assert.match(result.content[0].text,/128 KiB/);
 }finally{await large.close();}
});

test('guide resources, search and prompts are complete without accessing an API',async()=>{
 const f=await fixture();try{
  assert.equal((await f.client.listResources()).resources.length,2);
  const guide=await f.client.readResource({uri:'control-usage://admin-guide'});assert.match(guide.contents[0].text,/## Session and weekly limits/);
  const result=await call(f.client,'search_admin_guide',{query:'CSRF',guide:'mcp'});assert.ok(result.structuredContent.data.length>0);
  assert.equal((await f.client.listPrompts()).prompts.length,2);
  const prompt=await f.client.getPrompt({name:'investigate-request',arguments:{requestId:'r-1'}});assert.match(prompt.messages[0].content.text,/r-1/);
  assert.equal(f.calls.length,0);
 }finally{await f.close();}
});

test('real stdio client negotiates protocol and reads docs; absent session fails without a credential leak',async()=>{
 const client=new Client({name:'stdio-test',version:'1.0.0'});
 const transport=new StdioClientTransport({command:process.execPath,args:[fileURLToPath(new URL('../dist/index.js',import.meta.url))],stderr:'pipe',env:{CONTROL_USAGE_URL:'https://example.invalid',CONTROL_USAGE_MCP_SESSION_FILE:'/nonexistent/control-usage-test-session'}});
 try{
  await client.connect(transport);assert.equal((await client.listTools()).tools.length,15);
  const result=await call(client,'get_status');assert.equal(result.isError,true);assert.match(result.content[0].text,/Private MCP session/);
  const guide=await client.readResource({uri:'control-usage://mcp-guide'});assert.match(guide.contents[0].text,/## Configure the MCP client/);
 }finally{await client.close();}
});
