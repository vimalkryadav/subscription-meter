import { useEffect, useState } from 'react';
import { date, Heading, Loading, Notice, Panel, Segments, useResource } from './ui';
import StreamProbe from './StreamProbe';
import './operations.css';
interface Check { id: string; label: string; state: 'ok' | 'failed' | 'unknown'; value: number | null; unit: string; consecutiveFailures: number; alerting: boolean }
interface Report { generatedAt: string | null; status: string; stale: boolean; checks: Check[] }
const explanations: Record<string, string> = {
  gateway: 'Checks the serving application locally. The intentionally stopped standby is excluded.',
  prometheus: 'Confirms metrics can be collected from a serving gateway.',
  worker_ready: 'Confirms the audit consumer is polling and its database is available.',
  worker_lag: 'Records waiting for projection. A sustained backlog above 1,024 needs attention.',
  audit_guard: 'Confirms the gateway can admit audited requests within its safety limits.',
  upstream_auth: 'Observed 401/403 responses in the last ten minutes. No test inference is sent.',
  disk: 'Requires at least 10 GiB and 10% free on the workspace filesystem.',
  backup: 'Successful backup within 30 hours; a failed latest attempt needs attention.',
  backup_capacity: 'Warns before archive, database, store or restore-space budgets are exhausted.',
  restore: 'Successful isolated restore rehearsal within eight days; a failed latest attempt needs attention.',
};
type View = 'health' | 'capacity' | 'recovery' | 'stream';
export default function Operations() {
  const [view, setView] = useState<View>('health');
  return <><Heading title="Operations" subtitle="Service health and the tools to investigate an issue." /><div className="cu-toolbar"><Segments label="Operations view" value={view} onChange={setView} values={[{ value: 'health', label: 'Health' }, { value: 'capacity', label: 'Backup capacity' }, { value: 'recovery', label: 'Recovery' }, { value: 'stream', label: 'Stream diagnostics' }]} /></div>
    {view === 'health' && <Health />}{view === 'capacity' && <Capacity />}{view === 'recovery' && <Recovery />}{view === 'stream' && <StreamProbe />}
  </>;
}
function Health() {
  const resource = useResource<Report>('/admin/operations');
  useEffect(() => { const interval = setInterval(resource.refresh, 60_000); return () => clearInterval(interval); }, [resource.refresh]);
  const data = resource.data;
  const stale = !!data && (data.stale || !data.generatedAt || Date.now() - Date.parse(data.generatedAt) > 180_000);
  const checks = data ? [...data.checks].sort((a, b) => Number(a.state === 'ok') - Number(b.state === 'ok')) : [];
  return <><div className="cu-bar cu-view-actions"><p className="cu-muted">Updates every minute{data?.generatedAt ? ' · Last collected ' + date(data.generatedAt) : ''}</p><button disabled={resource.loading} onClick={resource.refresh}>{resource.loading && data ? 'Loading…' : 'Refresh health'}</button></div><Notice error>{resource.error}</Notice>
    {!data && resource.loading ? <Loading /> : data && <>
      <Notice error={stale || data.status !== 'ok'}>{stale ? 'Monitoring report is missing or stale. Current health is unknown.' : data.status === 'ok' ? 'All monitored checks are healthy.' : data.status === 'alert' ? 'A sustained operational issue needs attention.' : 'One or more checks need attention; sustained checks will raise an alert.'}</Notice>
      <div className="cu-operations-grid">{checks.map(check => <section className="cu-operation" key={check.id} aria-label={check.label}>
        <div className="cu-operation-heading"><h2>{check.label}</h2><span className={'cu-operation-state ' + (stale ? 'unknown' : check.state)}>{stale ? 'Stale' : check.state === 'ok' ? 'Healthy' : check.state === 'unknown' ? 'Unknown' : check.alerting ? 'Alert' : 'Needs attention'}</span></div>
        {check.unit && <strong>{check.value === null ? 'Unavailable' : check.value.toLocaleString()} <small>{check.unit}</small></strong>}
        {check.consecutiveFailures > 0 && <p className="cu-muted">{check.consecutiveFailures} consecutive unsuccessful {check.consecutiveFailures === 1 ? 'check' : 'checks'}</p>}
        <details><summary>Check details</summary><p>{explanations[check.id]}</p></details>
      </section>)}</div>
      <details className="cu-operation-scope"><summary>Monitoring scope and alerts</summary><p className="cu-muted">These checks run on the Coder workspace. They do not prove public tunnel reachability or upstream subscription compatibility. A host outage can also stop monitoring; an old report is shown as stale. Alerts appear here and in the host journal after three consecutive checks; they do not send email or messages.</p></details>
    </>}
  </>;
}
function Capacity() {
  const capacity = useResource<{generatedAt:string|null;archiveObservedAt:string|null;status:string;stale:boolean;rows:{id:string;usedBytes:number;limitBytes:number;reserveBytes:number;remainingBytes:number;state:string}[]}>('/admin/backup-capacity');
  const data = capacity.data;
  const stale = !!data && (data.stale || !data.generatedAt || Date.now() - Date.parse(data.generatedAt) > 180_000);
  return <><div className="cu-bar cu-view-actions"><p className="cu-muted">Storage budgets for backup and restore.</p><button onClick={capacity.refresh} disabled={capacity.loading}>Refresh capacity</button></div><Notice error>{capacity.error}</Notice>{capacity.loading ? <Loading /> : data && <Panel title="Backup and restore capacity">
    <Notice error={stale || data.status !== 'ok'}>{stale ? 'Capacity observations unavailable or stale.' : data.status === 'ok' ? 'Current observations have budget headroom.' : 'Capacity needs attention before the next backup or restore.'}</Notice>
    <p className="cu-muted">Collected {date(data.generatedAt)} · Last archive observed {date(data.archiveObservedAt)}</p>
    <div className="cu-table-scroll" role="region" aria-label="Backup capacity table" tabIndex={0}><table className="cu-capacity-table"><thead><tr><th>Budget</th><th>Observed / required</th><th>Ceiling / available</th><th>Archive reserve</th><th>Remaining</th><th>State</th></tr></thead><tbody>{data.rows.map(row => <tr key={row.id}><td>{({archive:'Last archive',database:'Source database',store:'Backup store',backup_free:'Backup filesystem restore space',docker_free:'Docker filesystem restore space'} as Record<string,string>)[row.id] || row.id}</td><td>{(row.usedBytes/1048576).toFixed(1)} MiB</td><td>{(row.limitBytes/1048576).toFixed(1)} MiB</td><td>{(row.reserveBytes/1048576).toFixed(1)} MiB</td><td>{(row.remainingBytes/1048576).toFixed(1)} MiB</td><td>{row.state.replaceAll('_', ' ')}</td></tr>)}</tbody></table></div>
    <details><summary>How capacity is measured</summary><p className="cu-muted">Archive size is from the last completed backup, not a prediction of the next dump. Filesystem rows compare required restore reserve with available free space. Warnings use the configured policy threshold. Retention remains seven completed archives plus the protected verified copy.</p></details>
  </Panel>}</>;
}
function Recovery() {
  const recovery = useResource<{generatedAt:string|null;status:string;action:string;failedChecks:string[];stale:boolean}>('/admin/recovery');
  return <><div className="cu-bar cu-view-actions"><p className="cu-muted">Latest scheduled health review.</p><button onClick={recovery.refresh} disabled={recovery.loading}>Refresh report</button></div><Notice error>{recovery.error}</Notice>{recovery.loading ? <Loading /> : recovery.data && <Panel title="Hourly health review">
    <Notice error={recovery.data.stale}>{recovery.data.stale ? 'Hourly report unavailable or stale' : recovery.data.status.replaceAll('_', ' ')}</Notice><dl className="cu-coverage"><div><dt>Reviewed</dt><dd>{date(recovery.data.generatedAt)}</dd></div><div><dt>Action</dt><dd>{recovery.data.action.replaceAll('_', ' ')}</dd></div><div><dt>Failed checks</dt><dd>{recovery.data.failedChecks.length ? recovery.data.failedChecks.join(', ') : 'None reported'}</dd></div></dl>
    <details><summary>Automatic recovery scope</summary><p className="cu-muted">Automatic recovery is limited to reclaiming already-processed Kafka history when a verified capacity limit blocks auditing. Unfamiliar failures and unresolved audit writes require investigation. Code releases still use tested blue/green CD.</p></details>
  </Panel>}</>;
}
