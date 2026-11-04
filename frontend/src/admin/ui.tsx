import { useEffect, useId, useRef, useState } from 'react';
import type { ReactNode, KeyboardEvent } from 'react';
import { api } from '../shared/api';
import { startResource } from './resource';

export type Row = Record<string, unknown>;
export const text = (value: unknown) => value == null ? '—' : String(value);
export const count = (value: unknown) => value == null ? '—' : new Intl.NumberFormat().format(Number(value));
export { date } from './format';
export const duration = (value: unknown) => value == null ? '—' : Number(value) >= 1000 ? (Number(value) / 1000).toFixed(2) + ' s' : Number(value).toFixed(0) + ' ms';
export const money = (value: unknown, currency = 'USD') => value == null ? 'Unpriced' : new Intl.NumberFormat('en', { style: 'currency', currency, maximumFractionDigits: 4 }).format(Number(value));
export const pretty = (value: unknown): string => {
  if (value == null) return 'Not captured';
  if (typeof value === 'string') { try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; } }
  return JSON.stringify(value, null, 2);
};
export const categories = ['implementation', 'debugging', 'testing', 'refactoring', 'review', 'documentation', 'research', 'planning', 'operations', 'mixed', 'unknown'];
export const options = (values: string[]) => values.map(value => ({ value, label: value.replaceAll('_', ' ') }));
export const providers = [{ value: '', label: 'All providers' }, { value: 'anthropic', label: 'Anthropic' }, { value: 'openai', label: 'OpenAI' }];

/** Every resource owns cancellation and ignores responses from superseded requests. */
export function useResource<T>(path: string | null) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(!!path);
  const [revision, setRevision] = useState(0);
  const [loadedPath, setLoadedPath] = useState<string | null>(null);
  useEffect(() => {
    if (!path) { setData(undefined); setLoadedPath(null); setLoading(false); setError(''); return; }
    setLoading(true); setError('');
    return startResource<T>(path, api<T>, {
      success: value => { setData(value); setLoadedPath(path); },
      failure: failure => setError((failure instanceof Error ? failure.message : 'Request failed') + ' Previously loaded data may be stale.'),
      complete: () => setLoading(false),
    });
  }, [path, revision]);
  return { data: loadedPath === path ? data : undefined, error, loading, refresh: () => setRevision(value => value + 1) };
}

export function useMutation() {
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const active = useRef(false), mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  async function run<T>(path: string, method: string, body?: unknown): Promise<T | undefined> {
    if (active.current) return;
    active.current = true; setBusy(true); setError('');
    try { return await api<T>(path, { method, ...(body === undefined ? {} : { body: JSON.stringify(body) }) }); }
    catch (failure) { if (mounted.current) setError(failure instanceof Error ? failure.message : 'Change failed'); throw failure; }
    finally { active.current = false; if (mounted.current) setBusy(false); }
  }
  return { busy, error, run, clearError: () => setError('') };
}

export function Notice({ children, error = false }: { children?: ReactNode; error?: boolean }) {
  return children ? <div className={error ? 'cu-notice cu-error' : 'cu-notice'} role={error ? 'alert' : 'status'}>{children}</div> : null;
}
export function Heading({ title, subtitle, children }: { title: string; subtitle?: string; children?: ReactNode }) {
  return <div className="cu-heading"><div><p className="cu-eyebrow">CONTROL-USAGE / ADMIN</p><h1>{title}</h1>{subtitle && <p>{subtitle}</p>}</div>{children}</div>;
}
export function Panel({ title, children }: { title: string; children: ReactNode }) { return <section className="cu-panel"><h2>{title}</h2>{children}</section>; }
export function Table({ headings, children, empty, label }: { headings: string[]; children: ReactNode; empty?: boolean; label?: string }) {
  return <div className="cu-table-scroll" tabIndex={0} role="region" aria-label={label || headings.join(', ')}><table><thead><tr>{headings.map((heading, i) => <th key={i} scope="col">{heading || 'Actions'}</th>)}</tr></thead><tbody>{empty ? <tr><td colSpan={headings.length} className="cu-empty">No matching records.</td></tr> : children}</tbody></table></div>;
}
export function Stat({ title, value, note }: { title: string; value: ReactNode; note?: ReactNode }) { return <article className="cu-stat"><h2>{title}</h2><strong>{value}</strong>{note && <p>{note}</p>}</article>; }
export function Segments<T extends string | number>({ label, values, value, onChange }: { label: string; values: { value: T; label: string }[]; value: T; onChange: (value: T) => void }) {
  return <div className="cu-segments" role="group" aria-label={label}>{values.map(item => <button type="button" key={item.value} aria-pressed={value === item.value} onClick={() => onChange(item.value)}>{item.label}</button>)}</div>;
}

