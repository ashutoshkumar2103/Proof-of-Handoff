import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { jobApi } from '../api/endpoints';
import type { ReactNode } from 'react';
import type { Job, JobChange, JobChangeAction, JobHistoryEntry, JobRun, JobStatus, JobTrigger, JobType } from '../api/types';
import { errorMessage, ErrorNotice, Spinner, useTransient } from './ui';

const JOBS_KEY = ['account-jobs'];
const HISTORY_KEY = ['account-job-history'];
const CHANGES_KEY = ['account-job-changes'];
/** How many runs a page of Jobs Monitoring History shows. */
const HISTORY_PAGE_SIZE = 10;

type Notice = { kind: 'success' | 'warning' | 'error'; text: string };
const NOTICE_CLASS: Record<Notice['kind'], string> = { success: 'notice-success', warning: 'notice-warning', error: 'notice-error' };

/** Ready-made schedules, for convenience only: the backend checks every schedule it is given. */
const PRESETS: { label: string; cron: string }[] = [
  { label: 'Every day at 09:00', cron: '0 0 9 * * *' },
  { label: 'Weekdays at 09:00', cron: '0 0 9 * * MON-FRI' },
  { label: 'Every Monday at 09:00', cron: '0 0 9 * * MON' },
  { label: 'Every 2 hours', cron: '0 0 */2 * * *' },
  { label: 'First day of the month at 09:00', cron: '0 0 9 1 * *' },
];
const CUSTOM = 'custom';

const STATUS_LABEL: Record<JobStatus, string> = {
  SENT: 'Email sent',
  PARTIAL: 'Partial success',
  NOTHING_TO_REPORT: 'Nothing to report',
  FAILED: 'Failed',
};
const STATUS_BADGE: Record<JobStatus, string> = {
  SENT: 'badge-success',
  PARTIAL: 'badge-warning',
  NOTHING_TO_REPORT: 'badge-primary',
  FAILED: 'badge-danger',
};
const TRIGGER_LABEL: Record<JobTrigger, string> = {
  SCHEDULED: 'Scheduled',
  RUN_NOW: 'Run Now',
  RUN_ALL_NOW: 'Run All Now',
};
const CHANGE_LABEL: Record<JobChangeAction, string> = {
  SCHEDULE_CHANGED: 'Schedule changed',
  PAUSED: 'Paused',
  RESUMED: 'Resumed',
};

function timezones(): string[] {
  const supported = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
  return supported ? supported('timeZone') : [];
}

/** A moment shown in the zone the job runs in, so "09:00" on the schedule reads as 09:00. */
function inZone(iso: string | null | undefined, zone: string | undefined): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString(undefined, {
      timeZone: zone, year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return new Date(iso).toLocaleString();
  }
}

function runSummary(runs: JobRun[]): Notice {
  const sent = runs.filter((r) => r.status === 'SENT').length;
  const partial = runs.filter((r) => r.status === 'PARTIAL').length;
  const failed = runs.filter((r) => r.status === 'FAILED').length;
  if (!sent && !partial && !failed) {
    return { kind: 'success', text: `All ${runs.length} jobs ran. There was nothing to report, so no emails were sent.` };
  }
  const emails = sent + partial;
  const others = runs.length - emails - failed;   // the runs that had nothing to report
  return {
    kind: failed === runs.length ? 'error' : partial || failed ? 'warning' : 'success',
    text: `All ${runs.length} jobs ran: ${emails} email${emails === 1 ? '' : 's'} sent`
      + `${partial ? ` (${partial} without some handoffs)` : ''}${failed ? `, ${failed} failed` : ''}.`
      + `${others > 0 ? ` ${others === 1 ? 'The other' : 'The others'} had nothing to report.` : ''} `
      + 'Each run is in Jobs Monitoring History.',
  };
}

/**
 * The customer's jobs: each can be switched on or off, scheduled with its own cron expression and timezone, and
 * run on demand. Running a job by hand never moves its schedule. Every run is on its own page
 * ({@link JobMonitoring}), so this page stays short however many jobs there are. The backend is the authority on everything
 * here — it validates schedules and decides what a run says.
 */
