import { useEffect, useId, useRef, useState } from 'react';
import { date, Table } from './ui';
export interface Point { time: number; value: number | null }
export interface Series { name: string; points: Point[] }
const colors = ['#79c8fa', '#aa99ff', '#56d5ab', '#efb365', '#ef8fa7', '#a8ca65'];
export function Chart({ title, series, from, to, stepSeconds, format = String }: { title: string; series: Series[]; from: number; to: number; stepSeconds: number; format?: (value: number) => string }) {
  const id = useId(), [focus, setFocus] = useState<number | null>(null), [showTable, setShowTable] = useState(false);
  const svg = useRef<SVGSVGElement>(null), [width, setWidth] = useState(480);
  useEffect(() => {
    if (!svg.current) return;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.max(220, Math.round(entry.contentRect.width))));
    observer.observe(svg.current);
    return () => observer.disconnect();
  }, [series.some(item => item.points.some(point => point.value !== null))]);
  const all = series.flatMap(item => item.points.filter(point => point.value !== null && Number.isFinite(point.value)));
  const times = [...new Set(series.flatMap(item => item.points.map(point => point.time)))].sort((a, b) => a - b);
  const max = Math.max(1, ...all.map(point => point.value || 0));
  const axis = (value: number) => {
    const label = format(value);
    return /^[\d,.\s]+$/.test(label) ? new Intl.NumberFormat(undefined, { notation: value >= 10000 ? 'compact' : 'standard', maximumFractionDigits: 1 }).format(value) : label;
  };
  const height = 210, left = Math.max(42, ...[0, max / 2, max].map(value => axis(value).length * 7 + 14)), right = 12, top = 16, bottom = 32;
  const startLabel = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(from);
  const endLabel = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' }).format(to);
  const x = (time: number) => left + (time - from) / Math.max(1, to - from) * (width - left - right);
  const y = (value: number) => height - bottom - value / max * (height - top - bottom);
  const activeTime = focus === null ? null : times[Math.min(focus, times.length - 1)];
  function path(points: Point[]) {
    let lastTime: number | null = null;
    return points.map(point => {
      if (point.value === null || !Number.isFinite(point.value)) { lastTime = null; return ''; }
      const command = lastTime === null || point.time - lastTime > stepSeconds * 1500 ? 'M' : 'L';
      lastTime = point.time;
      return command + x(point.time).toFixed(2) + ',' + y(point.value).toFixed(2);
    }).join(' ');
  }
  return <article className="cu-panel cu-chart"><h2>{title}</h2>{all.length ? <>
    <svg ref={svg} viewBox={`0 0 ${width} ${height}`} role="img" tabIndex={0} aria-labelledby={id + '-title ' + id + '-desc'} onFocus={() => setFocus(0)} onKeyDown={event => {
      if (['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) { event.preventDefault(); setFocus(current => event.key === 'Home' ? 0 : event.key === 'End' ? times.length - 1 : Math.max(0, Math.min(times.length - 1, (current || 0) + (event.key === 'ArrowRight' ? 1 : -1)))); }
    }} onPointerMove={event => { const rect = event.currentTarget.getBoundingClientRect(); const time = from + ((event.clientX - rect.left) / rect.width * width - left) / (width - left - right) * (to - from); let nearest = 0; for (let i = 1; i < times.length; i++) if (Math.abs(times[i] - time) < Math.abs(times[nearest] - time)) nearest = i; setFocus(nearest); }}>
      <title id={id + '-title'}>{title}</title><desc id={id + '-desc'}>Use left and right arrow keys to inspect observations. A data table is available below.</desc>
      {[0, .5, 1].map(fraction => <g key={fraction}><line x1={left} x2={width - right} y1={y(max * fraction)} y2={y(max * fraction)} stroke="#293644" /><text x={left - 8} y={y(max * fraction) + 4} textAnchor="end" fill="#a9b8c7" fontSize="12">{axis(max * fraction)}</text></g>)}
      {series.map((item, index) => <g key={item.name}><path d={path(item.points)} fill="none" stroke={colors[index % colors.length]} strokeWidth="1.75" />{item.points.filter(point => point.value !== null).length === 1 && item.points.filter(point => point.value !== null).map(point => <circle key={point.time} cx={x(point.time)} cy={y(point.value!)} r="3" fill={colors[index % colors.length]} />)}</g>)}
      {activeTime != null && <line x1={x(activeTime)} x2={x(activeTime)} y1={top} y2={height - bottom} stroke="#bccad8" strokeDasharray="4 4" />}
      <text x={left} y={height - 8} fill="#a9b8c7" fontSize="11">{startLabel}</text><text x={width - right} y={height - 8} textAnchor="end" fill="#a9b8c7" fontSize="11">{endLabel}</text>
    </svg>
    <div className="cu-chart-legend">{series.map((item, index) => <span key={item.name}><i style={{ background: colors[index % colors.length] }} />{item.name}</span>)}</div>
    <div className="cu-chart-readout" aria-live="polite">{activeTime == null ? 'Hover or focus the chart to inspect an interval.' : <>{date(activeTime)}{series.map(item => { const point = item.points.find(value => value.time === activeTime); return <span key={item.name}>{item.name}: {point?.value == null ? 'No sample' : format(point.value)}</span>; })}</>}</div>
    <details onToggle={event => setShowTable(event.currentTarget.open)}><summary>View chart data</summary>{showTable && <Table headings={['Time', ...series.map(item => item.name)]}>{times.map(time => <tr key={time}><td>{date(time)}</td>{series.map(item => { const point = item.points.find(value => value.time === time); return <td key={item.name}>{point?.value == null ? 'No sample' : format(point.value)}</td>; })}</tr>)}</Table>}</details>
  </> : <p className="cu-empty">No observations in this window.</p>}</article>;
}
