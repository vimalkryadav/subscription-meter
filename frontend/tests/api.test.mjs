import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
const source = await readFile(new URL('../src/shared/api.ts', import.meta.url), 'utf8');
const compiled = stripTypeScriptTypes(source);
const { api, getSession, setCsrf, ApiError } = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`);
test('session CSRF and cookie credentials reach mutations; transport never returns error bodies', async () => {
  const original = globalThis.fetch;
  try {
    const calls = [];
    globalThis.fetch = async (path, options) => {
      calls.push({ path, options });
      return new Response(JSON.stringify(path.endsWith('/session') ? { user: { role: 'member' }, csrfToken: 'fixture-csrf' } : { ok: true }));
    };
    await getSession();
    assert.deepEqual(await api('/portal/api/token', { method: 'POST', body: '{}' }), { ok: true });
    const { options } = calls[1];
    assert.equal(options.credentials, 'same-origin');
    assert.equal(options.cache, 'no-store');
    assert.equal(options.headers.get('X-CSRF-Token'), 'fixture-csrf');
    assert.equal(options.headers.get('X-Portal-Request'), '1');
    assert.equal(options.headers.get('Content-Type'), 'application/json');
    globalThis.fetch = async () => new Response('sensitive server error', { status: 500 });
    await assert.rejects(api('/admin/status'), error => error instanceof ApiError && error.status === 500 && !error.message.includes('sensitive'));
  } finally { globalThis.fetch = original; setCsrf(''); }
});
test('expired sessions clear CSRF and signal the shell; failed login does not discard login form', async () => {
  const original = globalThis.fetch, oldWindow = globalThis.window;
  try {
    const events = [];
    globalThis.window = { dispatchEvent: event => events.push(event.type) };
    globalThis.fetch = async () => new Response('', { status: 401 });
    setCsrf('old');
    await assert.rejects(api('/portal/api/login', { method: 'POST', body: '{}' }));
    assert.deepEqual(events, []);
    await assert.rejects(api('/admin/status'));
    assert.deepEqual(events, ['control-usage:session-expired']);
    globalThis.fetch = async (_, options) => { assert.equal(options.headers.get('X-CSRF-Token'), ''); return new Response(null, { status: 204 }); };
    assert.equal(await api('/portal/api/logout', { method: 'POST', body: '{}' }), undefined);
  } finally { globalThis.fetch = original; globalThis.window = oldWindow; }
});
test('external destinations are rejected before any network request', async () => {
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => { throw new Error('must not fetch'); };
    for (const path of ['https://elsewhere.example/', '//elsewhere.example/', '/\\elsewhere.example/', '/\n/elsewhere.example/'])
      await assert.rejects(api(path), /same-origin API path/);
  } finally { globalThis.fetch = original; }
});
test('a superseded session lookup cannot replace the active CSRF token', async () => {
  const original = globalThis.fetch;
  try {
    let resolve;
    globalThis.fetch = () => new Promise(done => { resolve = done; });
    const abort = new AbortController();
    const pending = getSession(abort.signal);
    abort.abort();
    setCsrf('current-session');
    resolve(new Response(JSON.stringify({ user: { role: 'member' }, csrfToken: 'superseded-session' })));
    await assert.rejects(pending, error => error.name === 'AbortError');
    globalThis.fetch = async (_, options) => {
      assert.equal(options.headers.get('X-CSRF-Token'), 'current-session');
      return new Response(null, { status: 204 });
    };
    await api('/portal/api/logout', { method: 'POST', body: '{}' });
  } finally { globalThis.fetch = original; setCsrf(''); }
});
