import ConnectionSetup from '../shared/ConnectionSetup';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, ApiError, getSession, setCsrf, type Session } from '../shared/api';
import './portal.css';

type AccessState = 'none' | 'pending' | 'approved' | 'active' | 'rejected' | 'revoked';
interface Usage { model: string; state: string; requests: number; input_tokens: number; output_tokens: number; cache_creation_input_tokens: number; cache_read_input_tokens: number }
interface Access { state: AccessState; request?: { rpm: number; concurrency: number }; member?: { rpm: number; concurrency: number }; usage: Usage[] }
const descriptions: Record<AccessState, string> = {
  none: 'Request access to get a personal gateway token.',
  pending: 'Your request is waiting for administrator approval. Check back here for an update.',
  approved: 'Your request is approved. Retrieve your personal token to start using the gateway.',
  active: 'Your token is active. Use it in Claude Code with the settings shown here.',
  rejected: 'Your administrator declined this request. You can update the reason and submit again.',
  revoked: 'Your access has been revoked. Submit a new request if you need access again.',
};
export default function PortalApp() {
  const [session, setSession] = useState<Session | null>(null);
  const [access, setAccess] = useState<Access | null>(null);
  const [registering, setRegistering] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [token, setToken] = useState('');
  const [rotate, setRotate] = useState(false);
  const busyRef = useRef(false);
  const generation = useRef(0);
  const confirmButton = useRef<HTMLButtonElement>(null);
  const rotationTrigger = useRef<HTMLButtonElement>(null);
  const gateway = location.origin + (location.protocol === 'https:' ? '/gateway' : '');
  const limits = access?.state === 'active' ? access.member ?? access.request : access?.request;
  const settings = `export ANTHROPIC_BASE_URL="${gateway}"\nexport ANTHROPIC_AUTH_TOKEN="${token || '<your-member-key>'}"\nunset ANTHROPIC_API_KEY\nclaude`;
  function signedOut() {
    generation.current++;
    setCsrf(''); setSession(null); setAccess(null); setToken(''); setRotate(false); setNotice('');
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
    generation.current++; setToken(''); setCsrf(value.csrfToken); setSession(value);
    if (value.user.role === 'admin') { location.replace('/manage'); return false; }
    return true;
  }
  useEffect(() => {
    let alive = true;
    const abort = new AbortController();
    const expired = () => { signedOut(); setError('Your session expired. Please sign in again.'); };
    window.addEventListener('control-usage:session-expired', expired);
    getSession(abort.signal).then(async value => { if (alive && applySession(value)) await refresh(); })
      .catch(cause => { if (alive && !(cause instanceof ApiError && cause.status === 401)) setError(cause.message); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; abort.abort(); window.removeEventListener('control-usage:session-expired', expired); };
  }, []);
  useEffect(() => { if (rotate) confirmButton.current?.focus(); }, [rotate]);
  function closeRotation() { setRotate(false); rotationTrigger.current?.focus(); }
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
      // Keep the one-time token visible even when the follow-up refresh fails.
      setAccess(current => current ? { ...current, state: 'active' } : current);
      await refresh();
    });
  }
  async function copy(text: string) {
    try { await navigator.clipboard.writeText(text); setNotice('Copied to clipboard.'); }
    catch { setError('Copy is unavailable. Select and copy the text manually.'); }
  }
  return <>
    <a className="skip" href="#main">Skip to content</a>
    <header><a href="/" className="brand" style={{ display: 'flex', alignItems: 'center', gap: 10 }}><img src="/assets/control-usage.svg" width="32" height="32" alt="" />control-usage</a><a href="/manage" className="muted">Admin console ↗</a></header>
    <main id="main">
      {error && <div className="error" role="alert">{error}</div>}
      <p className="notice" role="status" aria-live="polite">{notice}</p>
      {loading ? <p role="status">Loading your account…</p> : !session ? <section className="welcome">
        <div className="intro"><div className="eyebrow">Your workspace access</div><h1>One account.<br />Your own access token.</h1><p>Request access to your team's Claude Code gateway, get your personal token, and keep track of your usage.</p><div className="steps">{['Create an account and request access', 'Your administrator reviews the request', 'Get your token and connect Claude Code'].map((text, i) => <div className="step" key={text}><span className="number">{i + 1}</span>{text}</div>)}</div></div>
        <div className="card"><div className="tabs" aria-label="Account access">{[false, true].map(create => <button type="button" key={String(create)} disabled={busy} aria-pressed={create === registering} onClick={() => { setRegistering(create); setError(''); }}>{create ? 'Create account' : 'Sign in'}</button>)}</div><h2>{registering ? 'Create your account' : 'Welcome back'}</h2><p className="muted">{registering ? 'Your administrator will approve gateway access separately.' : 'Sign in to view your access and usage.'}</p>
          <form key={String(registering)} onSubmit={submitAuth} aria-busy={busy}><fieldset disabled={busy} style={{ border: 0, padding: 0, margin: 0 }}>
            {registering && <><label htmlFor="displayName">Your name</label><input id="displayName" name="displayName" maxLength={55} autoComplete="name" required /></>}
            <label htmlFor="username">Username</label><input id="username" name="username" minLength={3} maxLength={40} pattern="[A-Za-z0-9][A-Za-z0-9_.\-]{2,39}" autoComplete="username" required />
            <label htmlFor="password">Password</label><input id="password" name="password" type="password" minLength={registering ? 12 : 1} maxLength={128} autoComplete={registering ? 'new-password' : 'current-password'} required />
            {registering && <p className="muted">Use at least 12 characters. No subscription credentials are needed here.</p>}
            <button className="wide">{busy ? 'Please wait…' : registering ? 'Create account' : 'Sign in'}</button>
          </fieldset></form>
        </div>
      </section> : <section id="portal" aria-busy={busy}>
        <div className="row"><div><div className="eyebrow">Member dashboard</div><h1>Hello, {session.user.displayName}</h1><p>@{session.user.username}</p></div><div className="actions"><span className="pill">ANTHROPIC</span><button disabled={busy} className="secondary" onClick={() => action(() => refresh())}>Refresh</button><button disabled={busy} className="secondary" onClick={() => action(async () => { await api('/portal/api/logout', { method: 'POST', body: '{}' }); signedOut(); })}>Sign out</button></div></div>
        <div className="grid"><section className="card"><div className="row"><h2>Your access</h2>{access && <span className="pill">{access.state.toUpperCase()}</span>}</div><p>{access ? descriptions[access.state] : 'Loading access…'}</p>
          {access && limits && ['approved', 'active'].includes(access.state) && <p className="muted">{limits.rpm} requests/minute · {limits.concurrency} concurrent requests</p>}
          <p className="muted">Forwarded requests, responses, and tool events are recorded for administrator review, subject to capture limits and retention. Recognized credentials are redacted; project code and other content may be retained.</p>
          {access && ['none', 'rejected', 'revoked'].includes(access.state) && <form onSubmit={event => { event.preventDefault(); const form = event.currentTarget; const reason = new FormData(form).get('reason'); void action(async () => { await api('/portal/api/key-requests', { method: 'POST', body: JSON.stringify({ reason }) }); form.reset(); await refresh(); }); }}><label htmlFor="reason">What will you use this access for?</label><textarea id="reason" name="reason" maxLength={500} required disabled={busy} placeholder="Tell your administrator about your project or team." /><button disabled={busy} className="wide">Request access</button></form>}
          {access?.state === 'approved' && <button disabled={busy} onClick={() => issue(false)}>Get my auth token</button>}
          {access?.state === 'active' && <button ref={rotationTrigger} disabled={busy} className="secondary" onClick={() => setRotate(true)}>Rotate token</button>}
          {access && ['approved', 'active'].includes(access.state) && <p className="muted">Tokens are shown once. Rotating immediately invalidates your previous token.</p>}
        </section><section className="card"><h2>Connect your terminal</h2><ConnectionSetup gateway={gateway} /><details><summary>Temporary setup · this terminal only</summary><p className="muted">After approval, use your personal token with these settings.</p><pre>{settings}</pre><button className="secondary" onClick={() => copy(settings)}>Copy settings</button><p className="muted">Your requests are forwarded to Anthropic through control-usage.</p></details></section></div>
        {token && <section className="card success"><div className="row"><h2>Your ANTHROPIC_AUTH_TOKEN</h2><div className="actions"><button onClick={() => copy(token)}>Copy token</button><button className="secondary" onClick={() => setToken('')}>I saved it · Hide</button></div></div><p>Save this token now. It cannot be retrieved again after you leave this page. Keep it private.</p><pre>{token}</pre></section>}
        <section className="card" style={{ marginTop: 24 }}><h2>Your usage · Last 30 days</h2><p className="muted">Observed tokens for your account. This is not a subscription balance or a bill.</p><div className="scroll"><table><thead><tr>{['Model', 'State', 'Requests', 'Input', 'Output', 'Cache write', 'Cache read'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{access?.usage.map((row, index) => <tr key={`${row.model}-${row.state}-${index}`}>{[row.model, row.state, row.requests, row.input_tokens, row.output_tokens, row.cache_creation_input_tokens, row.cache_read_input_tokens].map((value, i) => <td key={i}>{typeof value === 'number' ? value.toLocaleString() : value}</td>)}</tr>)}{access && !access.usage.length && <tr><td colSpan={7}>No requests yet.</td></tr>}</tbody></table></div></section>
      </section>}
    </main><footer>control-usage · Access is approved by your administrator.</footer>
    {rotate && <div className="dialog-backdrop"><section className="card" role="dialog" aria-modal="true" aria-labelledby="rotate-title" onKeyDown={event => { if (event.key === 'Escape' && !busy) closeRotation(); if (event.key === 'Tab') { const buttons = event.currentTarget.querySelectorAll<HTMLButtonElement>('button:not(:disabled)'); if (!buttons.length) { event.preventDefault(); return; } if (event.shiftKey && document.activeElement === buttons[0]) { event.preventDefault(); buttons[buttons.length - 1].focus(); } else if (!event.shiftKey && document.activeElement === buttons[buttons.length - 1]) { event.preventDefault(); buttons[0].focus(); } } }}><h2 id="rotate-title">Rotate your token?</h2><p>Your current token will stop working immediately. Update every terminal that uses it with your new token.</p><div className="actions"><button ref={confirmButton} disabled={busy} className="secondary" onClick={closeRotation}>Cancel</button><button disabled={busy} onClick={() => issue(true)}>{busy ? 'Rotating…' : 'Rotate token'}</button></div></section></div>}
  </>;
}