/** Custom select with keyboard, typeahead, focus restoration and click-away support. */
export function Select({ label, value, onChange, items, disabled = false }: { label: string; value: string; onChange: (value: string) => void; items: { value: string; label: string }[]; disabled?: boolean }) {
  const id = useId(), root = useRef<HTMLDivElement>(null), trigger = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false), [focused, setFocused] = useState(0);
  const buffer = useRef({ value: '', at: 0 });
  const selected = Math.max(0, items.findIndex(item => item.value === value));
  useEffect(() => {
    if (!open) return;
    root.current?.querySelector<HTMLButtonElement>(`[data-option="${focused}"]`)?.focus();
  }, [open, focused]);
  useEffect(() => {
    const close = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false); };
    document.addEventListener('pointerdown', close); return () => document.removeEventListener('pointerdown', close);
  }, []);
  function choose(index: number) { onChange(items[index].value); setOpen(false); trigger.current?.focus(); }
  function key(event: KeyboardEvent) {
    if (event.key === 'Tab') { setOpen(false); return; }
    if (event.key === 'Escape') { event.preventDefault(); setOpen(false); trigger.current?.focus(); return; }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const current = open ? focused : selected;
      setFocused(event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : Math.max(0, Math.min(items.length - 1, current + (event.key === 'ArrowDown' ? 1 : -1)))); setOpen(true);
    } else if (open && (event.key === 'Enter' || event.key === ' ')) { event.preventDefault(); choose(focused); }
    else if (event.key.length === 1 && event.key !== ' ' && !event.ctrlKey && !event.metaKey) {
      event.preventDefault(); const now = Date.now(); buffer.current = { value: (now - buffer.current.at < 600 ? buffer.current.value : '') + event.key.toLowerCase(), at: now };
      const match = items.findIndex(item => item.label.toLowerCase().startsWith(buffer.current.value)); if (match >= 0) { setFocused(match); setOpen(true); }
    }
  }
  return <div className="cu-select-field"><span id={id + '-label'}>{label}</span><div className="cu-select" ref={root} onKeyDown={key} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
    <button type="button" ref={trigger} disabled={disabled} aria-haspopup="listbox" aria-expanded={open} aria-controls={id} aria-labelledby={id + '-label ' + id + '-value'} onClick={() => { setFocused(selected); setOpen(!open); }}><span id={id + '-value'}>{items[selected]?.label || 'Choose'}</span><span aria-hidden="true">⌄</span></button>
    {open && <div id={id} role="listbox" aria-labelledby={id + '-label'} className="cu-select-menu">{items.map((item, index) => <button type="button" role="option" key={item.value} data-option={index} aria-selected={item.value === value} tabIndex={index === focused ? 0 : -1} onClick={() => choose(index)}>{item.label}</button>)}</div>}
  </div></div>;
}

export function Confirm({ title, description, onConfirm, onClose, busy }: { title: string; description: string; onConfirm: () => void; onClose: () => void; busy?: boolean }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { const node = dialog.current; node?.showModal(); return () => node?.close(); }, []);
  const id = useId();
  return <dialog className="cu-dialog" ref={dialog} aria-labelledby={id} onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}><h2 id={id}>{title}</h2><p>{description}</p><div className="cu-bar"><button autoFocus onClick={onClose} disabled={busy}>Cancel</button><button className="cu-danger" disabled={busy} onClick={onConfirm}>{busy ? 'Saving…' : 'Confirm'}</button></div></dialog>;
}
