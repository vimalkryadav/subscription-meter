import { useState } from 'react';
import { count, date, Heading, Loading, money, Notice, Panel, Select, Stat, Table, text, useMutation, useResource } from './ui';
import type { Price } from './Broker';

interface DeploymentRow {
  id: string; name: string; kind: 'anthropic' | 'codex' | 'broker'; account: string; priority: number; weight: number; enabled: boolean;
  maxConcurrency: number | null; contextWindowTokens: number | null; credentialFile: string | null; seeded: boolean; connected: boolean;
  usedPercent: number | null; inFlight: number; sessions: number; firstByteMs: number | null; errorRate: number; cooldownUntil: string | null; quotaBlockedUntil: string | null; lastError: string | null;
}
interface RoutingStatus {
  gptModelsEnabled: boolean; fallbackEnabled: boolean; strategy: string; mapOpus: string; mapSonnet: string; mapHaiku: string; memberWeeklyBudgetUsd: number;
  allowedFails: number; cooldownSeconds: number; transientRetries: number; firstByteTimeoutSeconds: number; updatedAt: string; updatedBy: string;
  deployments: DeploymentRow[]; memberKeys: number;
  state: { sessionsOnClaude: number; sessionsOnCodex: number; sessionsOnBroker: number };
  servedLast24h: { provider: string; account: string; requests: number }[];
}
const onOff = [{ value: 'true', label: 'On' }, { value: 'false', label: 'Off' }];
const strategies = [
  { value: 'balanced', label: 'Balanced: quota left, health, load and latency' },
  { value: 'claude_first', label: 'Claude first: other subscriptions only when Claude can’t serve' },
  { value: 'latency', label: 'Lowest latency' },
  { value: 'least_busy', label: 'Least busy' },
];
const kinds: Record<string, string> = { anthropic: 'Claude subscription', codex: 'Codex login', broker: 'OpenAI API key' };
const pct = (v: number | null) => v == null ? '—' : Math.round(v) + '%';

function healthLabel(d: DeploymentRow) {
  if (!d.enabled) return 'Disabled';
  if (!d.connected) return 'Not connected';
  if (d.quotaBlockedUntil) return 'Out of quota until ' + date(d.quotaBlockedUntil);
  if (d.cooldownUntil) return 'Cooling down until ' + date(d.cooldownUntil);
  return 'Serving';
}

