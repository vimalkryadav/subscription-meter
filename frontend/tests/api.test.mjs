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
test('parallel expired requests share one refresh and retry with stable CSRF', async () => {
  const original = globalThis.fetch, oldWindow = globalThis.window;
  try {
    const events = [], attempts = new Map(); let refreshes = 0;
    globalThis.window = { dispatchEvent: event => events.push(event.type) };
    setCsrf('renewed');
    globalThis.fetch = async (path, options) => {
      if (path.endsWith('/session')) return new Response('', { status: 401 });
      if (path.endsWith('/refresh')) {
        refreshes++;
        await new Promise(resolve => setTimeout(resolve, 10));
        assert.equal(options.headers.get('X-Portal-Persistent'), '1');
        return Response.json({ csrfToken: 'renewed' });
      }
      const count = (attempts.get(path) || 0) + 1; attempts.set(path, count);
      if (count === 1) return new Response('', { status: 401 });
      assert.equal(options.headers.get('X-CSRF-Token'), 'renewed');
      assert.equal(count, 2);
      return Response.json({ ok: true });
    };
    const values = await Promise.all(Array.from({ length: 20 }, (_, n) => api('/admin/fixture/' + n, { method: 'POST', body: '{}' })));
    assert.equal(values.length, 20); assert.equal(refreshes, 1); assert.deepEqual(events, []);
  } finally { globalThis.fetch = original; globalThis.window = oldWindow; setCsrf(''); }
});
test('a transient renewal failure preserves login and a failed mutation is never replayed', async () => {
  const original = globalThis.fetch, oldWindow = globalThis.window;
  try {
    const events = [], calls = [];
    globalThis.window = { dispatchEvent: event => events.push(event.type) };
    setCsrf('still-valid-login');
    globalThis.fetch = async path => { calls.push(path); return new Response('', { status: path.endsWith('/refresh') ? 503 : 401 }); };
    await assert.rejects(api('/admin/status'), error => error.status === 503);
    assert.deepEqual(events, []);
    assert.deepEqual(calls, ['/admin/status', '/portal/api/session', '/portal/api/refresh']);
    calls.length = 0;
    globalThis.fetch = async (path, options) => {
      calls.push(path); assert.equal(options.headers.get('X-CSRF-Token'), 'still-valid-login');
      return new Response('', { status: 500 });
    };
    await assert.rejects(api('/admin/members', { method: 'POST', body: '{}' }), error => error.status === 500);
    assert.deepEqual(calls, ['/admin/members']);
  } finally { globalThis.fetch = original; globalThis.window = oldWindow; setCsrf(''); }
});
test('logout waits for an in-flight rotation and clears the renewed family', async () => {
  const original = globalThis.fetch; let finish, started;
  const rotating = new Promise(resolve => { started = resolve; });
  const wait = new Promise(resolve => { finish = resolve; });
  try {
    let renewed = false; const calls = [];
    globalThis.fetch = async (path, options) => {
      calls.push(path);
      if (path.endsWith('/refresh')) { started(); await wait; renewed = true; return Response.json({ csrfToken: 'next' }); }
      if (path.endsWith('/logout')) { assert.equal(renewed, true); assert.equal(options.headers.get('X-CSRF-Token'), 'next'); return Response.json({ signedOut: true }); }
      return renewed ? Response.json({ ok: true }) : new Response('', { status: 401 });
    };
    const request = api('/admin/status'); await rotating;
    const logout = api('/portal/api/logout', { method: 'POST', body: '{}' });
    await Promise.resolve(); assert.equal(calls.includes('/portal/api/logout'), false);
    finish(); await Promise.all([request, logout]);
    assert.equal(calls.filter(path => path.endsWith('/refresh')).length, 1);
    globalThis.fetch = async (_, options) => { assert.equal(options.headers.get('X-CSRF-Token'), ''); return Response.json({}); };
    await api('/portal/api/session');
  } finally { finish(); globalThis.fetch = original; setCsrf(''); }
});
test('another tab renewing cookies is reused without rotating again', async () => {
  const original = globalThis.fetch; let attempts = 0;
  try {
    globalThis.fetch = async (path, options) => {
      assert.notEqual(path, '/portal/api/refresh');
      if (path.endsWith('/session')) return Response.json({ csrfToken: 'other-tab' });
      if (++attempts === 1) return new Response('', { status: 401 });
      assert.equal(options.headers.get('X-CSRF-Token'), 'other-tab'); return Response.json({ ok: true });
    };
    assert.deepEqual(await api('/admin/status'), { ok: true }); assert.equal(attempts, 2);
  } finally { globalThis.fetch = original; setCsrf(''); }
});
test('a late session response cannot restore a login after sign-out or replace a newer login', async () => {
  const original = globalThis.fetch;
  try {
    for (const next of ['', 'new-login']) {
      setCsrf('old-login'); let finish;
      globalThis.fetch = () => new Promise(resolve => { finish = resolve; });
      const pending = getSession(); setCsrf(next);
      finish(Response.json({ csrfToken: 'old-login', user: { role: 'member' } }));
      await assert.rejects(pending, error => error.name === 'AbortError');
      globalThis.fetch = async (_, options) => { assert.equal(options.headers.get('X-CSRF-Token'), next); return Response.json({}); };
      await api('/admin/status');
    }
  } finally { globalThis.fetch = original; setCsrf(''); }
});
test('same-family session reads cannot suppress renewal of a delayed expiry rejection', async () => {
  const original = globalThis.fetch, oldWindow = globalThis.window;
  try {
    setCsrf('family'); let finish, current = false, refreshes = 0; const events = [];
    globalThis.window = { dispatchEvent: event => events.push(event.type) };
    globalThis.fetch = async path => {
      if (path.endsWith('/status') && !current) return new Promise(resolve => { finish = resolve; });
      if (path.endsWith('/session')) return new Response('', { status: 401 });
      if (path.endsWith('/refresh')) { refreshes++; current = true; return Response.json({ csrfToken: 'family' }); }
      return Response.json({ ok: true });
    };
    const pending = api('/admin/status'); setCsrf('family');
    finish(new Response('', { status: 401 }));
    assert.deepEqual(await pending, { ok: true }); assert.equal(refreshes, 1); assert.deepEqual(events, []);
  } finally { globalThis.fetch = original; globalThis.window = oldWindow; setCsrf(''); }
});
