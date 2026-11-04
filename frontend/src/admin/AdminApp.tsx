import { useEffect, useState } from 'react';
import { ApiError, getSession } from '../shared/api';
import type { Session } from '../shared/api';
import Metrics from './Metrics';
import RequestExplorer from './RequestExplorer';
import Accounting from './Accounting';
import { Accounts, Approvals, Members } from './Management';
import { Heading, Notice, Panel, pretty, text, useMutation, useResource } from './ui';
import type { Row } from './ui';
import './admin.css';

const pages = [{ id: 'metrics', label: 'Metrics', icon: '◴' }, { id: 'activity', label: 'Request explorer', icon: '⌕' }, { id: 'accounting', label: 'Usage & value', icon: '◈' }, { id: 'members', label: 'Members & keys', icon: '⚿' }, { id: 'approvals', label: 'Access requests', icon: '↳' }, { id: 'accounts', label: 'Accounts & sessions', icon: '◎' }, { id: 'connection', label: 'Connection', icon: '⌁' }];
function currentPage() { const id = location.hash.replace(/^#(?:view\/)?/, ''); return pages.some(page => page.id === id) ? id : 'metrics'; }
function Brand() { return <a className="cu-brand" href="/manage"><svg width="32" height="32" viewBox="0 0 64 64" fill="none" aria-hidden="true"><rect x="1" y="1" width="62" height="62" rx="16" fill="#141d27" stroke="#293849" strokeWidth="2" /><path d="M43 16a21 21 0 1 0 0 32" stroke="#6a9fff" strokeWidth="5" strokeLinecap="round" /><path d="M28 39V30M38 39V23M48 39V17" stroke="#edf3fa" strokeWidth="5" strokeLinecap="round" /><circle cx="48" cy="17" r="4" fill="#6a9fff" /></svg><span>control-usage</span></a>; }
export default function AdminApp() {
  const [session, setSession] = useState<Session | null>(null), [authError, setAuthError] = useState(''), [retry, setRetry] = useState(0), [page, setPage] = useState(currentPage);
  const logout = useMutation();
  useEffect(() => {
    let current = true; const abort = new AbortController(); setAuthError('');
    getSession(abort.signal).then(value => { if (!current) return; if (value.user.role !== 'admin') { location.replace('/'); return; } setSession(value); }).catch(failure => {
      if (!current) return;
      if (failure instanceof ApiError && (failure.status === 401 || failure.status === 403)) location.replace('/');
      else setAuthError(failure instanceof Error ? failure.message : 'Could not check your session.');
    });
    return () => { current = false; abort.abort(); };
  }, [retry]);
  useEffect(() => {
    const expired = () => { setSession(null); location.replace('/'); };
    const navigate = () => { setPage(currentPage()); };
    addEventListener('control-usage:session-expired', expired); addEventListener('hashchange', navigate); addEventListener('popstate', navigate);
    return () => { removeEventListener('control-usage:session-expired', expired); removeEventListener('hashchange', navigate); removeEventListener('popstate', navigate); };
  }, []);
  useEffect(() => { document.title = (pages.find(item => item.id === page)?.label || 'Admin') + ' · control-usage'; }, [page]);
  return <div className="cu-admin"><a className="cu-skip" href="#admin-content" onClick={event => { event.preventDefault(); document.getElementById('admin-content')?.focus(); }}>Skip to content</a><header className="cu-topbar"><Brand /><div className="cu-account">{session ? <><span>Administrator · @{session.user.username}</span><button disabled={logout.busy} onClick={async () => { try { await logout.run('/portal/api/logout', 'POST', {}); setSession(null); location.replace('/'); } catch { /* error below */ } }}>Sign out</button></> : <a href="/">Member portal</a>}</div></header>
    {!session ? <main className="cu-auth-state"><h1>Administrator access</h1><Notice error>{authError}</Notice>{authError ? <button onClick={() => setRetry(value => value + 1)}>Retry session check</button> : <p role="status">Checking your account…</p>}</main> : <>
      <aside className="cu-sidebar"><p className="cu-eyebrow">WORKSPACE</p><nav aria-label="Admin navigation">{pages.map(item => <a key={item.id} href={'#view/' + item.id} aria-current={page === item.id ? 'page' : undefined}><span aria-hidden="true">{item.icon}</span>{item.label}</a>)}</nav><div className="cu-sidebar-footer"><span className="cu-badge">GATEWAY</span><a href="/">Member portal ↗</a><p>Observed usage.<br />Not subscription billing.</p></div></aside>
      <main id="admin-content" className="cu-content" tabIndex={-1}><Notice error>{logout.error}</Notice>{page === 'metrics' && <Metrics />}{page === 'activity' && <RequestExplorer />}{page === 'accounting' && <Accounting />}{page === 'members' && <Members />}{page === 'approvals' && <Approvals />}{page === 'accounts' && <Accounts />}{page === 'connection' && <Connection />}</main>
    </>}
  </div>;
}

function Connection() {
  const status = useResource<Row>('/admin/status'), members = useResource<Row[]>('/admin/members');
  const base = location.origin + (location.protocol === 'https:' ? '/gateway' : '');
  const data = status.data;
  return <><Heading title="Connection" subtitle="Connect a member’s terminal to the gateway."><button disabled={status.loading} onClick={status.refresh}>Refresh status</button></Heading><Notice error>{status.error || members.error}</Notice>
    {members.data && !members.data.some(member => member.active) && <Notice>No active member keys. <a href="#view/members">Create a member key</a> to send requests through the gateway.</Notice>}
    <Panel title="Claude Code settings"><pre>{'export ANTHROPIC_BASE_URL="' + base + '"\nexport ANTHROPIC_AUTH_TOKEN="<your-member-key>"\nunset ANTHROPIC_API_KEY\nclaude'}</pre><p className="cu-muted">Use the member’s platform key. The upstream credential stays on the server. Captured inputs, outputs, and tool events are available to the administrator, subject to capture limits and retention.</p></Panel>
    <Panel title="Gateway status">{data ? <><dl className="cu-coverage"><div><dt>Subscription compatibility</dt><dd>{text(data.subscriptionCompatibility)}</dd></div><div><dt>Upstream credential configured</dt><dd>{data.credentialConfigured ? 'Yes' : 'No'}</dd></div><div><dt>Audit readiness</dt><dd>{data.auditReady ? 'Ready' : 'Not ready'}</dd></div><div><dt>Capture policy</dt><dd>{text(data.capturePolicy)}</dd></div><div><dt>Detail retention</dt><dd>{text(data.detailRetentionDays)} days</dd></div><div><dt>Runtime</dt><dd>Java {text(data.runtime)} · {text(data.thread)} threads</dd></div></dl><details><summary>All status metadata</summary><pre>{pretty(data)}</pre></details></> : <p role="status">Loading gateway status…</p>}</Panel>
  </>;
}
