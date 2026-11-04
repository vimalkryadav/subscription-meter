export const filterNames = ['memberId', 'model', 'requestId', 'upstreamRequestId', 'state', 'status', 'captureState', 'minDurationMs', 'maxDurationMs', 'usageSeen', 'provider', 'upstreamAccount', 'clientFamily', 'keyPrefix', 'category'] as const;
export type Filters = Record<typeof filterNames[number] | 'days' | 'from' | 'to', string>;
export function emptyFilters(): Filters { return Object.fromEntries([...filterNames, 'from', 'to'].map(key => [key, '']).concat([['days', '7']])) as Filters; }
export function presetWindow(filters: Filters, days: string): Filters { return { ...filters, days, from: '', to: '' }; }
export function readFilters(search: string): Filters {
  const params = new URLSearchParams(search), result = emptyFilters();
  for (const key of filterNames) result[key] = params.get('rq_' + key) || '';
  if (['1', '7', '30'].includes(params.get('rq_days') || '')) result.days = params.get('rq_days')!;
  for (const key of ['from', 'to'] as const) { const raw = params.get('rq_' + key); if (raw && Number.isFinite(Date.parse(raw))) result[key] = new Date(raw).toISOString(); }
  return result;
}
export function searchWindow(filters: Filters, now = Date.now()): { from: string; to: string } {
  const to = filters.to ? Date.parse(filters.to) : now;
  const from = filters.from ? Date.parse(filters.from) : to - Number(filters.days) * 86400000;
  if (!Number.isFinite(from) || !Number.isFinite(to) || from >= to) throw new Error('Choose a start before the end of the search window.');
  return { from: new Date(from).toISOString(), to: new Date(to).toISOString() };
}
export function searchParams(filters: Filters, window: { from: string; to: string }, cursor = '') {
  const params = new URLSearchParams({ ...window, limit: '25' });
  for (const key of filterNames) if (filters[key].trim()) params.set(key, filters[key].trim());
  if (cursor) params.set('cursor', cursor);
  return params;
}
export function persistFilters(filters: Filters, detail?: string, navigate = false) {
  const url = new URL(location.href);
  for (const key of [...url.searchParams.keys()]) if (key.startsWith('rq_')) url.searchParams.delete(key);
  for (const key of [...filterNames, 'days', 'from', 'to'] as const) if (filters[key]) url.searchParams.set('rq_' + key, filters[key]);
  if (detail) url.searchParams.set('rq_detail', detail);
  if (navigate) url.hash = 'view/activity';
  history.pushState({}, '', url);
  dispatchEvent(new PopStateEvent('popstate'));
  if (navigate) dispatchEvent(new HashChangeEvent('hashchange'));
}
export function openRequestScope(fields: Partial<Filters>, from: string, to: string) { persistFilters({ ...emptyFilters(), ...fields, from, to }, undefined, true); }
export function localDateInput(iso: string) { if (!iso) return ''; const value = new Date(iso); return new Date(value.getTime() - value.getTimezoneOffset() * 60000).toISOString().slice(0, -1); }
export const captureMessages: Record<string, string> = {
  available: 'Retained capture available. Recognized credentials are filtered.',
  incomplete: 'The response ended incompletely or was interrupted. Retained inputs, outputs, and events may be partial; this is separate from capture storage failure.',
  pending: 'Capture is pending. Request completion and audit persistence are separate.',
  truncated: 'Configured capture limits truncated this data. Export and events may be incomplete.',
  error: 'Capture encountered an error. Retained data may be incomplete.',
  expired: 'Capture retention has elapsed; only remaining metadata is available.',
  unavailable: 'Capture is unavailable. It may not have arrived, may have failed, or was never captured. This does not mean there was no input or output.',
};
