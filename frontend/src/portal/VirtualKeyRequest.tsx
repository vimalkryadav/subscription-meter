import { useEffect, useId, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { ApiError, api } from '../shared/api';

type State = 'none' | 'pending' | 'approved_awaiting_provisioning' | 'rejected' | 'active' | 'revoked';
type Status = { state: State; request: { reason: string } | null };

const labels: Record<State, string> = {
  none: 'Not requested',
  pending: 'Pending review',
  approved_awaiting_provisioning: 'Approved — awaiting provisioning',
  rejected: 'Rejected',
  active: 'Active',
  revoked: 'Revoked',
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
      <button className="secondary" type="button" disabled={loading || busy} onClick={() => { setStatus(undefined); setLoading(true); refresh(value => value + 1); }}>Refresh status</button>
    </div>
    <p>Request a virtual key for direct OpenAI API access through the gateway. An admin must provision it with a weekly budget, rate limit, and list of allowed models.</p>
    <p role="status">{loading ? 'Loading request status…' : status ? labels[status.state] : 'Request status unavailable'}</p>
    {status?.request && <p className="muted">Your request: {status.request.reason}</p>}
    {error && <p className="error" role="alert">{error}</p>}
    {!loading && status?.state === 'active' && <><ActiveKey /><EmbeddingUsage revision={revision} /></>}
    {status?.state === 'revoked' && <p>This virtual key no longer works.</p>}
    {(status?.state === 'none' || status?.state === 'rejected' || status?.state === 'revoked') &&
      <form className="access-request" onSubmit={submit}>
        <label htmlFor={`${id}-reason`}>What will you use direct OpenAI access for?</label>
        <textarea id={`${id}-reason`} value={reason} maxLength={500} required disabled={busy || loading}
          aria-describedby={`${id}-help`} onChange={event => setReason(event.target.value)} />
        <p id={`${id}-help`} className="muted">Describe your use case. Do not include API keys, passwords, or private prompts.</p>
        <button disabled={busy || loading || !reason.trim()}>{busy ? 'Submitting…' : status.state === 'none' ? 'Request a virtual key' : 'Submit a new request'}</button>
      </form>}
  </section>;
}

function ActiveKey() {
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const current = useRef<AbortController | null>(null);
  const baseUrl = `${window.location.origin}/gateway/api/v1`;
  useEffect(() => () => current.current?.abort(), []);

  async function accessKey(action: 'reveal' | 'copy') {
    if (current.current) return;
    setError('');
    setMessage('');
    if (action === 'copy' && !navigator.clipboard?.writeText) {
      setError('Clipboard unavailable. Reveal the key to copy it manually.');
      return;
    }
    const controller = new AbortController();
    current.current = controller;
    setBusy(true);
    try {
      let value = key;
      if (!value) {
        try {
          const result = await api<{ key: string }>('/portal/api/virtual-key-requests/key', { signal: controller.signal });
          if (controller.signal.aborted) return;
          if (!result?.key) throw new Error('Missing key');
          value = result.key;
        } catch {
          if (!controller.signal.aborted) setError('Could not load the key. Refresh status and try again.');
          return;
        }
      }
      if (action === 'reveal') setKey(value);
      else {
        try {
          await navigator.clipboard.writeText(value);
          if (!controller.signal.aborted) setMessage('Key copied.');
        } catch {
          if (!controller.signal.aborted) setError('Clipboard unavailable. Reveal the key to copy it manually.');
        }
      }
    } finally {
      current.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  return <div>
    <p>Set your OpenAI client’s base URL to <code>{baseUrl}</code> and use this virtual key as its API key. Keep it private.</p>
    {key && <pre className="token-reveal" style={{ userSelect: 'all' }}>{key}</pre>}
    <div className="actions">
      {key ? <button type="button" className="secondary" disabled={busy} onClick={() => { setKey(''); setError(''); setMessage(''); }}>Hide key</button>
        : <button type="button" className="secondary" disabled={busy} onClick={() => void accessKey('reveal')}>Reveal key</button>}
      <button type="button" className="secondary" disabled={busy} onClick={() => void accessKey('copy')}>Copy key</button>
    </div>
    <p role="status">{busy ? 'Working…' : message}</p>
    {error && <p className="error" role="alert">{error}</p>}
  </div>;
}

function EmbeddingUsage({ revision }: { revision: number }) {
  const [result, setResult] = useState<{ rows?: { model: string; requests: number; items: number; promptTokens: number; totalTokens: number }[]; error?: boolean }>({});
  useEffect(() => {
    const abort = new AbortController();
    api<NonNullable<typeof result.rows>>('/portal/api/virtual-key-requests/embedding-usage', { signal: abort.signal })
      .then(rows => { if (!abort.signal.aborted) setResult({ rows }); })
      .catch(() => { if (!abort.signal.aborted) setResult({ error: true }); });
    return () => abort.abort();
  }, [revision]);
  return <section><h3>Embeddings usage · last 30 days</h3>
    {result.error ? <p role="alert">Could not load embeddings usage.</p> : !result.rows ? <p role="status">Loading embeddings usage…</p> : !result.rows.length ? <p>No completed embeddings requests for this key.</p> :
      <div className="scroll" tabIndex={0} role="region" aria-label="Embeddings usage"><table><thead><tr>{['Model', 'Requests', 'Items', 'Prompt tokens', 'Total tokens'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead><tbody>{result.rows.map(row => <tr key={row.model}><th scope="row">{row.model}</th>{[row.requests, row.items, row.promptTokens, row.totalTokens].map((number, i) => <td key={i}>{number.toLocaleString()}</td>)}</tr>)}</tbody></table></div>}
  </section>;
}
