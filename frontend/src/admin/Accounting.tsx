import { useState } from 'react';
import type { FormEvent } from 'react';
import { count, date, Heading, money, Notice, options, Panel, pretty, providers, Select, Stat, Table, text, useMutation, useResource } from './ui';
import type { Row } from './ui';
import { openRequestScope } from './search';
import QuotaUsage from './QuotaUsage';

interface Report { from: string; to: string; generatedAt: string; summary: Row; members: Row[]; categories: Row[]; quota: { state: string; snapshots: Row[] }; notes: string[] }
interface Allocation { allocatedAmount: number; unallocatedAmount: number; currency: string; usageCoverage: string; note: string; members: Row[] }
export default function Accounting() {
  const [provider, setProvider] = useState(''), [days, setDays] = useState('7'), [member, setMember] = useState(''), [memberFilter, setMemberFilter] = useState('');
  const resource = useResource<Report>('/admin/accounting?' + new URLSearchParams({ days, provider, memberId: memberFilter }));
  const [invoiceRevision, setInvoiceRevision] = useState(0);
  const report = resource.data, summary = report?.summary;
  return <><Heading title="Usage & value" subtitle="Member shares, work categories, and transparent value estimates."><button disabled={resource.loading} onClick={() => { resource.refresh(); setInvoiceRevision(value => value + 1); }}>Refresh</button></Heading>
    <form className="cu-toolbar" onSubmit={event => { event.preventDefault(); setMemberFilter(member.trim()); }}><Select label="Provider" value={provider} onChange={setProvider} items={providers} /><Select label="Report window" value={days} onChange={setDays} items={[{ value: '1', label: 'Last 24 hours' }, { value: '7', label: 'Last 7 days' }, { value: '30', label: 'Last 30 days' }, { value: '90', label: 'Last 90 days' }]} /><label>Member ID<input value={member} maxLength={36} onChange={event => setMember(event.target.value)} placeholder="All members" /></label><button type="submit">Apply member</button><span className="cu-muted" aria-live="polite">{resource.loading ? 'Refreshing…' : report ? 'Updated ' + date(report.generatedAt) : ''}</span></form>
    <Notice error>{resource.error}</Notice>
    <QuotaUsage provider={provider} revision={invoiceRevision} />
    {report && summary && <><div className="cu-grid cu-stats"><Stat title="Recorded requests" value={count(summary.requestCount)} note={count(summary.usageObserved) + ' report usage · ' + count(summary.usageComplete) + ' complete'} /><Stat title="Known API-equivalent value" value={money(summary.knownValueUsd)} note="Estimated value, not your subscription bill" /><Stat title="Pricing coverage" value={count(summary.pricedRequests) + ' priced'} note={count(summary.partiallyPricedRequests) + ' partial · ' + count(summary.unpricedRequests) + ' unpriced'} /><Stat title="Account quota source" value={report.quota?.state || 'unavailable'} note="Latest account observations, independent of the report window" /></div>
      <div className="cu-grid cu-halves"><Panel title="Observed member share"><p className="cu-muted">Share of reported tokens in the selected cohort. This is not percentage of a subscription quota.</p><Table headings={['Member', 'Requests', 'Tokens', 'Observed share', 'Known value']} empty={!report.members.length}>{report.members.map(row => <tr key={text(row.memberId)}><td><button className="cu-link" onClick={() => openRequestScope({ memberId: text(row.memberId), provider }, report.from, report.to)}>{text(row.name)}</button></td><td>{count(row.requests)}</td><td>{count(row.reportedTokens)}</td><td>{row.observedTokenSharePercent == null ? 'Unknown' : Number(row.observedTokenSharePercent).toFixed(1) + '%'}</td><td>{money(row.knownValueUsd)}</td></tr>)}</Table></Panel>
      <Panel title="Work categories"><p className="cu-muted">Classification may be unknown or manually assigned. These categories do not measure productivity.</p><div className="cu-category-bars">{report.categories.map(row => <div key={text(row.category)}><button className="cu-link" onClick={() => openRequestScope({ category: text(row.category), provider, memberId: memberFilter }, report.from, report.to)}>{text(row.category)} · {count(row.requests)}</button><div className="cu-track" aria-hidden="true"><span style={{ width: (100 * Number(row.requests) / Math.max(1, ...report.categories.map(item => Number(item.requests)))) + '%' }} /></div></div>)}</div><Table headings={['Category', 'Requests', 'Tokens', 'Known value']} empty={!report.categories.length}>{report.categories.map(row => <tr key={text(row.category)}><td>{text(row.category)}</td><td>{count(row.requests)}</td><td>{count(row.reportedTokens)}</td><td>{money(row.knownValueUsd)}</td></tr>)}</Table></Panel></div>
      <Panel title="Quota observations"><p className="cu-muted">These are the latest retained account observations, not a live provider balance. Gateway responses can supply new observations; an optional trusted collector can also supply them. Refresh reloads recorded data and does not contact the provider.</p><Notice>A stale observation may simply mean there has been no recent traffic. An observation older than five minutes, or whose reported reset time has passed, cannot establish current remaining allowance. Member token share is separate from provider quota.</Notice>
        {report.quota?.snapshots?.length ? <><p className="cu-muted">Freshness evaluated at {date(report.generatedAt)}. Each quota window is independent.</p><Table headings={['Provider / account', 'Window', 'Last observed usage', 'Observed at', 'Reported reset', 'Freshness', 'Source']}>
          {report.quota.snapshots.map(row => {
            const asOf = Date.parse(report.generatedAt), observed = Date.parse(String(row.observed_at || '')), reset = Date.parse(String(row.resets_at || ''));
            const ageSeconds = Number.isFinite(observed) && Number.isFinite(asOf) ? Math.max(0, Math.floor((asOf - observed) / 1000)) : null;
            const resetElapsed = Number.isFinite(reset) && Number.isFinite(asOf) && reset <= asOf;
            const staleAge = ageSeconds !== null && asOf - observed > 300000;
            const freshness = resetElapsed ? 'Reset time passed; current usage unknown' : staleAge ? 'Observation older than five minutes' : row.state === 'available' ? 'Recent observation' : 'Freshness unavailable';
            const age = ageSeconds === null ? 'Age unavailable' : ageSeconds < 60 ? ageSeconds + ' seconds old' : ageSeconds < 3600 ? Math.floor(ageSeconds / 60) + ' minutes old' : Math.floor(ageSeconds / 3600) + ' h ' + Math.floor(ageSeconds % 3600 / 60) + ' min old';
            return <tr key={text(row.id)}><td>{text(row.provider)} / {text(row.upstream_account)}</td><td>{row.bucket === 'five_hour' ? 'Five-hour window' : row.bucket === 'seven_day' ? 'Seven-day window' : text(row.bucket)}</td><td>{row.used_percentage == null ? 'Unknown' : count(row.used_percentage) + '%'}</td><td>{date(row.observed_at)}<br /><span className="cu-muted">{age}</span></td><td>{date(row.resets_at)}</td><td>{freshness}</td><td>{row.source === 'upstream_headers_v1' ? 'Upstream response headers' : row.source === 'coder_statusline' ? 'Trusted Coder status line' : text(row.source)}</td></tr>;
          })}
        </Table><details><summary>Observation metadata</summary><pre>{pretty(report.quota.snapshots)}</pre></details></> : <Notice>No provider quota observations available. Subscription usage percentages cannot be inferred from tokens alone.</Notice>}<ul>{report.notes?.map((note, index) => <li key={index}>{note}</li>)}</ul></Panel>
    </>}
    <Invoices revision={invoiceRevision} />
  </>;
}