export function JobsPanel() {
  const qc = useQueryClient();
  const jobs = useQuery({ queryKey: JOBS_KEY, queryFn: jobApi.list });
  // What a run just said: shown for five seconds, then gone (the permanent record is Jobs Monitoring History, which this does not touch).
  const [notice, setNotice] = useTransient<Notice>();

  const runAll = useMutation({
    mutationFn: jobApi.runAll,
    onSuccess: (runs) => {
      setNotice(runSummary(runs));
      void qc.invalidateQueries({ queryKey: JOBS_KEY });
      void qc.invalidateQueries({ queryKey: HISTORY_KEY });
    },
    onError: (e) => setNotice({ kind: 'error', text: errorMessage(e) }),
  });

  if (jobs.isLoading) return <Spinner />;
  if (jobs.error) return <ErrorNotice error={jobs.error} />;
  const list = jobs.data ?? [];

  return (
    <div className="stack">
      <div className="card">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <div>
            <h1 style={{ margin: 0 }}>Jobs</h1>
            <p className="small muted" style={{ margin: '0.3rem 0 0' }}>
              Automatic emails about your own handoffs. Each job sends one email per run, and nothing when there is
              nothing to say. They start switched off. Every run is in{' '}
              <Link to="/account/jobs/history">Jobs Monitoring History</Link>.
            </p>
          </div>
          <button className="btn btn-primary" disabled={runAll.isPending}
                  onClick={() => { setNotice(null); runAll.mutate(); }}>
            {runAll.isPending ? 'Running…' : 'RUN ALL NOW'}
          </button>
        </div>
      </div>
      {notice && (
        <div className={`notice ${NOTICE_CLASS[notice.kind]}`} role={notice.kind === 'error' ? 'alert' : 'status'}>
          {notice.text}
        </div>
      )}

      {list.map((job) => (
        <JobCard key={job.type} job={job} onNotice={setNotice} />
      ))}

      <ConfigurationChanges zones={new Map(list.map((j) => [j.type, j.timezone]))} />
    </div>
  );
}

function JobCard({ job, onNotice }: { job: Job; onNotice: (n: Notice | null) => void }) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const refresh = () => qc.invalidateQueries({ queryKey: JOBS_KEY });

  const run = useMutation({
    mutationFn: (type: JobType) => jobApi.run(type),
    onSuccess: (r) => {
      onNotice({
        kind: r.status === 'FAILED' ? 'error' : r.status === 'PARTIAL' ? 'warning' : 'success',
        text: r.status === 'SENT' ? `${r.title}: one email was sent about ${r.handoffs.length} handoff${r.handoffs.length === 1 ? '' : 's'}.`
          : r.status === 'NOTHING_TO_REPORT' ? `${r.title}: nothing to report, so no email was sent.`
          : r.status === 'PARTIAL' ? `${r.title}: ${r.summary}`
          : `${r.title}: ${r.message ?? 'the email could not be sent.'}`,
      });
      void refresh();
      void qc.invalidateQueries({ queryKey: HISTORY_KEY });
    },
    onError: (e) => onNotice({ kind: 'error', text: errorMessage(e) }),
  });
  const toggle = useMutation({
    mutationFn: () => jobApi.setEnabled(job.type, !job.enabled),
    onSuccess: () => { onNotice(null); void refresh(); void qc.invalidateQueries({ queryKey: CHANGES_KEY }); },
    onError: (e) => onNotice({ kind: 'error', text: errorMessage(e) }),
  });

  return (
    <div className="card">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h3 style={{ margin: 0 }}>{job.title}</h3>
        <span className={`badge badge-dot ${job.enabled ? 'badge-success' : 'badge-warning'}`}>{job.enabled ? 'Active' : 'Paused'}</span>
      </div>
      <p className="small muted" style={{ margin: '0.3rem 0 0.6rem' }}>{job.description}</p>

      <dl className="job-facts">
        <dt>Schedule</dt>
        <dd><code>{job.cronExpression}</code> · {job.timezone}</dd>
        <dt>Next runs</dt>
        <dd>{job.enabled && job.upcomingRuns.length
          ? job.upcomingRuns.map((t) => inZone(t, job.timezone)).join('  ·  ')
          : 'Paused — it will not run until you resume it.'}</dd>
      </dl>

      <div className="row">
        <button className="btn btn-sm" disabled={run.isPending} onClick={() => { onNotice(null); run.mutate(job.type); }}>
          {run.isPending ? 'Running…' : 'Run now'}
        </button>
        <button className="btn btn-sm" disabled={toggle.isPending} onClick={() => toggle.mutate()}>
          {job.enabled ? 'Pause' : 'Resume'}
        </button>
        <button className={`btn btn-sm${editing ? ' btn-negative' : ''}`} onClick={() => setEditing(!editing)} aria-expanded={editing}>
          {editing ? 'Close' : 'Edit schedule'}
        </button>
      </div>

      {editing && <ScheduleEditor job={job} onSaved={() => { setEditing(false); onNotice(null); void refresh(); void qc.invalidateQueries({ queryKey: CHANGES_KEY }); }} />}
    </div>
  );
}