export default function Routing() {
  const status = useResource<RoutingStatus>('/admin/routing'), broker = useResource<{ prices: Price[] }>('/admin/broker/state'), save = useMutation();
  const [form, setForm] = useState<Record<string, string> | null>(null), [message, setMessage] = useState('');
  const [editing, setEditing] = useState<DeploymentRow | null>(null), [adding, setAdding] = useState(false);
  const data = status.data;
  const values = form ?? (data ? {
    gptModelsEnabled: String(data.gptModelsEnabled), fallbackEnabled: String(data.fallbackEnabled), strategy: data.strategy,
    mapOpus: data.mapOpus, mapSonnet: data.mapSonnet, mapHaiku: data.mapHaiku, memberWeeklyBudgetUsd: String(data.memberWeeklyBudgetUsd),
    allowedFails: String(data.allowedFails), cooldownSeconds: String(data.cooldownSeconds), transientRetries: String(data.transientRetries), firstByteTimeoutSeconds: String(data.firstByteTimeoutSeconds),
  } : null);
  const change = (name: string, value: string) => { setMessage(''); setForm({ ...values!, [name]: value }); };
  const models = (current: string) => [...(broker.data?.prices.some(p => p.model === current) ? [] : [{ value: current, label: current }]), ...(broker.data?.prices ?? []).map(p => ({ value: p.model, label: p.model + ' · $' + p.inputUsdPerMTok + ' / $' + p.outputUsdPerMTok }))];
  const servedLabel = (row: { provider: string; account: string }) => data?.deployments.find(d => d.account === row.account)?.name ?? row.provider + ' · ' + row.account;

  async function saveDeployment(id: string, body: Record<string, unknown>, creating: boolean) {
    try { await save.run('/admin/routing/deployments/' + encodeURIComponent(id), creating ? 'POST' : 'PUT', body); setEditing(null); setAdding(false); setMessage('Deployment saved. Routing uses it within a few seconds.'); status.refresh(); }
    catch { /* error above */ }
  }

  return <><Heading title="Routing" subtitle="Serve Claude Code from every subscription you have, by quota left, health and load. The OpenAI API key is used only when no subscription can serve."><button onClick={status.refresh} disabled={status.loading}>Refresh</button></Heading>
    <Notice error>{status.error || save.error}</Notice><Notice>{message}</Notice>
    {status.loading && !data ? <Loading /> : data && values && <>
      <div className="cu-grid cu-stats">
        <Stat title="Automatic routing" value={data.fallbackEnabled ? 'On' : 'Off'} note={data.fallbackEnabled ? strategies.find(s => s.value === data.strategy)?.label : 'All Claude Code traffic goes to the Claude subscription'} />
        <Stat title="Active sessions" value={count(data.state.sessionsOnClaude + data.state.sessionsOnCodex + data.state.sessionsOnBroker)} note={count(data.state.sessionsOnClaude) + ' Claude · ' + count(data.state.sessionsOnCodex) + ' Codex · ' + count(data.state.sessionsOnBroker) + ' API key'} />
        <Stat title="Healthy deployments" value={count(data.deployments.filter(d => healthLabel(d) === 'Serving').length) + ' / ' + count(data.deployments.length)} note="Health is tracked per app instance" />
        <Stat title="Member API keys" value={count(data.memberKeys)} note={'Created on first use · ' + money(data.memberWeeklyBudgetUsd) + ' / week each'} />
      </div>

      <Panel title="Deployments"><p className="cu-muted">Priority 1 deployments are always tried before priority 2. Within a priority, the strategy decides; a session’s current deployment comes first. Busy deployments (at their concurrency cap) are still tried before a lower priority.</p>
        <Table headings={['Deployment', 'Priority / weight', 'Status', 'Quota used', 'Sessions / in flight', 'First byte', 'Error rate', '']} empty={!data.deployments.length}>{data.deployments.map(d => <tr key={d.id}>
          <td>{d.name}<br /><span className="cu-muted">{kinds[d.kind]} · {d.account}{d.credentialFile ? ' · own credentials' : ''}</span></td>
          <td>{d.priority} / {d.weight}{d.maxConcurrency ? <><br /><span className="cu-muted">cap {d.maxConcurrency}</span></> : null}{d.contextWindowTokens ? <><br /><span className="cu-muted">≤{count(d.contextWindowTokens)} tokens</span></> : null}</td>
          <td>{healthLabel(d) === 'Serving' ? <span className="cu-badge">Serving</span> : healthLabel(d)}{d.lastError && <><br /><span className="cu-muted">last error: {d.lastError}</span></>}</td>
          <td>{d.kind === 'broker' ? 'budget-capped' : pct(d.usedPercent)}</td>
          <td>{count(d.sessions)} / {count(d.inFlight)}</td>
          <td>{d.firstByteMs == null ? '—' : (d.firstByteMs / 1000).toFixed(1) + ' s'}</td>
          <td>{Math.round(d.errorRate * 100)}%</td>
          <td><div className="cu-bar"><button className="cu-link" onClick={() => { setEditing(d); setAdding(false); }}>Edit</button>{!d.seeded && <button className="cu-link" onClick={async () => { try { await save.run('/admin/routing/deployments/' + encodeURIComponent(d.id), 'DELETE'); status.refresh(); } catch { /* shown above */ } }}>Remove</button>}</div></td>
        </tr>)}</Table>
        {editing && <DeploymentForm key={editing.id} existing={editing} busy={save.busy} onCancel={() => setEditing(null)} onSave={(id, body) => saveDeployment(id, body, false)} />}
        {adding ? <DeploymentForm busy={save.busy} onCancel={() => setAdding(false)} onSave={(id, body) => saveDeployment(id, body, true)} />
          : <button onClick={() => { setAdding(true); setEditing(null); }}>Add a Claude or Codex account</button>}
      </Panel>

      <Panel title="Who served Claude Code requests (last 24 hours)"><Table headings={['Served by', 'Requests']} empty={!data.servedLast24h.length}>{data.servedLast24h.map(row => <tr key={row.provider + row.account}><td>{servedLabel(row)}</td><td>{count(row.requests)}</td></tr>)}</Table></Panel>

      <Panel title="Settings"><form className="cu-filter-grid" onSubmit={async event => {
        event.preventDefault();
        try {
          await save.run('/admin/routing', 'PUT', { gptModelsEnabled: values.gptModelsEnabled === 'true', fallbackEnabled: values.fallbackEnabled === 'true', strategy: values.strategy,
            mapOpus: values.mapOpus, mapSonnet: values.mapSonnet, mapHaiku: values.mapHaiku, memberWeeklyBudgetUsd: Number(values.memberWeeklyBudgetUsd),
            allowedFails: Number(values.allowedFails), cooldownSeconds: Number(values.cooldownSeconds), transientRetries: Number(values.transientRetries), firstByteTimeoutSeconds: Number(values.firstByteTimeoutSeconds) });
          setForm(null); setMessage('Routing saved. It applies within a few seconds.'); status.refresh();
        } catch { /* error above */ }
      }}>
        <Select label="Automatic routing" value={values.fallbackEnabled} onChange={v => change('fallbackEnabled', v)} items={onOff} />
        <Select label="Strategy" value={values.strategy} onChange={v => change('strategy', v)} items={strategies} />
        <Select label="Members may choose OpenAI models (/model gpt-…)" value={values.gptModelsEnabled} onChange={v => change('gptModelsEnabled', v)} items={onOff} />
        <Select label="Claude Opus is served by" value={values.mapOpus} onChange={v => change('mapOpus', v)} items={models(values.mapOpus)} />
        <Select label="Claude Sonnet (and others) by" value={values.mapSonnet} onChange={v => change('mapSonnet', v)} items={models(values.mapSonnet)} />
        <Select label="Claude Haiku is served by" value={values.mapHaiku} onChange={v => change('mapHaiku', v)} items={models(values.mapHaiku)} />
        <label>New member key budget (USD / week)<input required type="number" min="0" max="1000" step="0.01" value={values.memberWeeklyBudgetUsd} onChange={e => change('memberWeeklyBudgetUsd', e.target.value)} /></label>
        <label>Retries on transient errors<input required type="number" min="0" max="3" value={values.transientRetries} onChange={e => change('transientRetries', e.target.value)} /></label>
        <label>Failures within a minute before cooldown<input required type="number" min="1" max="20" value={values.allowedFails} onChange={e => change('allowedFails', e.target.value)} /></label>
        <label>Cooldown (seconds)<input required type="number" min="5" max="3600" value={values.cooldownSeconds} onChange={e => change('cooldownSeconds', e.target.value)} /></label>
        <label>First-byte timeout for streams (seconds)<input required type="number" min="10" max="600" value={values.firstByteTimeoutSeconds} onChange={e => change('firstByteTimeoutSeconds', e.target.value)} /></label>
        <div className="cu-bar"><button className="cu-primary" disabled={save.busy || !form}>{save.busy ? 'Saving…' : 'Save routing'}</button>{form && <button type="button" onClick={() => setForm(null)}>Discard changes</button>}</div>
      </form>
        <p className="cu-muted">Transient errors (overloaded, 5xx, timeouts, connection failures) are retried with backoff, then the next deployment is tried. Quota refusals take a deployment out until its reset. Context-too-long errors move to another deployment. A request is only retried elsewhere before anything was sent to the member. Changed by {text(data.updatedBy)} · {date(data.updatedAt)}.</p></Panel>
    </>}
  </>;
}

