import { useEffect, useRef } from 'react';
import { date, Heading, Notice, Panel, useResource } from './ui';
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
export default function Operations() {
  const resource = useResource<Report>('/admin/operations');
  const recovery = useResource<{generatedAt:string|null;status:string;action:string;failedChecks:string[];stale:boolean}>('/admin/recovery');
  const capacity = useResource<{generatedAt:string|null;archiveObservedAt:string|null;status:string;stale:boolean;rows:{id:string;usedBytes:number;limitBytes:number;reserveBytes:number;remainingBytes:number;state:string}[]}>('/admin/backup-capacity');
  const refresh = useRef(resource.refresh); refresh.current = () => {resource.refresh();capacity.refresh();};
  useEffect(() => { const interval = setInterval(() => refresh.current(), 60_000); return () => clearInterval(interval); }, []);
  const data = resource.data;
  const stale = !!data && (data.stale || !data.generatedAt || Date.now() - Date.parse(data.generatedAt) > 180_000);
  return <><Heading title="Operations" subtitle="Local service health, audit processing, and recovery readiness."><button disabled={resource.loading} onClick={resource.refresh}>Refresh health</button></Heading>
    <Notice error>{resource.error}</Notice>
    <Panel title="Backup and restore capacity">
      <Notice error>{capacity.error}</Notice>
      <p>Collected: {date(capacity.data?.generatedAt)}. Archive size is from the last completed backup: {date(capacity.data?.archiveObservedAt)}; it is not a prediction of the next dump.</p>
      <Notice error={capacity.data?.stale || capacity.data?.status !== 'ok'}>{!capacity.data || capacity.data.stale || (capacity.data.generatedAt && Date.now()-Date.parse(capacity.data.generatedAt)>180_000) ? 'Capacity observations unavailable or stale.' : capacity.data.status === 'ok' ? 'Current observations have budget headroom.' : 'Capacity needs attention before the next backup or restore.'}</Notice>
      <div className="cu-table-scroll" role="region" aria-label="Backup capacity table" tabIndex={0}><table className="cu-capacity-table"><thead><tr><th>Budget</th><th>Observed / required</th><th>Ceiling / available</th><th>Reserved for next archive</th><th>Remaining</th></tr></thead><tbody>{capacity.data?.rows.map(row=><tr key={row.id}><td>{({archive:'Last archive',database:'Source database',store:'Backup store',backup_free:'Backup filesystem restore space',docker_free:'Docker filesystem restore space'} as Record<string,string>)[row.id]}</td><td>{(row.usedBytes/1048576).toFixed(1)} MiB</td><td>{(row.limitBytes/1048576).toFixed(1)} MiB</td><td>{(row.reserveBytes/1048576).toFixed(1)} MiB</td><td>{(row.remainingBytes/1048576).toFixed(1)} MiB</td></tr>)}</tbody></table></div>
      <p>Filesystem rows compare required restore reserve with available free space. Capacity warnings use the configured policy threshold. Retention remains seven completed archives plus the protected verified copy.</p>
      <button onClick={capacity.refresh} disabled={capacity.loading}>Refresh capacity</button>
    </Panel>
    <StreamProbe />
    <Panel title="Hourly health review">
      <p>Automatic recovery is limited to reclaiming already-processed Kafka history when a verified capacity limit blocks auditing. Unfamiliar failures and unresolved audit writes require investigation. Code releases still use tested blue/green CD.</p>
      <Notice error>{recovery.error}</Notice>
      {recovery.data && <p role="status">{recovery.data.stale ? 'Hourly report unavailable or stale' : recovery.data.status.replaceAll('_',' ')} · {date(recovery.data.generatedAt)} · action: {recovery.data.action.replaceAll('_',' ')}{recovery.data.failedChecks.length ? ' · checks: '+recovery.data.failedChecks.join(', ') : ''}</p>}
      <button onClick={recovery.refresh} disabled={recovery.loading}>Refresh hourly report</button>
    </Panel>
    {resource.loading && !data && <p role="status">Loading operational health…</p>}
    {data && <>
      <Notice error={stale || data.status !== 'ok'}>{stale ? 'Monitoring report is missing or stale. Current health is unknown.' : data.status === 'ok' ? 'All monitored checks are healthy.' : data.status === 'alert' ? 'A sustained operational issue needs attention.' : 'One or more checks need attention; sustained checks will raise an alert.'}</Notice>
      <p className="cu-muted">Last collected: {data.generatedAt ? new Date(data.generatedAt).toLocaleString() : 'Not available'}. Collection and this view refresh every minute. Alerts appear here and in the host journal after three consecutive checks.</p>
      <div className="cu-operations-grid">{data.checks.map(check => <section className="cu-operation" key={check.id} aria-label={check.label}>
        <div className="cu-operation-heading"><h2>{check.label}</h2><span className={'cu-operation-state ' + (stale ? 'unknown' : check.state)}>{stale ? 'Stale' : check.state === 'ok' ? 'Healthy' : check.state === 'unknown' ? 'Unknown' : check.alerting ? 'Alert' : 'Needs attention'}</span></div>
        <p>{explanations[check.id]}</p>{check.unit && <strong>{check.value === null ? 'Unavailable' : check.value.toLocaleString()} <small>{check.unit}</small></strong>}
        {check.consecutiveFailures > 0 && <p className="cu-muted">{check.consecutiveFailures} consecutive unsuccessful {check.consecutiveFailures === 1 ? 'check' : 'checks'}</p>}
      </section>)}</div>
      <Panel title="Scope"><p>These checks run on the Coder workspace. They do not prove public tunnel reachability or upstream subscription compatibility. A host outage can also stop monitoring; an old report is shown as stale. Alerts do not send email or messages.</p></Panel>
    </>}
  </>;
}
