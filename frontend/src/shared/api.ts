export interface User { id: string; username: string; displayName: string; role: 'admin' | 'member' }
export interface Session { user: User; csrfToken: string; accessExpiresIn?: number }
let csrf = '', generation = 0;
let refreshing: Promise<void> | null = null;
let loggingOut = false;
export function setCsrf(value: string) { if (csrf !== value) { csrf = value; generation++; } }
function superseded(): never { throw new DOMException('Login changed while the request was pending', 'AbortError'); }
export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) { super(message); this.status = status; this.name = 'ApiError'; }
}
const messages: Record<number, string> = {
  400: 'Check the values and try again.',
  401: 'Sign in failed or your login was revoked. Please sign in again.',
  403: 'Access or request verification failed. Refresh and try again.',
  404: 'This record is no longer available.',
  409: 'This record changed. Refresh before trying again.',
  413: 'The request is too large.',
  429: 'Too many requests. Wait a minute before trying again.',
};
function send(path: string, options: RequestInit = {}) {
  const headers = new Headers(options.headers);
  headers.set('X-Portal-Request', '1'); headers.set('X-Portal-Persistent', '1');
  headers.set('X-CSRF-Token', csrf);
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');
  return fetch(path, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
}
function failure(response: Response, path: string) {
  return new ApiError(response.status, path === '/portal/api/register' && response.status === 409
    ? 'That username is unavailable. Choose another.' : messages[response.status] ?? `Request failed (${response.status}). Try again.`);
}
async function renew() {
  if (refreshing) return refreshing;
  const expected = generation;
  const work = async () => {
    // Another browser tab may already have renewed the shared cookies.
    const current = await send('/portal/api/session', { signal: AbortSignal.timeout(15000) });
    let response = current;
    if (current.status === 401) {
      await current.body?.cancel();
      response = await send('/portal/api/refresh', { method: 'POST', body: '{}', signal: AbortSignal.timeout(15000) });
    }
    if (!response.ok) {
      await response.body?.cancel();
      if (generation !== expected) superseded();
      throw failure(response, '/portal/api/refresh');
    }
    const value = await response.json() as Session;
    if (generation !== expected && value.csrfToken !== csrf) superseded();
    setCsrf(value.csrfToken);
  };
  const locks = typeof navigator !== 'undefined' ? navigator.locks : undefined;
  refreshing = (locks ? locks.request('control-usage-login-refresh', work) : work()).finally(() => { refreshing = null; });
  return refreshing;
}
/** Opaque access and refresh tokens stay in HttpOnly cookies. Only CSRF is readable by JavaScript. */
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('\\') || /[\u0000-\u0020\u007f]/.test(path)) throw new Error('A same-origin API path is required.');
  const auth = ['/portal/api/login', '/portal/api/register', '/portal/api/refresh'].includes(path);
  const logout = path === '/portal/api/logout';
  if ((auth || logout) && refreshing) await refreshing.catch(() => {});
  if (logout) loggingOut = true;
  const initialCsrf = csrf;
  const checkIdentity = () => { if (!auth && initialCsrf && initialCsrf !== csrf) superseded(); };
  try {
    let response = await send(path, options);
    checkIdentity();
    if (response.status === 401 && !auth && (!loggingOut || logout)) {
      await response.body?.cancel();
      try { await renew(); }
      catch (error) {
        // Connectivity/server failures preserve the login; only definitive rejection signs out.
        if (error instanceof ApiError && error.status === 401) {
          setCsrf('');
          if (path !== '/portal/api/session') window.dispatchEvent(new Event('control-usage:session-expired'));
        }
        throw error;
      }
      checkIdentity();
      if (options.signal?.aborted) throw new DOMException('Request aborted', 'AbortError');
      response = await send(path, options); // One retry after an authentication rejection only.
      checkIdentity();
    }
    if (!response.ok) {
      await response.body?.cancel();
      if (response.status === 401 && !auth) {
        setCsrf('');
        if (path !== '/portal/api/session') window.dispatchEvent(new Event('control-usage:session-expired'));
      }
      throw failure(response, path);
    }
    if (logout) setCsrf('');
    if (response.status === 204) return undefined as T;
    const body = await response.text();
    return (body ? JSON.parse(body) : undefined) as T;
  } finally { if (logout) loggingOut = false; }
}
export async function getSession(signal?: AbortSignal): Promise<Session> {
  const expected = generation;
  const session = await api<Session>('/portal/api/session', { signal });
  if (signal?.aborted) throw new DOMException('Request aborted', 'AbortError');
  if (generation !== expected && session.csrfToken !== csrf) superseded();
  setCsrf(session.csrfToken);
  return session;
}
