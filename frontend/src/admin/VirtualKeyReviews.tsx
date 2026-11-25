import { useState } from 'react';
import { date, Loading, Notice, Panel, Segments, Table, useMutation, useResource } from './ui';

type State = 'pending' | 'approved_awaiting_provisioning' | 'rejected';
type Review = { username: string; displayName: string; request: { id: string; reason: string; requestedAt: string; decidedAt: string | null } };
const path = '/admin/virtual-key-requests';
const states: { value: State; label: string }[] = [
  { value: 'pending', label: 'Pending review' },
  { value: 'approved_awaiting_provisioning', label: 'Approved — awaiting provisioning' },
  { value: 'rejected', label: 'Rejected' },
];

export default function VirtualKeyReviews() {
  const [state, setState] = useState<State>('pending');
  const review = useResource<Review[]>(`${path}?state=${state}`);
  const mutation = useMutation();
  const [notice, setNotice] = useState('');

  function clearFeedback() {
    mutation.clearError();
    setNotice('');
  }

  async function decide(id: string, approve: boolean) {
    setNotice('');
    try {
      const result = await mutation.run<{ state: string }>(`${path}/${encodeURIComponent(id)}/decision`, 'POST', { approve });
      if (!result) return;
      setNotice(approve ? 'Approved — awaiting provisioning. No key was issued.' : 'Request rejected.');
      review.refresh();
    } catch { /* useMutation exposes the error */ }
  }

  const rows = review.data ?? [];
  const pending = state === 'pending';
  return <Panel title="Direct OpenAI virtual key requests">
    <p>Approval records the decision only: no credential is issued and no API access is enabled.</p>
    <div className="cu-bar">
      <Segments label="Request status" values={states} value={state} onChange={value => { if (!mutation.busy) { clearFeedback(); setState(value); } }} />
      <button type="button" disabled={review.loading || mutation.busy} onClick={() => { clearFeedback(); review.refresh(); }}>Refresh requests</button>
    </div>
    <Notice>{notice}</Notice>
    <Notice error>{review.error || mutation.error}</Notice>
    {review.loading ? <Loading /> : !review.error && (rows.length === 0 ? <p>No {pending ? 'pending' : state === 'rejected' ? 'rejected' : 'approved'} requests.</p> : <>
      {rows.length === 50 && <p className="cu-muted">{pending ? 'Showing the oldest 50 pending requests. More requests may appear after review.' : 'Showing up to 50 most recent decisions in this state.'}</p>}
      <Table label="Direct OpenAI virtual key requests" headings={['Account', 'Requested', 'Use case', pending ? 'Decision' : 'Decided']}>
        {rows.map(({ username, displayName, request }) => <tr key={request.id}>
          <th scope="row">{displayName} (@{username})</th>
          <td>{date(request.requestedAt)}</td>
          <td style={{ overflowWrap: 'anywhere' }}>{request.reason}</td>
          <td>{pending ? <div className="cu-bar">
            <button type="button" disabled={mutation.busy} aria-label={`Approve for provisioning: @${username}`} onClick={() => void decide(request.id, true)}>Approve for provisioning</button>
            <button type="button" disabled={mutation.busy} aria-label={`Reject request: @${username}`} onClick={() => void decide(request.id, false)}>Reject</button>
          </div> : date(request.decidedAt)}</td>
        </tr>)}
      </Table>
    </>)}
  </Panel>;
}
