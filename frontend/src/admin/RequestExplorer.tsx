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
  const [showUsage, setShowUsage] = useState(false), [showAccess, setShowAccess] = useState(false);
  const detailTrigger = useRef<HTMLElement | null>(null);
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
  const analytics = useResource<{ rows: Row[] }>(showUsage ? '/admin/analytics?days=' + applied.days : null);
  const access = useResource<Row[]>(showAccess ? '/admin/access?days=' + applied.days : null);
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
  function inspect(id: string) { detailTrigger.current = document.activeElement as HTMLElement; setDetail(id); const url = new URL(location.href); url.searchParams.set('rq_detail', id); history.pushState({}, '', url); }
  function closeDetail() { setDetail(''); detailTrigger.current?.focus(); const url = new URL(location.href); url.searchParams.delete('rq_detail'); history.pushState({}, '', url); }
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
        <div className="cu-bar cu-search-actions"><div><button className="cu-primary" type="submit">Search requests</button><button type="button" disabled={!/^[A-Za-z0-9_-]{1,36}$/.test(draft.requestId.trim())} onClick={() => inspect(draft.requestId.trim())}>Open ID directly</button><button type="button" onClick={() => persistFilters(emptyFilters())}>Clear</button></div><button type="button" aria-expanded={advanced} aria-controls="advanced-request-filters" onClick={() => setAdvanced(value => !value)}>Advanced filters{advancedCount ? ` (${advancedCount})` : ''} <span aria-hidden="true">{advanced ? '−' : '+'}</span></button></div>
        <p className="cu-muted">Search uses the selected dates and filters. Open ID directly looks up one request across all remaining metadata, independently of those filters.</p>
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
    <div className="cu-bar"><button aria-expanded={showUsage} aria-controls="request-usage" onClick={() => setShowUsage(value => !value)}>{showUsage ? 'Hide' : 'Show'} recent member usage</button><button aria-expanded={showAccess} aria-controls="request-access" onClick={() => setShowAccess(value => !value)}>{showAccess ? 'Hide' : 'Show'} recent gateway access</button></div>
    {showUsage && <div id="request-usage"><Panel title="Usage by member"><p className="cu-muted">Last {applied.days} days. These recent aggregates are independent of custom search dates and all detailed filters.</p><Notice error>{analytics.error}</Notice><Table headings={['Member / model', 'State', 'Requests', 'Input', 'Output', 'Cache write', 'Cache read']} empty={!analytics.loading && !analytics.data?.rows.length}>{analytics.data?.rows.map((row, i) => <tr key={i}><td>{text(row.name)} / {text(row.model)}</td><td>{text(row.state)}</td>{['requests', 'input_tokens', 'output_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens'].map(key => <td key={key}>{count(row[key])}</td>)}</tr>)}</Table></Panel></div>}
    {showAccess && <div id="request-access"><Panel title="Gateway access"><p className="cu-muted">Latest 50 attempts in the last {applied.days} days, including rejected requests. Independent of custom search dates and detailed filters. Rejected bodies are not captured.</p><Notice error>{access.error}</Notice><Table headings={['Time', 'Request ID', 'Method / path', 'Status', 'Duration']} empty={!access.loading && !access.data?.length}>{access.data?.map((row, i) => <tr key={i}><td>{date(row.started_at)}</td><td>{row.request_id ? <button onClick={() => inspect(text(row.request_id))}>{text(row.request_id)}</button> : '—'}</td><td>{text(row.method)} {text(row.path)}</td><td>{text(row.status_code)}</td><td>{duration(row.duration_ms)}</td></tr>)}</Table></Panel></div>}
  </>;
}

function RequestDetail({ id, onClose }: { id: string; onClose: () => void }) {
  const request = useResource<Row>('/admin/request-search/' + encodeURIComponent(id) + '/summary');
  const [tab, setTab] = useState('summary');
  const [revision, setRevision] = useState(0), section = useRef<HTMLElement>(null);
  useEffect(() => { section.current?.focus(); }, [id]);
  const tabs = ['summary', 'input', 'output', 'events', 'headers', 'value'];
  const data = request.data;
  return <section className="cu-panel" ref={section} tabIndex={-1} aria-busy={request.loading} aria-label={'Request detail ' + id}><div className="cu-bar"><h2>Request detail</h2><button onClick={() => { request.refresh(); setRevision(value => value + 1); }} disabled={request.loading}>Refresh detail</button>{data && <a className="cu-button" href={'/admin/requests/' + encodeURIComponent(id) + '/export'} download={'request-' + id + '.json'}>Download JSON</a>}<button onClick={onClose}>Close detail</button></div>
    <p className="cu-mono">{id}</p><Notice error>{request.error}</Notice><Notice>{request.loading ? 'Loading request…' : captureMessages[String(data?.capture_status)] || 'Capture status unavailable'}</Notice>
    <Segments label="Request detail sections" values={tabs.map(value => ({ value, label: value === 'value' ? 'Usage & category' : value[0].toUpperCase() + value.slice(1) }))} value={tab} onChange={setTab} />
    {data && <>
      {tab === 'summary' && <pre>{pretty(data)}</pre>}
      {['input', 'output', 'headers'].includes(tab) && <CaptureContent key={revision + ':' + tab} id={id} section={tab} startedAt={data.capture_version} />}
      {tab === 'events' && <Events key={revision} id={id} eventCount={data.event_count} />}
      {tab === 'value' && <RequestValue key={revision} id={id} />}
    </>}
  </section>;
}
function CaptureContent({ id, section, startedAt }: { id: string; section: string; startedAt: unknown }) {
  const capturedAt = typeof startedAt === 'string' && Number.isFinite(Date.parse(startedAt)) ? startedAt : null;
  const resource = useResource<{ body: string | null; requestHeaders: string | null; responseHeaders: string | null }>(capturedAt
    ? '/admin/request-search/' + encodeURIComponent(id) + '/content?' + new URLSearchParams({ startedAt: capturedAt, section }) : null);
  return <div aria-busy={resource.loading}>
    <Notice error>{resource.error}</Notice>
    {!capturedAt ? <Notice>No retained capture version is available. Refresh the summary if capture is still pending.</Notice> : resource.loading ? <p role="status">Loading {section}…</p> : resource.data && <pre>{section === 'headers'
      ? 'REQUEST\n' + pretty(resource.data.requestHeaders) + '\n\nRESPONSE\n' + pretty(resource.data.responseHeaders)
      : section === 'output' && resource.data.body === '' ? 'Streaming response: inspect Events.' : pretty(resource.data.body)}</pre>}
    {resource.error && <p className="cu-muted">This capture may have expired or changed. Refresh detail to load its current summary.</p>}
  </div>;
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
