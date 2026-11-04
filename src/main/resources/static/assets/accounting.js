let accountingGeneration=0,accountingAbort=null;
const dollars=value=>value==null?'Unpriced':new Intl.NumberFormat('en-US',{style:'currency',currency:'USD',maximumFractionDigits:4}).format(Number(value));
async function refreshAccounting(){
  const generation=++accountingGeneration;accountingAbort?.abort();accountingAbort=new AbortController();$('accountingError').hidden=true;$('accountingUpdated').textContent='Refreshing…';
  try{const params=new URLSearchParams({days:'7'});if($('accountingProvider').value)params.set('provider',$('accountingProvider').value);
    const report=await api('/admin/accounting?'+params,{signal:accountingAbort.signal});if(generation!==accountingGeneration)return;
    const summary=report.summary;$('accountingRequests').textContent=formatCount(summary.requestCount);$('accountingCoverage').textContent=formatCount(summary.usageObserved)+' report usage · '+formatCount(summary.usageComplete)+' complete';
    $('accountingValue').textContent=dollars(summary.knownValueUsd);$('accountingPriced').textContent=formatCount(summary.pricedRequests)+' priced';$('accountingUnpriced').textContent=formatCount(summary.partiallyPricedRequests)+' partial · '+formatCount(summary.unpricedRequests)+' unpriced';
    $('accountingQuota').textContent=report.quota?.state==='available'?'Available':report.quota?.state==='stale'?'Stale':'Unavailable';
    $('accountingQuotaNote').textContent='Latest account observations, independent of this report window. Member share is not quota usage.';
    populateTable('accountingMembers',report.members||[],[r=>r.name,r=>formatCount(r.requests),r=>formatCount(r.reportedTokens),r=>r.observedTokenSharePercent==null?'Unknown':Number(r.observedTokenSharePercent).toFixed(1)+'%',r=>dollars(r.knownValueUsd)]);
    populateTable('accountingCategories',report.categories||[],[r=>r.category,r=>formatCount(r.requests),r=>formatCount(r.reportedTokens),r=>dollars(r.knownValueUsd)]);
    $('categoryBars').replaceChildren();const max=Math.max(1,...(report.categories||[]).map(r=>Number(r.requests)));
    for(const category of report.categories||[]){const row=document.createElement('div'),label=document.createElement('button'),track=document.createElement('div'),bar=document.createElement('span');row.className='category-bar';label.textContent=category.category+' · '+category.requests;label.onclick=()=>openRequestScope({category:category.category,provider:$('accountingProvider').value},report.from,report.to);track.className='category-track';bar.style.width=(100*Number(category.requests)/max)+'%';track.append(bar);row.append(label,track);$('categoryBars').append(row);}
    $('quotaObservations').textContent=report.quota?.snapshots?.length?JSON.stringify(report.quota.snapshots,null,2):'No provider quota observations available. Subscription usage percentages cannot be inferred from tokens alone.';
    $('accountingNotes').replaceChildren();for(const note of report.notes||[]){const item=document.createElement('li');item.textContent=String(note);$('accountingNotes').append(item);}
    $('accountingUpdated').textContent='Updated '+new Date(report.generatedAt).toLocaleTimeString();
  }catch(error){if(error.name==='AbortError'||generation!==accountingGeneration)return;$('accountingError').textContent=error.message+' Previous values may be stale.';$('accountingError').hidden=false;$('accountingUpdated').textContent='Refresh failed';}
}
async function loadRequestAccounting(id,generation){
  $('requestValue').textContent='Loading usage and category…';setControl('categoryOverride','');$('saveCategory').disabled=true;
  try{const data=await api('/admin/requests/'+encodeURIComponent(id)+'/accounting',{signal:detailAbort.signal});if(generation!==detailGeneration)return;
    $('requestValue').textContent='API-equivalent value: '+dollars(data.estimate?.knownSubtotalUsd)+' · '+(data.estimate?.status||'unavailable')+'\nNot a subscription charge.\n\n'+JSON.stringify(data,null,2);
    setControl('categoryOverride',data.classification?.category||'unknown');$('saveCategory').disabled=false;
  }catch(error){if(error.name!=='AbortError'&&generation===detailGeneration)$('requestValue').textContent='Usage/category information unavailable: '+error.message;}
}
$('saveCategory').onclick=async()=>{if(!selectedDetail||!$('categoryOverride').value)return;const id=selectedDetail.id,generation=detailGeneration;$('saveCategory').disabled=true;
  try{await api('/admin/requests/'+encodeURIComponent(id)+'/classification',{method:'PATCH',body:JSON.stringify({category:$('categoryOverride').value})});if(generation===detailGeneration)await loadRequestAccounting(id,generation);}
  catch(error){if(generation===detailGeneration){$('requestValue').textContent=error.message;$('saveCategory').disabled=false;}}};
