import { useState } from 'react';
import type { FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { isSubscriptionRefusal, openBlob } from '../api/client';
import { reportApi } from '../api/endpoints';
import type { HandoffReport, HandoffStatus, ReportQuery } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { HandoffCard } from '../components/HandoffCard';
import { Icon } from '../components/Icon';
import { ReportAssistantButton } from '../components/ReportAssistantSheet';
import { EmptyState, ErrorNotice, Spinner, errorMessage } from '../components/ui';
import {
  FILTER_STATUSES, formatDay, NO_ACTIVE_SUBSCRIPTION_MESSAGE, presetRange, qty, REPORT_PRESET_LABELS, STATUS_LABELS,
  SUBSCRIPTION_ENDED_MESSAGE, ZONE,
} from '../lib/format';
import type { ReportPreset } from '../lib/format';

type StatusFilter = HandoffStatus | 'ALL' | 'OVERDUE';
type View = 'list' | 'summary';

const PAGE_SIZE = 20;
const DAY = /^\d{4}-\d{2}-\d{2}$/;
const PRESETS: ReportPreset[] = ['TODAY', 'THIS_WEEK', 'THIS_MONTH', 'LAST_MONTH', 'THIS_QUARTER', 'CUSTOM'];
const STATUS_FILTERS: StatusFilter[] = ['ALL', ...FILTER_STATUSES, 'OVERDUE'];
const filterLabel = (f: StatusFilter) => (f === 'ALL' ? 'All statuses' : f === 'OVERDUE' ? 'Overdue (past return date)' : STATUS_LABELS[f]);

/** The orders the backend can sort the report by that make sense on a phone. */
const SORTS: { value: string; label: string }[] = [
  { value: 'created,desc', label: 'Newest first' },
  { value: 'created,asc', label: 'Oldest first' },
  { value: 'expectedReturn,asc', label: 'Return date (soonest)' },
  { value: 'reference,asc', label: 'Reference' },
];

/**
 * Reports: how the handoffs created in a period are doing. Read-only — the backend works out every total, filter, sort, page and the
 * CSV; this page asks and shows. It needs an active subscription like starting a handoff does (the backend refuses otherwise, and
 * this page says so instead of asking).
 */
export function ReportsPage() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const view: View = params.get('view') === 'summary' ? 'summary' : 'list';
  const ended = user?.subscription.status === 'INACTIVE';

  return (
    <div className="stack">
      <div className="spread spread-nowrap">
        <h1>Reports</h1>
        <ReportAssistantButton />
      </div>
      <p className="muted small">The handoffs you created in a period, and their totals. Nothing here can be changed.</p>

      <div className="segmented" role="group" aria-label="Report view">
        {(['list', 'summary'] as const).map((v) => (
          <button key={v} type="button" className={view === v ? 'active' : ''} aria-pressed={view === v}
                  onClick={() => setParams(v === 'list' ? {} : { view: v }, { replace: true })}>
            {v === 'list' ? 'Handoff list' : 'Summary'}
          </button>
        ))}
      </div>

      {ended ? (
        <div className="notice notice-warning" role="status">
          {user?.subscription.plan ? SUBSCRIPTION_ENDED_MESSAGE : NO_ACTIVE_SUBSCRIPTION_MESSAGE} Reports need an active plan.
        </div>
      ) : view === 'list' ? <HandoffList /> : <Summary />}
    </div>
  );
}

/** The period both views are about: a preset or two dates, which only take effect on Apply (the boxes are a draft until then). */
function useReportPeriod(onChange: () => void = () => {}) {
  const initial = presetRange('THIS_MONTH');
  const [preset, setPreset] = useState<ReportPreset>('THIS_MONTH');
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [applied, setApplied] = useState(initial);

  const rangeError = !DAY.test(from) || !DAY.test(to) ? 'Choose both dates.' : from > to ? 'The start date must not be after the end date.' : null;
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

function useReport(query: ReportQuery, page: number, size: number) {
  return useQuery({
    queryKey: ['report', query, page, size],
    queryFn: ({ signal }) => reportApi.handoffs(query, page, size, signal),
    placeholderData: keepPreviousData,   // the old rows stay up (dimmed) while the next page or sort arrives
    retry: false,                        // a refusal or a bad request is the same on a second try
  });
}

/** A refusal about the plan reads as the plan's own sentence, not as a technical error. */
function ReportError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  if (isSubscriptionRefusal(error)) return <div className="notice notice-warning" role="alert">{errorMessage(error)}</div>;
  return <ErrorNotice error={error} onRetry={onRetry} />;
}

