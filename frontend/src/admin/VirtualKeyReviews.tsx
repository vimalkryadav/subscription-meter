import { useState } from 'react';
import type { FormEvent } from 'react';
import { modelOptions } from './Broker';
import type { Price } from './Broker';
import { Confirm, date, Loading, MultiSelect, Notice, Panel, Segments, Table, useMutation, useResource } from './ui';

type State = 'pending' | 'approved_awaiting_provisioning' | 'active' | 'revoked' | 'rejected';
type Review = { username: string; displayName: string; request: { id: string; reason: string; requestedAt: string; decidedAt: string | null } };
type Limits = { weeklyBudgetUsd: number; rpm: number; allowedModels: string[] };
const path = '/admin/virtual-key-requests';
const states: { value: State; label: string }[] = [
  { value: 'pending', label: 'Pending review' },
  { value: 'approved_awaiting_provisioning', label: 'Approved — awaiting provisioning' },
  { value: 'active', label: 'Active' },
  { value: 'revoked', label: 'Revoked' },
  { value: 'rejected', label: 'Rejected' },
];
const stateDescriptions: Record<State, string> = {
  pending: 'No pending requests.',
  approved_awaiting_provisioning: 'No approved requests awaiting provisioning.',
  active: 'No active keys.',
  revoked: 'No revoked keys.',
  rejected: 'No rejected requests.',
};

export default function VirtualKeyReviews() {
  const [state, setState] = useState<State>('pending');
  const review = useResource<Review[]>(`${path}?state=${state}`);
  const mutation = useMutation();
  const [notice, setNotice] = useState('');
  const [provisioning, setProvisioning] = useState<Review | null>(null);
  const [revoking, setRevoking] = useState<Review | null>(null);

  function clearFeedback() {
    mutation.clearError();
    setNotice('');
  }

  async function act(id: string, action: 'provision' | 'decision' | 'revoke', body: Limits | { approve: false } | Record<string, never>, message: string) {
    setNotice('');
    try {
      await mutation.run(`${path}/${encodeURIComponent(id)}/${action}`, 'POST', body);
      setNotice(message);
      review.refresh();
      return true;
    } catch { return false; /* useMutation exposes the error */ }
  }

  const rows = review.data ?? [];
  const pending = state === 'pending';
  const canProvision = pending || state === 'approved_awaiting_provisioning';
  return <Panel title="Direct OpenAI virtual key requests">
    <p>Members must already have active Claude Code access before you can provision a direct API key. The member can reveal the key in their portal once it is active.</p>
    <div className="cu-bar">
      <Segments label="Request status" values={states} value={state} onChange={value => {
        if (!mutation.busy && value !== state) { clearFeedback(); setProvisioning(null); setState(value); }
      }} />
      <button type="button" disabled={review.loading || mutation.busy} onClick={() => { clearFeedback(); setProvisioning(null); review.refresh(); }}>Refresh requests</button>
    </div>
    <Notice>{notice}</Notice>
    <Notice error>{review.error || mutation.error}</Notice>
    {provisioning && <ProvisionForm key={provisioning.request.id} username={provisioning.username} busy={mutation.busy}
      onCancel={() => { clearFeedback(); setProvisioning(null); }} onSubmit={async limits => {
        if (await act(provisioning.request.id, 'provision', limits, `Key provisioned for @${provisioning.username}.`)) setProvisioning(null);
      }} />}
    {review.loading ? <Loading /> : !review.error && (rows.length === 0 ? <p>{stateDescriptions[state]}</p> : <>
      {rows.length === 50 && <p className="cu-muted">{pending ? 'Showing the oldest 50 pending requests. More requests may appear after review.' : 'Showing up to 50 most recently decided requests in this state.'}</p>}
      <Table label="Direct OpenAI virtual key requests" headings={['Account', 'Requested', 'Use case', ...(pending ? [] : ['Decided']), ...(canProvision || state === 'active' ? ['Actions'] : [])]}>
        {rows.map(row => <tr key={row.request.id}>
          <th scope="row">{row.displayName} (@{row.username})</th>
          <td>{date(row.request.requestedAt)}</td>
          <td>{row.request.reason}</td>
          {!pending && <td>{date(row.request.decidedAt)}</td>}
          {canProvision && <td><div className="cu-bar">
            <button type="button" disabled={mutation.busy} aria-label={`Provision key: @${row.username}`} onClick={() => { clearFeedback(); setProvisioning(row); }}>Provision key</button>
            {pending && <button type="button" disabled={mutation.busy || provisioning !== null} aria-label={`Reject request: @${row.username}`} onClick={() => void act(row.request.id, 'decision', { approve: false }, 'Request rejected.')}>Reject</button>}
          </div></td>}
          {state === 'active' && <td><button type="button" disabled={mutation.busy} aria-label={`Revoke key: @${row.username}`} onClick={() => { clearFeedback(); setRevoking(row); }}>Revoke key</button></td>}
        </tr>)}
      </Table>
    </>)}
    {revoking && <Confirm title={`Revoke @${revoking.username}’s key?`} description="The member will no longer be able to use this key for direct API calls. Their Claude Code access is unaffected." busy={mutation.busy}
      onClose={() => setRevoking(null)} onConfirm={async () => {
        await act(revoking.request.id, 'revoke', {}, `Key revoked for @${revoking.username}.`);
        setRevoking(null);
      }} />}
  </Panel>;
}

