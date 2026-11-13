import { useState } from 'react';
import InvestigationRegion from './InvestigationRegion';
import { count, date, Notice, Panel, Table, useResource } from './ui';
import { openRequestScope } from './search';
import { quotaLabel } from './quota';

interface Member {
  memberId: string; name: string; requests: number; usageObserved: number; usageComplete: number;
  reportedTokens: number; gatewayTokenSharePercent: number | null; allowanceUsedPercent: number | null;
}
interface Window {
  provider: string; account: string; bucket: string; modelScope: string; usedPercent: number | null; observedAt: string | null; resetsAt: string | null;
  source: string | null; freshness: string; startsAt: string | null; gatewayThrough: string | null;
  requests: number | null; usageObserved: number | null; usageComplete: number | null; reportedTokens: number | null; members: Member[];
}
interface Report { generatedAt: string; outsideUsage: string; windows: Window[] }
const percent = (value: number | null) => value == null ? 'Unknown' : count(value) + '%';

export default function QuotaUsage({ provider, revision }: { provider: string; revision: number }) {
  const resource = useResource<Report>('/admin/quota-usage?' + new URLSearchParams({ provider, refresh: String(revision) }));
  const [search, setSearch] = useState('');
  return <Panel title="Session & weekly limits">
    <p className="cu-muted">Provider-reported account usage and member activity in the corresponding reset windows. These windows are independent of the filters in Member usage.</p>
    <Notice>Outside usage is unknown. A member’s gateway share measures reported tokens, not their percentage of Claude’s allowance. Model weighting, missing usage and activity outside this gateway prevent exact attribution.</Notice>
    <p className="cu-muted">The all-models weekly limit and Fable weekly limit are independent observations. Do not add their percentages. The Fable breakdown includes only recognized Fable requests.</p>
    <div className="cu-toolbar"><label>Find a member in quota windows<input type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Name or member ID" /></label><span className="cu-muted">Filtering keeps the all-member denominator.</span></div>
    <Notice error>{resource.error}</Notice>
    <InvestigationRegion loading={resource.loading}>{resource.data && <>
      {!resource.data.windows.length && <Notice>No trusted quota observations available for this provider. Session and weekly usage are unknown.</Notice>}
      <div className="cu-grid cu-quota-windows">{resource.data.windows.map(window => <QuotaWindow key={[window.provider, window.account, window.bucket].join('/')} window={window} search={search} />)}</div>
      <p className="cu-muted">Gateway totals read at {date(resource.data.generatedAt)}. Refresh reads stored data; it does not contact Claude. Recorded tokens include input, output, cache creation and cache reads. Partial records can change as audit processing catches up.</p>
    </>}</InvestigationRegion>
  </Panel>;
}

function QuotaWindow({ window: w, search }: { window: Window; search: string }) {
  const label = quotaLabel(w.bucket);
  const query = search.trim().toLocaleLowerCase();
  const members = w.members.filter(member => (member.name + ' ' + member.memberId).toLocaleLowerCase().includes(query));
  const freshness = w.freshness === 'reset_passed' ? 'Historical window · reset passed; current usage unknown'
    : w.freshness === 'stale' ? 'Stale observation · current usage unknown'
    : w.freshness === 'recent' ? 'Recent observation · not a live balance'
    : w.freshness === 'unavailable' ? 'No observation for this limit · usage and reset unknown' : 'Invalid reset window · member breakdown unavailable';
  return <article className="cu-quota-window">
    <div className="cu-bar"><h3>{label}</h3><span className="cu-badge">{w.provider} / {w.account}</span></div>
    <p className="cu-quota-value">{percent(w.usedPercent)} <span>last reported account usage</span></p>
    {w.usedPercent !== null && <div className="cu-track" role="meter" aria-label={label + ' last reported account usage'} aria-valuemin={0} aria-valuemax={100} aria-valuenow={w.usedPercent}><span style={{ width: w.usedPercent + '%' }} /></div>}
    <p className="cu-muted">{freshness}</p>
    <dl className="cu-coverage"><div><dt>Provider observation</dt><dd>{date(w.observedAt)}</dd></div><div><dt>Reported reset</dt><dd>{date(w.resetsAt)}</dd></div><div><dt>Source</dt><dd>{w.source === 'upstream_headers_v1' ? 'Upstream response headers' : w.source === 'coder_statusline' ? 'Trusted Coder status line' : w.source || 'Unavailable'}</dd></div></dl>
    {w.freshness === 'unavailable' && <p className="cu-muted">This limit needs its own trusted provider observation. Its percentage, reset time and member window are not copied from another limit.</p>}
    {w.startsAt && w.gatewayThrough && <>
      <h4>Members in this window</h4>
      <p className="cu-muted">Requests started from {date(w.startsAt)} to {date(w.gatewayThrough)} (end exclusive). The start is derived from the reported reset minus {w.bucket === 'five_hour' ? 'five hours' : 'seven days'}. Gateway totals may include activity after the provider observation.</p>
      <p>{count(w.requests)} requests · {count(w.reportedTokens)} reported tokens</p>
      <p className="cu-muted">{count(w.usageComplete)} complete usage records · {count(w.usageObserved! - w.usageComplete!)} partial · {count(w.requests! - w.usageObserved!)} without usage</p>
      {w.modelScope === 'fable' && <p className="cu-muted">Fable scope: requested model <code>fable</code> or IDs beginning <code>claude-fable-</code>. Other and unrecognized model IDs are excluded.</p>}
      <p className="cu-table-hint">Scroll horizontally to see all columns on smaller screens.</p>
      <Table headings={['Member', 'Tokens / requests', 'Gateway token share', 'Full allowance used']} empty={!members.length} label={label + ' member usage'}>
        {members.map(member => <tr key={member.memberId}><td><button className="cu-link" onClick={() => openRequestScope({ memberId: member.memberId, provider: w.provider, upstreamAccount: w.account, modelFamily: w.modelScope === 'fable' ? 'fable' : '' }, w.startsAt!, w.gatewayThrough!)}>{member.name}</button></td><td>{count(member.reportedTokens)} / {count(member.requests)}<br /><span className="cu-muted">{count(member.usageComplete)} complete · {count(member.requests - member.usageComplete)} incomplete</span></td><td>{percent(member.gatewayTokenSharePercent)}{member.gatewayTokenSharePercent != null && <div className="cu-track" aria-hidden="true"><span style={{ width: member.gatewayTokenSharePercent + '%' }} /></div>}</td><td>{percent(member.allowanceUsedPercent)}</td></tr>)}
      </Table>
      {!w.members.length && <p className="cu-muted">No retained gateway requests in this window. This does not imply zero account usage.</p>}
    </>}
  </article>;
}
