import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { saveBlob } from '../api/client';
import { reportApi } from '../api/endpoints';
import type { HandoffReport, HandoffStatus, HandoffSummary, ReportQuery } from '../api/types';
import { useSubscriptionGate } from '../auth/useSubscriptionGate';
import type { SubscriptionGate } from '../auth/useSubscriptionGate';
import { ReportAssistant } from '../components/ReportAssistant';
import { StatusBadge } from '../components/StatusBadge';
import { EmptyState, ErrorNotice, Gated, Spinner, errorMessage } from '../components/ui';
import {
  formatDate, formatDay, presetRange, qty, REPORT_PRESET_LABELS, STATUS_LABELS, ZONE,
} from '../lib/format';
import type { ReportPreset } from '../lib/format';

type StatusFilter = HandoffStatus | 'ALL' | 'OVERDUE';
type SortKey = 'reference' | 'title' | 'recipient' | 'created' | 'expectedReturn' | 'status' | 'given' | 'returned' | 'missing';
type SortDir = 'asc' | 'desc';

const PAGE_SIZE = 20;
const DAY = /^\d{4}-\d{2}-\d{2}$/;

const PRESETS: ReportPreset[] = ['TODAY', 'THIS_WEEK', 'THIS_MONTH', 'LAST_MONTH', 'THIS_QUARTER', 'CUSTOM'];

/** Every state a handoff can rest in, in lifecycle order, plus "overdue" (a handoff past its return date, not a state of its own). */
const STATUS_FILTERS: StatusFilter[] = [
  'ALL', 'DRAFT', 'AWAITING_RECIPIENT', 'ACTIVE_WITH_RECIPIENT', 'PARTIALLY_RETURNED', 'FULLY_RETURNED', 'CLOSED',
  'REJECTED', 'CANCELLED', 'DISPUTED', 'OVERDUE',
];
const filterLabel = (f: StatusFilter) =>
  f === 'ALL' ? 'All statuses' : f === 'OVERDUE' ? 'Overdue (past return date)' : STATUS_LABELS[f];

const COLUMNS: { key: SortKey; label: string; num?: boolean }[] = [
  { key: 'reference', label: 'Reference' },
  { key: 'title', label: 'Title' },
  { key: 'recipient', label: 'Recipient' },
  { key: 'created', label: 'Created' },
  { key: 'expectedReturn', label: 'Expected return' },
  { key: 'status', label: 'Status' },
  { key: 'given', label: 'Given', num: true },
  { key: 'returned', label: 'Returned', num: true },
  { key: 'missing', label: 'Missing', num: true },
];

/** Which of the two views of the report is shown: the handoffs themselves (the Quotation List), or their totals (the Summary Report). */
export type ReportView = 'list' | 'summary';

/**
 * Reports: how the handoffs created in a period are doing. Read-only — everything is worked out by the backend (the totals,
 * the filtering, the sorting, the paging, the CSV); these pages ask and show. Needs an active subscription, like starting a
 * handoff does, so it is held by the same gate and dialog.
 */
export function ReportsPage({ view }: { view: ReportView }) {
  const gate = useSubscriptionGate();
  if (gate.state === 'checking') return <Spinner />;
  if (!gate.shown) return null;   // never shown as usable without a subscription: all the customer sees is the dialog
  return <Gated blocked={gate.blocked}>{view === 'list' ? <QuotationList gate={gate} /> : <SummaryReport gate={gate} />}</Gated>;
}

