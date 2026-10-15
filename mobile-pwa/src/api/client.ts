import type { ApiError } from './types';

/** Empty (the same origin, `/api/v1/...`) unless the app is served apart from the API: then the API's address, set at build time. */
const BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');
const TOKEN_KEY = 'handoffly.mobile.token';

// The session is the backend's JWT, kept like the desktop app keeps it. The password is never stored.
let authToken: string | null = readStoredToken();
let sessionEnded: (() => void) | undefined;

function readStoredToken(): string | null {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}

export function setToken(token: string | null) {
  authToken = token;
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch { /* storage may be blocked (private mode): the session then lasts until the page closes */ }
}

export function getToken(): string | null {
  return authToken;
}

// Another tab or installed window of the app signed out, or renewed the session: follow it, so that this one neither keeps using a token that was ended nor an older copy of it.
window.addEventListener('storage', (e) => {
  if (e.key !== TOKEN_KEY) return;
  authToken = e.newValue;
  if (e.newValue === null) sessionEnded?.();
});

/** Called when the backend stops accepting this session (expired, or ended by a password change elsewhere). */
export function onSessionEnded(handler: () => void) {
  sessionEnded = handler;
}

export class HttpError extends Error {
  constructor(public readonly error: ApiError) {
    super(error.detail || `Request failed (${error.status})`);
  }
}

/** The backend's codes for "this account has no paid-up plan, so this cannot be done". */
const SUBSCRIPTION_CODES = ['subscription_expired', 'no_active_subscription'];

export function isSubscriptionRefusal(err: unknown): boolean {
  return err instanceof HttpError && !!err.error.code && SUBSCRIPTION_CODES.includes(err.error.code);
}

/** The request never got an answer: offline, the server is down, or the address is wrong. */
export function isNetworkError(err: unknown): boolean {
  return err instanceof HttpError && err.error.status === 0;
}

const UNREACHABLE = 'HandOffly can’t be reached right now. Check your connection and try again.';

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const data = await res.json();
    return { status: res.status, code: data.code, detail: data.detail || data.title, errors: data.errors };
  } catch {
    return { status: res.status, detail: res.statusText };
  }
}

async function send(path: string, init: RequestInit, auth: boolean): Promise<Response> {
  const headers = new Headers(init.headers);
  if (auth && authToken) headers.set('Authorization', `Bearer ${authToken}`);
  let res: Response;
  try {
    res = await fetch(`${BASE}/api/v1${path}`, { ...init, headers, cache: 'no-store' });
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err;
    throw new HttpError({ status: 0, detail: UNREACHABLE });
  }
  if (res.status === 401 && auth) {
    // Token invalid, expired or ended: clear it and tell the app, so it goes back to the sign-in page.
    setToken(null);
    sessionEnded?.();
  }
  if (!res.ok) throw new HttpError(await toApiError(res));
  return res;
}

type Options = { method?: string; body?: unknown; auth?: boolean; signal?: AbortSignal };

export async function api<T>(path: string, opts: Options = {}): Promise<T> {
  const { method = 'GET', body, auth = true, signal } = opts;
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await send(path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined, signal }, auth);
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

/** Authenticated or token-scoped download (a plain link cannot send the auth header): a blob plus the server's filename. */
export async function downloadFile(path: string, auth = true): Promise<{ blob: Blob; filename: string | null }> {
  const res = await send(path, {}, auth);
  const name = res.headers.get('Content-Disposition')?.match(/filename="?([^";]+)"?/i)?.[1];
  return { blob: await res.blob(), filename: name ?? null };
}

/** Hands a downloaded file to the phone (its download or viewer). The app never keeps it: the blob address is dropped after a minute. */
export function openBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