function DeploymentForm({ existing, busy, onSave, onCancel }: { existing?: DeploymentRow; busy: boolean; onSave: (id: string, body: Record<string, unknown>) => void; onCancel: () => void }) {
  const [id, setId] = useState(existing?.id ?? ''), [name, setName] = useState(existing?.name ?? ''), [kind, setKind] = useState<string>(existing?.kind ?? 'anthropic');
  const [account, setAccount] = useState(existing?.account ?? ''), [priority, setPriority] = useState(String(existing?.priority ?? 1)), [weight, setWeight] = useState(String(existing?.weight ?? 100));
  const [enabled, setEnabled] = useState(String(existing?.enabled ?? true)), [cap, setCap] = useState(existing?.maxConcurrency == null ? '' : String(existing.maxConcurrency));
  const [context, setContext] = useState(existing?.contextWindowTokens == null ? '' : String(existing.contextWindowTokens)), [credential, setCredential] = useState(existing?.credentialFile ?? '');
  const optional = (v: string) => v.trim() === '' ? null : Number(v);
  return <Panel title={existing ? 'Edit ' + existing.name : 'Add an account'}>
    {!existing && <p className="cu-muted">Put the new account’s credential file in the server’s <code>secrets/extra-credentials/</code> folder (for a Claude subscription: its OAuth token; for Codex: an access token kept fresh by a refresher). It appears in the app at <code>/run/control-usage-extra/&lt;file&gt;</code>.</p>}
    <form className="cu-filter-grid" onSubmit={e => { e.preventDefault(); onSave(existing ? existing.id : id.trim(), { name: name.trim(), kind, account: account.trim(), priority: Number(priority), weight: Number(weight), enabled: enabled === 'true', maxConcurrency: optional(cap), contextWindowTokens: optional(context), credentialFile: credential.trim() || null }); }}>
      {!existing && <label>Id<input required pattern="[a-z0-9-]{3,40}" value={id} onChange={e => setId(e.target.value)} placeholder="claude-team-2" /></label>}
      <label>Name<input required maxLength={100} value={name} onChange={e => setName(e.target.value)} /></label>
      {!existing && <Select label="Kind" value={kind} onChange={setKind} items={[{ value: 'anthropic', label: 'Claude subscription' }, { value: 'codex', label: 'Codex login' }]} />}
      {!existing && <label>Account label<input required pattern="[a-z0-9-]{2,80}" value={account} onChange={e => setAccount(e.target.value)} placeholder="claude-team-2" /></label>}
      <Select label="Enabled" value={enabled} onChange={setEnabled} items={onOff} />
      <label>Priority (1 = preferred)<input required type="number" min="1" max="9" value={priority} onChange={e => setPriority(e.target.value)} /></label>
      <label>Weight<input required type="number" min="1" max="1000" value={weight} onChange={e => setWeight(e.target.value)} /></label>
      <label>Concurrency cap (empty = none)<input type="number" min="1" max="1000" value={cap} onChange={e => setCap(e.target.value)} /></label>
      <label>Context window, tokens (empty = unknown)<input type="number" min="1000" max="10000000" value={context} onChange={e => setContext(e.target.value)} /></label>
      {!(existing?.seeded) && existing?.kind !== 'broker' && <label>Credential file<input required={!existing} pattern="/run/control-usage-extra/[A-Za-z0-9._-]+" value={credential} onChange={e => setCredential(e.target.value)} placeholder="/run/control-usage-extra/claude-team-2" /></label>}
      <div className="cu-bar"><button className="cu-primary" disabled={busy}>{existing ? 'Save deployment' : 'Add deployment'}</button><button type="button" onClick={onCancel}>Cancel</button></div>
    </form></Panel>;
}
