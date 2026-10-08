import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { handoffApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import type { HandoffStatus, HandoffSummary } from '../api/types';
import { StatusBadge } from '../components/StatusBadge';
import { EmptyState, ErrorNotice, Spinner, useNoActivePlanModal } from '../components/ui';
import { getPendingPayment } from '../lib/checkout';
import { formatDate, qty, STATUS_LABELS } from '../lib/format';

type Filter = HandoffStatus | 'ALL' | 'OVERDUE';
type SortField = 'id' | 'createdAt' | 'updatedAt';
type SortDir = 'asc' | 'desc';

type Tone = 'danger' | 'warning' | 'info';

/**
 * What needs the owner's attention, shown first. Each is a count the dashboard already returns and a filter the list already has —
 * nothing is worked out here. (Handoffs due soon, or with items missing, are not counted by the backend yet.)
 */
const ATTENTION: { filter: Filter; label: string; hint: string; tone: Tone }[] = [
  { filter: 'OVERDUE', label: 'Overdue', hint: 'Past their return date', tone: 'danger' },
  { filter: 'AWAITING_RECIPIENT', label: 'Awaiting recipient', hint: 'Not accepted yet', tone: 'info' },
];

/** The rest of the status cards, in the order a handoff moves through them. */
const OVERVIEW_ORDER: HandoffStatus[] = ['DRAFT', 'ACTIVE_WITH_RECIPIENT', 'PARTIALLY_RETURNED', 'FULLY_RETURNED', 'CLOSED'];

// Mirrors the backend's rule (a status no handoff reaches today, RETURN_PENDING, is kept so an old one would still be counted).
const OVERDUE_STATUSES: HandoffStatus[] = ['ACTIVE_WITH_RECIPIENT', 'RETURN_PENDING', 'PARTIALLY_RETURNED'];

export function DashboardPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  // E.g. "Your Quarterly plan is now active." after paying for a plan and signing in.
  const arrival = useLocation().state as { notice?: string; noPlanDialogSeen?: boolean } | null;
  const notice = arrival?.notice;

  // An account that signed up without paying has no plan: say so, and how to get it activated. Not while a payment made
  // before signing up is still being applied to it (that is about to give it one), nor straight after the customer closed
  // that same dialog on a page they could not use.
  const showNoPlan = useNoActivePlanModal();
  const needsActivation = !!user && !user.subscription.plan && !getPendingPayment() && !arrival?.noPlanDialogSeen;
  useEffect(() => {
    if (!needsActivation) return;
    const dialog = new AbortController();
    void showNoPlan(dialog.signal);
    return () => dialog.abort();
  }, [needsActivation, showNoPlan]);

  const [filter, setFilter] = useState<Filter>('ALL');
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  // Default: by code (id) ascending, i.e. HO-1, HO-2, … in sequence.
  const [sortField, setSortField] = useState<SortField>('id');
  const [sortDir, setSortDir] = useState<SortDir>('asc');

  const toggleSort = (field: SortField) => {
    if (field === sortField) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortField(field);
      setSortDir('asc');
    }
  };
  const sortArrow = (field: SortField) => (field === sortField ? (sortDir === 'asc' ? ' ▲' : ' ▼') : '');

  const dashboard = useQuery({ queryKey: ['dashboard'], queryFn: handoffApi.dashboard });

  const statusesForQuery: HandoffStatus[] | undefined =
    filter === 'ALL' ? undefined
      : filter === 'OVERDUE' ? OVERDUE_STATUSES
        : [filter];

  const list = useQuery({
    queryKey: ['handoffs', statusesForQuery, search, sortField, sortDir],
    queryFn: () => handoffApi.list({
      status: statusesForQuery, q: search || undefined, size: 50,
      sort: `${sortField},${sortDir}`,
    }),
  });

  const rows = useMemo(() => {
    const content = list.data?.content ?? [];
    return filter === 'OVERDUE' ? content.filter((h) => h.overdue) : content;
  }, [list.data, filter]);

  const counts = dashboard.data;
  const countOf = (f: Filter) => (!counts ? 0 : f === 'ALL' ? counts.total : f === 'OVERDUE' ? counts.overdueCount : counts.statusCounts[f] ?? 0);
  const nothingToAttend = ATTENTION.every((a) => countOf(a.filter) === 0);
  const noHandoffsYet = counts?.total === 0 && filter === 'ALL' && !search;

  // The cards sit above the list they filter, so bring the list into view when one is picked.
  const listRef = useRef<HTMLDivElement>(null);
  function choose(f: Filter) {
    setFilter(f);
    listRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  return (
    <div className="stack">
      <div className="spread">
        <div>
          <h1>Dashboard</h1>
          {user && <h1 className="welcome">Welcome, {user.displayName}</h1>}
          <p className="muted">
            Track every handoff from give to full return.
            {user && <span className="small"> · Account ID <strong>{user.accountCode}</strong></span>}
          </p>
        </div>
        <div className="row">
          {/* Only offered when the plan includes it; the backend enforces the same rule. */}
          {user?.support.contactSupport && <Link to="/support" className="btn">Contact Support</Link>}
          <Link to="/handoffs/new" className="btn btn-primary">+ New handoff</Link>
        </div>
      </div>

      {notice && <div className="notice notice-success">{notice}</div>}

      {dashboard.isLoading ? <Spinner /> : dashboard.isError ? <ErrorNotice error={dashboard.error} /> : noHandoffsYet ? null : (
        <>
          <section>
            <h2 className="section-label">Needs attention</h2>
            <div className="stat-grid attention-grid">
              {ATTENTION.map((a) => (
                <StatCard key={a.filter} label={a.label} hint={a.hint} tone={a.tone} n={countOf(a.filter)}
                          active={filter === a.filter} onClick={() => choose(a.filter)} />
              ))}
            </div>
            {nothingToAttend && <p className="muted small" style={{ margin: '0.5rem 0 0' }}>Nothing needs your attention right now.</p>}
          </section>
          <section>
            <h2 className="section-label">Overview</h2>
            <div className="stat-grid">
              <StatCard label="All handoffs" n={countOf('ALL')} active={filter === 'ALL'} onClick={() => choose('ALL')} />
              {OVERVIEW_ORDER.map((s) => (
                <StatCard key={s} label={STATUS_LABELS[s]} n={countOf(s)} active={filter === s} onClick={() => choose(s)} />
              ))}
            </div>
          </section>
        </>
      )}

      {noHandoffsYet ? (
        <EmptyState title="No handoffs yet"
                    action={<Link to="/handoffs/new" className="btn btn-primary">Create your first handoff</Link>}>
          A handoff records what you give someone, their acknowledgement, and every return until it is all back.
        </EmptyState>
      ) : (
      <div className="card scroll-target" ref={listRef}>
        <div className="list-toolbar">
          <h2 style={{ margin: 0 }}>{filter === 'ALL' ? 'All handoffs' : filter === 'OVERDUE' ? 'Overdue' : STATUS_LABELS[filter]}</h2>
          <form className="search-bar" onSubmit={(e) => { e.preventDefault(); setSearch(q); }}>
            <input placeholder="Search title, recipient, code…" value={q}
                   onChange={(e) => setQ(e.target.value)} />
            <button className="btn">Search</button>
            {search && <button type="button" className="btn btn-ghost" onClick={() => { setQ(''); setSearch(''); }}>Clear</button>}
          </form>
        </div>

        {list.isLoading ? <Spinner /> : list.isError ? <ErrorNotice error={list.error} /> : (
          rows.length === 0 ? (
            <div className="center mt-3">
              <p className="muted">{search ? `No handoffs match “${search}”.` : 'No handoffs in this view.'}</p>
              {(search || filter !== 'ALL') && (
                <button className="btn btn-sm" onClick={() => { setQ(''); setSearch(''); setFilter('ALL'); }}>Show all handoffs</button>
              )}
            </div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th className="sortable" onClick={() => toggleSort('id')}>Code{sortArrow('id')}</th>
                    <th>Title</th><th>Recipient</th><th>Status</th>
                    <th className="num">Outgoing</th><th className="num">Returned</th>
                    <th className="num">Missing</th><th className="num">Remaining</th>
                    <th className="sortable" onClick={() => toggleSort('createdAt')}>Created{sortArrow('createdAt')}</th>
                    <th className="sortable" onClick={() => toggleSort('updatedAt')}>Updated{sortArrow('updatedAt')}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((h) => <Row key={h.id} h={h} onOpen={() => navigate(`/handoffs/${h.id}`)} />)}
                </tbody>
              </table>
            </div>
          )
        )}
      </div>
      )}
    </div>
  );
}

