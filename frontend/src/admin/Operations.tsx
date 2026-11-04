import { useEffect, useRef } from 'react';
import { Heading, Notice, Panel, useResource } from './ui';
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
  restore: 'Successful isolated restore rehearsal within eight days; a failed latest attempt needs attention.',
};
export default function Operations() {
  const resource = useResource<Report>('/admin/operations');
  const refresh = useRef(resource.refresh); refresh.current = resource.refresh;
  useEffect(() => { const interval = setInterval(() => refresh.current(), 60_000); return () => clearInterval(interval); }, []);
  const data = resource.data;
  const stale = !!data && (data.stale || !data.generatedAt || Date.now() - Date.parse(data.generatedAt) > 180_000);
  return <><Heading title="Operations" subtitle="Local service health, audit processing, and recovery readiness."><button disabled={resource.loading} onClick={resource.refresh}>Refresh health</button></Heading>
    <Notice error>{resource.error}</Notice>
    <StreamProbe />
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
