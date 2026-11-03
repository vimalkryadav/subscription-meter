// Small, dependency-free SVG renderer. The data table exposes every plotted value to keyboard/screen-reader users.
const MeterCharts = (() => {
  const palette=['#397de6','#d08720','#1f9b83','#9169d0','#d16b7a','#5a9cba','#b18549','#7d8c9c'];
  const number = value => new Intl.NumberFormat(undefined,{maximumFractionDigits:1,notation:'compact'}).format(value);
  const duration = value => value==null?'—':value<1000?Math.round(value)+' ms':(value/1000).toFixed(2)+' s';
  function svgNode(tag,attrs={},text) {
    const node=document.createElementNS('http://www.w3.org/2000/svg',tag);
    for(const [key,value] of Object.entries(attrs))node.setAttribute(key,String(value));
    if(text!==undefined)node.textContent=text;
    return node;
  }
  function chart(id,series,{from,to,format=number,label='Value',zero=false,step}) {
    const host=document.getElementById(id);host.replaceChildren();
    const valid=series.flatMap(s=>s.points).filter(p=>p.value!=null&&Number.isFinite(Number(p.value)));
    if(!valid.length){const empty=document.createElement('div');empty.className='chart-empty';empty.textContent='No samples in this window';host.append(empty);return;}
    const width=450,height=200,left=48,right=12,top=12,bottom=30;
    const maximum=Math.max(1,...valid.map(p=>Number(p.value)))*1.1;
    const x=t=>left+(t-from)/(to-from)*(width-left-right),y=v=>height-bottom-v/maximum*(height-top-bottom);
    const svg=svgNode('svg',{viewBox:`0 0 ${width} ${height}`,role:'img','aria-label':label+' over time. Expand chart data below for values.'});
    for(let i=0;i<=3;i++){const value=maximum*i/3,yy=y(value);svg.append(svgNode('line',{x1:left,x2:width-right,y1:yy,y2:yy,class:'gridline'}),svgNode('text',{x:left-7,y:yy+3,'text-anchor':'end'},format(value)));}
    for(let i=0;i<=2;i++){const time=from+(to-from)*i/2;svg.append(svgNode('text',{x:x(time),y:height-7,'text-anchor':i===0?'start':i===2?'end':'middle'},new Date(time).toLocaleString(undefined,{...(to-from>86400000?{month:'short',day:'numeric'}:{}),hour:'2-digit',minute:'2-digit'})));}
    const legend=document.createElement('div');legend.className='legend';
    const rows=[];
    series.forEach((s,index)=>{
      const color=palette[index%palette.length];let path='',previous=null;
      for(const p of s.points){
        if(p.value==null){previous=null;continue;}
        const gap=previous==null||(!zero&&p.time-previous>step*1500);
        path+=(gap?' M ':' L ')+x(p.time)+' '+y(p.value);previous=p.time;
        const dot=svgNode('circle',{cx:x(p.time),cy:y(p.value),r:2.5,fill:color,class:'chart-point'});
        dot.append(svgNode('title',{},new Date(p.time).toLocaleString()+' · '+s.name+': '+format(p.value)));svg.append(dot);
        rows.push([p.time,s.name,p.value]);
      }
      svg.append(svgNode('path',{d:path,stroke:color,class:'series'}));
      const item=document.createElement('span'),swatch=document.createElement('i');swatch.style.background=color;item.append(swatch,document.createTextNode(s.name));legend.append(item);
    });
    host.append(svg,legend);
    const details=document.createElement('details'),summary=document.createElement('summary'),scroll=document.createElement('div'),table=document.createElement('table');
    details.className='chart-data';summary.textContent='Chart data';
    const header=table.createTHead().insertRow();for(const text of ['Interval start','Series',label]){const th=document.createElement('th');th.textContent=text;header.append(th);}
    const body=table.createTBody();for(const [time,name,value] of rows.sort((a,b)=>a[0]-b[0])){const row=body.insertRow();for(const text of [new Date(time).toLocaleString(),name,format(value)])row.insertCell().textContent=text;}
    scroll.append(table);details.append(summary,scroll);host.append(details);
  }
  return {chart,number,duration};
})();
