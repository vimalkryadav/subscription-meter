let metricHours=24, metricPercentile='p95', metricReport=null, metricsVersion=0;
const formatCount=value=>new Intl.NumberFormat().format(Number(value||0));

function showPage() {
  const allowed=['metrics','activity','members','approvals','connection'];
  const target=location.hash.slice(1).replace(/^view\//,'');
  const page=allowed.includes(target)?target:'metrics';
  document.querySelectorAll('[data-page]').forEach(section=>section.hidden=section.dataset.page!==page);
  document.querySelectorAll('#navigation a').forEach(link=>{if(link.hash==='#view/'+page)link.setAttribute('aria-current','page');else link.removeAttribute('aria-current');});
  document.title=(page==='members'?'Members & keys':page==='approvals'?'Access requests':page[0].toUpperCase()+page.slice(1))+' · control-usage';
}
addEventListener('hashchange',()=>{showPage();window.scrollTo(0,0);});showPage();

function populateTable(id,rows,columns) {
  const body=$(id);body.replaceChildren();
  if(!rows.length){const row=body.insertRow(),cell=row.insertCell();cell.colSpan=columns.length;cell.className='empty';cell.textContent='No requests in this window';return;}
  for(const item of rows){const row=body.insertRow();for(const column of columns){const cell=row.insertCell();cell.textContent=column(item);}}
}
function renderMetrics() {
  if(!metricReport)return;
  const r=metricReport,s=r.summary;
  $('metricsEmpty').hidden=Number(s.requests)!==0;
  $('metricsEmptyText').textContent='No gateway requests in the last '+(metricHours===168?'7 days':metricHours+' hours')+'. Usage appears after requests are sent through the gateway.';
  $('showWeek').hidden=metricHours===168;
  $('totalRequests').textContent=formatCount(s.requests);
  $('requestSummary').textContent=formatCount(s.completed)+' complete · '+formatCount(s.pending)+' pending';
  $('totalTokens').textContent=formatCount(s.tokens);
  $('failedRequests').textContent=formatCount(s.failed);
  $('failureSummary').textContent=s.requests?(100*s.failed/s.requests).toFixed(1)+'% of recorded requests':'No requests in this window';
  $('durationLabel').textContent='Request duration · '+metricPercentile;
  $('totalDuration').textContent=MeterCharts.duration(s['duration_'+metricPercentile]);
  $('usageCoverage').textContent=formatCount(s.usage_observed)+' / '+formatCount(s.requests)+' requests';
  $('truncatedCaptures').textContent=formatCount(s.truncated);
  $('metadataOnly').textContent=formatCount(s.metadata_only);
  const start=Date.parse(r.from),to=Date.parse(r.to),step=r.stepSeconds;
  const from=Math.floor(start/(step*1000))*step*1000;
  const buckets=new Map(r.traffic.map(p=>[Number(p.bucket)*1000,p]));
  const traffic=[];for(let t=from;t<to;t+=step*1000)traffic.push({time:t,...(buckets.get(t)||{requests:0,tokens:0,failed:0})});
  const settings={from,to,step};
  for(const [id,field,label] of [['requestsChart','requests','Requests'],['tokensChart','tokens','Tokens'],['failuresChart','failed','Failed / incomplete']]){
    const points=s.requests?traffic.map(p=>({time:p.time,value:Number(p[field])})):[];
    MeterCharts.chart(id,[{name:label,points}],{...settings,label,zero:true});
  }
  const models=[...new Set(r.timings.map(p=>p.model))];
  for(const [id,prefix,title] of [['latencyChart','duration_','Request duration'],['firstByteChart','first_byte_','First response byte']]){
    const series=models.map(model=>({name:model,points:r.timings.filter(p=>p.model===model).map(p=>({time:Number(p.bucket)*1000,value:p[prefix+metricPercentile]}))}));
    MeterCharts.chart(id,series,{...settings,label:title+' · '+metricPercentile,format:MeterCharts.duration});
  }
  $('latencyTitle').textContent='Request duration · '+metricPercentile;
  $('firstByteTitle').textContent='First response byte · '+metricPercentile;
  populateTable('topMembers',r.topMembers,[p=>p.label,p=>formatCount(p.requests),p=>formatCount(p.tokens)]);
  populateTable('topModels',r.topModels,[p=>p.label,p=>formatCount(p.requests),p=>formatCount(p.tokens)]);
  populateTable('metricErrors',r.errors,[p=>p.code,p=>p.state,p=>formatCount(p.requests)]);
}
async function refreshMetrics() {
  const version=++metricsVersion;
  $('metricsData').setAttribute('aria-busy','true');$('metricsData').hidden=true;
  $('metricsEmpty').hidden=true;
  $('metricsError').hidden=true;$('metricsUpdated').textContent='Loading metrics…';
  try{
    const report=await api('/admin/metrics?hours='+metricHours);
    if(version!==metricsVersion)return;
    metricReport=report;renderMetrics();$('metricsData').hidden=false;
    $('metricsUpdated').textContent='Updated '+new Date(report.to).toLocaleTimeString()+' · '+report.stepSeconds/60+' min intervals';
  }catch(error){if(version!==metricsVersion)return;metricReport=null;$('metricsError').textContent=error.message;$('metricsError').hidden=false;$('metricsUpdated').textContent='Could not load metrics';}
  finally{if(version===metricsVersion)$('metricsData').setAttribute('aria-busy','false');}
}
$('refreshMetrics').onclick=refreshMetrics;
$('showWeek').onclick=()=>$('metricWindows').querySelector('[data-hours="168"]').click();
for(const button of $('metricWindows').querySelectorAll('button'))button.onclick=()=>{
  metricHours=Number(button.dataset.hours);
  for(const item of $('metricWindows').querySelectorAll('button'))item.setAttribute('aria-pressed',String(item===button));
  refreshMetrics();
};
for(const button of $('metricPercentiles').querySelectorAll('button'))button.onclick=()=>{
  metricPercentile=button.dataset.percentile;
  for(const item of $('metricPercentiles').querySelectorAll('button'))item.setAttribute('aria-pressed',String(item===button));
  renderMetrics();
};
// The existing admin session gate owns authentication and dispatches this only after an admin login.
addEventListener('meter:admin-ready',refreshMetrics);
connectAdmin().then(()=>{if(!$('content').hidden)dispatchEvent(new Event('meter:admin-ready'));});
