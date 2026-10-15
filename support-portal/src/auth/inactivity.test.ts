import { describe, expect, it } from 'vitest';
import {
  createInactivityMonitor, hasIdleExpired, INACTIVITY_LIMIT_MS, readInactivityNotice, startActivityClock, writeInactivityNotice,
} from './inactivity';
import type { KeyValueStore } from './inactivity';

const MINUTE = 60_000;
const SECOND = 1_000;

/** What localStorage is to the tabs of one browser: one place they all read and write. */
function sharedStore(): KeyValueStore {
  const data = new Map<string, string>();
  return {
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => { data.set(k, v); },
    removeItem: (k) => { data.delete(k); },
  };
}

/** One browser: a clock the test moves, a shared store, and any number of tabs (monitors) on them. */
function browser() {
  const clock = { t: 1_000_000 };
  const store = sharedStore();
  const reports: number[] = [];
  const expired: string[] = [];
  const tab = (name: string) => createInactivityMonitor({
    now: () => clock.t,
    store,
    report: () => { reports.push(clock.t); },
    expire: () => { expired.push(name); },
  });
  return { clock, store, reports, expired, tab, advance: (ms: number) => { clock.t += ms; } };
}

describe('15 minutes of inactivity', () => {
  it('logs out at exactly 15 minutes after the last interaction, once', () => {
    const b = browser();
    const tab = b.tab('a');
    startActivityClock(b.store, b.clock.t);

    b.advance(INACTIVITY_LIMIT_MS - 1);
    tab.check();
    expect(b.expired).toEqual([]);

    b.advance(1);
    tab.check();
    tab.check();
    b.advance(10 * SECOND);
    tab.check();
    expect(b.expired).toEqual(['a']);
  });

  it('restarts the 15 minutes whenever the person interacts', () => {
    const b = browser();
    const tab = b.tab('a');
    startActivityClock(b.store, b.clock.t);

    b.advance(10 * MINUTE);
    tab.activity();
    b.advance(INACTIVITY_LIMIT_MS - 1);   // 14:59 after the last interaction, 24:59 after sign-in
    tab.check();
    expect(b.expired).toEqual([]);

    b.advance(1);
    tab.check();
    expect(b.expired).toEqual(['a']);
  });

  it('never logs out a person who keeps working, however long the page stays open', () => {
    const b = browser();
    const tab = b.tab('a');
    startActivityClock(b.store, b.clock.t);

    for (let i = 0; i < 20; i++) {   // five hours, a click every 14 minutes
      b.advance(14 * MINUTE);
      tab.check();
      tab.activity();
    }
    expect(b.expired).toEqual([]);
  });

  it('is not restarted by timers, polling or checks: only interaction counts', () => {
    const b = browser();
    const tab = b.tab('a');
    startActivityClock(b.store, b.clock.t);

    // A page that polls and ticks all the time, and is never touched.
    for (let s = 10; s < 15 * 60; s += 10) {
      b.advance(10 * SECOND);
      tab.check();
    }
    expect(b.expired).toEqual([]);
    b.advance(10 * SECOND);   // 15:00
    tab.check();
    expect(b.expired).toEqual(['a']);
    expect(b.reports).toEqual([]);   // nothing was reported to the server as activity either
  });

  it('does not let an interaction after the limit bring the session back (sleep, suspend, a stale tab)', () => {
    const b = browser();
    const tab = b.tab('a');
    startActivityClock(b.store, b.clock.t);

    b.advance(8 * 60 * MINUTE);   // the laptop slept: no tick ran at all
    tab.activity();               // the first thing that happens is the person touching the mouse
    expect(b.expired).toEqual(['a']);
    expect(b.reports).toEqual([]);
    expect(hasIdleExpired(b.store, b.clock.t)).toBe(true);   // the touch was not recorded as activity

    b.advance(MINUTE);
    tab.activity();
    expect(b.expired).toEqual(['a']);   // still one
  });

  it('judges a woken page by the clock, not by how many ticks it counted', () => {
    const b = browser();
    const tab = b.tab('a');
    startActivityClock(b.store, b.clock.t);
    b.advance(30 * MINUTE);   // timers were frozen; the page comes back to the front
    tab.check();
    expect(b.expired).toEqual(['a']);
  });

  it('keeps every tab signed in while the person works in any one of them, and ends them all together', () => {
    const b = browser();
    const a = b.tab('a');
    const c = b.tab('b');
    startActivityClock(b.store, b.clock.t);

    b.advance(10 * MINUTE);
    a.activity();                 // working in tab a
    b.advance(10 * MINUTE);       // tab b has had no interaction for 20 minutes
    c.check();
    expect(b.expired).toEqual([]);

    b.advance(5 * MINUTE);        // now 15 minutes since the last interaction anywhere
    a.check();
    c.check();
    expect(b.expired.sort()).toEqual(['a', 'b']);
  });

  it('tells the server about activity at most once a minute, and only because of interaction', () => {
    const b = browser();
    const tab = b.tab('a');
    startActivityClock(b.store, b.clock.t);
    const start = b.clock.t;

    for (let s = 1; s <= 180; s++) {   // a second-by-second burst of interaction for three minutes
      b.advance(SECOND);
      tab.activity();
    }
    expect(b.reports).toEqual([start + 60 * SECOND, start + 120 * SECOND, start + 180 * SECOND]);
  });

  it('reports again, once, for the first interaction after a pause (two tabs do not both report)', () => {
    const b = browser();
    const a = b.tab('a');
    const c = b.tab('b');
    startActivityClock(b.store, b.clock.t);

    b.advance(5 * MINUTE);
    a.activity();
    c.activity();   // the same moment, in the other tab
    expect(b.reports).toHaveLength(1);
  });

  it('starts counting when a page opens with a session and no record, but never from a record that is already old', () => {
    const fresh = browser();
    fresh.tab('a').resume();
    expect(hasIdleExpired(fresh.store, fresh.clock.t + 14 * MINUTE)).toBe(false);
    expect(hasIdleExpired(fresh.store, fresh.clock.t + 15 * MINUTE)).toBe(true);

    const old = browser();
    startActivityClock(old.store, old.clock.t);
    old.advance(2 * 60 * MINUTE);
    old.tab('a').resume();
    expect(hasIdleExpired(old.store, old.clock.t)).toBe(true);   // opening the page does not renew it
  });

  it('knows nothing is wrong when nothing was ever recorded (the server judges that session)', () => {
    expect(hasIdleExpired(sharedStore(), Date.now())).toBe(false);
  });

  it('still works in a browser that blocks storage: the tab goes by its own interactions', () => {
    const clock = { t: 5_000_000 };
    const blocked: KeyValueStore = {
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
      removeItem: () => { throw new Error('blocked'); },
    };
    const expired: string[] = [];
    const tab = createInactivityMonitor({ now: () => clock.t, store: blocked, report: () => {}, expire: () => { expired.push('a'); } });
    tab.resume();
    clock.t += 14 * MINUTE;
    tab.activity();
    clock.t += 14 * MINUTE;
    tab.check();
    expect(expired).toEqual([]);
    clock.t += MINUTE;
    tab.check();
    expect(expired).toEqual(['a']);
  });

  it('keeps the "logged out for inactivity" notice until the next sign-in', () => {
    const store = sharedStore();
    expect(readInactivityNotice(store)).toBe(false);
    writeInactivityNotice(true, store);
    expect(readInactivityNotice(store)).toBe(true);
    writeInactivityNotice(false, store);
    expect(readInactivityNotice(store)).toBe(false);
  });
});
