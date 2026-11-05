import { useEffect, useRef, useState } from 'react';
import { getSession } from '../shared/api';
import { Notice, Panel } from './ui';
import { probeProfiles, readProbe } from './streamProbeProtocol';
import type { ProbeProfile, ProbeResult } from './streamProbeProtocol';

export default function StreamProbe() {
  const [busy, setBusy] = useState(false), [events, setEvents] = useState(0), [error, setError] = useState(''), [result, setResult] = useState<ProbeResult | null>(null);
  const current = useRef<AbortController | null>(null);
  const [profile, setProfile] = useState<ProbeProfile>('quick');
  useEffect(() => () => { current.current?.abort(); current.current = null; }, []);
  async function run(selected: ProbeProfile) {
    if (current.current) return;
    const controller = new AbortController(); current.current = controller;
    setProfile(selected); setBusy(true); setError(''); setResult(null); setEvents(0);
    let timedOut = false;
    const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, probeProfiles[selected].timeoutMs);
    try {
      const session = await getSession(controller.signal), started = performance.now();
      const response = await fetch('/admin/operations/stream-probe?profile=' + selected, { method: 'POST', body: '{}',
        credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
        headers: { 'Content-Type': 'application/json', 'X-Portal-Request': '1', 'X-CSRF-Token': session.csrfToken } });
      if (response.status === 401) { window.dispatchEvent(new Event('control-usage:session-expired')); throw new Error('Your session expired. Sign in again.'); }
      if (!response.ok) throw new Error(response.status === 429 ? 'Other streaming tests are running. Try again shortly.' : 'The streaming test could not start. Refresh your session and try again.');
      const measured = await readProbe(response, started, count => { if (current.current === controller) setEvents(count); }, () => performance.now(), selected);
      if (current.current === controller) setResult(measured);
    } catch (failure) {
      if (current.current === controller) setError(timedOut ? `The connection did not finish within ${probeProfiles[selected].timeoutMs / 1000} seconds.` : controller.signal.aborted ? 'Streaming test cancelled.' : failure instanceof TypeError ? 'The browser could not complete the streaming connection.' : failure instanceof Error ? failure.message : 'Streaming test failed.');
    } finally {
      clearTimeout(timeout);
      if (current.current === controller) { current.current = null; setBusy(false); }
    }
  }
  const seconds = (ms: number) => (ms / 1000).toFixed(2) + ' s';
  return <Panel title="Browser streaming test">
    <p className="cu-muted">The quick test sends 16 events over 3 seconds. The deployment test sends 71 events over 35 seconds to check a longer connection. Neither starts a deployment or creates model calls or usage records.</p>
    <div className="cu-bar"><button className="cu-primary" disabled={busy} onClick={() => void run('quick')}>{busy && profile === 'quick' ? 'Testing stream…' : 'Test browser streaming'}</button><button disabled={busy} onClick={() => void run('deployment')}>{busy && profile === 'deployment' ? 'Testing deployment stream…' : 'Test 35-second stream'}</button>{busy && <button onClick={() => current.current?.abort()}>Cancel test</button>}<span role="status">{busy ? `${events} / ${probeProfiles[profile].events} events received` : result ? `${result.events} / ${result.events} events received` : ''}</span></div>
    <Notice error>{error}</Notice>
    {result && <><Notice error={result.delivery !== 'incremental'}>{result.delivery === 'incremental' ? 'Incremental delivery observed.' : result.delivery === 'buffered' ? 'The events arrived together after being buffered.' : 'The events arrived in delayed batches.'} This measures this browser connection, not provider response time or an uptime guarantee.</Notice>
      <dl className="cu-coverage"><div><dt>First event</dt><dd>{seconds(result.firstEventMs)}</dd></div><div><dt>Completed in</dt><dd>{seconds(result.totalMs)}</dd></div><div><dt>Event delivery span</dt><dd>{seconds(result.deliverySpanMs)}</dd></div></dl>
      {result.release && <p className="cu-muted">Serving release: <code title={result.release}>{result.release.slice(0, 12)}</code></p>}
    </>}
  </Panel>;
}