function PeriodForm({ period }: { period: ReturnType<typeof useReportPeriod> }) {
  return (
    <form className="card" onSubmit={period.apply}>
      <div className="field">
        <label htmlFor="report-preset">Period</label>
        <div className="select-wrap">
          <select id="report-preset" value={period.preset} onChange={(e) => period.chooseRange(e.target.value as ReportPreset)}>
            {PRESETS.map((p) => <option key={p} value={p}>{REPORT_PRESET_LABELS[p]}</option>)}
          </select>
          <Icon name="chevron-down" className="icon select-caret" />
        </div>
      </div>
      <div className="two">
        <div className="field">
          <label htmlFor="report-from">From</label>
          <input id="report-from" type="date" value={period.from} onChange={(e) => period.setFrom(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="report-to">To</label>
          <input id="report-to" type="date" value={period.to} onChange={(e) => period.setTo(e.target.value)} />
        </div>
      </div>
      {period.rangeError && <div className="form-error" role="alert">{period.rangeError}</div>}
      <button className="btn btn-primary btn-block" disabled={!!period.rangeError || !period.dirty}>Apply</button>
    </form>
  );
}

function NothingInPeriod({ report }: { report: HandoffReport }) {
  return (
    <EmptyState title="No handoffs in this period">
      No handoff was created between {formatDay(report.period.from)} and {formatDay(report.period.to)}. Try a longer period.
    </EmptyState>
  );
}

/** The Quotation List: the period's handoffs themselves, which can be filtered by status, sorted, paged and exported. */
function HandoffList() {
  const [filter, setFilter] = useState<StatusFilter>('ALL');
  const [sort, setSort] = useState(SORTS[0].value);
  const [page, setPage] = useState(0);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);
  const period = useReportPeriod(() => setPage(0));

  const query: ReportQuery = {
    from: period.applied.from, to: period.applied.to, timezone: ZONE, sort,
    ...(filter === 'OVERDUE' ? { overdue: true } : filter === 'ALL' ? {} : { status: filter }),
  };
  const report = useReport(query, page, PAGE_SIZE);
  const data = report.data;

  async function exportCsv() {
    setExporting(true);
    setExportError(null);
    try {
      const { blob, filename } = await reportApi.exportCsv(query);
      openBlob(blob, filename);
    } catch (error) {
      setExportError(errorMessage(error));
    } finally {
      setExporting(false);
    }
  }

  return (
    <>
      <PeriodForm period={period} />
      {report.isError && <ReportError error={report.error} onRetry={() => void report.refetch()} />}
      {!data && report.isLoading && <Spinner />}
      {data && (
        <div className={`stack ${report.isPlaceholderData ? 'is-refreshing' : ''}`} aria-busy={report.isPlaceholderData}>
          {data.summary.created === 0 ? <NothingInPeriod report={data} /> : (
            <>
              <div className="toolbar toolbar-2">
                <div className="select-wrap">
                  <select aria-label="Show handoffs with status" value={filter} onChange={(e) => { setFilter(e.target.value as StatusFilter); setPage(0); }}>
                    {STATUS_FILTERS.map((f) => <option key={f} value={f}>{filterLabel(f)}</option>)}
                  </select>
                  <Icon name="chevron-down" className="icon select-caret" />
                </div>
                <div className="select-wrap">
                  <select aria-label="Sort handoffs" value={sort} onChange={(e) => { setSort(e.target.value); setPage(0); }}>
                    {SORTS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
                  </select>
                  <Icon name="chevron-down" className="icon select-caret" />
                </div>
              </div>
              <button type="button" className="btn btn-block" onClick={() => void exportCsv()} disabled={exporting}>
                <Icon name="download" />{exporting ? 'Preparing…' : 'Export CSV'}
              </button>
              {exportError && <div className="notice notice-error" role="alert">{exportError}</div>}

              {data.handoffs.totalElements === 0 ? (
                <div className="empty">
                  <p className="muted">No handoffs in this period are {filter === 'OVERDUE' ? 'overdue' : `“${filterLabel(filter)}”`}.</p>
                  <button className="btn btn-sm" onClick={() => { setFilter('ALL'); setPage(0); }}>Show all statuses</button>
                </div>
              ) : (
                <>
                  <p className="muted small">
                    {data.handoffs.totalElements} {data.handoffs.totalElements === 1 ? 'handoff' : 'handoffs'} · page {data.handoffs.page + 1} of {Math.max(1, data.handoffs.totalPages)}
                  </p>
                  <ul className="cards">{data.handoffs.content.map((h) => <li key={h.id}><HandoffCard h={h} /></li>)}</ul>
                  <div className="action-bar">
                    <button className="btn" disabled={data.handoffs.first} onClick={() => setPage((p) => Math.max(0, p - 1))}>Previous</button>
                    <button className="btn" disabled={data.handoffs.last} onClick={() => setPage((p) => p + 1)}>Next</button>
                  </div>
                </>
              )}
            </>
          )}
        </div>
      )}
    </>
  );
}

/** The Summary Report: the totals for the period. Every figure is the backend's; "open" and "overdue" are as of today. */
function Summary() {
  const period = useReportPeriod();
  const query: ReportQuery = { from: period.applied.from, to: period.applied.to, timezone: ZONE };
  const report = useReport(query, 0, 1);   // only the totals are shown, so no more than one row is read
  const data = report.data;
  const s = data?.summary;

  return (
    <>
      <PeriodForm period={period} />
      {report.isError && <ReportError error={report.error} onRetry={() => void report.refetch()} />}
      {!data && report.isLoading && <Spinner />}
      {data && s && (
        <div className={`stack ${report.isPlaceholderData ? 'is-refreshing' : ''}`} aria-busy={report.isPlaceholderData}>
          {s.created === 0 ? <NothingInPeriod report={data} /> : (
            <>
              <section aria-label="Handoffs" className="stat-grid">
                <Tile label="Handoffs created" value={String(s.created)} hint="In this period" />
                <Tile label="Handoffs closed" value={String(s.closed)} hint="Of those created" />
                <Tile label="Open handoffs" value={String(s.open)} hint="Sent, not closed yet" />
                <Tile label="Overdue handoffs" value={String(s.overdue)} hint="Past their return date now" tone={s.overdue > 0 ? 'neg' : undefined} />
              </section>
              <section aria-label="Items" className="stat-grid">
                <Tile label="Items given" value={qty(s.itemsGiven)} hint="On handoffs that were sent" />
                <Tile label="Items returned" value={qty(s.itemsReturned)} hint="Confirmed returns" />
                <Tile label="Items missing" value={qty(s.itemsMissing)} hint="Marked missing, not recovered" tone={Number(s.itemsMissing) > 0 ? 'neg' : undefined} />
                <Tile label="Items still out" value={qty(s.itemsStillOut)} hint="Not back yet" />
                <Tile label="Items rejected" value={qty(s.itemsRejected)} hint="On handoffs the recipient rejected" />
                <Tile label="Items cancelled" value={qty(s.itemsCancelled)} hint="On handoffs that were cancelled" />
              </section>
              <p className="muted small">Items given = returned + missing + still out + rejected + cancelled. Drafts count as created but have given nothing.</p>
            </>
          )}
        </div>
      )}
    </>
  );
}

function Tile({ label, value, hint, tone }: { label: string; value: string; hint: string; tone?: 'neg' }) {
  return (
    <div className="tile tile-left">
      <div className={`tile-n ${tone ?? ''}`}>{value}</div>
      <div className="tile-l">{label}</div>
      <div className="tile-h">{hint}</div>
    </div>
  );
}

