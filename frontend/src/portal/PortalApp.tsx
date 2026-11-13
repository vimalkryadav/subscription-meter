import ConnectionSetup from '../shared/ConnectionSetup';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError, getSession, setCsrf, type Session } from '../shared/api';
import './portal.css';

type AccessState = 'none' | 'pending' | 'approved' | 'active' | 'rejected' | 'revoked';
interface Usage { model: string; state: string; requests: number; input_tokens: number; output_tokens: number; cache_creation_input_tokens: number; cache_read_input_tokens: number }
interface Access { state: AccessState; request?: { rpm: number; concurrency: number }; member?: { rpm: number; concurrency: number }; usage: Usage[] }
const descriptions: Record<AccessState, string> = {
  none: 'Tell your administrator what you need access for. Once approved, you can create your personal token.',
  pending: 'Your administrator is reviewing your request. Refresh to check for an update.',
  approved: 'You’re approved. Create your personal token, then connect Claude Code.',
  active: 'Your personal token is ready to use with Claude Code.',
  rejected: 'Your administrator declined your request. You can update the reason and submit again.',
  revoked: 'Your access was revoked. Submit a new request if you need access again.',
};
const titles: Record<AccessState, string> = { none: 'Request access', pending: 'Awaiting approval', approved: 'Get your token', active: 'Your access', rejected: 'Request access again', revoked: 'Request access again' };
export default function PortalApp() {
  const [session, setSession] = useState<Session | null>(null);
  const [access, setAccess] = useState<Access | null>(null);
  const [registering, setRegistering] = useState(false);
  const [loading, setLoading] = useState(true);
  const [sessionFailed, setSessionFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [token, setToken] = useState('');
  const [rotate, setRotate] = useState(false);
  const busyRef = useRef(false);
  const generation = useRef(0);
  const confirmButton = useRef<HTMLButtonElement>(null);
  const rotationTrigger = useRef<HTMLButtonElement>(null);
  const rotationWasOpen = useRef(false);
  const tokenHeading = useRef<HTMLHeadingElement>(null);
  const gateway = location.origin + (location.protocol === 'https:' ? '/gateway' : '');
  const limits = access?.state === 'active' ? access.member ?? access.request : access?.request;
  const usage = access?.usage ?? [];
  const totals = usage.reduce((sum, row) => ({ requests: sum.requests + row.requests, input: sum.input + row.input_tokens, output: sum.output + row.output_tokens }), { requests: 0, input: 0, output: 0 });
  function signedOut() {
    generation.current++;
    setCsrf(''); setSessionFailed(false); setSession(null); setAccess(null); setToken(''); setRotate(false); setNotice('');
  }
  async function refresh(expected = generation.current) {
    const data = await api<{ access: Access }>('/portal/api/access');
    if (expected !== generation.current) return;
    setAccess(data.access);
    if (data.access.state !== 'active') setToken('');
  }
  async function action(work: () => Promise<void>) {
    if (busyRef.current) return;
    busyRef.current = true; setBusy(true); setError(''); setNotice('');
    try { await work(); } catch (cause) { setError(cause instanceof Error ? cause.message : 'Request failed.'); }
    finally { busyRef.current = false; setBusy(false); }
  }
  function applySession(value: Session) {
    generation.current++; setSessionFailed(false); setToken(''); setAccess(null); setCsrf(value.csrfToken); setSession(value);
    if (value.user.role === 'admin') { location.replace('/manage'); return false; }
    return true;
  }
  useEffect(() => {
    let alive = true;
    const abort = new AbortController();
    const expired = () => { signedOut(); setError('Please sign in again to continue.'); };
    window.addEventListener('control-usage:session-expired', expired);
    getSession(abort.signal).then(async value => { if (alive && applySession(value)) await refresh(); })
      .catch(cause => { if (alive && !(cause instanceof ApiError && cause.status === 401)) { setError(cause.message); setSessionFailed(true); } })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; abort.abort(); window.removeEventListener('control-usage:session-expired', expired); };
  }, []);
  useEffect(() => {
    if (rotate && !busy) confirmButton.current?.focus();
    else if (!rotate && rotationWasOpen.current) rotationTrigger.current?.focus();
    rotationWasOpen.current = rotate;
  }, [rotate, busy]);
  useEffect(() => {
    if (!rotate) return;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = overflow; };
  }, [rotate]);
  useEffect(() => { if (token) tokenHeading.current?.focus(); }, [token]);
  function closeRotation() { setRotate(false); }
  async function retrySession() {
    await action(async () => {
      setLoading(true);
      try { const value = await getSession(); if (applySession(value)) await refresh(); }
      catch (cause) { if (cause instanceof ApiError && cause.status === 401) signedOut(); else throw cause; }
      finally { setLoading(false); }
    });
  }
  async function submitAuth(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    await action(async () => {
      const body = { username: data.get('username'), password: data.get('password'), ...(registering ? { displayName: data.get('displayName') } : {}) };
      const value = await api<Session>(`/portal/api/${registering ? 'register' : 'login'}`, { method: 'POST', body: JSON.stringify(body) });
      form.reset();
      if (applySession(value)) await refresh();
    });
  }
  async function issue(rotating: boolean) {
    await action(async () => {
      const issued = await api<{ key: string }>(`/portal/api/token${rotating ? '/rotate' : ''}`, { method: 'POST', body: '{}' });
      setToken(issued.key); setRotate(false);
      // The one-time token must survive a failed follow-up refresh.
      setAccess(current => current ? { ...current, state: 'active' } : current);
      await refresh();
    });
  }
  async function copy(text: string) {
    try { await navigator.clipboard.writeText(text); setNotice('Token copied. Paste it at the setup command’s hidden prompt.'); }
    catch { setError('Copy is unavailable. Select and copy the token manually.'); }
  }
  const waiting = loading || session?.user.role === 'admin' || Boolean(session && !access && busy);
  return <>
    <a className="skip" href="#main" tabIndex={rotate ? -1 : undefined}>Skip to content</a>
    <header className="portal-header" inert={rotate}><a href="/" className="brand"><img src="/assets/control-usage.svg" width="30" height="30" alt="" />control-usage</a>{session && session.user.role !== 'admin' && <button disabled={busy} className="secondary" onClick={() => action(async () => { await api('/portal/api/logout', { method: 'POST', body: '{}' }); signedOut(); })}>Sign out</button>}</header>
    <main id="main" inert={rotate}>
      {error && !rotate && <div className="error" role="alert">{error}</div>}
      <div role="status" aria-live="polite">{notice && <p className="notice">{notice}</p>}</div>
      {waiting ? <div className="portal-loading" role="status">Loading…</div> : sessionFailed && !session ? <section className="card account-retry"><h1>Couldn’t load your account</h1><p>Check your connection and try again.</p><button disabled={busy} onClick={retrySession}>Try again</button></section> : !session ? <section className="welcome">
        <div className="intro"><div className="eyebrow">Claude Code for your team</div><h1>Your work.<br />Your access.</h1><p>Connect your terminal with a personal token and see your usage in one place.</p><p className="muted">New here? Create an account, request access, and connect after your administrator approves it.</p></div>
        <section className="card auth-card" aria-labelledby="auth-title"><div className="tabs" role="group" aria-label="Account access">{[false, true].map(create => <button type="button" key={String(create)} disabled={busy} aria-pressed={create === registering} onClick={() => { setRegistering(create); setError(''); }}>{create ? 'Create account' : 'Sign in'}</button>)}</div><h2 id="auth-title">{registering ? 'Create your account' : 'Welcome back'}</h2><p className="muted">{registering ? 'Gateway access is approved separately.' : 'Sign in to manage your token and usage.'}</p>
          <form key={String(registering)} onSubmit={submitAuth} aria-busy={busy}><fieldset disabled={busy}>
            {registering && <><label htmlFor="displayName">Your name</label><input id="displayName" name="displayName" maxLength={55} autoComplete="name" required /></>}
            <label htmlFor="username">Username</label><input id="username" name="username" minLength={3} maxLength={40} pattern="[A-Za-z0-9][A-Za-z0-9_.\-]{2,39}" autoComplete="username" autoCapitalize="none" spellCheck={false} required />
            <label htmlFor="password">Password</label><input id="password" name="password" type="password" minLength={registering ? 12 : 1} maxLength={128} autoComplete={registering ? 'new-password' : 'current-password'} aria-describedby={registering ? 'password-help' : undefined} required />
            {registering && <p id="password-help" className="muted">At least 12 characters. Use a password for this platform.</p>}
            <button className="wide">{busy ? 'Loading…' : registering ? 'Create account' : 'Sign in'}</button>
          </fieldset></form>
        </section>
      </section> : <section id="portal">
        <div className="page-heading"><div><div className="eyebrow">Your workspace</div><h1>{session.user.displayName}</h1><p className="muted">@{session.user.username}</p></div><button disabled={busy} className="secondary" onClick={() => action(() => refresh())}>{busy ? 'Loading…' : 'Refresh'}</button></div>
        {!access ? <section className="card"><h2>Unable to load your access</h2><p>Use Refresh to try again.</p></section> : <>
          <section className="card access-card" aria-labelledby="access-title"><div className="row"><h2 id="access-title">{titles[access.state]}</h2><span className={`pill state-${access.state}`}>{access.state === 'none' ? 'Not requested' : access.state}</span></div><p>{descriptions[access.state]}</p>
            {limits && ['approved', 'active'].includes(access.state) && <p className="muted">{limits.rpm} requests/minute · {limits.concurrency} concurrent requests</p>}
            {['none', 'rejected', 'revoked'].includes(access.state) && <form className="access-request" onSubmit={event => { event.preventDefault(); const form = event.currentTarget; const reason = new FormData(form).get('reason'); void action(async () => { await api('/portal/api/key-requests', { method: 'POST', body: JSON.stringify({ reason }) }); form.reset(); await refresh(); }); }}><label htmlFor="reason">What will you use this access for?</label><textarea id="reason" name="reason" maxLength={500} required disabled={busy} placeholder="Describe your project or team." /><button disabled={busy}>{busy ? 'Loading…' : 'Request access'}</button></form>}
            {access.state === 'approved' && <><button disabled={busy} onClick={() => issue(false)}>{busy ? 'Loading…' : 'Create my token'}</button><p className="muted">Your token is shown once. Keep it private.</p></>}
            {access.state === 'active' && <button ref={rotationTrigger} disabled={busy} className="secondary" onClick={() => { setError(''); setRotate(true); }}>Replace token</button>}
            {token && <section className="token-reveal" aria-labelledby="token-title"><h3 id="token-title" ref={tokenHeading} tabIndex={-1}>Save your new token</h3><p>Shown once. Copy it now, then paste it at the setup command’s hidden prompt. Keep it private.</p><pre tabIndex={0} aria-label="Your one-time token">{token}</pre><div className="actions"><button onClick={() => copy(token)}>Copy token</button><button className="secondary" onClick={() => { setToken(''); rotationTrigger.current?.focus(); }}>I saved it · Hide</button></div></section>}
            <details className="capture-details"><summary>Requests and responses are recorded</summary><p>Forwarded requests, responses, and tool events are recorded for administrator review, subject to capture limits and retention. Recognized credentials are redacted; project code and other content may be retained.</p></details>
          </section>
          {access.state === 'active' && <section className="card setup-card"><h2>Connect Claude Code</h2><ConnectionSetup gateway={gateway} /></section>}
          {(access.state === 'active' || usage.length > 0) && <section className="card usage-card" aria-labelledby="usage-title"><div className="row"><h2 id="usage-title">Your usage</h2><span className="muted">Last 30 days</span></div>{usage.length > 0 ? <><dl className="usage-summary"><div><dt>Requests</dt><dd>{totals.requests.toLocaleString()}</dd></div><div><dt>Input tokens</dt><dd>{totals.input.toLocaleString()}</dd></div><div><dt>Output tokens</dt><dd>{totals.output.toLocaleString()}</dd></div></dl><details><summary>Usage by model and result</summary><div className="scroll" tabIndex={0} role="region" aria-label="Usage by model"><table><thead><tr>{['Model', 'Result', 'Requests', 'Input', 'Output', 'Cache write', 'Cache read'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{usage.map((row, index) => <tr key={`${row.model}-${row.state}-${index}`}>{[row.model, row.state, row.requests, row.input_tokens, row.output_tokens, row.cache_creation_input_tokens, row.cache_read_input_tokens].map((value, i) => <td key={i}>{typeof value === 'number' ? value.toLocaleString() : value}</td>)}</tr>)}</tbody></table></div></details><p className="muted">Observed tokens, not your remaining subscription allowance or a bill. Cache tokens are listed in the breakdown.</p></> : <p>Your usage will appear after your first gateway request.</p>}</section>}
        </>}
      </section>}
    </main><footer inert={rotate}>control-usage · Team access to Claude Code</footer>
    {rotate && <div className="dialog-backdrop"><section className="card" role="dialog" aria-modal="true" aria-labelledby="rotate-title" aria-describedby="rotate-description" onKeyDown={event => { if (event.key === 'Escape' && !busy) closeRotation(); if (event.key === 'Tab') { const buttons = event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'); if (!buttons.length) { event.preventDefault(); return; } if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons[buttons.length - 1].focus(); } else if (!event.shiftKey && document.activeElement === buttons[buttons.length - 1]) { event.preventDefault(); buttons[0].focus(); } } }}><h2 id="rotate-title">Replace your token?</h2>{error && <div className="error" role="alert">{error}</div>}<p id="rotate-description">Your current token will stop working immediately. Run setup with the new token on every device that uses it, then restart existing Claude sessions.</p><div className="actions"><button ref={confirmButton} disabled={busy} className="secondary" onClick={closeRotation}>Cancel</button><button disabled={busy} onClick={() => issue(true)}>{busy ? 'Loading…' : 'Replace token'}</button></div></section></div>}
  </>;
}
