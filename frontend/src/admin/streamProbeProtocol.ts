export const probeProfiles = {
  quick: { events: 16, timeoutMs: 15000, minimumSpanMs: 2500 },
  deployment: { events: 71, timeoutMs: 45000, minimumSpanMs: 34000 },
} as const;
export type ProbeProfile = keyof typeof probeProfiles;
export interface ProbeResult { events: number; firstEventMs: number; totalMs: number; deliverySpanMs: number; serverSpanMs: number; release: string | null; delivery: 'incremental' | 'batched' | 'buffered' }

/** Fixed diagnostic protocol only; never parses provider payloads or stores content. */
export async function readProbe(response: Response, started: number, onProgress: (events: number) => void, clock: () => number = () => performance.now(), profile: ProbeProfile = 'quick'): Promise<ProbeResult> {
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('The streaming test returned an unexpected response.');
  const expected = probeProfiles[profile];
  const reader = response.body.getReader(), decoder = new TextDecoder('utf-8', { fatal: true });
  let bytes = 0, pending = '', events = 0, first = 0, last = 0, serverFirst = 0, serverLast = 0, complete = false;
  function accept(text: string, arrived: number) {
    pending += text;
    let end: number;
    while ((end = pending.indexOf('\n')) >= 0) {
      const line = pending.slice(0, end).replace(/\r$/, ''); pending = pending.slice(end + 1);
      if (!line || line.startsWith(':') || line.startsWith('event:')) continue;
      if (!line.startsWith('data:')) throw new Error('The streaming test returned an invalid event.');
      let value;
      try { value = JSON.parse(line.slice(5).trim()); } catch { throw new Error('The streaming test returned an invalid event.'); }
      if (!value || events >= expected.events || value.sequence !== events || value.total !== expected.events ||
          !Number.isFinite(value.serverElapsedMs) || value.serverElapsedMs < serverLast || value.serverElapsedMs > 120000 ||
          typeof value.complete !== 'boolean' || value.complete !== (events === expected.events - 1)) throw new Error('The streaming test returned missing or unordered events.');
      if (events === 0) { first = arrived; serverFirst = value.serverElapsedMs; }
      last = arrived; serverLast = value.serverElapsedMs; complete = value.complete === true; events++;
      onProgress(events);
    }
  }
  try {
    while (true) {
      const item = await reader.read();
      if (item.done) break;
      bytes += item.value.byteLength;
      if (bytes > 16384) throw new Error('The streaming test exceeded its response limit.');
      accept(decoder.decode(item.value, { stream: true }), clock());
    }
    accept(decoder.decode(), clock());
    if (events !== expected.events || !complete || pending.trim()) throw new Error('The connection ended before all test events arrived.');
    const span = Math.max(0, last - first), serverSpan = serverLast - serverFirst;
    if (serverSpan < expected.minimumSpanMs) throw new Error('The streaming test did not cover the expected time interval.');
    const release = response.headers.get('x-control-usage-release');
    return { events, firstEventMs: Math.round(first - started), totalMs: Math.round(clock() - started),
      release: release && /^[a-f0-9]{40}$/.test(release) ? release : null,
      deliverySpanMs: Math.round(span), serverSpanMs: Math.round(serverSpan), delivery: span >= serverSpan * .8 ? 'incremental' : span <= serverSpan * .2 ? 'buffered' : 'batched' };
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
