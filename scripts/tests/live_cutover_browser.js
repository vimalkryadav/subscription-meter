// Install through Playwright's page executor on an authenticated admin Operations page.
// Then call window.controlUsageCutover.start(beforeSha, afterSha) from page.evaluate.
// This never starts a deployment or makes a provider request. Do not navigate while running.
async (page) => {
  if (new URL(page.url()).pathname !== '/manage') throw new Error('Open the authenticated admin page first');
  return await page.evaluate(() => {
    if (window.controlUsageCutover?.running) throw new Error('A cutover probe is already running');
    let running = false, result = null;
    const copy = value => JSON.parse(JSON.stringify(value));
    const sha = value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value);
    window.controlUsageCutover = {
      get running() { return running; },
      snapshot() { return result ? copy(result) : null; },
      start(before, after) {
        if (running || result) throw new Error('One run per installation; collect results before reinstalling');
        if (!sha(before) || !sha(after) || before === after) throw new Error('Two distinct full release SHAs required');
        running = true;
        const started = performance.now(), at = () => Math.round(performance.now() - started);
        result = { schemaVersion: 1, before, after, startedAt: new Date().toISOString(), cutoverMs: null, state: 'running', rows: [] };
        const abort = new AbortController();
        const timer = setTimeout(() => abort.abort(), 85000);
        const jobs = [];
        const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
        const run = async () => {
          try {
            const sessionResponse = await fetch('/portal/api/session', { credentials: 'same-origin', cache: 'no-store', signal: abort.signal });
            if (!sessionResponse.ok) throw new Error('admin_session_required');
            const session = await sessionResponse.json();
            if (session.user?.role !== 'admin') throw new Error('admin_session_required');
            const stream = async (cohort, index, expectedRelease) => {
              const row = { cohort, index, startedMs: at(), firstMs: null, lastMs: null, finishedMs: null,
                status: 0, release: null, events: 0, complete: false, serverSpanMs: null, deliverySpanMs: null, protocol: null, error: null };
              result.rows.push(row);
              let reader;
              try {
                const response = await fetch('/admin/operations/stream-probe?profile=deployment', {
                  method: 'POST', credentials: 'same-origin', cache: 'no-store', body: '{}',
                  headers: { 'Content-Type': 'application/json', 'X-Portal-Request': '1', 'X-CSRF-Token': session.csrfToken },
                  signal: AbortSignal.any([abort.signal, AbortSignal.timeout(45000)]) });
                row.status = response.status;
                const release = response.headers.get('x-control-usage-release');
                row.release = sha(release) ? release : null;
                if (row.status !== 200 || row.release !== expectedRelease || !response.headers.get('content-type')?.includes('text/event-stream') || !response.body) throw new Error('unexpected_response');
                reader = response.body.getReader();
                const decoder = new TextDecoder('utf-8', { fatal: true });
                let pending = '', bytes = 0, firstServer = null, lastServer = 0;
                const accept = text => {
                  pending += text;
                  let end;
                  while ((end = pending.indexOf('\n')) >= 0) {
                    const line = pending.slice(0, end).replace(/\r$/, ''); pending = pending.slice(end + 1);
                    if (!line) continue;
                    if (!line.startsWith('data: ')) throw new Error('invalid_frame');
                    const event = JSON.parse(line.slice(6));
                    if (row.events >= 71 || event.sequence !== row.events || event.total !== 71 ||
                        event.complete !== (row.events === 70) || !Number.isFinite(event.serverElapsedMs) ||
                        event.serverElapsedMs < lastServer || event.serverElapsedMs > 45000) throw new Error('invalid_sequence');
                    if (row.events === 0) { row.firstMs = at(); firstServer = event.serverElapsedMs; }
                    row.lastMs = at(); lastServer = event.serverElapsedMs; row.events++;
                  }
                };
                while (true) {
                  const part = await reader.read(); if (part.done) break;
                  bytes += part.value.byteLength; if (bytes > 16384) throw new Error('size_limit');
                  accept(decoder.decode(part.value, { stream: true }));
                }
                accept(decoder.decode());
                if (pending.trim() || row.events !== 71 || lastServer - firstServer < 34000) throw new Error('incomplete_stream');
                row.serverSpanMs = lastServer - firstServer; row.deliverySpanMs = row.lastMs - row.firstMs;
                row.complete = true;
                const entry = performance.getEntriesByType('resource').filter(e =>
                  e.name === location.origin + '/admin/operations/stream-probe?profile=deployment' &&
                  e.startTime >= started + row.startedMs - 2).at(-1);
                row.protocol = entry?.nextHopProtocol || null;
              } catch (error) {
                const safe = ['unexpected_response', 'invalid_frame', 'invalid_sequence', 'size_limit', 'incomplete_stream'];
                row.error = safe.includes(error.message) ? error.message : 'transport_or_parse_failure';
              } finally {
                if (reader) { await reader.cancel().catch(() => {}); reader.releaseLock(); }
                row.finishedMs = at();
              }
            };
            for (let i = 0; i < 10; i++) jobs.push(stream('before', i, before));
            const deadline = performance.now() + 25000;
            let switched = false;
            while (performance.now() < deadline && !abort.signal.aborted) {
              if (result.rows.some(r => r.error)) throw new Error('pre_cutover_stream_failed');
              const response = await fetch('/', { cache: 'no-store', signal: AbortSignal.any([abort.signal, AbortSignal.timeout(3000)]) });
              if (!response.ok) throw new Error('public_root_unavailable');
              const html = await response.text();
              const releases = [...html.matchAll(/\/assets\/releases\/([a-f0-9]{40})\//g)].map(m => m[1]);
              if (!releases.length || releases.some(v => v !== before && v !== after)) throw new Error('unexpected_public_release');
              if (releases.every(v => v === after)) { switched = true; break; }
              await pause(500);
            }
            if (!switched) throw new Error('cutover_not_observed_within_window');
            result.cutoverMs = at();
            if (result.rows.length !== 10 || result.rows.some(r => r.firstMs === null || r.finishedMs !== null)) throw new Error('old_streams_not_open_at_cutover');
            for (let i = 0; i < 10; i++) jobs.push(stream('after', i, after));
            await Promise.all(jobs);
            result.state = result.rows.length === 20 && result.rows.every(r => r.complete && !r.error) ? 'complete' : 'failed';
          } catch (error) {
            const safe = ['admin_session_required', 'pre_cutover_stream_failed', 'public_root_unavailable', 'unexpected_public_release', 'cutover_not_observed_within_window', 'old_streams_not_open_at_cutover'];
            result.error = safe.includes(error.message) ? error.message : 'transport_failure';
            result.state = 'failed'; abort.abort(); await Promise.all(jobs);
          } finally {
            clearTimeout(timer); running = false; result.finishedMs = at();
          }
        };
        void run();
        return { started: true, maximumStreams: 20, providerRequests: 0 };
      },
    };
    return { installed: true };
  });
}
