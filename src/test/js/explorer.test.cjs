// DOM-free interaction tests: no browser, navigation, upstream call, or rendered preview.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
class Element {
  constructor(){this.value='';this.children=[];this.hidden=false;this.attributes={};this.textContent='';}
  closest(selector){return selector==='table'?this:null;}
  setAttribute(key,value){this.attributes[key]=value;}
  replaceChildren(...children){this.children=children;}
  append(...children){this.children.push(...children);}
}
function setup(){
  const elements=new Map(),requests=[],listeners={};
  const $=id=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
  const context=vm.createContext({$,URL,URLSearchParams,Date,AbortController,selectedDetail:null,
    location:{search:'',href:'https://example.invalid/manage#view/activity'},history:{pushState(){}},
    document:{createElement:()=>new Element(),querySelectorAll:()=>[]},addEventListener:(type,fn)=>listeners[type]=fn,
    api:(url,options)=>new Promise((resolve,reject)=>requests.push({url,options,resolve,reject})),
    cell:(row,value)=>{const item=new Element();item.textContent=value;row.append(item);},
    pretty:value=>String(value),loadRequestAccounting:()=>{},console});
  vm.runInContext(fs.readFileSync('src/main/resources/static/assets/explorer.js','utf8'),context);
  return {context,$,requests,listeners};
}
function page(name,nextCursor=null){return {items:[{id:name,name,model:'model',started_at:'2026-01-01T00:00:00Z',state:'complete',status_code:200,duration_ms:1,capture_status:'available'}],nextCursor,from:'2026-01-01T00:00:00Z',to:'2026-01-02T00:00:00Z',generatedAt:'2026-01-02T00:00:00Z'};}
test('late search responses cannot replace a newer filter result',async()=>{
  const {context,$,requests}=setup();
  const first=context.refreshRequests();$('modelFilter').value='new-model';const second=context.refreshRequests();
  assert.equal(requests[0].options.signal.aborted,true);
  assert.equal(new URL(requests[1].url,'https://example.invalid').searchParams.get('model'),'new-model');
  requests[1].resolve(page('new'));await second;requests[0].resolve(page('old'));await first;
  assert.match($('requests').children[0].children[0].textContent,/new$/);
});
test('next/previous use server cursors and never request offset pages',async()=>{
  const {context,$,requests}=setup();const first=context.refreshRequests();requests[0].resolve(page('first','opaque-cursor'));await first;
  $('nextRequests').onclick();assert.equal(new URL(requests[1].url,'https://example.invalid').searchParams.get('cursor'),'opaque-cursor');
  assert.equal(new URL(requests[1].url,'https://example.invalid').searchParams.has('offset'),false);
  requests[1].resolve(page('second'));await new Promise(resolve=>setImmediate(resolve));
  $('previousRequests').onclick();assert.equal(new URL(requests[2].url,'https://example.invalid').searchParams.has('cursor'),false);
  requests[2].resolve(page('first','opaque-cursor'));await new Promise(resolve=>setImmediate(resolve));
});
test('late detail responses cannot reveal the previously selected payload',async()=>{
  const {context,$,requests}=setup();const first=context.inspect('first',false),second=context.inspect('second',false);
  requests[1].resolve({id:'second',capture_status:'available',request_body:'second input',response_body:'second output',event_count:0});await second;
  requests[0].resolve({id:'first',capture_status:'available',request_body:'old input'});await first;
  assert.equal($('requestPayload').textContent,'second input');
  requests[2].resolve([]);await new Promise(resolve=>setImmediate(resolve));
});
