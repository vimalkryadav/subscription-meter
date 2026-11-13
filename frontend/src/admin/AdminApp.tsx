import ConnectionSetup from '../shared/ConnectionSetup';
import { Component, lazy, Suspense, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError, getSession } from '../shared/api';
import type { Session } from '../shared/api';
import { Heading, Loading, Notice, Panel, pretty, text, useMutation, useResource } from './ui';
import type { Row } from './ui';
import './admin.css';
const Metrics = lazy(() => import('./Metrics'));
const RequestExplorer = lazy(() => import('./RequestExplorer'));
const Accounting = lazy(() => import('./Accounting'));
const Operations = lazy(() => import('./Operations'));
const Members = lazy(() => import('./Management').then(module => ({ default: module.Members })));
const Approvals = lazy(() => import('./Management').then(module => ({ default: module.Approvals })));
const Accounts = lazy(() => import('./Management').then(module => ({ default: module.Accounts })));
const AdminGuide = lazy(() => import('./AdminGuide'));

const navigationGroups = [{ label: 'Monitor', ids: ['metrics', 'activity', 'accounting'] }, { label: 'Manage', ids: ['members', 'approvals', 'accounts'] }, { label: 'Workspace', ids: ['operations', 'connection', 'guide'] }];
const pages = [{ id: 'metrics', label: 'Metrics', icon: '◴' }, { id: 'activity', label: 'Request explorer', icon: '⌕' }, { id: 'accounting', label: 'Usage & value', icon: '◈' }, { id: 'members', label: 'Members & keys', icon: '⚿' }, { id: 'approvals', label: 'Access requests', icon: '↳' }, { id: 'accounts', label: 'Accounts & sessions', icon: '◎' }, { id: 'operations', label: 'Operations', icon: '◉' }, { id: 'connection', label: 'Connection', icon: '⌁' }, { id: 'guide', label: 'Admin guide & MCP', icon: '?' }];
/** Failed route bundles must not remove navigation or account controls. */
class RouteErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (!this.state.failed) return this.props.children;
    return <section className="cu-panel" role="alert" aria-labelledby="route-error-heading"><h1 id="route-error-heading">This page could not load</h1><p className="cu-muted">Reload to get the latest version and try again. You can also open another page from the navigation.</p><button className="cu-primary" onClick={() => location.reload()}>Reload page</button></section>;
  }
}
function currentPage() { const id = location.hash.replace(/^#(?:view\/)?/, ''); return pages.some(page => page.id === id) ? id : 'metrics'; }
function Brand() { return <a className="cu-brand" href="/manage"><svg width="32" height="32" viewBox="0 0 64 64" fill="none" aria-hidden="true"><rect x="1" y="1" width="62" height="62" rx="16" fill="#141d27" stroke="#293849" strokeWidth="2" /><path d="M43 16a21 21 0 1 0 0 32" stroke="#6a9fff" strokeWidth="5" strokeLinecap="round" /><path d="M28 39V30M38 39V23M48 39V17" stroke="#edf3fa" strokeWidth="5" strokeLinecap="round" /><circle cx="48" cy="17" r="4" fill="#6a9fff" /></svg><span>control-usage</span></a>; }
export default function AdminApp() {
  const [session, setSession] = useState<Session | null>(null), [authError, setAuthError] = useState(''), [retry, setRetry] = useState(0), [page, setPage] = useState(currentPage), [menuOpen, setMenuOpen] = useState(false);
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
    const navigate = () => { setPage(currentPage()); setMenuOpen(false); document.getElementById('admin-content')?.focus(); window.scrollTo({ top: 0, behavior: 'instant' }); };
    addEventListener('control-usage:session-expired', expired); addEventListener('hashchange', navigate); addEventListener('popstate', navigate);
    return () => { removeEventListener('control-usage:session-expired', expired); removeEventListener('hashchange', navigate); removeEventListener('popstate', navigate); };
  }, []);
  useEffect(() => { document.title = (pages.find(item => item.id === page)?.label || 'Admin') + ' · control-usage'; }, [page]);
  return <div className="cu-admin"><a className="cu-skip" href="#admin-content" onClick={event => { event.preventDefault(); document.getElementById('admin-content')?.focus(); }}>Skip to content</a><header className="cu-topbar"><div className="cu-brand-group"><Brand />{session && <button className="cu-menu-toggle" aria-expanded={menuOpen} aria-controls="admin-navigation" onClick={() => setMenuOpen(!menuOpen)}>{menuOpen ? 'Close menu' : 'Menu'}</button>}</div><div className="cu-account">{session ? <><span>Administrator · @{session.user.username}</span><button disabled={logout.busy} onClick={async () => { try { await logout.run('/portal/api/logout', 'POST', {}); setSession(null); location.replace('/'); } catch { /* error below */ } }}>Sign out</button></> : null}</div></header>
    {!session ? <main id="admin-content" className="cu-auth-state" tabIndex={-1}><Notice error>{authError}</Notice>{authError ? <div className="cu-bar"><button onClick={() => setRetry(value => value + 1)}>Retry session check</button><a href="/">Member portal</a></div> : <Loading />}</main> : <>
      <aside id="admin-navigation" className={'cu-sidebar' + (menuOpen ? ' is-open' : '')}><nav aria-label="Admin navigation">{navigationGroups.map(group => <div className="cu-nav-group" key={group.label}><p className="cu-eyebrow">{group.label}</p>{group.ids.map(id => { const item = pages.find(page => page.id === id)!; return <a key={item.id} href={'#view/' + item.id} onClick={() => setMenuOpen(false)} aria-current={page === item.id ? 'page' : undefined}><span aria-hidden="true">{item.icon}</span>{item.label}</a>; })}</div>)}</nav><div className="cu-sidebar-footer"><a href="/">Member portal ↗</a></div></aside>
      <main id="admin-content" className="cu-content" tabIndex={-1}><Notice error>{logout.error}</Notice><RouteErrorBoundary key={page}><Suspense fallback={<Loading />}>{page === 'metrics' && <Metrics />}{page === 'activity' && <RequestExplorer />}{page === 'accounting' && <Accounting />}{page === 'members' && <Members />}{page === 'approvals' && <Approvals />}{page === 'accounts' && <Accounts />}{page === 'operations' && <Operations />}{page === 'connection' && <Connection />}{page === 'guide' && <AdminGuide />}</Suspense></RouteErrorBoundary></main>
    </>}
  </div>;
}

