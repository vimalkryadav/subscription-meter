import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
const source = await readFile(new URL('../src/admin/streamProbeProtocol.ts', import.meta.url), 'utf8');
const { readProbe } = await import(`data:text/javascript;base64,${Buffer.from(stripTypeScriptTypes(source)).toString('base64')}`);
const event = i => `data: ${JSON.stringify({ sequence: i, total: 16, serverElapsedMs: i * 200, complete: i === 15 })}\n\n`;
const response = chunks => new Response(new ReadableStream({ start(controller) { for (const chunk of chunks) controller.enqueue(new TextEncoder().encode(chunk)); controller.close(); } }), { headers: { 'content-type': 'text/event-stream' } });

test('split frames are parsed in order and incremental delivery is distinguished from buffering', async () => {
  let now = 100; const counts = [];
  const chunks = Array.from({ length: 16 }, (_, i) => event(i));
  const incremental = await readProbe(response(chunks), 0, count => counts.push(count), () => { const at = now; now += 200; return at; });
  assert.equal(incremental.delivery, 'incremental'); assert.equal(incremental.events, 16); assert.equal(incremental.firstEventMs, 100);
  assert.deepEqual(counts, Array.from({ length: 16 }, (_, i) => i + 1));
  const all = chunks.join('');
  const buffered = await readProbe(response([all.slice(0, 11), all.slice(11)]), 0, () => {}, () => 3500);
  assert.equal(buffered.delivery, 'buffered'); assert.equal(buffered.deliverySpanMs, 0);
});

test('missing, duplicated, out of order, malformed and unbounded events fail instead of reporting success', async () => {
  for (const chunks of [[event(0)], [event(1)], [event(0), event(0)], ['data: broken\n\n'], ['x'.repeat(16385)]]) {
    await assert.rejects(readProbe(response(chunks), 0, () => {}, () => 100));
  }
  await assert.rejects(readProbe(new Response('{}'), 0, () => {}), /unexpected response/);
});

test('a broken stream cancels the reader and cannot produce a complete result', async () => {
  let cancelled = false;
  const body = new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode(event(4))); }, cancel() { cancelled = true; } });
  await assert.rejects(readProbe(new Response(body, { headers: { 'content-type': 'text/event-stream' } }), 0, () => {}), /unordered/);
  assert.equal(cancelled, true);
});

test('deployment profile requires all 71 events over the longer interval and reports the release', async () => {
  const chunks = Array.from({ length: 71 }, (_, i) => `data: ${JSON.stringify({ sequence: i, total: 71, serverElapsedMs: i * 500, complete: i === 70 })}\n\n`);
  const res = response(chunks); res.headers.set('x-control-usage-release', 'a'.repeat(40));
  let now = 100;
  const result = await readProbe(res, 0, () => {}, () => { const at = now; now += 500; return at; }, 'deployment');
  assert.equal(result.events, 71); assert.equal(result.serverSpanMs, 35000);
  assert.equal(result.delivery, 'incremental'); assert.equal(result.release, 'a'.repeat(40));
  await assert.rejects(readProbe(response(chunks), 0, () => {}, () => 100), /unordered/);
  await assert.rejects(readProbe(response(Array.from({length:16},(_,i)=>event(i))), 0, () => {}, () => 100, 'deployment'), /unordered/);
  await assert.rejects(readProbe(response(chunks.slice(0,-1)), 0, () => {}, () => 100, 'deployment'), /before all/);
  const tooFast=chunks.map(c=>c.replace(/"serverElapsedMs":\d+/, '"serverElapsedMs":0'));
  await assert.rejects(readProbe(response(tooFast), 0, () => {}, () => 100, 'deployment'), /expected time interval/);
});

test('malformed completion flags are rejected and an arbitrary release header is not shown', async () => {
  await assert.rejects(readProbe(response([event(0).replace('"complete":false','"complete":"false"')]), 0, () => {}), /unordered/);
  const res=response(Array.from({length:16},(_,i)=>event(i)));res.headers.set('x-control-usage-release','unexpected content');
  assert.equal((await readProbe(res, 0, () => {}, () => 3500)).release, null);
});
