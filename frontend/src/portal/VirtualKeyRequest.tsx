import { useEffect, useId, useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError, api } from '../shared/api';

type State = 'none' | 'pending' | 'approved_awaiting_provisioning' | 'rejected';
type Status = { state: State; request: { reason: string } | null };

const labels: Record<State, string> = {
  none: 'Not requested',
  pending: 'Pending review',
  approved_awaiting_provisioning: 'Approved — awaiting provisioning',
  rejected: 'Rejected',
};

export default function VirtualKeyRequest() {
  const id = useId();
  const [status, setStatus] = useState<Status>();
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [revision, refresh] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setStatus(undefined);
    setError('');
    api<Status>('/portal/api/virtual-key-requests', { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setStatus(value); })
      .catch(failure => { if (!controller.signal.aborted) setError(failure instanceof Error ? failure.message : 'Could not load request status.'); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [revision]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!reason.trim() || busy || loading) return;
    setBusy(true);
    setError('');
    try {
      const value = await api<Status>('/portal/api/virtual-key-requests', {
        method: 'POST', body: JSON.stringify({ reason: reason.trim() }),
      });
      setStatus(value);
      setReason('');
    } catch (failure) {
      setError(failure instanceof ApiError && failure.status === 409
        ? 'A request already exists. Refresh status to see it.'
        : failure instanceof Error ? failure.message : 'Could not submit your request.');
    } finally {
      setBusy(false);
    }
  }

  return <section className="card" aria-labelledby={`${id}-title`}>
    <div className="row"><h2 id={`${id}-title`}>Direct OpenAI virtual key</h2>
      <button className="secondary" type="button" disabled={loading || busy} onClick={() => refresh(value => value + 1)}>Refresh status</button>
    </div>
    <p>Request access for direct use of the OpenAI API. This workflow records approvals only; it does not issue a key or enable API calls.</p>
    <p role="status">{loading ? 'Loading request status…' : status ? labels[status.state] : 'Request status unavailable'}</p>
    {status?.request && <p className="muted">Your request: {status.request.reason}</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {(status?.state === 'none' || status?.state === 'rejected') &&
      <form className="access-request" onSubmit={submit}>
        <label htmlFor={`${id}-reason`}>What will you use direct OpenAI access for?</label>
        <textarea id={`${id}-reason`} value={reason} maxLength={500} required disabled={busy || loading}
          aria-describedby={`${id}-help`} onChange={event => setReason(event.target.value)} />
        <p id={`${id}-help`} className="muted">Describe your use case. Do not include API keys, passwords, or private prompts.</p>
        <button disabled={busy || loading || !reason.trim()}>{busy ? 'Submitting…' : status.state === 'rejected' ? 'Submit a new request' : 'Request a virtual key'}</button>
      </form>}
  </section>;
}
