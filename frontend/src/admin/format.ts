export function date(value: unknown): string {
  if (value == null || value === '') return '—';
  const parsed = typeof value === 'number' ? new Date(value) : new Date(String(value));
  return Number.isFinite(parsed.getTime()) ? parsed.toLocaleString() : '—';
}
