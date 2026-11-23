import { useState } from 'react';
import { count, date, Heading, Loading, money, Notice, Panel, Select, Stat, Table, text, useMutation, useResource } from './ui';
import type { Price } from './Broker';

interface RoutingStatus {
  gptModelsEnabled: boolean; fallbackEnabled: boolean; codexEnabled: boolean; brokerEnabled: boolean;
  mapOpus: string; mapSonnet: string; mapHaiku: string; memberWeeklyBudgetUsd: number; updatedAt: string; updatedBy: string;
  codexConfigured: boolean; memberKeys: number;
  state: { anthropicBlockedUntil: string | null; codexBlockedUntil: string | null; codexUsedPercent: number | null; codexResetAt: string | null; sessionsOnFallback: number };
  servedLast24h: { provider: string; account: string; requests: number }[];
}
const onOff = [{ value: 'true', label: 'On' }, { value: 'false', label: 'Off' }];
const servedLabel = (row: { provider: string; account: string }) => row.provider === 'anthropic' ? 'Claude subscription' : row.account === 'codex' ? 'Codex login' : row.account === 'broker' ? 'OpenAI API key (broker)' : row.provider + ' · ' + row.account;

export default function Routing() {
  const status = useResource<RoutingStatus>('/admin/routing'), broker = useResource<{ prices: Price[] }>('/admin/broker/state'), save = useMutation();
  const [form, setForm] = useState<Record<string, string> | null>(null), [saved, setSaved] = useState('');
  const data = status.data;
  const values = form ?? (data ? { gptModelsEnabled: String(data.gptModelsEnabled), fallbackEnabled: String(data.fallbackEnabled), codexEnabled: String(data.codexEnabled), brokerEnabled: String(data.brokerEnabled), mapOpus: data.mapOpus, mapSonnet: data.mapSonnet, mapHaiku: data.mapHaiku, memberWeeklyBudgetUsd: String(data.memberWeeklyBudgetUsd) } : null);
  const change = (name: string, value: string) => { setSaved(''); setForm({ ...values!, [name]: value }); };
  const models = (current: string) => [...(broker.data?.prices.some(p => p.model === current) ? [] : [{ value: current, label: current }]), ...(broker.data?.prices ?? []).map(p => ({ value: p.model, label: p.model + ' · $' + p.inputUsdPerMTok + ' / $' + p.outputUsdPerMTok }))];
  const st = data?.state;
  return <><Heading title="Routing" subtitle="When the Claude subscription runs out, serve Claude Code from the shared Codex login, then from each member’s own budget-capped OpenAI key."><button onClick={status.refresh} disabled={status.loading}>Refresh</button></Heading>
    <Notice error>{status.error || save.error}</Notice><Notice>{saved}</Notice>
    {status.loading && !data ? <Loading /> : data && values && st && <>
      <div className="cu-grid cu-stats">
        <Stat title="Claude subscription" value={st.anthropicBlockedUntil ? 'Out of quota' : 'Serving'} note={st.anthropicBlockedUntil ? 'Until ' + date(st.anthropicBlockedUntil) : 'Fallback starts on Claude’s quota refusal'} />
        <Stat title="Codex login" value={!data.codexConfigured ? 'Not connected' : st.codexBlockedUntil ? 'Out of quota' : st.codexUsedPercent == null ? 'Ready' : st.codexUsedPercent + '% used'} note={st.codexBlockedUntil ? 'Until ' + date(st.codexBlockedUntil) : st.codexResetAt ? 'Weekly window resets ' + date(st.codexResetAt) : 'Usage appears after the first Codex request'} />
        <Stat title="Sessions on fallback" value={count(st.sessionsOnFallback)} note="A moved session stays on its fallback while active" />
        <Stat title="Member fallback keys" value={count(data.memberKeys)} note={'Created on first use · ' + money(data.memberWeeklyBudgetUsd) + ' / week each'} />
      </div>
      <Panel title="Who served Claude Code requests (last 24 hours)"><Table headings={['Served by', 'Requests']} empty={!data.servedLast24h.length}>{data.servedLast24h.map(row => <tr key={row.provider + row.account}><td>{servedLabel(row)}</td><td>{count(row.requests)}</td></tr>)}</Table></Panel>
      <Panel title="Settings"><form className="cu-filter-grid" onSubmit={async event => {
        event.preventDefault();
        try {
          await save.run('/admin/routing', 'PUT', { gptModelsEnabled: values.gptModelsEnabled === 'true', fallbackEnabled: values.fallbackEnabled === 'true', codexEnabled: values.codexEnabled === 'true', brokerEnabled: values.brokerEnabled === 'true', mapOpus: values.mapOpus, mapSonnet: values.mapSonnet, mapHaiku: values.mapHaiku, memberWeeklyBudgetUsd: Number(values.memberWeeklyBudgetUsd) });
          setForm(null); setSaved('Routing saved. It applies within a few seconds.'); status.refresh();
        } catch { /* error above */ }
      }}>
        <Select label="Automatic fallback" value={values.fallbackEnabled} onChange={v => change('fallbackEnabled', v)} items={onOff} />
        <Select label="Members may choose OpenAI models (/model gpt-…)" value={values.gptModelsEnabled} onChange={v => change('gptModelsEnabled', v)} items={onOff} />
        <Select label="Tier 2: Codex login" value={values.codexEnabled} onChange={v => change('codexEnabled', v)} items={onOff} />
        <Select label="Tier 3: member OpenAI keys (broker)" value={values.brokerEnabled} onChange={v => change('brokerEnabled', v)} items={onOff} />
        <Select label="Claude Opus is served by" value={values.mapOpus} onChange={v => change('mapOpus', v)} items={models(values.mapOpus)} />
        <Select label="Claude Sonnet (and others) by" value={values.mapSonnet} onChange={v => change('mapSonnet', v)} items={models(values.mapSonnet)} />
        <Select label="Claude Haiku is served by" value={values.mapHaiku} onChange={v => change('mapHaiku', v)} items={models(values.mapHaiku)} />
        <label>New member key budget (USD / week)<input required type="number" min="0" max="1000" step="0.01" value={values.memberWeeklyBudgetUsd} onChange={event => change('memberWeeklyBudgetUsd', event.target.value)} /></label>
        <div className="cu-bar"><button className="cu-primary" disabled={save.busy || !form}>{save.busy ? 'Saving…' : 'Save routing'}</button>{form && <button type="button" onClick={() => setForm(null)}>Discard changes</button>}</div>
      </form>
        <p className="cu-muted">The Codex login only offers some models (the gpt-6 and gpt-5.6 families); other mapped models are served by the member’s OpenAI key. Changing the new-key budget does not change existing keys: edit those under OpenAI API. Changed by {text(data.updatedBy)} · {date(data.updatedAt)}.</p></Panel>
    </>}
  </>;
}