function ProvisionForm({ username, busy, onSubmit, onCancel }: { username: string; busy: boolean; onSubmit: (limits: Limits) => Promise<void>; onCancel: () => void }) {
  const broker = useResource<{ prices: Price[] }>('/admin/broker/state');
  const [budget, setBudget] = useState('');
  const [rpm, setRpm] = useState('');
  const [models, setModels] = useState<string[]>([]);
  const [modelText, setModelText] = useState('');
  const pricedModels = modelOptions(broker.data?.prices ?? []);
  const allowedModels = pricedModels.length ? models : [...new Set(modelText.split(',').map(model => model.trim()).filter(Boolean))];
  const weeklyBudgetUsd = Number(budget);
  const requestsPerMinute = Number(rpm);
  const valid = Number.isFinite(weeklyBudgetUsd) && weeklyBudgetUsd >= 0.01 && weeklyBudgetUsd <= 100
    && Number.isInteger(requestsPerMinute) && requestsPerMinute > 0 && requestsPerMinute <= 120
    && allowedModels.length > 0 && allowedModels.length <= 20 && allowedModels.every(model => /^[A-Za-z0-9._:-]{1,100}$/.test(model));

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (valid && !busy && !broker.loading) void onSubmit({ weeklyBudgetUsd, rpm: requestsPerMinute, allowedModels });
  }

  return <Panel title={`Provision key for @${username}`}>
    <p className="cu-muted">Enter a positive budget and rate limit. Allow at least one model with a configured broker price.</p>
    <form className="cu-filter-grid" onSubmit={submit}>
      <label>Weekly budget (USD)<input autoFocus required disabled={busy} type="number" min="0.01" max="100" step="0.01" value={budget} onChange={event => setBudget(event.target.value)} /></label>
      <label>Requests per minute<input required disabled={busy} type="number" min="1" max="120" step="1" value={rpm} onChange={event => setRpm(event.target.value)} /></label>
      {broker.loading ? <Loading /> : pricedModels.length ? <MultiSelect label="Allowed models" items={pricedModels} values={models} onChange={setModels} disabled={busy} />
        : <label>Allowed model IDs (comma-separated)<input required disabled={busy} value={modelText} onChange={event => setModelText(event.target.value)} /></label>}
      <div className="cu-bar"><button className="cu-primary" disabled={!valid || busy || broker.loading}>{busy ? 'Provisioning…' : 'Provision key'}</button><button type="button" onClick={onCancel} disabled={busy}>Cancel</button></div>
    </form>
  </Panel>;
}
