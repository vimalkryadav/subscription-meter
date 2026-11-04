// Playwright page executor, only for the isolated tunnel_probe.py diagnostic origin.
async (page) => {
  if(await page.title()!=='Isolated tunnel diagnostic')throw new Error('Diagnostic origin required');
  return await page.evaluate(async()=>{
    const mode=new URL(location.href).searchParams.get('mode')||'sse';
    if(!['sse','ndjson'].includes(mode))throw new Error('Invalid diagnostic mode');
    const before=await(await fetch('/stats',{cache:'no-store'})).json();
    performance.clearResourceTimings();
    const rows=await Promise.all(Array.from({length:20},async(_,index)=>{
      const start=performance.now();let first=null,status=0,body='',bytes=0,firstEvent=null,lastEvent=null;
      try{
        const response=await fetch('/stream?mode='+mode,{method:'POST',credentials:'omit',signal:AbortSignal.timeout(45000),
          headers:{'Content-Type':'application/json','Authorization':'Bearer diagnostic-only-not-a-credential','anthropic-version':'2023-06-01','anthropic-beta':'claude-code-20250219'},body:'{"diagnostic":true}'});
        status=response.status;const reader=response.body.getReader(),decoder=new TextDecoder();let pending='';const events=[];
        while(true){const item=await reader.read();if(item.done)break;if(first===null)first=performance.now()-start;bytes+=item.value.byteLength;if(bytes>8192)throw new Error('Response bound');const text=decoder.decode(item.value,{stream:true});body+=text;pending+=text;let pos;
          while((pos=pending.indexOf('\n'))>=0){const line=pending.slice(0,pos).trim();pending=pending.slice(pos+1);if(line&&(mode!=='sse'||line.startsWith('data: '))){events.push(JSON.parse(mode==='sse'?line.slice(6):line));lastEvent=performance.now()-start;if(firstEvent===null)firstEvent=lastEvent;}}
        }
        body+=decoder.decode();
        const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(body));
        return {index,status,firstMs:Math.round(first),totalMs:Math.round(performance.now()-start),sequenceComplete:JSON.stringify(events.map(e=>e.seq))===JSON.stringify(Array.from({length:16},(_,i)=>i)),hash:Array.from(new Uint8Array(digest),v=>v.toString(16).padStart(2,'0')).join(''),deliverySpanMs:firstEvent===null?null:Math.round(lastEvent-firstEvent)};
      }catch(error){return {index,status,error:error.name,totalMs:Math.round(performance.now()-start)};}
    }));
    const protocols=[...new Set(performance.getEntriesByType('resource').filter(e=>e.name.includes('/stream?')).map(e=>e.nextHopProtocol))];
    const after=await(await fetch('/stats',{cache:'no-store'})).json();
    function p95(key){const v=rows.map(x=>x[key]).filter(v=>v!==null&&v!==undefined).sort((a,b)=>a-b);return v.length?v[Math.ceil(v.length*.95)-1]:null;}
    return {client:'browser',url:location.origin,mode,protocols,requests:20,complete:rows.filter(x=>x.sequenceComplete).length,firstP95Ms:p95('firstMs'),durationP95Ms:p95('totalMs'),deliverySpanP95Ms:p95('deliverySpanMs'),originBefore:before,originAfter:after,rows};
  });
}
