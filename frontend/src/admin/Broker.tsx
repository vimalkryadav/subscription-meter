import { useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError } from '../shared/api';
import { Confirm, count, date, Loading, money, Notice, Panel, Select, Stat, Table, text, useMutation, useResource } from './ui';

interface BrokerKey { id: string; name: string; purpose: string; memberId: string | null; prefix: string; active: boolean; viewable: boolean; weeklyBudgetUsd: number | null; rpm: number | null; allowedModels: string[]; maxOutputTokens: number; expiresAt: string | null; createdAt: string; createdBy: string; spentUsd: number; callsThisWeek: number }
interface Price { model: string; inputUsdPerMTok: number; cachedInputUsdPerMTok: number; outputUsdPerMTok: number; longContextTokens: number | null; longInputUsdPerMTok: number | null; longOutputUsdPerMTok: number | null; updatedBy: string }
interface BrokerState { realKeyConfigured: boolean; upstreamHost: string; weeklyCapUsd: number; weekStart: string; spentUsd: number; remainingUsd: number; settings: { updatedAt: string; updatedBy: string }; keys: BrokerKey[]; prices: Price[] }
interface Call { id: string; createdAt: string; keyName: string | null; purpose: string | null; model: string | null; streaming: boolean; state: string; statusCode: number | null; reservedUsd: number; costUsd: number | null; inputTokens: number | null; outputTokens: number | null; durationMs: number | null; errorType: string | null }
interface Member { id: string; name: string; active: boolean }

const purposes = [{ value: 'session_summary', label: 'Session summaries' }, { value: 'member_fallback', label: 'Member fallback' }, { value: 'other', label: 'Other' }];
const purposeLabel = (value: string | null) => purposes.find(item => item.value === value)?.label ?? text(value);
const optionalNumber = (value: string) => value.trim() === '' ? null : Number(value);
const modelList = (value: string) => value.split(',').map(item => item.trim()).filter(Boolean);

