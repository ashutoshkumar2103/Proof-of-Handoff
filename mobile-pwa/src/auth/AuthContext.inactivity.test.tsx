// @vitest-environment jsdom
import { act, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getToken, setToken } from '../api/client';
import { makeUser } from '../test/utils';
import { AuthProvider, useAuth } from './AuthContext';
import { INACTIVITY_LIMIT_MS } from './inactivity';

const TOKEN_KEY = 'handoffly.mobile.token';
const LAST_ACTIVITY_KEY = 'handoffly.mobile.activity.last';
const NOTICE_KEY = 'handoffly.mobile.activity.logged-out';
const MINUTE = 60_000;

function Probe() {
  const auth = useAuth();
  return (
    <div>
      <span data-testid="who">{auth.user?.displayName ?? 'nobody'}</span>
      <span data-testid="notice">{String(auth.inactivityLogout)}</span>
    </div>
  );
}

/** The app's session handling on its own, with a clock the test moves and a backend it controls. */
describe('inactivity logout in the app', () => {
  let now: number;
  let tick: (() => void) | undefined;
  let calls: { path: string; method: string }[];
  let respond: (path: string) => Response;
  const realSetInterval = window.setInterval.bind(window);

  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

  beforeEach(() => {
    now = 1_900_000_000_000;
    tick = undefined;
    calls = [];
    localStorage.clear();
    respond = (path) => path.endsWith('/auth/activity') ? json({ token: 'renewed-token', expiresAt: '2030-01-01T00:00:00Z' }) : json(makeUser({ displayName: 'Test Owner' }));
    vi.spyOn(Date, 'now').mockImplementation(() => now);
    // The 10-second look at the clock is run by the test, at the moment it chooses.
    vi.spyOn(window, 'setInterval').mockImplementation(((fn: () => void, ms?: number) => {
      if (ms === 10_000) { tick = fn; return 1; }
      return realSetInterval(fn, ms);
    }) as typeof window.setInterval);
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      const path = String(url).replace('/api/v1', '');
      calls.push({ path, method: init?.method ?? 'GET' });
      return respond(path);
    }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    setToken(null);
    localStorage.clear();
  });

  async function signedIn() {
    setToken('session-token');
    localStorage.setItem(LAST_ACTIVITY_KEY, String(now));
    render(
      <QueryClientProvider client={new QueryClient()}>
        <AuthProvider><Probe /></AuthProvider>
      </QueryClientProvider>,
    );
    await screen.findByText('Test Owner');
    calls.length = 0;
  }

  const look = () => act(() => { tick?.(); });
  const touch = (name = 'pointermove') => act(() => { window.dispatchEvent(new Event(name)); });
  const who = () => screen.getByTestId('who').textContent;
  const reports = () => calls.filter((c) => c.path === '/auth/activity');

  it('signs the person out at 15 minutes of inactivity: no token, no data, and the message', async () => {
    await signedIn();
    now += INACTIVITY_LIMIT_MS - 1;
    look();
    expect(who()).toBe('Test Owner');

    now += 1;
    look();
    expect(who()).toBe('nobody');
    expect(getToken()).toBeNull();
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(screen.getByTestId('notice')).toHaveTextContent('true');
    expect(localStorage.getItem(NOTICE_KEY)).toBe('1');
    expect(calls).toEqual([]);   // ending it asks the backend for nothing
  });

  it('interaction before the limit keeps the session, and tells the backend so (and keeps the renewed token)', async () => {
    await signedIn();
    now += 10 * MINUTE;
    touch();
    await waitFor(() => expect(getToken()).toBe('renewed-token'));
    expect(reports()).toHaveLength(1);
    expect(reports()[0].method).toBe('POST');

    now += INACTIVITY_LIMIT_MS - 1;   // 14:59 after that interaction
    look();
    expect(who()).toBe('Test Owner');
    now += 1;
    look();
    expect(who()).toBe('nobody');
  });

  it('is not kept alive by background requests, timers or the tab coming to the front', async () => {
    await signedIn();
    for (let i = 0; i < 14; i++) {
      now += MINUTE;
      look();
      // The app asks for the account again when it comes back to the front; that is not a person doing something.
      Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
      act(() => { document.dispatchEvent(new Event('visibilitychange')); });
    }
    expect(who()).toBe('Test Owner');
    now += MINUTE;
    look();
    expect(who()).toBe('nobody');
    expect(reports()).toEqual([]);
  });

  it('does not let the first touch after a long sleep bring the session back', async () => {
    await signedIn();
    now += 8 * 60 * MINUTE;   // the phone slept: no timer ran
    touch();                  // and the first thing that happens is a touch
    expect(who()).toBe('nobody');
    expect(getToken()).toBeNull();
    expect(reports()).toEqual([]);
    touch('keydown');
    expect(who()).toBe('nobody');
  });

  it('ends the session when the page comes back to the front after the limit', async () => {
    await signedIn();
    now += 30 * MINUTE;
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    act(() => { document.dispatchEvent(new Event('visibilitychange')); });
    await waitFor(() => expect(who()).toBe('nobody'));
  });

  it('follows another tab that signed out, and an older copy of the token is replaced by a renewed one', async () => {
    await signedIn();
    act(() => { window.dispatchEvent(new StorageEvent('storage', { key: TOKEN_KEY, newValue: 'renewed-elsewhere' })); });
    expect(getToken()).toBe('renewed-elsewhere');
    expect(who()).toBe('Test Owner');

    act(() => { window.dispatchEvent(new StorageEvent('storage', { key: TOKEN_KEY, newValue: null })); });
    expect(getToken()).toBeNull();
    expect(who()).toBe('nobody');
  });

  it('a second tab sees the message the first one set, once the token is gone', async () => {
    await signedIn();
    localStorage.setItem(NOTICE_KEY, '1');   // what the tab that timed out wrote first
    act(() => { window.dispatchEvent(new StorageEvent('storage', { key: TOKEN_KEY, newValue: null })); });
    expect(who()).toBe('nobody');
    expect(screen.getByTestId('notice')).toHaveTextContent('true');
  });

  it('does not ask for any data when it opens after 15 minutes or more away', async () => {
    setToken('session-token');
    localStorage.setItem(LAST_ACTIVITY_KEY, String(now - 20 * MINUTE));
    render(
      <QueryClientProvider client={new QueryClient()}>
        <AuthProvider><Probe /></AuthProvider>
      </QueryClientProvider>,
    );
    await waitFor(() => expect(screen.getByTestId('notice')).toHaveTextContent('true'));
    expect(who()).toBe('nobody');
    expect(getToken()).toBeNull();
    expect(calls).toEqual([]);
  });

  it('a session the backend already ended is one clean sign-out: one request, no retry, no loop', async () => {
    respond = () => json({ status: 401, detail: 'Authentication required.' }, 401);
    setToken('session-token');
    localStorage.setItem(LAST_ACTIVITY_KEY, String(now));
    render(
      <QueryClientProvider client={new QueryClient()}>
        <AuthProvider><Probe /></AuthProvider>
      </QueryClientProvider>,
    );
    await waitFor(() => expect(who()).toBe('nobody'));
    expect(calls).toEqual([{ path: '/auth/me', method: 'GET' }]);
    expect(getToken()).toBeNull();
    expect(screen.getByTestId('notice')).toHaveTextContent('false');   // not an inactivity logout: no such message
  });
});