/** The period both views are about: a preset or two dates, which only take effect on Apply (the boxes are a draft until then). */
function useReportPeriod(onChange: () => void = () => {}) {
  const initial = presetRange('THIS_MONTH');
  const [preset, setPreset] = useState<ReportPreset>('THIS_MONTH');
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [applied, setApplied] = useState(initial);   // what the report is showing

  const rangeError = !DAY.test(from) || !DAY.test(to) ? 'Choose both dates.'
    : from > to ? 'The start date must not be after the end date.' : null;
  const dirty = from !== applied.from || to !== applied.to;

  function chooseRange(next: ReportPreset) {
    setPreset(next);
    if (next === 'CUSTOM') return;
    const range = presetRange(next);
    setFrom(range.from);
    setTo(range.to);
    setApplied(range);
    onChange();
  }

  function apply(e: FormEvent) {
    e.preventDefault();
    if (rangeError || !dirty) return;
    setApplied({ from, to });
    onChange();
  }

  return {
    applied, preset, from, to, rangeError, dirty, chooseRange, apply,
    setFrom: (value: string) => { setFrom(value); setPreset('CUSTOM'); },
    setTo: (value: string) => { setTo(value); setPreset('CUSTOM'); },
  };
}

/** The report for a period, as the backend works it out; a refusal about the subscription is the gate's to show. */
function useReport(gate: SubscriptionGate, query: ReportQuery, page: number, size: number) {
  const report = useQuery({
    queryKey: ['report', query, page, size],
    queryFn: ({ signal }) => reportApi.handoffs(query, page, size, signal),
    enabled: gate.state === 'active',
    placeholderData: keepPreviousData,   // the old rows stay up (dimmed) while the next page or sort arrives
    retry: false,                        // a refusal or a bad request is the same on a second try
  });
  const { handle } = gate;
  useEffect(() => { if (report.error) handle(report.error); }, [report.error, handle]);
  return report;
}