function Invoices({ revision }: { revision: number }) {
  // Revision is used in a harmless supported query string to refresh without remounting form state.
  const invoices = useResource<Row[]>('/admin/subscription-invoices?refresh=' + revision);
  const [selected, setSelected] = useState('');
  const allocation = useResource<Allocation>(selected ? '/admin/subscription-invoices/' + encodeURIComponent(selected) + '/allocation' : null);
  const mutation = useMutation(), [message, setMessage] = useState('');
  const [form, setForm] = useState({ provider: '', upstreamAccount: '', from: '', to: '', amount: '', currency: '', fraction: '', allocationBasis: '', provenance: '' });
  function field(key: keyof typeof form, value: string) { setForm(current => ({ ...current, [key]: value })); }
  async function submit(event: FormEvent) {
    event.preventDefault(); setMessage('');
    if (!form.provider || !form.currency || !form.allocationBasis || !form.provenance) { setMessage('Choose the provider, currency, allocation basis, and amount source.'); return; }
    const from = new Date(form.from), to = new Date(form.to);
    if (!(from < to)) { setMessage('The period end must be after its start.'); return; }
    try {
      const result = await mutation.run<{ id: string }>('/admin/subscription-invoices', 'POST', { provider: form.provider, upstreamAccount: form.upstreamAccount.trim(), periodStart: from.toISOString(), periodEnd: to.toISOString(), amount: Number(form.amount), currency: form.currency, gatewayFraction: Number((Number(form.fraction) / 100).toFixed(6)), allocationBasis: form.allocationBasis, provenance: form.provenance });
      if (result) { setMessage('Expense recorded. Allocation remains separate from provider quota and API-equivalent value.'); invoices.refresh(); setSelected(result.id); }
    } catch { setMessage('Check the expense list before submitting again to avoid recording the same expense twice.'); }
  }
  return <Panel title="Subscription expense allocation"><p className="cu-muted">Record an invoice or estimated subscription expense. Explicitly choose the gateway allocation and leave outside activity unallocated. This does not measure provider quota or incremental charges.</p>
    <form onSubmit={submit}><div className="cu-filter-grid">
      <Select label="Provider" value={form.provider} onChange={value => field('provider', value)} items={[{ value: '', label: 'Choose provider' }, ...providers.slice(1)]} />
      <label>Upstream account<input required value={form.upstreamAccount} onChange={event => field('upstreamAccount', event.target.value)} pattern="[a-zA-Z0-9_-]{1,80}" placeholder="Account alias, e.g. primary" /></label>
      <label>Period start (your timezone)<input required type="datetime-local" value={form.from} onChange={event => field('from', event.target.value)} /></label><label>Period end, exclusive<input required type="datetime-local" value={form.to} onChange={event => field('to', event.target.value)} /></label>
      <label>Expense amount<input required type="number" min="0" max="1000000" step="0.01" value={form.amount} onChange={event => field('amount', event.target.value)} /></label>
      <Select label="Currency" value={form.currency} onChange={value => field('currency', value)} items={[{ value: '', label: 'Choose currency' }, ...options(['USD', 'INR', 'EUR', 'GBP'])]} />
      <label>Expense allocated to gateway (%)<input required type="number" min="0" max="100" step="0.0001" value={form.fraction} onChange={event => field('fraction', event.target.value)} placeholder="Explicit allocation, 0–100" /></label>
      <Select label="Allocation basis" value={form.allocationBasis} onChange={value => field('allocationBasis', value)} items={[{ value: '', label: 'Choose allocation' }, { value: 'reported_tokens', label: 'Reported token share' }, { value: 'equal', label: 'Equal split among members with recorded requests' }]} />
      <Select label="Amount source" value={form.provenance} onChange={value => field('provenance', value)} items={[{ value: '', label: 'Choose source' }, { value: 'manual_invoice', label: 'Manually recorded invoice' }, { value: 'estimated_subscription', label: 'Estimated subscription expense' }]} />
    </div><div className="cu-bar"><button className="cu-primary" disabled={mutation.busy}>{mutation.busy ? 'Recording…' : 'Record expense'}</button><button type="button" onClick={invoices.refresh}>Refresh expense list</button></div></form>
    <Notice error>{mutation.error || invoices.error}</Notice><Notice>{message}</Notice>
    <Table headings={['Provider / account', 'Period', 'Amount', 'Source', '']} empty={!invoices.loading && !invoices.data?.length}>{invoices.data?.map(invoice => <tr key={text(invoice.id)}><td>{text(invoice.provider)} / {text(invoice.upstream_account)}</td><td>{date(invoice.period_start)} – {date(invoice.period_end)}</td><td>{money(invoice.amount, text(invoice.currency))}</td><td>{invoice.provenance === 'manual_invoice' ? 'Manually recorded invoice' : 'Estimated subscription expense'}</td><td><button onClick={() => setSelected(text(invoice.id))}>View allocation</button></td></tr>)}</Table>
    {selected && <section><div className="cu-bar"><h3>Member allocation</h3><button onClick={() => setSelected('')}>Close allocation</button></div><Notice error>{allocation.error}</Notice>{allocation.loading && <p role="status">Loading allocation…</p>}{allocation.data && <><p>Allocated {money(allocation.data.allocatedAmount, allocation.data.currency)} · unallocated {money(allocation.data.unallocatedAmount, allocation.data.currency)} · usage coverage {allocation.data.usageCoverage}. {allocation.data.note}</p><Table headings={['Member', 'Requests', 'Reported tokens', 'Allocated amount']} empty={!allocation.data.members.length}>{allocation.data.members.map(row => <tr key={text(row.memberId)}><td>{text(row.name)}</td><td>{count(row.requests)}</td><td>{count(row.tokens)}</td><td>{money(row.allocatedAmount, allocation.data!.currency)}</td></tr>)}</Table></>}</section>}
  </Panel>;
}
