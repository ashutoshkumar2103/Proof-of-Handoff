import { useCallback, useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { isSubscriptionEnded } from '../api/client';
import { authApi } from '../api/endpoints';
import type { User } from '../api/types';
import { useNoActivePlanModal, useSubscriptionEndedModal } from '../components/ui';
import { useAuth } from './AuthContext';

/** How often a gated page asks the backend whether the subscription is still active while it is open. */
const SUBSCRIPTION_CHECK_MS = 30_000;

export type SubscriptionState = 'checking' | 'active' | 'ended';

export interface SubscriptionGate {
  /** `checking` until the backend has first answered, then whatever it last said. */
  state: SubscriptionState;
  /** The page must not be usable now: the subscription has ended, or the backend just refused something for that reason. */
  blocked: boolean;
  /** Whether the page has been found usable at least once. If not (it opened with an ended subscription) it shows nothing but the dialog. */
  shown: boolean;
  /**
   * For an error from a request this page makes: if it is the backend refusing because the subscription ended, block the
   * page and show the dialog, and return true; otherwise return false and leave the error to the caller.
   */
  handle: (error: unknown) => boolean;
  /** The freshest account the page can trust: what the backend last said, or the signed-in account if it could not be asked. */
  account: User | undefined;
  /** Asks the backend again now (never a second request while one is already on its way). */
  recheck: () => void;
  /** The account was just changed from this page (an upgrade): asks again now, replacing any check already on its way. */
  refresh: () => void;
}

/**
 * Keeps a page that needs an active subscription (New handoff, HandoffCheck) honest while it is open, using the account's
 * own `me` — there is no separate endpoint. The backend is asked the moment the page opens, every 30 seconds while the tab is
 * visible, and again at once when the customer comes back to the tab or the window. If the subscription has ended the page
 * is blocked and the one shared dialog is shown (a single OK, then the Dashboard — for an account that never had a plan,
 * the no-plan dialog with the ways to reach support instead); if it turns out to be active again the
 * dialog comes down by itself and the page is usable. This only makes the screen react promptly. It is NOT what protects
 * anything: the backend judges every request afresh from the clock, whatever any page last saw.
 */
export function useSubscriptionGate(): SubscriptionGate {
  const { user, updateUser } = useAuth();
  const showEnded = useSubscriptionEndedModal();
  const showNoPlan = useNoActivePlanModal();

  const check = useQuery({
    queryKey: ['subscription-watch'],
    queryFn: ({ signal }) => authApi.me(signal),   // the signal cancels the request if the page goes away mid-check
    refetchInterval: SUBSCRIPTION_CHECK_MS,        // not while the tab is hidden: returning to it checks at once (below)
    staleTime: 0,
    gcTime: 0,                                     // every visit starts with a fresh check, never an old answer
    retry: false,                                  // a failed check must not hold the page up; the next one follows
  });
  const { data, isError, refetch } = check;
  // Never a second request while one is already on its way, however many reasons arrive together.
  const recheck = useCallback(() => { void refetch({ cancelRefetch: false }); }, [refetch]);

  const refresh = useCallback(() => { void refetch(); }, [refetch]);

  useEffect(() => {
    const onVisible = () => { if (document.visibilityState === 'visible') recheck(); };
    window.addEventListener('focus', recheck);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.removeEventListener('focus', recheck);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [recheck]);

  // Keep the signed-in account in step with what the backend says (it may have ended, or support may have renewed it).
  useEffect(() => {
    if (data && user && (data.subscription.status !== user.subscription.status
        || data.subscription.validUntil !== user.subscription.validUntil || data.plan !== user.plan)) {
      updateUser(data);
    }
  }, [data, user, updateUser]);

  // If the very first check cannot be made, fall back on what the account last said; the backend still decides.
  const status = data?.subscription.status ?? (isError ? user?.subscription.status : undefined);
  const state: SubscriptionState = status === undefined ? 'checking' : status === 'INACTIVE' ? 'ended' : 'active';
  const account = data ?? (isError ? user ?? undefined : undefined);

  const [refused, setRefused] = useState(false);   // the backend turned a request down for this reason, whatever the last check said
  useEffect(() => { setRefused(false); }, [state]);   // a fresh answer from the backend replaces an earlier refusal
  const blocked = state === 'ended' || refused;

  // The page is shown only once the account has been found active; after that it stays (disabled) if the subscription ends.
  const [shown, setShown] = useState(false);
  if (state === 'active' && !shown) setShown(true);

  // While blocked the dialog is up. This effect owns it: when the page is usable again, or goes away, the dialog goes too.
  // An account that never had a plan is told so and offered support; one whose plan ran out is told it ended.
  const neverHadPlan = !!account && !account.subscription.plan;
  useEffect(() => {
    if (!blocked) return;
    const dialog = new AbortController();
    void (neverHadPlan ? showNoPlan(dialog.signal, true) : showEnded(dialog.signal));
    return () => dialog.abort();
  }, [blocked, neverHadPlan, showEnded, showNoPlan]);

  const handle = useCallback((error: unknown) => {
    if (!isSubscriptionEnded(error)) return false;
    setRefused(true);
    recheck();   // bring the page's own view in line with what the backend just said
    return true;
  }, [recheck]);

  return { state, blocked, shown, handle, account, recheck, refresh };
}
