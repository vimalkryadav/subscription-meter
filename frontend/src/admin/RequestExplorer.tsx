import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { categories, count, date, duration, Heading, money, Notice, options, Panel, pretty, providers, Select, Segments, Table, text, useMutation, useResource } from './ui';
import type { Row } from './ui';
import { captureMessages, emptyFilters, localDateInput, persistFilters, presetWindow, readFilters, searchParams, searchWindow } from './search';
import type { Filters } from './search';

interface Page { items: Row[]; nextCursor: string | null; from: string; to: string; generatedAt: string }
export default function RequestExplorer() {
  const [draft, setDraft] = useState(() => readFilters(location.search));
  const [applied, setApplied] = useState(draft);
  const [window, setWindow] = useState(() => { try { return searchWindow(draft); } catch { return searchWindow(emptyFilters()); } });
  const [cursors, setCursors] = useState(['']);
  const [detail, setDetail] = useState(() => new URLSearchParams(location.search).get('rq_detail') || '');
  const [validation, setValidation] = useState('');
  const [advanced, setAdvanced] = useState(false);
  const advancedCount = Object.entries(draft).filter(([key, value]) => !['days', 'requestId', 'state'].includes(key) && value.trim()).length;
  useEffect(() => {
    const restore = () => {
      const filters = readFilters(location.search); setDraft(filters); setApplied(filters);
      try { setWindow(searchWindow(filters)); setValidation(''); } catch (failure) { setValidation((failure as Error).message); }
      setCursors(['']); setDetail(new URLSearchParams(location.search).get('rq_detail') || '');
    };
    addEventListener('popstate', restore); return () => removeEventListener('popstate', restore);
  }, []);
  const result = useResource<Page>('/admin/request-search?' + searchParams(applied, window, cursors.at(-1)));
  const analytics = useResource<{ rows: Row[] }>('/admin/analytics?days=' + applied.days);
  const access = useResource<Row[]>('/admin/access?days=' + applied.days);
  function field(name: keyof Filters, value: string) { setDraft(current => ({ ...current, [name]: value })); }
  function submit(event?: FormEvent) {
    event?.preventDefault();
    try { searchWindow(draft); setValidation(''); persistFilters(draft); } catch (failure) { setValidation((failure as Error).message); }
  }
  function refresh() {
    // Rolling presets advance on refresh, while explicit custom bounds remain pinned.
    try { setWindow(searchWindow(applied)); setCursors(['']); setValidation(''); } catch (failure) { setValidation((failure as Error).message); }
    result.refresh(); analytics.refresh(); access.refresh();
  }
  function inspect(id: string) { setDetail(id); const url = new URL(location.href); url.searchParams.set('rq_detail', id); history.pushState({}, '', url); }
  function closeDetail() { setDetail(''); const url = new URL(location.href); url.searchParams.delete('rq_detail'); history.pushState({}, '', url); }
  const inputFields: { key: keyof Filters; label: string; type?: string; max?: number; min?: number; length?: number }[] = [
    { key: 'upstreamAccount', label: 'Upstream account', length: 80 }, { key: 'clientFamily', label: 'Client family', length: 100 },
    { key: 'keyPrefix', label: 'Member key prefix', length: 16 }, { key: 'memberId', label: 'Member ID', length: 36 },
    { key: 'model', label: 'Model', length: 200 }, { key: 'requestId', label: 'Request ID', length: 36 },
    { key: 'upstreamRequestId', label: 'Upstream request ID', length: 200 }, { key: 'status', label: 'HTTP status', type: 'number', min: 100, max: 599 },
    { key: 'minDurationMs', label: 'Minimum duration (ms)', type: 'number', min: 0 }, { key: 'maxDurationMs', label: 'Maximum duration (ms)', type: 'number', min: 0 },
  ];
  return <>
    <Heading title="Request explorer" subtitle="Inspect captured requests, responses, and ordered streaming events."><button onClick={refresh} disabled={result.loading}>Refresh activity</button></Heading>
    <Panel title="Recorded requests">
      <form onSubmit={submit}>
        <div className="cu-filter-grid cu-primary-filters">
          <label>Request ID<input value={draft.requestId} onChange={event => field('requestId', event.target.value)} maxLength={36} placeholder="Find an exact request ID…" /></label>
          <Select label={draft.from || draft.to ? 'Window · custom dates' : 'Activity window'} value={draft.days} onChange={value => setDraft(current => presetWindow(current, value))} items={[{ value: '1', label: 'Last 24 hours' }, { value: '7', label: 'Last 7 days' }, { value: '30', label: 'Last 30 days' }]} />
          <Select label="Request outcome" value={draft.state} onChange={value => field('state', value)} items={[{ value: '', label: 'All outcomes' }, ...options(['complete', 'pending', 'partial', 'disconnected', 'stream_error', 'upstream_error', 'gateway_error', 'interrupted'])]} />
        </div>
        <div className="cu-bar cu-search-actions"><div><button className="cu-primary" type="submit">Search requests</button><button type="button" onClick={() => persistFilters(emptyFilters())}>Clear</button></div><button type="button" aria-expanded={advanced} aria-controls="advanced-request-filters" onClick={() => setAdvanced(value => !value)}>Advanced filters{advancedCount ? ` (${advancedCount})` : ''} <span aria-hidden="true">{advanced ? '−' : '+'}</span></button></div>
        {advanced && <div id="advanced-request-filters" className="cu-advanced-filters"><div className="cu-filter-grid">
          <Select label="Provider" value={draft.provider} onChange={value => field('provider', value)} items={providers} />
          <Select label="Model family" value={draft.modelFamily} onChange={value => field('modelFamily', value)} items={[{ value: '', label: 'All model families' }, { value: 'fable', label: 'Fable only' }]} />
          <Select label="Work category" value={draft.category} onChange={value => field('category', value)} items={[{ value: '', label: 'All categories' }, ...options(categories)]} />
          {inputFields.filter(item => item.key !== 'requestId').map(item => <label key={item.key}>{item.label}<input value={draft[item.key]} onChange={event => field(item.key, event.target.value)} type={item.type || 'text'} min={item.min} max={item.max} maxLength={item.length} /></label>)}
          {(['from', 'to'] as const).map(key => <label key={key}>{key === 'from' ? 'From' : 'Until'} (your timezone)<input type="datetime-local" step="0.001" value={localDateInput(draft[key])} onChange={event => field(key, event.target.value ? new Date(event.target.value).toISOString() : '')} /></label>)}
          <Select label="Capture" value={draft.captureState} onChange={value => field('captureState', value)} items={[{ value: '', label: 'All captures' }, ...options(Object.keys(captureMessages))]} />
          <Select label="Usage reporting" value={draft.usageSeen} onChange={value => field('usageSeen', value)} items={[{ value: '', label: 'Any coverage' }, { value: 'true', label: 'Usage reported' }, { value: 'false', label: 'Usage missing' }]} />
        </div></div>}
      </form>
      <Notice error>{validation || result.error}</Notice>
      <p className="cu-result-summary" aria-live="polite"><strong>{result.loading ? 'Loading requests…' : result.data ? `${result.data.items.length} requests on this page` : 'No loaded results'}</strong><span>{date(window.from)} – {date(window.to)}</span></p>
      <div aria-busy={result.loading}><Table headings={['Started / member', 'Model', 'Status', 'Duration', 'Capture', '']} empty={!result.loading && !result.data?.items.length}>{result.data?.items.map(row => <tr key={text(row.id)}><td>{date(row.started_at)}<br />{text(row.name)}</td><td>{text(row.model)}</td><td>{text(row.status_code)} · {text(row.state)}</td><td>{duration(row.duration_ms)}</td><td><span className="cu-badge">{text(row.capture_status)}</span></td><td><button onClick={() => inspect(text(row.id))} aria-label={'Inspect request ' + text(row.id)}>Inspect</button></td></tr>)}</Table></div>
      <div className="cu-bar"><button disabled={result.loading || cursors.length === 1} onClick={() => setCursors(current => current.slice(0, -1))}>Previous</button><button disabled={result.loading || !result.data?.nextCursor} onClick={() => { if (result.data?.nextCursor) setCursors(current => [...current, result.data!.nextCursor!]); }}>Next</button><span>Page {cursors.length} · {result.data?.items.length || 0} requests</span></div>
    </Panel>
    {detail && <RequestDetail key={detail} id={detail} onClose={closeDetail} />}
    <Panel title="Usage by member"><p className="cu-muted">Last {applied.days} days. These aggregates use the activity window, independently of the detailed search filters.</p><Notice error>{analytics.error}</Notice><Table headings={['Member / model', 'State', 'Requests', 'Input', 'Output', 'Cache write', 'Cache read']} empty={!analytics.loading && !analytics.data?.rows.length}>{analytics.data?.rows.map((row, i) => <tr key={i}><td>{text(row.name)} / {text(row.model)}</td><td>{text(row.state)}</td>{['requests', 'input_tokens', 'output_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens'].map(key => <td key={key}>{count(row[key])}</td>)}</tr>)}</Table></Panel>
    <Panel title="Gateway access"><p className="cu-muted">Latest 50 attempts in the last {applied.days} days, including rejected requests. Rejected bodies are not captured.</p><Notice error>{access.error}</Notice><Table headings={['Time', 'Request ID', 'Method / path', 'Status', 'Duration']} empty={!access.loading && !access.data?.length}>{access.data?.map((row, i) => <tr key={i}><td>{date(row.started_at)}</td><td>{text(row.request_id)}</td><td>{text(row.method)} {text(row.path)}</td><td>{text(row.status_code)}</td><td>{duration(row.duration_ms)}</td></tr>)}</Table></Panel>
  </>;
}

function RequestDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const request = useResource<Row>('/admin/request-search/' + encodeURIComponent(id));
  const [tab, setTab] = useState('summary');
  const [revision, setRevision] = useState(0), section = useRef<HTMLElement>(null);
  useEffect(() => { section.current?.focus(); }, [id]);
  const tabs = ['summary', 'input', 'output', 'events', 'headers', 'value'];
  const data = request.data;
  const metadata = data ? Object.fromEntries(Object.entries(data).filter(([key]) => !['request_body', 'response_body', 'request_headers', 'response_headers'].includes(key))) : null;
  return <section className="cu-panel" ref={section} tabIndex={-1} aria-busy={request.loading} aria-label={'Request detail ' + id}><div className="cu-bar"><h2>Request detail</h2><button onClick={() => { request.refresh(); setRevision(value => value + 1); }} disabled={request.loading}>Refresh detail</button>{data && <a className="cu-button" href={'/admin/requests/' + encodeURIComponent(id) + '/export'} download={'request-' + id + '.json'}>Download JSON</a>}<button onClick={onClose}>Close detail</button></div>
    <p className="cu-mono">{id}</p><Notice error>{request.error}</Notice><Notice>{request.loading ? 'Loading request…' : captureMessages[String(data?.capture_status)] || 'Capture status unavailable'}</Notice>
    <Segments label="Request detail sections" values={tabs.map(value => ({ value, label: value === 'value' ? 'Usage & category' : value[0].toUpperCase() + value.slice(1) }))} value={tab} onChange={setTab} />
    {data && <>
      {tab === 'summary' && <pre>{pretty(metadata)}</pre>}
      {tab === 'input' && <pre>{pretty(data.request_body)}</pre>}
      {tab === 'output' && <pre>{data.response_body === '' ? 'Streaming response: inspect Events.' : pretty(data.response_body)}</pre>}
      {tab === 'headers' && <pre>{'REQUEST\n' + pretty(data.request_headers) + '\n\nRESPONSE\n' + pretty(data.response_headers)}</pre>}
      {tab === 'events' && <Events key={revision} id={id} eventCount={data.event_count} />}
      {tab === 'value' && <RequestValue key={revision} id={id} />}
    </>}
  </section>;
}
function Events({ id, eventCount }: { id: string; eventCount: unknown }) {
  const [pages, setPages] = useState([0]);
  const events = useResource<Row[]>('/admin/requests/' + encodeURIComponent(id) + '/events?after=' + pages.at(-1) + '&limit=100');
  const rows = events.data || [];
  return <><Notice error>{events.error}</Notice><p className="cu-muted">{events.loading ? 'Loading events…' : rows.length ? 'Sequences ' + text(rows[0].sequence) + '–' + text(rows.at(-1)?.sequence) : 'No events on this page. Non-streaming responses use Output.'} · {count(eventCount)} reported stored events</p>
    {rows.map(row => <details key={text(row.sequence)}><summary>#{text(row.sequence)} · {text(row.event_type)} · {duration(row.elapsed_ms)}{row.truncated ? ' · incomplete frame' : ''}</summary><pre>{pretty(row.event_data)}</pre></details>)}
    <div className="cu-bar"><button disabled={events.loading || pages.length === 1} onClick={() => setPages(current => current.slice(0, -1))}>Previous events</button><button disabled={events.loading || rows.length < 100} onClick={() => setPages(current => [...current, Number(rows.at(-1)?.sequence)])}>Next events</button></div></>;
}
function RequestValue({ id }: { id: string }) {
  const path = '/admin/requests/' + encodeURIComponent(id);
  const resource = useResource<{ estimate?: { knownSubtotalUsd?: number; status?: string }; classification?: { category?: string }; [key: string]: unknown }>(path + '/accounting');
  const change = useMutation(); const [category, setCategory] = useState(''); const [saved, setSaved] = useState(false);
  useEffect(() => { setCategory(resource.data?.classification?.category || 'unknown'); }, [resource.data]);
  return <><Notice error>{resource.error || change.error}</Notice><p>API-equivalent value: {money(resource.data?.estimate?.knownSubtotalUsd)} · {resource.data?.estimate?.status || 'unavailable'}. This is not a subscription charge.</p><pre>{resource.loading ? 'Loading…' : pretty(resource.data)}</pre>
    <Select label="Work category" value={category} onChange={value => { setCategory(value); setSaved(false); }} items={options(categories)} />
    <div className="cu-bar"><button disabled={change.busy || !resource.data} onClick={async () => { try { await change.run(path + '/classification', 'PATCH', { category }); setSaved(true); resource.refresh(); } catch { /* hook displays error */ } }}>Save manual category</button>{saved && <span role="status">Category saved.</span>}</div><p className="cu-muted">Categories describe request content, not productivity or effort. Manual changes are audited.</p></>;
}