function Connection() {
  const status = useResource<Row>('/admin/status'), members = useResource<Row[]>('/admin/members');
  const base = location.origin + (location.protocol === 'https:' ? '/gateway' : '');
  const data = status.data;
  return <><Heading title="Connection" subtitle="Connect a member’s terminal to the gateway."><button disabled={status.loading} onClick={status.refresh}>Refresh status</button></Heading><Notice error>{status.error || members.error}</Notice>
    {members.data && !members.data.some(member => member.active) && <Notice>No active member keys. <a href="#view/members">Create a member key</a> to send requests through the gateway.</Notice>}
    <Panel title="Claude Code settings"><ConnectionSetup gateway={base} /><details><summary>Temporary setup · this terminal only</summary><pre>{'export ANTHROPIC_BASE_URL="' + base + '"\nexport ANTHROPIC_AUTH_TOKEN="<your-member-key>"\nunset ANTHROPIC_API_KEY\nclaude'}</pre><p className="cu-muted">Use the member’s platform key. The upstream credential stays on the server. Captured inputs, outputs, and tool events are available to the administrator, subject to capture limits and retention.</p></details></Panel>
    <Panel title="Gateway status">{status.loading ? <Loading /> : data ? <><dl className="cu-coverage"><div><dt>Subscription compatibility</dt><dd>{text(data.subscriptionCompatibility)}</dd></div><div><dt>Upstream credential configured</dt><dd>{data.credentialConfigured ? 'Yes' : 'No'}</dd></div><div><dt>Audit readiness</dt><dd>{data.auditReady ? 'Ready' : 'Not ready'}</dd></div><div><dt>Capture policy</dt><dd>{text(data.capturePolicy)}</dd></div><div><dt>Detail retention</dt><dd>{text(data.detailRetentionDays)} days</dd></div><div><dt>Runtime</dt><dd>Java {text(data.runtime)} · {text(data.thread)} threads</dd></div></dl><details><summary>All status metadata</summary><pre>{pretty(data)}</pre></details></> : <p className="cu-muted">Gateway status is unavailable. Refresh to try again.</p>}</Panel>
  </>;
}
