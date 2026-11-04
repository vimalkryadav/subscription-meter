import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
async function load(name) {
  const source = await readFile(new URL('../src/admin/' + name + '.ts', import.meta.url), 'utf8');
  return import(`data:text/javascript;base64,${Buffer.from(stripTypeScriptTypes(source)).toString('base64')}`);
}
const { startResource } = await load('resource');
const { emptyFilters, presetWindow, readFilters, searchWindow, searchParams, captureMessages } = await load('search');
const { date } = await load('format');
const flush = () => new Promise(resolve => setImmediate(resolve));

test('superseded search/detail resources abort and cannot reveal old payloads or errors', async () => {
  const pending = [], visible = [], errors = [], completions = [];
  const fetcher = (path, options) => new Promise((resolve, reject) => pending.push({ path, options, resolve, reject }));
  const handlers = id => ({ success: value => visible.push(value), failure: error => errors.push(error), complete: () => completions.push(id) });
  const stopOld = startResource('/admin/request-search/old', fetcher, handlers('old'));
  stopOld();
  assert.equal(pending[0].options.signal.aborted, true);
  const stopCurrent = startResource('/admin/request-search/new', fetcher, handlers('new'));
  pending[1].resolve({ request_body: 'selected request' }); await flush();
  pending[0].resolve({ request_body: 'previous private request' }); await flush();
  assert.deepEqual(visible, [{ request_body: 'selected request' }]);
  assert.deepEqual(completions, ['new']); assert.deepEqual(errors, []);
  stopCurrent();
  const stopFailed = startResource('/admin/request-search/abandoned', fetcher, handlers('abandoned'));
  stopFailed(); pending[2].reject(new Error('old failure')); await flush();
  assert.deepEqual(errors, []); assert.deepEqual(completions, ['new']);
});

test('current failures reach the resource view and settle its busy state', async () => {
  const errors = [], completions = [];
  startResource('/admin/metrics', async () => { throw new Error('fixture network failure'); }, { success: () => assert.fail('no successful response'), failure: error => errors.push(error.message), complete: () => completions.push(true) });
  await flush(); assert.deepEqual(errors, ['fixture network failure']); assert.deepEqual(completions, [true]);
});

test('request paging preserves its fixed window and opaque cursors without offset or payload search', () => {
  const filters = { ...emptyFilters(), model: 'sonnet fixture', request_body: 'private input must not enter URL' };
  const range = searchWindow(filters, Date.parse('2026-11-04T12:00:00Z'));
  const first = searchParams(filters, range), next = searchParams(filters, range, 'opaque/+=='), previous = searchParams(filters, range);
  assert.equal(next.get('cursor'), 'opaque/+==');
  assert.equal(next.get('from'), first.get('from')); assert.equal(next.get('to'), first.get('to'));
  assert.equal(next.has('offset'), false); assert.equal(next.has('request_body'), false);
  assert.equal(previous.has('cursor'), false); assert.equal(previous.toString(), first.toString());
  assert.equal(first.get('model'), 'sonnet fixture'); assert.equal(first.get('limit'), '25');
});

test('window presets clear prior custom bounds while URL restoration validates dates', () => {
  const restored = readFilters('?rq_days=bad&rq_from=not-a-date&rq_to=2026-11-04T00%3A00%3A00Z&rq_captureState=incomplete&rq_request_body=private');
  assert.equal(restored.days, '7'); assert.equal(restored.from, ''); assert.equal(restored.captureState, 'incomplete');
  assert.equal(Object.hasOwn(restored, 'request_body'), false);
  const changed = presetWindow({ ...restored, from: '2026-10-04T00:00:00Z' }, '1');
  const range = searchWindow(changed, Date.parse('2026-11-05T00:00:00Z'));
  assert.equal(changed.from, ''); assert.equal(changed.to, '');
  assert.equal(range.from, '2026-11-04T00:00:00.000Z'); assert.equal(range.to, '2026-11-05T00:00:00.000Z');
  assert.throws(() => searchWindow({ ...emptyFilters(), from: range.to, to: range.from }), /start before/);
});

test('capture incomplete/pending/expired are distinct and chart epochs render as valid dates', () => {
  assert.match(captureMessages.incomplete, /interrupted/); assert.match(captureMessages.pending, /pending/); assert.match(captureMessages.expired, /retention/);
  assert.notEqual(captureMessages.incomplete, captureMessages.available);
  const epoch = 1793793600000;
  assert.equal(date(epoch), new Date(epoch).toLocaleString()); assert.equal(date(0), new Date(0).toLocaleString());
  assert.equal(date('not a date'), '—'); assert.equal(date(null), '—');
});
