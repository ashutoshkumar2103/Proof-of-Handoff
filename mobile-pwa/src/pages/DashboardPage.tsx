import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { handoffApi } from '../api/endpoints';
import type { HandoffStatus } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { HandoffCard } from '../components/HandoffCard';
import { Icon } from '../components/Icon';
import { EmptyState, ErrorNotice, Spinner, Toast } from '../components/ui';
import { FILTER_STATUSES, STATUS_LABELS } from '../lib/format';
import { useTransient } from '../lib/useTransient';

type Filter = HandoffStatus | 'ALL' | 'OVERDUE';

/** The statuses a handoff can be overdue in (the backend marks `overdue` on each row; the list API has no overdue filter of its own). */
const OVERDUE_STATUSES: HandoffStatus[] = ['ACTIVE_WITH_RECIPIENT', 'RETURN_PENDING', 'PARTIALLY_RETURNED'];

/** The choices of the status dropdown: every state a handoff rests in, and "Overdue" (past its return date). */
const FILTERS: Filter[] = ['ALL', 'OVERDUE', ...FILTER_STATUSES];

const filterLabel = (f: Filter) => (f === 'ALL' ? 'All handoffs' : f === 'OVERDUE' ? 'Overdue' : STATUS_LABELS[f]);
const PAGE_SIZE = 20;
const SEARCH_DELAY_MS = 300;

export function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [notice, setNotice] = useTransient<string>();
  const [filter, setFilter] = useState<Filter>('ALL');
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');

  // What the page before said (e.g. "Draft deleted.") is shown once, then forgotten, so a refresh does not repeat it.
  useEffect(() => {
    const arrived = (location.state as { notice?: string } | null)?.notice;
    if (arrived) { setNotice(arrived); navigate(location.pathname, { replace: true, state: null }); }
  }, []);   // eslint-disable-line react-hooks/exhaustive-deps

  // Typing searches by itself a moment after the last key; Enter searches at once.
  useEffect(() => {
    const t = setTimeout(() => setSearch(q.trim()), SEARCH_DELAY_MS);
    return () => clearTimeout(t);
  }, [q]);

  const dashboard = useQuery({ queryKey: ['dashboard'], queryFn: handoffApi.dashboard });

  const statuses: HandoffStatus[] | undefined = filter === 'ALL' ? undefined : filter === 'OVERDUE' ? OVERDUE_STATUSES : [filter];
  const list = useInfiniteQuery({
    queryKey: ['handoffs', filter, search],
    queryFn: ({ pageParam }) => handoffApi.list({ status: statuses, q: search || undefined, page: pageParam, size: PAGE_SIZE, sort: 'id,desc' }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.last ? undefined : last.page + 1),
  });

  const rows = useMemo(() => {
    const all = list.data?.pages.flatMap((p) => p.content) ?? [];
    return filter === 'OVERDUE' ? all.filter((h) => h.overdue) : all;
  }, [list.data, filter]);

  // An overdue view is a page's worth of candidates narrowed to the late ones: keep loading until there is something to show or nothing is left.
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = list;
  useEffect(() => {
    if (filter === 'OVERDUE' && rows.length < 5 && hasNextPage && !isFetchingNextPage) void fetchNextPage();
  }, [filter, rows.length, hasNextPage, isFetchingNextPage, fetchNextPage]);

  const counts = dashboard.data;
  const countOf = (f: Filter) => (!counts ? undefined : f === 'ALL' ? counts.total : f === 'OVERDUE' ? counts.overdueCount : counts.statusCounts[f] ?? 0);
  const awaiting = countOf('AWAITING_RECIPIENT') ?? 0;
  const overdue = countOf('OVERDUE') ?? 0;
  const noneYet = counts?.total === 0;
  const pick = (f: Filter) => setFilter((current) => (current === f ? 'ALL' : f));   // tapping the active chip lets go of it

  return (
    <div className="stack">
      <div>
        <h1>Handoffs</h1>
        {user && <p className="muted small">Hello, {user.displayName} · Account ID <strong>{user.accountCode}</strong></p>}
      </div>

      {notice && <Toast tone="success">{notice}</Toast>}
      {dashboard.isError && <ErrorNotice error={dashboard.error} onRetry={() => void dashboard.refetch()} />}

      {counts && !noneYet && (overdue > 0 || awaiting > 0) && (
        <section aria-label="Needs attention" className="attention">
          <span className="attention-label">Needs attention</span>
          {overdue > 0 && (
            <button type="button" className="attn-chip attn-chip-danger" aria-pressed={filter === 'OVERDUE'} onClick={() => pick('OVERDUE')}>
              <strong>{overdue}</strong> Overdue
            </button>
          )}
          {awaiting > 0 && (
            <button type="button" className="attn-chip attn-chip-info" aria-pressed={filter === 'AWAITING_RECIPIENT'} onClick={() => pick('AWAITING_RECIPIENT')}>
              <strong>{awaiting}</strong> Awaiting recipient
            </button>
          )}
        </section>
      )}

      {noneYet ? (
        <EmptyState title="No handoffs yet" action={<Link to="/new" className="btn btn-primary">Create your first handoff</Link>}>
          A handoff records what you give someone, their acknowledgement, and every return until it is all back.
        </EmptyState>
      ) : (
        <>
          <div className="toolbar">
            <form className="search input-icon" role="search" onSubmit={(e) => { e.preventDefault(); setSearch(q.trim()); }}>
              <Icon name="search" className="icon input-icon-lead" />
              <input type="search" aria-label="Search handoffs" placeholder="Search title, recipient, code…" value={q}
                     enterKeyHint="search" onChange={(e) => setQ(e.target.value)} />
            </form>
            <div className="select-wrap">
              <select aria-label="Filter by status" value={filter} onChange={(e) => setFilter(e.target.value as Filter)}>
                {FILTERS.map((f) => {
                  const n = countOf(f);
                  return <option key={f} value={f}>{filterLabel(f)}{n !== undefined ? ` (${n})` : ''}</option>;
                })}
              </select>
              <Icon name="chevron-down" className="icon select-caret" />
            </div>
          </div>

          {list.isLoading ? <Spinner /> : list.isError ? <ErrorNotice error={list.error} onRetry={() => void list.refetch()} /> : rows.length === 0 ? (
            <div className="empty">
              {isFetchingNextPage ? <Spinner /> : (
                <>
                  <p className="muted">{search ? `No handoffs match “${search}”.` : 'No handoffs in this view.'}</p>
                  {(search || filter !== 'ALL') && (
                    <button className="btn btn-sm" onClick={() => { setQ(''); setSearch(''); setFilter('ALL'); }}>Show all handoffs</button>
                  )}
                </>
              )}
            </div>
          ) : (
            <ul className="cards">
              {rows.map((h) => <li key={h.id}><HandoffCard h={h} /></li>)}
            </ul>
          )}

          {hasNextPage && !list.isLoading && (
            <button className="btn btn-block" disabled={isFetchingNextPage} onClick={() => void fetchNextPage()}>
              {isFetchingNextPage ? 'Loading…' : 'Load more'}
            </button>
          )}
        </>
      )}
    </div>
  );
}
