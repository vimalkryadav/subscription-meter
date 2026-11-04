import { useState } from 'react';
import { Chart } from './Charts';
import { count, date, duration, Heading, Notice, Panel, Segments, Stat, Table, text, useResource } from './ui';
import type { Row } from './ui';
import { openRequestScope } from './search';
interface MetricsReport { from: string; to: string; stepSeconds: number; summary: Record<string, number | null>; traffic: Row[]; timings: Row[]; topMembers: Row[]; topModels: Row[]; errors: Row[] }
export default function Metrics() {
  const [hours, setHours] = useState(24), [percentile, setPercentile] = useState('p95');
  const resource = useResource<MetricsReport>('/admin/metrics?hours=' + hours), report = resource.data;
  const summary = report?.summary;
  const from = report ? Math.floor(Date.parse(report.from) / (report.stepSeconds * 1000)) * report.stepSeconds * 1000 : 0;
  const to = report ? Date.parse(report.to) : 0;
  const traffic: Row[] = [];
  if (report && report.summary.requests) {
    const buckets = new Map(report.traffic.map(point => [Number(point.bucket) * 1000, point]));
    for (let time = from; time < to; time += report.stepSeconds * 1000) traffic.push({ time, ...(buckets.get(time) || { requests: 0, tokens: 0, failed: 0 }) });
  }
  const models = [...new Set(report?.timings.map(row => String(row.model)) || [])];
  return <><Heading title="Metrics" subtitle="Traffic, response timings, and the members and models behind each request."><button onClick={resource.refresh} disabled={resource.loading}>Refresh metrics</button></Heading>
    <div className="cu-toolbar"><Segments label="Metrics time window" values={[{ value: 1, label: '1 h' }, { value: 6, label: '6 h' }, { value: 24, label: '24 h' }, { value: 168, label: '7 d' }]} value={hours} onChange={setHours} /><Segments label="Timing percentile" values={['p50', 'p95', 'p99'].map(value => ({ value, label: value }))} value={percentile} onChange={setPercentile} /><span className="cu-muted" aria-live="polite">{resource.loading ? 'Loading metrics…' : report ? 'Updated ' + date(report.to) + ' · ' + report.stepSeconds / 60 + ' min intervals' : ''}</span></div>
    <Notice error>{resource.error}</Notice>
    {report && summary && <div aria-busy={resource.loading}>
      {!summary.requests && <Notice>No recorded requests in this window. Usage appears after requests are sent through the gateway. {hours !== 168 && <button onClick={() => setHours(168)}>View last 7 days</button>}</Notice>}
      <div className="cu-grid cu-stats"><Stat title="Requests" value={count(summary.requests)} note={count(summary.completed) + ' complete · ' + count(summary.pending) + ' pending'} /><Stat title="Observed tokens" value={count(summary.tokens)} note="Input, output, cache write & read" /><Stat title="Failed / incomplete" value={count(summary.failed)} note={summary.requests ? (100 * Number(summary.failed) / summary.requests).toFixed(1) + '% of recorded requests' : 'No requests in this window'} /><Stat title={'Request duration · ' + percentile} value={duration(summary['duration_' + percentile])} note="All recorded request durations" /></div>
      <h2>Traffic</h2><div className="cu-grid cu-thirds">{[['requests', 'Requests per interval'], ['tokens', 'Tokens per interval'], ['failed', 'Failed / incomplete per interval']].map(([field, title]) => <Chart key={field} title={title} series={[{ name: title, points: traffic.map(point => ({ time: Number(point.time), value: Number(point[field]) })) }]} from={from} to={to} stepSeconds={report.stepSeconds} format={count} />)}</div>
      <h2>Who used the gateway</h2><p className="cu-muted">Top 10 in this window. Select a member or model to investigate requests.</p><div className="cu-grid cu-halves">{(['topMembers', 'topModels'] as const).map(key => <Panel key={key} title={key === 'topMembers' ? 'Top members / keys' : 'Top models'}><Table headings={[key === 'topMembers' ? 'Member' : 'Model', 'Requests', 'Tokens']} empty={!report[key].length}>{report[key].map((row, index) => <tr key={index}><td><button className="cu-link" onClick={() => openRequestScope(key === 'topMembers' ? { memberId: text(row.id) } : { model: text(row.label) }, report.from, report.to)}>{text(row.label)}</button></td><td>{count(row.requests)}</td><td>{count(row.tokens)}</td></tr>)}</Table></Panel>)}</div>
      <h2>Request timings by model</h2><div className="cu-grid cu-halves">{[['duration_', 'Request duration'], ['first_byte_', 'First response byte']].map(([prefix, title]) => <Chart key={prefix} title={title + ' · ' + percentile} from={from} to={to} stepSeconds={report.stepSeconds} format={duration} series={models.map(model => ({ name: model, points: report.timings.filter(row => row.model === model).map(row => ({ time: Number(row.bucket) * 1000, value: row[prefix + percentile] == null ? null : Number(row[prefix + percentile]) })) }))} />)}</div>
      <p className="cu-muted">First byte measures response bytes received from upstream, not the first generated token. Gaps mean no timing samples.</p>
      <div className="cu-grid cu-halves"><Panel title="Errors and incomplete requests"><Table headings={['HTTP status', 'Outcome', 'Requests']} empty={!report.errors.length}>{report.errors.map((row, index) => <tr key={index}><td>{text(row.code)}</td><td>{text(row.state)}</td><td>{count(row.requests)}</td></tr>)}</Table></Panel><Panel title="Capture coverage"><dl className="cu-coverage"><div><dt>Usage reported by upstream</dt><dd>{count(summary.usage_observed)} / {count(summary.requests)} requests</dd></div><div><dt>Truncated captures</dt><dd>{count(summary.truncated)}</dd></div><div><dt>Metadata only</dt><dd>{count(summary.metadata_only)}</dd></div></dl><p className="cu-muted">Missing usage is not zero consumption. Token totals can be partial for interrupted responses.</p></Panel></div>
    </div>}</>;
}
