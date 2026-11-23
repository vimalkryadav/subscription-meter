import { lazy, Suspense, useState } from 'react';
import { categories, count, date, Heading, Loading, money, Notice, options, Panel, Segments, Select, Stat, Table, text, useMutation, useResource } from './ui';
import type { Price } from './Broker';

const Broker = lazy(() => import('./Broker'));

interface SessionRow { memberId: string; memberName: string | null; sessionId: string; firstRequestAt: string; lastRequestAt: string; requestCount: number; status: string; summary: string | null; category: string | null; model: string | null; costUsd: number | null; lastError: string | null }
interface SpendRow { createdAt: string; purpose: string; model: string; state: string; reservedUsd: number; costUsd: number | null; inputTokens: number | null; outputTokens: number | null }
interface ApiStatus { keyConfigured: boolean; weeklyCapUsd: number; summariesEnabled: boolean; summaryModel: string; inputUsdPerMTok: number; cachedInputUsdPerMTok: number; outputUsdPerMTok: number; updatedAt: string; updatedBy: string; weekStart: string; weekEnd: string; spentUsd: number; remainingUsd: number; recent: SpendRow[] }

const statusLabels: Record<string, string> = { ok: 'Summarized', pending: 'Waiting for idle', running: 'Summarizing', skipped: 'No prompts', failed: 'Failed', budget_exhausted: 'Weekly cap reached' };

export function Sessions() {
  const [days, setDays] = useState(7), [member, setMember] = useState(''), [category, setCategory] = useState(''), [search, setSearch] = useState('');
  const sessions = useResource<SessionRow[]>('/admin/sessions?days=' + days), api = useResource<ApiStatus>('/admin/openai-api');
  const members = [...new Map((sessions.data ?? []).map(row => [row.memberId, row.memberName ?? row.memberId])).entries()].sort((a, b) => a[1].localeCompare(b[1]));
  const visible = sessions.data?.filter(row => (!member || row.memberId === member) && (!category || row.category === category)
    && ((row.summary ?? '') + ' ' + (row.memberName ?? '') + ' ' + row.sessionId).toLowerCase().includes(search.toLowerCase()));
  const byCategory = new Map<string, number>();
  visible?.forEach(row => { if (row.category) byCategory.set(row.category, (byCategory.get(row.category) ?? 0) + 1); });
  const top = [...byCategory.entries()].sort((a, b) => b[1] - a[1]);
  const largest = top[0]?.[1] ?? 1;
  return <><Heading title="Sessions" subtitle="What members worked on, summarized from recorded Claude Code sessions after they go idle for 30 minutes."><button onClick={sessions.refresh} disabled={sessions.loading}>Refresh</button></Heading>
    <Notice error>{sessions.error}</Notice>
    {api.data && !api.data.keyConfigured && <Notice>The gateway has no virtual key for summaries yet, so sessions are listed but not summarized. Set one up under <a href="#view/openai">OpenAI API</a>.</Notice>}
    {api.data && api.data.keyConfigured && !api.data.summariesEnabled && <Notice>Summaries are turned off. Turn them on under <a href="#view/openai">OpenAI API</a>.</Notice>}
    <div className="cu-toolbar"><Segments label="Period" value={days} onChange={setDays} values={[{ value: 1, label: '24 hours' }, { value: 7, label: '7 days' }, { value: 30, label: '30 days' }]} />
      <Select label="Member" value={member} onChange={setMember} items={[{ value: '', label: 'All members' }, ...members.map(([value, label]) => ({ value, label }))]} />
      <Select label="Category" value={category} onChange={setCategory} items={[{ value: '', label: 'All categories' }, ...options(categories)]} />
      <label className="cu-search-field">Search summaries<input type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Project, task or member" /></label></div>
    {top.length > 0 && <Panel title="Sessions by category"><div className="cu-category-bars">{top.map(([name, value]) => <div key={name}><span>{name} · {value}</span><div className="cu-track"><span style={{ width: (100 * value / largest) + '%' }} /></div></div>)}</div></Panel>}
    <Panel title="Sessions">{sessions.loading && !sessions.data ? <Loading /> : <Table headings={['Member', 'Last active', 'Requests', 'Category', 'Summary', 'Status']} empty={!visible?.length}>{visible?.map(row => <tr key={row.memberId + ':' + row.sessionId}>
      <td>{text(row.memberName ?? row.memberId)}</td>
      <td>{date(row.lastRequestAt)}<br /><span className="cu-muted">started {date(row.firstRequestAt)}</span></td>
      <td>{count(row.requestCount)}</td>
      <td>{row.category ? <span className="cu-badge">{row.category}</span> : '—'}</td>
      <td>{row.summary ?? <span className="cu-muted">Not summarized yet</span>}<br /><span className="cu-muted cu-mono" title={row.sessionId}>session {row.sessionId.slice(0, 8)}</span></td>
      <td>{statusLabels[row.status] ?? row.status}{row.lastError && <><br /><span className="cu-muted">{row.lastError}</span></>}</td>
    </tr>)}</Table>}</Panel>
  </>;
}