const currencyAmount=(value,currency)=>new Intl.NumberFormat('en',{style:'currency',currency,maximumFractionDigits:4}).format(Number(value));
let invoicesGeneration=0,allocationGeneration=0;
async function refreshInvoices(){
  const generation=++invoicesGeneration;
  try{const invoices=await api('/admin/subscription-invoices');if(generation!==invoicesGeneration)return;$('invoiceRows').replaceChildren();
    for(const invoice of invoices){const row=document.createElement('tr');[invoice.provider+' / '+invoice.upstream_account,new Date(invoice.period_start).toLocaleDateString()+' – '+new Date(invoice.period_end).toLocaleDateString(),currencyAmount(invoice.amount,invoice.currency),invoice.provenance==='manual_invoice'?'Manually recorded invoice':'Estimated subscription expense'].forEach(value=>cell(row,value));
      const action=document.createElement('td'),button=document.createElement('button');button.textContent='View allocation';button.onclick=()=>loadInvoiceAllocation(invoice.id);action.append(button);row.append(action);$('invoiceRows').append(row);}
    if(!invoices.length){const row=document.createElement('tr'),td=document.createElement('td');td.colSpan=5;td.textContent='No subscription expenses recorded.';row.append(td);$('invoiceRows').append(row);}
  }catch(error){if(generation===invoicesGeneration)$('invoiceMessage').textContent=error.message;}
}
async function loadInvoiceAllocation(id){
  const generation=++allocationGeneration;$('invoiceAllocation').hidden=false;$('invoiceAllocationSummary').textContent='Loading allocation…';$('invoiceAllocationMembers').replaceChildren();
  try{const allocation=await api('/admin/subscription-invoices/'+encodeURIComponent(id)+'/allocation');if(generation!==allocationGeneration)return;
    $('invoiceAllocationSummary').textContent='Allocated '+currencyAmount(allocation.allocatedAmount,allocation.currency)+' · unallocated '+currencyAmount(allocation.unallocatedAmount,allocation.currency)+' · usage coverage '+allocation.usageCoverage+'. '+allocation.note;
    populateTable('invoiceAllocationMembers',allocation.members,[r=>r.name,r=>formatCount(r.requests),r=>formatCount(r.tokens),r=>currencyAmount(r.allocatedAmount,allocation.currency)]);
  }catch(error){if(generation===allocationGeneration)$('invoiceAllocationSummary').textContent=error.message;}
}
$('invoiceForm').onsubmit=async event=>{
  event.preventDefault();if($('createInvoice').disabled)return;
  if(['invoiceProvider','invoiceCurrency','invoiceBasis','invoiceProvenance'].some(id=>!$(id).value)){$('invoiceMessage').textContent='Choose the provider, currency, allocation basis, and amount source.';return;}
  const from=new Date($('invoiceFrom').value),to=new Date($('invoiceTo').value);
  if(!(from<to)){$('invoiceMessage').textContent='The period end must be after its start.';return;}
  const body={provider:$('invoiceProvider').value,upstreamAccount:$('invoiceAccount').value.trim(),periodStart:from.toISOString(),periodEnd:to.toISOString(),amount:Number($('invoiceAmount').value),currency:$('invoiceCurrency').value,gatewayFraction:Number((Number($('invoiceFraction').value)/100).toFixed(6)),allocationBasis:$('invoiceBasis').value,provenance:$('invoiceProvenance').value};
  $('createInvoice').disabled=true;$('invoiceMessage').textContent='Recording expense…';
  try{const result=await api('/admin/subscription-invoices',{method:'POST',body:JSON.stringify(body)});$('invoiceMessage').textContent='Expense recorded. Allocation remains separate from provider quota and API-equivalent value.';await refreshInvoices();await loadInvoiceAllocation(result.id);}
  catch(error){$('invoiceMessage').textContent=error.message+' Check the expense list before submitting again.';}
  finally{$('createInvoice').disabled=false;}
};
$('refreshAccounting').onclick=()=>{refreshAccounting();refreshInvoices();};$('accountingProvider').onchange=refreshAccounting;
addEventListener('meter:admin-ready',()=>{if(location.hash==='#view/accounting')refreshInvoices();});
addEventListener('hashchange',()=>{if(location.hash==='#view/accounting'&&!$('content').hidden)refreshInvoices();});
