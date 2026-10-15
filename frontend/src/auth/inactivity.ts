import { useEffect, useRef } from 'react';

/**
 * Signs the customer out after 15 minutes without interaction (the server ends the session on its own too: a token whose holder has
 * not reported activity for that long is refused). What counts is a person doing something in the page (pointer, key, touch,
 * scroll); timers, polling and requests do not. The last interaction is kept where every tab of the app can read it, so a person working in
 * one tab is not signed out of another, and all tabs end together. Elapsed time is judged from the clock whenever the page looks
 * (a tick, coming back to the tab, waking from sleep), never from counting ticks, and an interaction that arrives after the limit does not
 * bring the session back. Only the time of the last interaction is stored: no token, no form content.
 */
export const INACTIVITY_LIMIT_MS = 15 * 60 * 1000;
export const INACTIVITY_LOGOUT_MESSAGE = 'You were logged out after 15 minutes of inactivity for security.';

const LAST_ACTIVITY_KEY = 'handoffly.activity.last';
const LAST_REPORT_KEY = 'handoffly.activity.reported';
const NOTICE_KEY = 'handoffly.activity.logged-out';
/** The server is told about activity at most this often (its own limit allows for it). */
const REPORT_INTERVAL_MS = 60_000;
/** Interaction is written down at most this often, as pointer moves arrive in hundreds. */
const RECORD_INTERVAL_MS = 1_000;
const CHECK_INTERVAL_MS = 10_000;
const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'scroll', 'touchstart'] as const;

export type KeyValueStore = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

function read(store: KeyValueStore, key: string): number | null {
  try {
    const value = Number(store.getItem(key));
    return Number.isFinite(value) && value > 0 ? value : null;
  } catch {
    return null;   // storage may be blocked: the tab then only knows its own activity
  }
}

function write(store: KeyValueStore, key: string, value: number) {
  try { store.setItem(key, String(value)); } catch { /* see read */ }
}

/** A person has just signed in: the clock starts now. */
export function startActivityClock(store: KeyValueStore = localStorage, now: number = Date.now()) {
  write(store, LAST_ACTIVITY_KEY, now);
  write(store, LAST_REPORT_KEY, now);
}

/** True when the last recorded interaction is 15 minutes old or more. Nothing recorded is not "idle": the server judges such a session. */
export function hasIdleExpired(store: KeyValueStore = localStorage, now: number = Date.now()): boolean {
  const last = read(store, LAST_ACTIVITY_KEY);
  return last !== null && now - last >= INACTIVITY_LIMIT_MS;
}

/** Whether the person was last signed out for inactivity (kept until they sign in again, so every tab can say so once). */
export function readInactivityNotice(store: KeyValueStore = localStorage): boolean {
  try { return store.getItem(NOTICE_KEY) === '1'; } catch { return false; }
}

export function writeInactivityNotice(on: boolean, store: KeyValueStore = localStorage) {
  try {
    if (on) store.setItem(NOTICE_KEY, '1');
    else store.removeItem(NOTICE_KEY);
  } catch { /* the message is then only shown in this tab */ }
}

export interface InactivityDeps {
  now: () => number;
  store: KeyValueStore;
  /** Tell the server a person is active (called at most once a minute). */
  report: () => void;
  /** End the session. Called once. */
  expire: () => void;
}

/** The rules, without a page: the hook below feeds it events and ticks, tests feed it a clock. */
export function createInactivityMonitor({ now, store, report, expire }: InactivityDeps) {
  let ended = false;
  let ownLast = 0;      // this tab's own record: used only where the shared one cannot be read (storage blocked)
  let ownWritten = 0;

  const lastActivity = () => read(store, LAST_ACTIVITY_KEY) ?? (ownLast || null);
  const idle = () => {
    const last = lastActivity();
    return last !== null && now() - last >= INACTIVITY_LIMIT_MS;
  };
  const end = () => {
    if (ended) return;
    ended = true;
    expire();
  };

  return {
    /** A page was opened with a session already: if nothing is known about its last interaction, it starts now. */
    resume() {
      if (lastActivity() === null) {
        ownLast = now();
        write(store, LAST_ACTIVITY_KEY, ownLast);
        write(store, LAST_REPORT_KEY, ownLast);
      }
    },
    /** The person did something. After the limit that is too late: it ends the session instead of restoring it. */
    activity() {
      if (ended) return;
      if (idle()) { end(); return; }
      const t = now();
      if (t - ownWritten >= RECORD_INTERVAL_MS) {
        ownWritten = t;
        ownLast = t;
        write(store, LAST_ACTIVITY_KEY, t);
      }
      if (t - (read(store, LAST_REPORT_KEY) ?? 0) >= REPORT_INTERVAL_MS) {
        write(store, LAST_REPORT_KEY, t);
        report();
      }
    },
    /** Looks at the clock (a tick, the tab coming back, the machine waking). */
    check() {
      if (!ended && idle()) end();
    },
  };
}

/** While signed in: watches for interaction and ends the session at the limit. */
export function useInactivityLogout(signedIn: boolean, expire: () => void, report: () => void) {
  const latest = useRef({ expire, report });
  latest.current = { expire, report };

  useEffect(() => {
    if (!signedIn) return;
    const monitor = createInactivityMonitor({
      now: Date.now,
      store: localStorage,
      report: () => latest.current.report(),
      expire: () => latest.current.expire(),
    });
    monitor.resume();
    const onActivity = () => monitor.activity();
    const check = () => monitor.check();
    for (const name of ACTIVITY_EVENTS) window.addEventListener(name, onActivity, { capture: true, passive: true });
    document.addEventListener('visibilitychange', check);
    window.addEventListener('focus', check);
    window.addEventListener('pageshow', check);
    const timer = window.setInterval(check, CHECK_INTERVAL_MS);
    return () => {
      for (const name of ACTIVITY_EVENTS) window.removeEventListener(name, onActivity, { capture: true });
      document.removeEventListener('visibilitychange', check);
      window.removeEventListener('focus', check);
      window.removeEventListener('pageshow', check);
      window.clearInterval(timer);
    };
  }, [signedIn]);
}