function PeriodForm({ period }: { period: ReturnType<typeof useReportPeriod> }) {
  return (
    <form className="card report-filter" onSubmit={period.apply}>
      <div className="field">
        <label htmlFor="report-preset">Period</label>
        <select id="report-preset" value={period.preset} onChange={(e) => period.chooseRange(e.target.value as ReportPreset)}>
          {PRESETS.map((p) => <option key={p} value={p}>{REPORT_PRESET_LABELS[p]}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor="report-from">From</label>
        <input id="report-from" type="date" value={period.from} onChange={(e) => period.setFrom(e.target.value)} />
      </div>
      <div className="field">
        <label htmlFor="report-to">To</label>
        <input id="report-to" type="date" value={period.to} onChange={(e) => period.setTo(e.target.value)} />
      </div>
      <div className="field">
        <button className="btn btn-primary" disabled={!!period.rangeError || !period.dirty}>Apply</button>
      </div>
      {period.rangeError && <div className="form-error report-filter-error" role="alert">{period.rangeError}</div>}
    </form>
  );
}

/** What a period with no handoffs says, on either view. */
function NothingInPeriod({ report }: { report: HandoffReport }) {
  return (
    <EmptyState title="No handoffs in this period">
      No handoff was created between {formatDay(report.period.from)} and {formatDay(report.period.to)}, so there is nothing to total.
      Try a longer period.
    </EmptyState>
  );
}

/** The Summary Report: the totals for the period, and the questions that can be asked about them (the AI Reports assistant). */
function SummaryReport({ gate }: { gate: SubscriptionGate }) {
  const period = useReportPeriod();
  const query: ReportQuery = { from: period.applied.from, to: period.applied.to, timezone: ZONE };
  const report = useReport(gate, query, 0, 1);   // only the totals are shown, so no more than one row is read
  const data = report.data;

  return (
    <div className="stack">
      <div className="spread">
        <div>
          <h1>Summary Report</h1>
          <p className="muted">The totals for the handoffs you created in a period. Nothing on this page can be changed.</p>
        </div>
        <ReportAssistant />
      </div>

      <PeriodForm period={period} />

      {report.isError && !gate.blocked && <ErrorNotice error={report.error} />}
      {!data && report.isLoading && <Spinner />}

      {data && (
        <div className={report.isPlaceholderData ? 'is-refreshing' : ''} aria-busy={report.isPlaceholderData}>
          {data.summary.created === 0 ? <NothingInPeriod report={data} /> : <Summary report={data} />}
        </div>
      )}
    </div>
  );
}

/** The Quotation List: the period's handoffs themselves, which can be filtered by status, sorted, paged and exported. */
function QuotationList({ gate }: { gate: SubscriptionGate }) {
  const { handle } = gate;
  const [filter, setFilter] = useState<StatusFilter>('ALL');
  const [sortKey, setSortKey] = useState<SortKey>('created');
  const [sortDir, setSortDir] = useState<SortDir>('asc');
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const period = useReportPeriod(() => setPage(0));

  const query: ReportQuery = {
    from: period.applied.from, to: period.applied.to, timezone: ZONE, sort: `${sortKey},${sortDir}`,
    ...(filter === 'OVERDUE' ? { overdue: true } : filter === 'ALL' ? {} : { status: filter }),
  };
  const report = useReport(gate, query, page, PAGE_SIZE);

  function sortBy(key: SortKey) {
    if (key === sortKey) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else { setSortKey(key); setSortDir('asc'); }
    setPage(0);
  }

  async function exportCsv() {
    setExporting(true);
    setExportError(null);
    try {
      const { blob, filename } = await reportApi.exportCsv(query);
      saveBlob(blob, filename);
    } catch (error) {
      if (!handle(error)) setExportError(errorMessage(error));
    } finally {
      setExporting(false);
    }
  }

  const data = report.data;

  return (
    <div className="stack">
      <div className="spread">
        <div>
          <h1>Quotation List</h1>
          <p className="muted">How the handoffs you created in a period are doing. Nothing on this page can be changed.</p>
        </div>
        <ReportAssistant />
      </div>

      <PeriodForm period={period} />

      {report.isError && !gate.blocked && <ErrorNotice error={report.error} />}
      {!data && report.isLoading && <Spinner />}

      {data && (
        <div className={`stack ${report.isPlaceholderData ? 'is-refreshing' : ''}`} aria-busy={report.isPlaceholderData}>
          {data.summary.created === 0 ? <NothingInPeriod report={data} /> : (
            <div className="card">
              <div className="list-toolbar">
                <h2 style={{ margin: 0 }}>Handoff report</h2>
                <div className="row report-toolbar">
                  <select aria-label="Show handoffs with status" value={filter}
                          onChange={(e) => { setFilter(e.target.value as StatusFilter); setPage(0); }}>
                    {STATUS_FILTERS.map((f) => <option key={f} value={f}>{filterLabel(f)}</option>)}
                  </select>
                  <button type="button" className="btn" onClick={exportCsv} disabled={exporting}>
                    {exporting ? 'Preparing…' : 'Export CSV'}
                  </button>
                </div>
              </div>
              {exportError && <div className="notice notice-error" style={{ marginBottom: '0.75rem' }}>{exportError}</div>}

              {data.handoffs.totalElements === 0 ? (
                <div className="center mt-3">
                  <p className="muted">No handoffs in this period are {filter === 'OVERDUE' ? 'overdue' : `“${filterLabel(filter)}”`}.</p>
                  <button className="btn btn-sm" onClick={() => { setFilter('ALL'); setPage(0); }}>Show all statuses</button>
                </div>
              ) : (
                <>
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          {COLUMNS.map((c) => (
                            <th key={c.key} className={`sortable ${c.num ? 'num' : ''}`} onClick={() => sortBy(c.key)}
                                tabIndex={0} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); sortBy(c.key); } }}
                                aria-sort={c.key === sortKey ? (sortDir === 'asc' ? 'ascending' : 'descending') : undefined}>
                              {c.label}{c.key === sortKey ? (sortDir === 'asc' ? ' ▲' : ' ▼') : ''}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {data.handoffs.content.map((h) => <Row key={h.id} h={h} />)}
                      </tbody>
                    </table>
                  </div>
                  <div className="spread mt-2">
                    <span className="muted small">
                      {data.handoffs.totalElements} {data.handoffs.totalElements === 1 ? 'handoff' : 'handoffs'}
                      {filter !== 'ALL' && <> · {filterLabel(filter)}</>}
                      {' · '}Page {data.handoffs.page + 1} of {Math.max(1, data.handoffs.totalPages)}
                    </span>
                    <div className="row">
                      <button className="btn btn-sm" disabled={data.handoffs.first} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</button>
                      <button className="btn btn-sm" disabled={data.handoffs.last} onClick={() => setPage((p) => p + 1)}>Next</button>
                    </div>
                  </div>
                </>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/** The totals for the period. Every figure is the backend's; "open" and "overdue" are as of today, not as of the end of the period. */
function Summary({ report }: { report: HandoffReport }) {
  const s = report.summary;
  return (
    <section>
      <h2 className="section-label">Summary</h2>
      <div className="stat-grid summary-grid">
        <SummaryTile label="Handoffs created" value={String(s.created)} hint="In this period" />
        <SummaryTile label="Handoffs closed" value={String(s.closed)} hint="Of those created" />
        <SummaryTile label="Open handoffs" value={String(s.open)} hint="Sent, not closed yet" />
        <SummaryTile label="Overdue handoffs" value={String(s.overdue)} hint="Past their return date now" tone={s.overdue > 0 ? 'danger' : undefined} />
      </div>
      {/* Every item given is in exactly one of the other five, so the row adds up and nothing has to be hunted for. */}
      <div className="stat-grid summary-grid mt-2">
        <SummaryTile label="Items given" value={qty(s.itemsGiven)} hint="On handoffs that were sent" />
        <SummaryTile label="Items returned" value={qty(s.itemsReturned)} hint="Confirmed returns" />
        <SummaryTile label="Items missing" value={qty(s.itemsMissing)} hint="Marked missing, not recovered" tone={Number(s.itemsMissing) > 0 ? 'danger' : undefined} />
        <SummaryTile label="Items still out" value={qty(s.itemsStillOut)} hint="Not back yet" />
        <SummaryTile label="Items rejected" value={qty(s.itemsRejected)} hint="On handoffs the recipient rejected" />
        <SummaryTile label="Items cancelled" value={qty(s.itemsCancelled)} hint="On handoffs that were cancelled" />
      </div>
      <p className="muted small" style={{ margin: '0.5rem 0 0' }}>
        Items given = returned + missing + still out + rejected + cancelled. All statuses; drafts count as created but have given nothing.
      </p>
    </section>
  );
}

function SummaryTile({ label, value, hint, tone }: { label: string; value: string; hint: string; tone?: 'danger' }) {
  return (
    <div className={`stat static ${tone ? `attn-${tone}` : ''}`}>
      <div className="n">{value}</div>
      <div className="l">{label}</div>
      <div className="hint">{hint}</div>
    </div>
  );
}

function Row({ h }: { h: HandoffSummary }) {
  const sent = !!h.outgoingAt;   // a draft has given nothing, so it has no quantities to show
  const figure = (value: string, missing = false) => (
    <td className={`num ${missing && sent && Number(value) > 0 ? 'remaining-open' : ''}`}>
      {sent ? qty(value) : <span className="muted" title="Not sent yet">—</span>}
    </td>
  );
  return (
    <tr>
      <td className="small"><Link to={`/handoffs/${h.id}`}>{h.publicCode}</Link></td>
      <td>
        <Link to={`/handoffs/${h.id}`}><strong>{h.title}</strong></Link>
        {h.overdue && <span className="badge badge-danger" style={{ marginLeft: 6 }}>Overdue</span>}
      </td>
      <td>{h.recipientName}</td>
      <td className="small muted">{formatDate(h.createdAt)}</td>
      <td className="small muted">{formatDate(h.dueAt)}</td>
      <td><StatusBadge status={h.status} /></td>
      {figure(h.totalOutgoing)}
      {figure(h.totalReturned)}
      {figure(h.totalMissing, true)}
    </tr>
  );
}
