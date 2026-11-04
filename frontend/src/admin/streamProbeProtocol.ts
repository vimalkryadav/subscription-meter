export interface ProbeResult { events: number; firstEventMs: number; totalMs: number; deliverySpanMs: number; serverSpanMs: number; delivery: 'incremental' | 'batched' | 'buffered' }

/** Fixed diagnostic protocol only; never parses provider payloads or stores content. */
export async function readProbe(response: Response, started: number, onProgress: (events: number) => void, clock: () => number = () => performance.now()): Promise<ProbeResult> {
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('The streaming test returned an unexpected response.');
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
      if (!value || events >= 16 || value.sequence !== events || value.total !== 16 ||
          !Number.isFinite(value.serverElapsedMs) || value.serverElapsedMs < serverLast || value.serverElapsedMs > 120000 ||
          (value.complete === true) !== (events === 15)) throw new Error('The streaming test returned missing or unordered events.');
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
    if (events !== 16 || !complete || pending.trim()) throw new Error('The connection ended before all test events arrived.');
    const span = Math.max(0, last - first), serverSpan = serverLast - serverFirst;
    if (serverSpan < 2500) throw new Error('The streaming test did not cover the expected time interval.');
    return { events, firstEventMs: Math.round(first - started), totalMs: Math.round(clock() - started),
      deliverySpanMs: Math.round(span), serverSpanMs: Math.round(serverSpan), delivery: span >= serverSpan * .8 ? 'incremental' : span <= serverSpan * .2 ? 'buffered' : 'batched' };
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