function ScheduleEditor({ job, onSaved }: { job: Job; onSaved: () => void }) {
  const preset = PRESETS.find((p) => p.cron === job.cronExpression);
  const [choice, setChoice] = useState(preset ? preset.cron : CUSTOM);
  const [cron, setCron] = useState(job.cronExpression);
  const [zone, setZone] = useState(job.timezone);
  const [error, setError] = useState<string | null>(null);
  const zones = timezones();

  const save = useMutation({
    mutationFn: () => jobApi.updateSchedule(job.type, { cronExpression: cron.trim(), timezone: zone.trim() }),
    onSuccess: onSaved,
    onError: (e) => setError(errorMessage(e)),
  });

  function pick(value: string) {
    setChoice(value);
    if (value !== CUSTOM) setCron(value);
  }

  return (
    <form className="job-editor" onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}>
      {error && <div className="notice notice-error mb-2" role="alert">{error}</div>}
      <div className="field">
        <label htmlFor={`preset-${job.type}`}>When</label>
        <select id={`preset-${job.type}`} value={choice} onChange={(e) => pick(e.target.value)}>
          {PRESETS.map((p) => <option key={p.cron} value={p.cron}>{p.label}</option>)}
          <option value={CUSTOM}>Custom (cron expression)</option>
        </select>
      </div>
      <div className="field-row">
        <div className="field">
          <label htmlFor={`cron-${job.type}`}>Cron expression</label>
          <input id={`cron-${job.type}`} value={cron} maxLength={100} spellCheck={false}
                 onChange={(e) => { setCron(e.target.value); setChoice(CUSTOM); }} required />
        </div>
        <div className="field">
          <label htmlFor={`zone-${job.type}`}>Timezone</label>
          <input id={`zone-${job.type}`} value={zone} maxLength={60} list="job-timezones" spellCheck={false}
                 onChange={(e) => setZone(e.target.value)} required />
          <datalist id="job-timezones">{zones.map((z) => <option key={z} value={z} />)}</datalist>
        </div>
      </div>
      <p className="small muted">
        Six fields: <code>second minute hour day-of-month month day-of-week</code>. For example <code>0 30 8 * * MON-FRI</code>
        {' '}is 08:30 on weekdays. A job can run at most once an hour, and the schedule is checked when you save it.
      </p>
      <div className="row">
        <button className="btn btn-primary btn-sm" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save schedule'}</button>
      </div>
    </form>
  );
}

/**
 * Who changed a job's settings: each time a schedule was changed, or a job paused or resumed, newest first, with the person who did it and
 * what it was before. On the page it is a compact section (so the page does not grow with every change) whose button opens the whole table
 * in a modal. A table of its own, apart from the runs in Jobs Monitoring History, which it does not touch. Read-only; the backend
 * writes it, and only this customer's own entries are ever returned.
 */