export function OpenAIApi() {
  const status = useResource<ApiStatus>('/admin/openai-api'), save = useMutation(), broker = useResource<{ prices: Price[] }>('/admin/broker/state');
  const [form, setForm] = useState<Record<string, string> | null>(null), [saved, setSaved] = useState('');
  const data = status.data;
  const values = form ?? (data ? { weeklyCapUsd: String(data.weeklyCapUsd), summaryModel: data.summaryModel, inputUsdPerMTok: String(data.inputUsdPerMTok), cachedInputUsdPerMTok: String(data.cachedInputUsdPerMTok), outputUsdPerMTok: String(data.outputUsdPerMTok), summariesEnabled: String(data.summariesEnabled) } : null);
  const change = (name: string, value: string) => { setSaved(''); setForm({ ...values!, [name]: value }); };
  const used = data && Number(data.weeklyCapUsd) > 0 ? Math.min(100, 100 * Number(data.spentUsd) / Number(data.weeklyCapUsd)) : 100;
  return <><Heading title="OpenAI API" subtitle="One real OpenAI key, held only by the key broker. Callers use virtual keys with their own budgets and limits; every call is recorded."><button onClick={status.refresh} disabled={status.loading}>Refresh</button></Heading>
    <Suspense fallback={<Loading />}><Broker /></Suspense>
    <h2 className="cu-section-title">Session summaries</h2>
    <Notice error>{status.error || save.error}</Notice><Notice>{saved}</Notice>
    {status.loading && !data ? <Loading /> : data && values && <>
      {!data.keyConfigured && <Notice>The gateway has no virtual key for summaries. Create one above with purpose “Session summaries” and write it to the server’s <code>secrets/openai-virtual-key</code> file; it is read on each use, so no restart is needed.</Notice>}
      <div className="cu-grid cu-stats"><Stat title="Summary spend this week" value={money(data.spentUsd)} note={'Gateway-side record · week of ' + date(data.weekStart)} /><Stat title="Summary limit" value={money(data.weeklyCapUsd)} note={'Inner limit for summaries · changed by ' + data.updatedBy} /><Stat title="Remaining" value={money(data.remainingUsd)} note={<span className="cu-track" style={{ display: 'block' }}><span style={{ width: used + '%' }} /></span>} /><Stat title="Virtual key" value={data.keyConfigured ? 'Configured' : 'Missing'} note="The key itself is never shown." /></div>
      <Panel title="Summary settings"><form className="cu-filter-grid" onSubmit={async event => {
        event.preventDefault();
        try {
          await save.run('/admin/openai-api', 'PUT', { weeklyCapUsd: Number(values.weeklyCapUsd), summariesEnabled: values.summariesEnabled === 'true', summaryModel: values.summaryModel.trim(), inputUsdPerMTok: Number(values.inputUsdPerMTok), cachedInputUsdPerMTok: Number(values.cachedInputUsdPerMTok), outputUsdPerMTok: Number(values.outputUsdPerMTok) });
          setForm(null); setSaved('Summary settings saved.'); status.refresh();
        } catch { /* error shown above */ }
      }}>
        <label>Weekly limit for summaries (USD)<input required type="number" min="0" max="10000" step="0.01" value={values.weeklyCapUsd} onChange={event => change('weeklyCapUsd', event.target.value)} /></label>
        <Select label="Session summaries" value={values.summariesEnabled} onChange={value => change('summariesEnabled', value)} items={[{ value: 'true', label: 'On' }, { value: 'false', label: 'Off' }]} />
        {broker.data?.prices.length ? <Select label="Summary model" value={values.summaryModel} onChange={model => { const price = broker.data!.prices.find(item => item.model === model); setSaved(''); setForm({ ...values, summaryModel: model, ...(price ? { inputUsdPerMTok: String(price.inputUsdPerMTok), cachedInputUsdPerMTok: String(price.cachedInputUsdPerMTok), outputUsdPerMTok: String(price.outputUsdPerMTok) } : {}) }); }}
          items={[...(broker.data.prices.some(item => item.model === values.summaryModel) ? [] : [{ value: values.summaryModel, label: values.summaryModel + ' (no broker price)' }]), ...broker.data.prices.map(item => ({ value: item.model, label: item.model + ' · $' + item.inputUsdPerMTok + ' / $' + item.outputUsdPerMTok }))]} />
          : <label>Summary model<input required maxLength={100} pattern="[A-Za-z0-9._:\-]+" value={values.summaryModel} onChange={event => change('summaryModel', event.target.value)} /></label>}
        <label>Input $ / 1M tokens<input required type="number" min="0" max="1000" step="0.000001" value={values.inputUsdPerMTok} onChange={event => change('inputUsdPerMTok', event.target.value)} /></label>
        <label>Cached input $ / 1M tokens<input required type="number" min="0" max="1000" step="0.000001" value={values.cachedInputUsdPerMTok} onChange={event => change('cachedInputUsdPerMTok', event.target.value)} /></label>
        <label>Output $ / 1M tokens<input required type="number" min="0" max="1000" step="0.000001" value={values.outputUsdPerMTok} onChange={event => change('outputUsdPerMTok', event.target.value)} /></label>
        <div className="cu-bar"><button className="cu-primary" disabled={save.busy || !form}>{save.busy ? 'Saving…' : 'Save settings'}</button>{form && <button type="button" onClick={() => setForm(null)}>Discard changes</button>}</div>
      </form><p className="cu-muted">This inner limit applies only to summaries and is checked before the broker’s own budgets. Choosing a model fills in the broker’s prices for it. The summaries virtual key must allow the chosen model. Changes are recorded in the admin action log.</p></Panel>
      <Panel title="Recent summary calls"><Table headings={['Time', 'Purpose', 'Model', 'Result', 'Tokens in / out', 'Cost']} empty={!data.recent.length}>{data.recent.map((row, index) => <tr key={index}><td>{date(row.createdAt)}</td><td>{row.purpose.replaceAll('_', ' ')}</td><td>{row.model}</td><td>{row.state}</td><td>{count(row.inputTokens)} / {count(row.outputTokens)}</td><td>{money(row.state === 'settled' ? row.costUsd : row.reservedUsd)}{row.state !== 'settled' && row.state !== 'failed' && <span className="cu-muted"> (reserved)</span>}</td></tr>)}</Table></Panel>
    </>}
  </>;
}
