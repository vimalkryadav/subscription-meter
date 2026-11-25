import { useEffect, useState } from 'react';
import { api } from './api';
import './workspace.css';

type Tier = 'claude' | 'codex' | 'paid' | 'other';
interface Usage { tier: Tier; requests: number; tokens: number }
interface Entry { label: string; requests: number; tokens: number; you: boolean }
interface Overview { own: Usage[]; leaderboard: Entry[]; days: number }
const tiers = [
  { id: 'claude', name: 'Claude subscription', icon: '✳', note: 'Included subscription usage' },
  { id: 'codex', name: 'Codex subscription', icon: '◇', note: 'Included subscription usage' },
  { id: 'paid', name: 'Paid API fallback', icon: '↳', note: 'Metered OpenAI API usage' },
  { id: 'other', name: 'Other / unattributed', icon: '·', note: 'Not assigned to a routing tier' },
] as const;
const number = new Intl.NumberFormat();

export default function UsageWorkspace({ admin = false, view = 'usage' }: { admin?: boolean; view?: 'usage' | 'leaderboard' }) {
  const [days, setDays] = useState(7), [revision, setRevision] = useState(0);
  const [metric, setMetric] = useState<'tokens' | 'requests'>('requests');
  const [result, setResult] = useState<{ days: number; revision: number; data?: Overview; error?: string }>();
  useEffect(() => {
    const controller = new AbortController();
    api<Overview>(`${admin ? '/admin' : '/portal/api'}/usage-overview?days=${days}`, { signal: controller.signal })
      .then(data => { if (!controller.signal.aborted) setResult({ days, revision, data }); })
      .catch(error => { if (!controller.signal.aborted) setResult({ days, revision, error: error instanceof Error ? error.message : 'Usage is temporarily unavailable.' }); });
    return () => controller.abort();
  }, [admin, days, revision]);
  const current = result?.days === days && result.revision === revision ? result : undefined;
  const data = current?.data;
  const leaders = [...(data?.leaderboard ?? [])].sort((a, b) => b[metric] - a[metric]);
  const leaderboard = view === 'leaderboard';
  return <section className="ux-usage" aria-label={leaderboard ? 'Usage leaderboard' : 'Usage by route'}>
    <div className="ux-heading"><div><span className="ux-kicker">{leaderboard ? 'Team activity' : 'Routing activity'}</span><h2>{leaderboard ? 'Usage leaderboard' : admin ? 'Team usage by route' : 'Your usage by route'}</h2><p>{leaderboard ? 'Activity totals by display name. Usage is not a measure of productivity.' : 'See what ran on subscriptions and what reached the paid fallback.'}</p></div>
      <div className="ux-filters"><div className="ux-segment" role="group" aria-label="Usage period">{[7, 30].map(value => <button type="button" key={value} aria-pressed={days === value} onClick={() => setDays(value)}>{value} days</button>)}</div><button type="button" className="ux-refresh" onClick={() => setRevision(value => value + 1)}>Refresh</button></div>
    </div>
    {!current ? <p className="ux-state" role="status">Loading activity…</p> : current.error ? <p className="ux-state" role="alert">{current.error}</p> : data && (leaderboard ? <>
      <div className="ux-filters ux-ranking"><div className="ux-segment" role="group" aria-label="Rank by">{(['requests', 'tokens'] as const).map(value => <button type="button" key={value} aria-pressed={metric === value} onClick={() => setMetric(value)}>{value === 'requests' ? 'Requests' : 'Tokens'}</button>)}</div></div>
      {leaders.length === 0 ? <p className="ux-state">No leaderboard activity is available for this period.</p> : <div className="ux-table" tabIndex={0} role="region" aria-label="Leaderboard results"><table><thead><tr><th scope="col">Rank</th><th scope="col">Member</th><th scope="col" aria-sort={metric === 'requests' ? 'descending' : 'none'}>Requests</th><th scope="col" aria-sort={metric === 'tokens' ? 'descending' : 'none'}>Observed tokens</th></tr></thead><tbody>{leaders.map((entry, index) => <tr key={index} className={entry.you ? 'ux-you' : undefined}><td>{index === 0 || entry[metric] !== leaders[index - 1][metric] ? index + 1 : leaders.findIndex(other => other[metric] === entry[metric]) + 1}</td><th scope="row">{entry.label}{entry.you && ' (You)'}</th><td>{number.format(entry.requests)}</td><td>{number.format(entry.tokens)}</td></tr>)}</tbody></table></div>}
    </> : <div className="ux-tiers">{tiers.filter(tier => tier.id !== 'other' || data.own.some(row => row.tier === 'other' && row.requests > 0)).map(tier => {
      const usage = data.own.find(row => row.tier === tier.id);
      return <article key={tier.id} className="ux-tier"><span className="ux-tier-icon" aria-hidden="true">{tier.icon}</span><h3>{tier.name}</h3><p>{tier.note}</p><dl><div><dt>Requests</dt><dd>{number.format(usage?.requests ?? 0)}</dd></div><div><dt>Observed tokens</dt><dd>{number.format(usage?.tokens ?? 0)}</dd></div></dl></article>;
    })}</div>)}
    {data && <p className="ux-footnote">Gateway activity for the last {data.days} days. Observed tokens include cache activity and may be incomplete. These totals aren’t quota balances or charges.</p>}
  </section>;
}