function ConfigurationChanges({ zones }: { zones: Map<JobType, string> }) {
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  const changes = useQuery({
    queryKey: [...CHANGES_KEY, page], queryFn: () => jobApi.changes(page),
    placeholderData: keepPreviousData,   // the old rows stay up while the next page arrives
  });
  if (changes.isLoading) return <Spinner />;
  if (changes.error) return <ErrorNotice error={changes.error} />;
  const data = changes.data!;

  function close() {
    setOpen(false);
    setPage(0);   // it opens at the newest changes next time
    trigger.current?.focus();
  }

  return (
    <div className="card">
      <div className="card-header" style={{ marginBottom: 0, flexWrap: 'wrap' }}>
        <div>
          <h2 style={{ margin: 0 }}>Configuration Changes</h2>
          <div className="small muted">{data.totalElements} {data.totalElements === 1 ? 'change' : 'changes'} recorded</div>
        </div>
        <button ref={trigger} type="button" className="btn btn-sm" aria-haspopup="dialog" onClick={() => setOpen(true)}>
          Click here to view all logs
        </button>
      </div>
      {open && (
        <LogsModal title="Configuration Changes" closeLabel="Close configuration changes" onClose={close}>
          <p className="small muted">Who changed a job's schedule, or paused or resumed it, newest first. This is a record only.</p>
          {data.content.length === 0 ? (
            <p className="muted">No job has been changed yet.</p>
          ) : (
            <>
              <div className="table-wrap">
                <table className="table-cards">
                  <thead>
                    <tr><th>When</th><th>Job</th><th>Change</th><th>Previous</th><th>New</th><th>User</th></tr>
                  </thead>
                  <tbody>
                    {data.content.map((c: JobChange) => (
                      <tr key={c.id}>
                        <td data-label="When" style={{ whiteSpace: 'normal', minWidth: 110 }}>{inZone(c.changedAt, zones.get(c.type))}</td>
                        <td data-label="Job" style={{ whiteSpace: 'normal' }}>{c.title}</td>
                        <td data-label="Change" style={{ whiteSpace: 'normal' }}>{CHANGE_LABEL[c.action]}</td>
                        <td data-label="Previous" style={{ whiteSpace: 'normal' }}>{c.previousValue ?? '—'}</td>
                        <td data-label="New" style={{ whiteSpace: 'normal' }}>{c.newValue}</td>
                        <td data-label="User" style={{ whiteSpace: 'normal' }}>{c.performedBy}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {data.totalPages > 1 && (
                <div className="spread mt-2">
                  <button className="btn btn-sm" disabled={data.first} onClick={() => setPage(page - 1)}>Newer</button>
                  <span className="small muted">Page {data.page + 1} of {data.totalPages}</span>
                  <button className="btn btn-sm" disabled={data.last} onClick={() => setPage(page + 1)}>Older</button>
                </div>
              )}
            </>
          )}
        </LogsModal>
      )}
    </div>
  );
}

/** A modal that shows a log, in the pattern of the handoff's Event History: closes with its buttons, Esc or a click outside, scrolls inside, and the page behind stays put. */
function LogsModal({ title, closeLabel, onClose, children }: { title: string; closeLabel: string; onClose: () => void; children: ReactNode }) {
  const closeButton = useRef<HTMLButtonElement>(null);

  useEffect(() => { closeButton.current?.focus(); }, []);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);
  useEffect(() => {   // the page behind does not scroll while the modal is open
    const before = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = before; };
  }, []);

  return (
    <div className="modal-overlay" onMouseDown={onClose}>
      <div className="modal modal-wide" style={{ maxWidth: 900 }} role="dialog" aria-modal="true" aria-labelledby="logs-modal-title"
           onMouseDown={(e) => e.stopPropagation()}>
        <div className="spread" style={{ flexWrap: 'nowrap', alignItems: 'flex-start' }}>
          <h3 className="modal-title" id="logs-modal-title">{title}</h3>
          <button ref={closeButton} type="button" className="btn btn-sm btn-negative" aria-label={closeLabel} onClick={onClose}>✕</button>
        </div>
        {children}
        <div className="modal-actions">
          <button type="button" className="btn btn-negative" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}

/**
 * Every run of every job, newest first, as a read-only table of its own (Account → Jobs Monitoring History): one row per actual
 * run, whatever started it. The Result column is the account of the run, written by the backend — what was sent, what could not be
 * and why — and View details shows the same facts as lists. Times are shown in the zone the job is scheduled in.
 */
export function JobMonitoring() {
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<number | null>(null);
  const history = useQuery({
    queryKey: [...HISTORY_KEY, page], queryFn: () => jobApi.history(page, HISTORY_PAGE_SIZE),
    placeholderData: keepPreviousData,   // the old rows stay up while the next page arrives
  });
  const jobs = useQuery({ queryKey: JOBS_KEY, queryFn: jobApi.list });
  if (history.isLoading) return <Spinner />;
  if (history.error) return <ErrorNotice error={history.error} />;
  const data = history.data!;
  const zones = new Map((jobs.data ?? []).map((j) => [j.type, j.timezone]));

  return (
    <div className="card">
      <h1 style={{ marginTop: 0 }}>Jobs Monitoring History</h1>
      <p className="small muted">
        Every run of your jobs, newest first. This is a record only; change a job, or run it, under <Link to="/account/jobs">Jobs</Link>.
      </p>
      {data.content.length === 0 ? (
        <p className="muted">No job has run yet.</p>
      ) : (
        <>
          <div className="table-wrap">
            <table className="table-cards">
              <thead>
                <tr><th>Job</th><th>Run time</th><th>Trigger</th><th>User</th><th>Result</th><th aria-label="Details"></th></tr>
              </thead>
              <tbody>
                {data.content.map((run) => <HistoryRow key={run.id} run={run} zone={zones.get(run.type)}
                                                      open={open === run.id} onToggle={() => setOpen(open === run.id ? null : run.id)} />)}
              </tbody>
            </table>
          </div>
          {data.totalPages > 1 && (
            <div className="spread mt-2">
              <button className="btn btn-sm" disabled={data.first} onClick={() => { setOpen(null); setPage(page - 1); }}>Newer</button>
              <span className="small muted">Page {data.page + 1} of {data.totalPages}</span>
              <button className="btn btn-sm" disabled={data.last} onClick={() => { setOpen(null); setPage(page + 1); }}>Older</button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

/** One run: a row of the table, and under it (when opened) the successful and failed handoffs as lists. */
function HistoryRow({ run, zone, open, onToggle }: { run: JobHistoryEntry; zone: string | undefined; open: boolean; onToggle: () => void }) {
  const hasDetails = run.successfulHandoffs.length > 0 || run.failedHandoffs.length > 0;
  return (
    <>
      <tr>
        <td style={{ whiteSpace: 'normal' }}>{run.title}</td>
        <td data-label="Run time" style={{ whiteSpace: 'normal', minWidth: 110 }}>{inZone(run.runAt, zone)}</td>
        <td data-label="Trigger" style={{ whiteSpace: 'normal' }}>{run.trigger ? TRIGGER_LABEL[run.trigger] : '—'}</td>
        <td data-label="User" style={{ whiteSpace: 'normal' }}>{run.performedBy}</td>
        <td style={{ whiteSpace: 'normal', minWidth: 220 }}>
          <span className={`badge badge-dot ${STATUS_BADGE[run.status]}`} title={STATUS_LABEL[run.status]} aria-label={STATUS_LABEL[run.status]} />
          {' '}{run.summary}
        </td>
        <td>
          {hasDetails && (
            <button type="button" className="btn btn-sm btn-ghost" aria-expanded={open} onClick={onToggle}>
              {open ? 'Hide details' : 'View details'}
            </button>
          )}
        </td>
      </tr>
      {open && (
        <tr>
          <td colSpan={6} style={{ whiteSpace: 'normal' }}>
            <dl className="job-facts" style={{ margin: 0 }}>
              <dt>Successfully sent</dt>
              <dd>{run.successfulHandoffs.length ? run.successfulHandoffs.join(', ') : 'None'}</dd>
              <dt>Failed</dt>
              <dd>{run.failedHandoffs.length ? run.failedHandoffs.join(', ') : 'None'}</dd>
              {run.failureReason && <><dt>Reason</dt><dd>{run.failureReason}</dd></>}
            </dl>
          </td>
        </tr>
      )}
    </>
  );
}