/** A count that filters the list when picked. `tone` marks it as needing attention, but only while there is something to look at. */
function StatCard({ label, n, onClick, active, tone, hint }:
  { label: string; n: number; onClick: () => void; active: boolean; tone?: Tone; hint?: string }) {
  return (
    <div className={`stat ${active ? 'active-filter' : ''} ${tone && n > 0 ? `attn-${tone}` : ''}`} onClick={onClick}
         role="button" tabIndex={0} onKeyDown={(e) => e.key === 'Enter' && onClick()}>
      <div className="n">{n}</div>
      <div className="l">{label}</div>
      {hint && <div className="hint">{hint}</div>}
    </div>
  );
}

function Row({ h, onOpen }: { h: HandoffSummary; onOpen: () => void }) {
  const remainingOpen = Number(h.totalRemaining) > 0;
  return (
    <tr onClick={onOpen} style={{ cursor: 'pointer' }}>
      <td className="small muted">{h.publicCode}</td>
      <td>
        <strong>{h.title}</strong>
        {h.overdue && <span className="badge badge-danger" style={{ marginLeft: 6 }}>Overdue</span>}
      </td>
      <td>{h.recipientName}</td>
      <td><StatusBadge status={h.status} /></td>
      <td className="num">{qty(h.totalOutgoing)}</td>
      <td className="num">{qty(h.totalReturned)}</td>
      <td className={`num ${Number(h.totalMissing) > 0 ? 'remaining-open' : 'muted'}`}>{qty(h.totalMissing)}</td>
      <td className={`num ${remainingOpen ? 'remaining-open' : 'remaining-zero'}`}>{qty(h.totalRemaining)}</td>
      <td className="small muted">{formatDate(h.createdAt)}</td>
      <td className="small muted">{formatDate(h.updatedAt)}</td>
    </tr>
  );
}