export default function Broker() {
  const state = useResource<BrokerState>('/admin/broker/state'), members = useResource<Member[]>('/admin/members');
  const [callKey, setCallKey] = useState(''), calls = useResource<Call[]>('/admin/broker/calls?limit=100' + (callKey ? '&keyId=' + callKey : ''));
  const [creating, setCreating] = useState(false), [editing, setEditing] = useState<BrokerKey | null>(null), [revoking, setRevoking] = useState<BrokerKey | null>(null);
  const [issued, setIssued] = useState(''), [message, setMessage] = useState(''), [shown, setShown] = useState<{ name: string; key: string } | null>(null);
  async function showKey(key: BrokerKey) {
    setMessage(''); setShown(null);
    try { const result = await mutation.run<{ key: string }>('/admin/broker/keys/' + key.id + '/secret', 'GET'); if (result) setShown({ name: key.name, key: result.key }); }
    catch (failure) { mutation.clearError(); setMessage(failure instanceof ApiError && failure.status === 409 ? key.name + ' cannot be shown again (the broker admin token changed since it was created). Create a replacement key.' : 'Could not load the key. Try again.'); }
  }
  const [cap, setCap] = useState<string | null>(null), [priceForm, setPriceForm] = useState<Record<string, string> | null>(null);
  const mutation = useMutation();
  const data = state.data;
  const memberName = (id: string | null) => id ? members.data?.find(member => member.id === id)?.name ?? id : '—';
  const refresh = () => { state.refresh(); calls.refresh(); };
  async function act(work: () => Promise<unknown>, done: string) { setMessage(''); try { await work(); setMessage(done); refresh(); return true; } catch { return false; /* error shown above */ } }
  if (state.error) return <Panel title="Key broker"><Notice error>{state.error}</Notice><p className="cu-muted">The broker is a separate service that holds the real OpenAI key. Deploy it with <code>scripts/deploy_broker.py</code>; until then this section is unavailable.</p><button onClick={state.refresh}>Retry</button></Panel>;
  if (!data) return <Loading />;
  const used = Number(data.weeklyCapUsd) > 0 ? Math.min(100, 100 * Number(data.spentUsd) / Number(data.weeklyCapUsd)) : 100;
  return <>
    <Notice error>{mutation.error}</Notice><Notice>{message}</Notice>
    {!data.realKeyConfigured && <Notice>The broker has no OpenAI key. Write it to the server’s <code>secrets/openai-api-key</code> file; only the broker container can read it.</Notice>}
    <div className="cu-grid cu-stats">
      <Stat title="Spent this week (all keys)" value={money(data.spentUsd)} note={'Week of ' + date(data.weekStart) + ' · resets Monday 00:00 UTC'} />
      <Stat title="Weekly cap" value={money(data.weeklyCapUsd)} note={'Changed by ' + data.settings.updatedBy + ' · ' + date(data.settings.updatedAt)} />
      <Stat title="Remaining" value={money(data.remainingUsd)} note={<span className="cu-track" style={{ display: 'block' }}><span style={{ width: used + '%' }} /></span>} />
      <Stat title="Real OpenAI key" value={data.realKeyConfigured ? 'Configured' : 'Missing'} note={'Held only by the broker. Never shown. Upstream: ' + data.upstreamHost} />
    </div>
    <Panel title="Weekly cap"><form className="cu-bar" onSubmit={event => { event.preventDefault(); void act(() => mutation.run('/admin/broker/settings', 'PUT', { weeklyCapUsd: Number(cap) }), 'Weekly cap saved. It applies immediately.').then(ok => { if (ok) setCap(null); }); }}>
      <label>Weekly cap for all OpenAI spending (USD)<input required type="number" min="0" max="10000" step="0.01" value={cap ?? String(data.weeklyCapUsd)} onChange={event => setCap(event.target.value)} /></label>
      <button className="cu-primary" disabled={mutation.busy || cap === null}>Save cap</button></form>
      <p className="cu-muted">Every call reserves its worst-case cost first, so total spending cannot pass this cap. Also set a budget limit on the key in OpenAI’s dashboard as a second safeguard.</p></Panel>

    <Panel title="Virtual keys"><div className="cu-bar"><p className="cu-muted" style={{ margin: 0 }}>Callers use these instead of the real key. Each can have its own weekly budget, rate limit and allowed models.</p><button className="cu-primary" aria-expanded={creating} onClick={() => { setCreating(!creating); setIssued(''); }}>{creating ? 'Close' : 'Create virtual key'}</button></div>
      {creating && <KeyForm members={members.data ?? []} busy={mutation.busy} onSubmit={async body => { try { const result = await mutation.run<{ key: string }>('/admin/broker/keys', 'POST', body); if (result) { setIssued(result.key); refresh(); } } catch { /* shown above */ } }} />}
      {issued && <div className="cu-secret"><p>Copy this key now; you can view it again later with Show key. For session summaries, write it to the server’s <code>secrets/openai-virtual-key</code> file.</p><code>{issued}</code><div className="cu-bar"><button onClick={async () => { try { await navigator.clipboard.writeText(issued); setMessage('Key copied.'); } catch { setMessage('Clipboard unavailable. Select and copy the key directly.'); } }}>Copy key</button><button onClick={() => setIssued('')}>Hide key</button></div></div>}
      {shown && <div className="cu-secret"><p>{shown.name}. This view was recorded in the admin logs.</p><code>{shown.key}</code><div className="cu-bar"><button onClick={async () => { try { await navigator.clipboard.writeText(shown.key); setMessage('Key copied.'); } catch { setMessage('Clipboard unavailable. Select and copy the key directly.'); } }}>Copy key</button><button onClick={() => setShown(null)}>Hide key</button></div></div>}
      {editing && <KeyForm key={editing.id} existing={editing} members={members.data ?? []} busy={mutation.busy} onCancel={() => setEditing(null)} onSubmit={body => act(() => mutation.run('/admin/broker/keys/' + editing.id, 'PATCH', { ...body, active: true }), 'Key updated.').then(ok => { if (ok) setEditing(null); })} />}
      <Table headings={['Name', 'Purpose / member', 'Prefix', 'This week', 'Budget', 'Limits', 'Status', '']} empty={!data.keys.length}>{data.keys.map(key => <tr key={key.id}>
        <td>{key.name}<br /><span className="cu-muted">created {date(key.createdAt)} by {key.createdBy}</span></td>
        <td>{purposeLabel(key.purpose)}<br /><span className="cu-muted">{memberName(key.memberId)}</span></td>
        <td className="cu-mono">{key.prefix}…</td>
        <td>{money(key.spentUsd)}<br /><span className="cu-muted">{count(key.callsThisWeek)} calls</span></td>
        <td>{key.weeklyBudgetUsd == null ? 'Global cap only' : money(key.weeklyBudgetUsd) + ' / week'}</td>
        <td>{key.rpm == null ? 'No RPM limit' : key.rpm + ' / min'}<br /><span className="cu-muted">{key.allowedModels.length ? key.allowedModels.join(', ') : 'Any priced model'} · ≤{count(key.maxOutputTokens)} output tokens{key.expiresAt ? ' · expires ' + date(key.expiresAt) : ''}</span></td>
        <td>{key.active ? <span className="cu-badge">Active</span> : 'Revoked'}</td>
        <td>{key.active && <div className="cu-bar">{key.viewable && <button className="cu-link" disabled={mutation.busy} onClick={() => void showKey(key)}>Show key</button>}<button className="cu-link" onClick={() => { setEditing(key); setCreating(false); }}>Edit</button><button className="cu-link" onClick={() => setRevoking(key)}>Revoke</button></div>}</td>
      </tr>)}</Table></Panel>

    <Panel title="Model prices"><p className="cu-muted">Only priced models can be called, because the cap is enforced with these rates. Keep them in line with OpenAI’s published prices (USD per 1M tokens).</p>
      <Table headings={['Model', 'Input', 'Cached input', 'Output', 'Long context (above tokens: in / out)', '']} empty={!data.prices.length}>{data.prices.map(price => <tr key={price.model}><td className="cu-mono">{price.model}</td><td>{price.inputUsdPerMTok}</td><td>{price.cachedInputUsdPerMTok}</td><td>{price.outputUsdPerMTok}</td><td>{price.longContextTokens ? count(price.longContextTokens) + ': ' + text(price.longInputUsdPerMTok) + ' / ' + text(price.longOutputUsdPerMTok) : '—'}</td>
        <td><div className="cu-bar"><button className="cu-link" onClick={() => setPriceForm({ model: price.model, inputUsdPerMTok: String(price.inputUsdPerMTok), cachedInputUsdPerMTok: String(price.cachedInputUsdPerMTok), outputUsdPerMTok: String(price.outputUsdPerMTok) })}>Edit</button><button className="cu-link" onClick={() => void act(() => mutation.run('/admin/broker/prices/' + encodeURIComponent(price.model), 'DELETE'), 'Price removed; that model can no longer be called.')}>Remove</button></div></td></tr>)}</Table>
      {priceForm ? <form className="cu-filter-grid" onSubmit={event => { event.preventDefault(); void act(() => mutation.run('/admin/broker/prices', 'PUT', { model: priceForm.model.trim(), inputUsdPerMTok: Number(priceForm.inputUsdPerMTok), cachedInputUsdPerMTok: Number(priceForm.cachedInputUsdPerMTok), outputUsdPerMTok: Number(priceForm.outputUsdPerMTok) }), 'Price saved.').then(ok => { if (ok) setPriceForm(null); }); }}>
        {(['model', 'inputUsdPerMTok', 'cachedInputUsdPerMTok', 'outputUsdPerMTok'] as const).map(field => <label key={field}>{{ model: 'Model', inputUsdPerMTok: 'Input $ / 1M', cachedInputUsdPerMTok: 'Cached input $ / 1M', outputUsdPerMTok: 'Output $ / 1M' }[field]}<input required {...(field === 'model' ? { maxLength: 100, pattern: '[A-Za-z0-9._:\\-]+' } : { type: 'number', min: '0', max: '1000', step: '0.000001' })} value={priceForm[field] ?? ''} onChange={event => setPriceForm({ ...priceForm, [field]: event.target.value })} /></label>)}
        <div className="cu-bar"><button className="cu-primary" disabled={mutation.busy}>Save price</button><button type="button" onClick={() => setPriceForm(null)}>Cancel</button></div></form>
        : <button onClick={() => setPriceForm({ model: '', inputUsdPerMTok: '', cachedInputUsdPerMTok: '', outputUsdPerMTok: '' })}>Add model price</button>}
    </Panel>

    <Panel title="Recent calls"><div className="cu-toolbar"><Select label="Key" value={callKey} onChange={setCallKey} items={[{ value: '', label: 'All keys' }, ...data.keys.map(key => ({ value: key.id, label: key.name + ' (' + key.prefix + ')' }))]} /><button onClick={calls.refresh} disabled={calls.loading}>Refresh calls</button></div>
      <Notice error>{calls.error}</Notice>
      {calls.loading && !calls.data ? <Loading /> : <Table headings={['Time', 'Key', 'Model', 'Result', 'Tokens in / out', 'Cost', 'Duration']} empty={!calls.data?.length}>{calls.data?.map(call => <tr key={call.id}>
        <td>{date(call.createdAt)}</td><td>{text(call.keyName)}<br /><span className="cu-muted">{purposeLabel(call.purpose)}</span></td>
        <td>{text(call.model)}{call.streaming && <span className="cu-muted"> · stream</span>}</td>
        <td>{call.state}{call.statusCode != null && <span className="cu-muted"> · HTTP {call.statusCode}</span>}{call.errorType && <><br /><span className="cu-muted">{call.errorType.replaceAll('_', ' ')}</span></>}</td>
        <td>{count(call.inputTokens)} / {count(call.outputTokens)}</td>
        <td>{call.state === 'settled' ? money(call.costUsd) : call.state === 'reserved' || call.state === 'unknown' ? <>{money(call.reservedUsd)} <span className="cu-muted">(reserved)</span></> : money(0)}</td>
        <td>{call.durationMs == null ? '—' : (call.durationMs / 1000).toFixed(1) + ' s'}</td></tr>)}</Table>}
    </Panel>
    {revoking && <Confirm title={'Revoke ' + revoking.name + '?'} description="Calls with this virtual key are refused immediately. The real OpenAI key is unaffected." busy={mutation.busy} onClose={() => setRevoking(null)} onConfirm={() => void act(() => mutation.run('/admin/broker/keys/' + revoking.id, 'PATCH', { name: revoking.name, weeklyBudgetUsd: revoking.weeklyBudgetUsd, rpm: revoking.rpm, allowedModels: revoking.allowedModels, maxOutputTokens: revoking.maxOutputTokens, expiresAt: revoking.expiresAt, active: false }), 'Key revoked.').then(ok => { if (ok) setRevoking(null); })} />}
  </>;
}

