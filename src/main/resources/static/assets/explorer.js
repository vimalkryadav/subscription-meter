// Request investigation owns its filters, cursors, and network generations.
const requestFields={memberId:'memberFilter',model:'modelFilter',requestId:'idFilter',upstreamRequestId:'upstreamFilter',state:'stateFilter',status:'statusFilter',captureState:'captureFilter',minDurationMs:'minDurationFilter',maxDurationMs:'maxDurationFilter',usageSeen:'usageFilter',provider:'providerFilter',upstreamAccount:'accountFilter',clientFamily:'clientFilter',keyPrefix:'keyPrefixFilter',category:'categoryFilter'};
let searchGeneration=0,searchAbort=null,searchPage=null,searchCursor='',searchHistory=[],searchWindow=null;
let detailGeneration=0,detailAbort=null,eventAfter=0,eventHistory=[],eventNext=0,eventBusy=false;
function setControl(id,value){
  const input=$(id);input.value=value;
  const root=input.closest('[data-dropdown]');
  if(root){const options=[...root.querySelectorAll('[role=option]')],chosen=options.find(o=>o.dataset.value===value)||options[0];input.value=chosen.dataset.value;root.querySelector('[data-dropdown-value]').textContent=chosen.textContent;options.forEach(o=>o.setAttribute('aria-selected',String(o===chosen)));}
}
function restoreRequestFilters(){
  const params=new URLSearchParams(location.search);
  setControl('days',['1','7','30'].includes(params.get('rq_days'))?params.get('rq_days'):'7');
  for(const [key,id] of Object.entries(requestFields))setControl(id,params.get('rq_'+key)||'');
  for(const key of ['from','to']){$(key+'Filter').value='';const value=params.get('rq_'+key);if(value&&!Number.isNaN(Date.parse(value))){const date=new Date(value);$(key+'Filter').value=new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,-1);}}
}
function persistRequestFilters(id){
  const url=new URL(location.href);
  for(const key of [...url.searchParams.keys()])if(key.startsWith('rq_'))url.searchParams.delete(key);
  for(const [key,field] of Object.entries(requestFields))if($(field).value.trim())url.searchParams.set('rq_'+key,$(field).value.trim());
  for(const key of ['from','to'])if($(key+'Filter').value)url.searchParams.set('rq_'+key,new Date($(key+'Filter').value).toISOString());
  url.searchParams.set('rq_days',$('days').value);
  if(id)url.searchParams.set('rq_detail',id);
  history.pushState({},'',url);
}
function resetRequestSearch(){searchCursor='';searchHistory=[];searchWindow=null;}
function openRequestScope(fields,from,to){
  for(const id of Object.values(requestFields))setControl(id,'');
  for(const [key,value] of Object.entries(fields))if(requestFields[key])setControl(requestFields[key],String(value));
  for(const [key,value] of Object.entries({from,to})){const date=new Date(value);$(key+'Filter').value=Number.isNaN(date.getTime())?'':new Date(date.getTime()-date.getTimezoneOffset()*60000).toISOString().slice(0,-1);}
  resetRequestSearch();persistRequestFilters();location.hash='view/activity';refreshRequests();
}
async function refreshRequests(){
  const generation=++searchGeneration;searchAbort?.abort();searchAbort=new AbortController();
  $('previousRequests').disabled=true;$('nextRequests').disabled=true;
  $('searchError').hidden=true;$('searchUpdated').textContent=searchPage?'Refreshing · previous results remain visible':'Loading requests…';
  $('requests').closest('table').setAttribute('aria-busy','true');
  try{
    const params=new URLSearchParams({limit:'25'});
    for(const [key,id] of Object.entries(requestFields))if($(id).value.trim())params.set(key,$(id).value.trim());
    if(!searchWindow){const to=$('toFilter').value?new Date($('toFilter').value):new Date();const from=$('fromFilter').value?new Date($('fromFilter').value):new Date(to.getTime()-Number($('days').value)*86400000);searchWindow={from:from.toISOString(),to:to.toISOString()};}
    params.set('from',searchWindow.from);params.set('to',searchWindow.to);if(searchCursor)params.set('cursor',searchCursor);
    const page=await api('/admin/request-search?'+params,{signal:searchAbort.signal});if(generation!==searchGeneration)return;
    searchPage=page;searchWindow={from:page.from,to:page.to};$('requests').replaceChildren();
    for(const request of page.items){const row=document.createElement('tr');
      [new Date(request.started_at).toLocaleString()+' / '+request.name,request.model,(request.status_code??'—')+' · '+request.state,request.duration_ms==null?'Pending':request.duration_ms+' ms',request.capture_status].forEach(value=>cell(row,value));
      const td=document.createElement('td'),button=document.createElement('button');button.textContent='Inspect';button.type='button';button.setAttribute('aria-label','Inspect request '+request.id);button.onclick=()=>inspect(request.id);td.append(button);row.append(td);$('requests').append(row);}
    if(!page.items.length){const row=document.createElement('tr'),td=document.createElement('td');td.colSpan=6;td.className='empty';td.textContent='No recorded requests match these filters. Broaden the window or clear filters. Recently completed captures may still be arriving.';row.append(td);$('requests').append(row);}
    $('requestPage').textContent='Page '+(searchHistory.length+1)+' · '+page.items.length+' requests';
    $('previousRequests').disabled=searchHistory.length===0;$('nextRequests').disabled=!page.nextCursor;
    $('searchUpdated').textContent='Updated '+new Date(page.generatedAt).toLocaleTimeString();
    $('filterSummary').textContent=new Date(page.from).toLocaleString()+' – '+new Date(page.to).toLocaleString();
    const detail=new URLSearchParams(location.search).get('rq_detail');if(detail&&selectedDetail?.id!==detail)await inspect(detail,false);
  }catch(error){if(error.name==='AbortError'||generation!==searchGeneration)return;$('searchError').textContent=error.message+' Previous results may be stale.';$('searchError').hidden=false;$('searchUpdated').textContent='Refresh failed';}
  finally{if(generation===searchGeneration)$('requests').closest('table').setAttribute('aria-busy','false');}
}
const captureMessages={available:'Retained capture available. Credentials are filtered.',incomplete:'The response ended incompletely or was interrupted. Retained input, output, and events may be partial; this is separate from capture storage failure.',pending:'Capture is pending. Request completion and audit persistence are separate.',truncated:'Capture was truncated by configured limits. Export and events may be incomplete.',error:'Capture encountered an error. Retained data may be incomplete.',expired:'The capture retention window has elapsed; only remaining metadata is available.',unavailable:'Capture is unavailable. It may not have arrived, may have failed, or was never captured. This does not mean the request had no input or output.'};
async function inspect(id,persist=true){
  const generation=++detailGeneration;detailAbort?.abort();detailAbort=new AbortController();selectedDetail=null;eventHistory=[];eventAfter=0;eventNext=0;eventBusy=false;
  $('requestDetail').hidden=false;$('requestDetail').setAttribute('aria-busy','true');$('detailStatus').textContent='Loading request…';$('detailError').hidden=true;$('exportRequest').disabled=true;
  for(const name of ['detailMetadata','requestPayload','responsePayload','detailHeaders','requestValue','eventCount'])$(name).textContent='';$('events').replaceChildren();
  if(persist){persistRequestFilters(id);$('requestDetail').scrollIntoView?.({block:'start'});}
  try{
    const detail=await api('/admin/request-search/'+encodeURIComponent(id),{signal:detailAbort.signal});if(generation!==detailGeneration)return;
    selectedDetail=detail;const {request_body,response_body,request_headers,response_headers,...metadata}=detail;
    $('detailMetadata').textContent=JSON.stringify(metadata,null,2);$('requestPayload').textContent=pretty(request_body);
    $('responsePayload').textContent=response_body===''?'Streaming response: inspect the Events section.':pretty(response_body);
    $('detailHeaders').textContent='REQUEST\n'+pretty(request_headers)+'\n\nRESPONSE\n'+pretty(response_headers);
    $('detailStatus').textContent=captureMessages[detail.capture_status]||'Capture status unavailable';$('exportRequest').disabled=false;
    loadEvents();loadRequestAccounting(id,generation);
  }catch(error){if(error.name==='AbortError'||generation!==detailGeneration)return;$('detailError').textContent=error.message;$('detailError').hidden=false;$('detailStatus').textContent='Could not load this request.';}
  finally{if(generation===detailGeneration)$('requestDetail').setAttribute('aria-busy','false');}
}
async function loadEvents(){
  if(!selectedDetail||eventBusy)return;eventBusy=true;const generation=detailGeneration,id=selectedDetail.id;
  $('moreEvents').disabled=true;$('previousEvents').disabled=true;
  try{const rows=await api('/admin/requests/'+encodeURIComponent(id)+'/events?after='+eventAfter+'&limit=100',{signal:detailAbort.signal});if(generation!==detailGeneration)return;
    $('events').replaceChildren();for(const event of rows){const item=document.createElement('details'),heading=document.createElement('summary'),body=document.createElement('pre');heading.textContent='#'+event.sequence+' · '+event.event_type+' · '+event.elapsed_ms+' ms'+(event.truncated?' · incomplete frame':'');body.textContent=pretty(event.event_data);item.append(heading,body);$('events').append(item);}
    eventNext=rows.length===100?rows[rows.length-1].sequence:0;$('moreEvents').disabled=!eventNext;$('previousEvents').disabled=!eventHistory.length;
    $('eventCount').textContent=rows.length?'Sequences '+rows[0].sequence+'–'+rows[rows.length-1].sequence+' · '+(selectedDetail.event_count??'unknown')+' reported stored events':'No events available on this page. Non-streaming responses use the Output section.';
  }catch(error){if(error.name!=='AbortError'&&generation===detailGeneration){$('detailError').textContent=error.message;$('detailError').hidden=false;}}
  finally{if(generation===detailGeneration)eventBusy=false;}
}
$('requestFilters').onsubmit=event=>{event.preventDefault();resetRequestSearch();persistRequestFilters();refreshRequests();};
$('clearRequestFilters').onclick=()=>{for(const id of Object.values(requestFields))setControl(id,'');$('fromFilter').value='';$('toFilter').value='';resetRequestSearch();persistRequestFilters();refreshRequests();};
$('previousRequests').onclick=()=>{searchCursor=searchHistory.pop()||'';refreshRequests();};
$('nextRequests').onclick=()=>{if(!searchPage?.nextCursor)return;searchHistory.push(searchCursor);searchCursor=searchPage.nextCursor;refreshRequests();};
$('previousEvents').onclick=()=>{if(eventBusy)return;eventAfter=eventHistory.pop()||0;loadEvents();};
$('moreEvents').onclick=()=>{if(eventBusy||!eventNext)return;eventHistory.push(eventAfter);eventAfter=eventNext;loadEvents();};
$('closeRequestDetail').onclick=()=>{++detailGeneration;detailAbort?.abort();selectedDetail=null;$('requestDetail').hidden=true;persistRequestFilters();};
$('exportRequest').onclick=()=>{if(!selectedDetail)return;const link=document.createElement('a');link.href='/admin/requests/'+encodeURIComponent(selectedDetail.id)+'/export';link.download='request-'+selectedDetail.id+'.json';link.click();};
document.querySelectorAll('[data-detail-tab]').forEach(button=>button.onclick=()=>{document.querySelectorAll('[data-detail-tab]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));document.querySelectorAll('[data-detail-panel]').forEach(panel=>panel.hidden=panel.dataset.detailPanel!==button.dataset.detailTab);});
addEventListener('popstate',()=>{restoreRequestFilters();resetRequestSearch();if(!$('content').hidden)refreshRequests();if(!new URLSearchParams(location.search).has('rq_detail')){++detailGeneration;detailAbort?.abort();selectedDetail=null;$('requestDetail').hidden=true;}});
restoreRequestFilters();
