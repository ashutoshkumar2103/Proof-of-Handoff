import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { handoffApi } from '../api/endpoints';
import type { HandoffStatus, HandoffSummary } from '../api/types';
import { StatusBadge } from '../components/StatusBadge';
import { ErrorNotice, Spinner } from '../components/ui';
import { formatDate, qty, STATUS_LABELS } from '../lib/format';

type Filter = HandoffStatus | 'ALL' | 'OVERDUE';
type SortField = 'id' | 'createdAt' | 'updatedAt';
type SortDir = 'asc' | 'desc';

const CARD_ORDER: HandoffStatus[] = [
  'DRAFT', 'AWAITING_RECIPIENT', 'ACTIVE_WITH_RECIPIENT', 'RETURN_PENDING',
  'PARTIALLY_RETURNED', 'FULLY_RETURNED', 'CLOSED',
];

const OVERDUE_STATUSES: HandoffStatus[] = ['ACTIVE_WITH_RECIPIENT', 'RETURN_PENDING', 'PARTIALLY_RETURNED'];

export function DashboardPage() {
  const navigate = useNavigate();
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

  return (
    <div className="stack">
      <div className="spread">
        <div>
          <h1>Dashboard</h1>
          <p className="muted">Track every handoff from give to full return.</p>
        </div>
        <Link to="/handoffs/new" className="btn btn-primary">+ New handoff</Link>
      </div>

      {dashboard.isLoading ? <Spinner /> : dashboard.isError ? <ErrorNotice error={dashboard.error} /> : (
        <div className="stat-grid">
          <StatCard label="All handoffs" n={dashboard.data!.total}
                    active={filter === 'ALL'} onClick={() => setFilter('ALL')} />
          {CARD_ORDER.map((s) => (
            <StatCard key={s} label={STATUS_LABELS[s]} n={dashboard.data!.statusCounts[s] ?? 0}
                      active={filter === s} onClick={() => setFilter(s)} />
          ))}
          <StatCard label="Overdue" n={dashboard.data!.overdueCount} danger
                    active={filter === 'OVERDUE'} onClick={() => setFilter('OVERDUE')} />
        </div>
      )}

      <div className="card">
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
          rows.length === 0 ? <p className="muted center mt-3">No handoffs found.</p> : (
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
    </div>
  );
}

function StatCard({ label, n, onClick, active, danger }:
  { label: string; n: number; onClick: () => void; active: boolean; danger?: boolean }) {
  return (
    <div className={`stat ${active ? 'active-filter' : ''}`} onClick={onClick} role="button" tabIndex={0}
         onKeyDown={(e) => e.key === 'Enter' && onClick()}>
      <div className="n" style={danger && n > 0 ? { color: 'var(--danger)' } : undefined}>{n}</div>
      <div className="l">{label}</div>
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