function KeyForm({ existing, members, busy, onSubmit, onCancel }: { existing?: BrokerKey; members: Member[]; busy: boolean; onSubmit: (body: Record<string, unknown>) => void | Promise<void>; onCancel?: () => void }) {
  const [name, setName] = useState(existing?.name ?? ''), [purpose, setPurpose] = useState(existing?.purpose ?? 'session_summary'), [member, setMember] = useState(existing?.memberId ?? '');
  const [budget, setBudget] = useState(existing?.weeklyBudgetUsd == null ? '' : String(existing.weeklyBudgetUsd)), [rpm, setRpm] = useState(existing?.rpm == null ? '' : String(existing.rpm));
  const [models, setModels] = useState(existing?.allowedModels.join(', ') ?? ''), [maxOutput, setMaxOutput] = useState(String(existing?.maxOutputTokens ?? 16384)), [expires, setExpires] = useState(existing?.expiresAt?.slice(0, 10) ?? '');
  function submit(event: FormEvent) {
    event.preventDefault();
    void onSubmit({ name: name.trim(), ...(existing ? {} : { purpose, memberId: member || null }), weeklyBudgetUsd: optionalNumber(budget), rpm: optionalNumber(rpm), allowedModels: modelList(models), maxOutputTokens: Number(maxOutput), expiresAt: expires ? new Date(expires + 'T23:59:59Z').toISOString() : null });
  }
  return <Panel title={existing ? 'Edit ' + existing.name : 'New virtual key'}><form className="cu-filter-grid" onSubmit={submit}>
    <label>Name<input autoFocus required maxLength={100} value={name} onChange={event => setName(event.target.value)} placeholder="Session summaries" /></label>
    {!existing && <Select label="Purpose" value={purpose} onChange={setPurpose} items={purposes} />}
    {!existing && <Select label="Member (optional)" value={member} onChange={setMember} items={[{ value: '', label: 'Not tied to a member' }, ...members.filter(item => item.active).map(item => ({ value: item.id, label: item.name }))]} />}
    <label>Weekly budget (USD, empty = cap only)<input type="number" min="0" max="10000" step="0.01" value={budget} onChange={event => setBudget(event.target.value)} /></label>
    <label>Requests / minute (empty = none)<input type="number" min="1" max="10000" value={rpm} onChange={event => setRpm(event.target.value)} /></label>
    <label>Allowed models (comma-separated, empty = any priced)<input maxLength={2000} value={models} onChange={event => setModels(event.target.value)} placeholder="gpt-6-luna" /></label>
    <label>Max output tokens per call<input required type="number" min="1" max="128000" value={maxOutput} onChange={event => setMaxOutput(event.target.value)} /></label>
    <label>Expires (optional)<input type="date" value={expires} onChange={event => setExpires(event.target.value)} /></label>
    <div className="cu-bar"><button className="cu-primary" disabled={busy}>{existing ? 'Save key' : 'Create key'}</button>{onCancel && <button type="button" onClick={onCancel}>Cancel</button>}</div>
  </form></Panel>;
}
