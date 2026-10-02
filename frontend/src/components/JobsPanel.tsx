import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { jobApi } from '../api/endpoints';
import type { Job, JobRun, JobStatus, JobType } from '../api/types';
import { errorMessage, ErrorNotice, Spinner } from './ui';

const JOBS_KEY = ['account-jobs'];

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
  NOTHING_TO_REPORT: 'Nothing to report',
  FAILED: 'Failed',
};
const STATUS_BADGE: Record<JobStatus, string> = {
  SENT: 'badge-success',
  NOTHING_TO_REPORT: 'badge-primary',
  FAILED: 'badge-danger',
};

function timezones(): string[] {
  const supported = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
  return supported ? supported('timeZone') : [];
}

/** A moment shown in the zone the job runs in, so "09:00" on the schedule reads as 09:00. */
function inZone(iso: string | null | undefined, zone: string): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString(undefined, {
      timeZone: zone, year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return new Date(iso).toLocaleString();
  }
}

function runSummary(runs: JobRun[]): string {
  const sent = runs.filter((r) => r.status === 'SENT').length;
  const failed = runs.filter((r) => r.status === 'FAILED').length;
  if (!sent && !failed) return `All ${runs.length} jobs ran. There was nothing to report, so no emails were sent.`;
  return `All ${runs.length} jobs ran: ${sent} email${sent === 1 ? '' : 's'} sent`
    + `${failed ? `, ${failed} failed` : ''}. The others had nothing to report.`;
}

/**
 * The customer's jobs: each can be switched on or off, scheduled with its own cron expression and timezone, and
 * run on demand. Running a job by hand never moves its schedule. How each one's latest run went is on its own page
 * ({@link JobMonitoring}), so this page stays short however many jobs there are. The backend is the authority on everything
 * here — it validates schedules and decides what a run says.
 */
export function JobsPanel() {
  const qc = useQueryClient();
  const jobs = useQuery({ queryKey: JOBS_KEY, queryFn: jobApi.list });
  const [notice, setNotice] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  const runAll = useMutation({
    mutationFn: jobApi.runAll,
    onSuccess: (runs) => {
      setNotice({ kind: 'success', text: runSummary(runs) });
      void qc.invalidateQueries({ queryKey: JOBS_KEY });
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
              nothing to say. They start switched off. Each job's latest run is in{' '}
              <Link to="/account/jobs/history">Jobs Monitoring History</Link>.
            </p>
          </div>
          <button className="btn btn-primary" disabled={runAll.isPending}
                  onClick={() => { setNotice(null); runAll.mutate(); }}>
            {runAll.isPending ? 'Running…' : 'RUN ALL NOW'}
          </button>
        </div>
        {notice && (
          <div className={`notice ${notice.kind === 'error' ? 'notice-error' : 'notice-success'} mt-2`}
               role={notice.kind === 'error' ? 'alert' : 'status'}>
            {notice.text}
          </div>
        )}
      </div>

      {list.map((job) => (
        <JobCard key={job.type} job={job} onNotice={setNotice} />
      ))}
    </div>
  );
}

function JobCard({ job, onNotice }: { job: Job; onNotice: (n: { kind: 'success' | 'error'; text: string } | null) => void }) {
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const refresh = () => qc.invalidateQueries({ queryKey: JOBS_KEY });

  const run = useMutation({
    mutationFn: (type: JobType) => jobApi.run(type),
    onSuccess: (r) => {
      onNotice({
        kind: r.status === 'FAILED' ? 'error' : 'success',
        text: r.status === 'SENT' ? `${r.title}: one email was sent about ${r.handoffs.length} handoff${r.handoffs.length === 1 ? '' : 's'}.`
          : r.status === 'NOTHING_TO_REPORT' ? `${r.title}: nothing to report, so no email was sent.`
          : `${r.title}: ${r.message ?? 'the email could not be sent.'}`,
      });
      void refresh();
    },
    onError: (e) => onNotice({ kind: 'error', text: errorMessage(e) }),
  });
  const toggle = useMutation({
    mutationFn: () => jobApi.setEnabled(job.type, !job.enabled),
    onSuccess: () => { onNotice(null); void refresh(); },
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
        <dt>Last run</dt>
        <dd>
          {job.lastRunAt ? inZone(job.lastRunAt, job.timezone) : 'Not run yet'}
          {job.lastStatus && <> · <span className={`badge ${STATUS_BADGE[job.lastStatus]}`}>{STATUS_LABEL[job.lastStatus]}</span></>}
          {job.lastHandoffs.length > 0 && <> · {job.lastHandoffs.join(', ')}</>}
          {job.lastDetail && <div className="small muted">{job.lastDetail}</div>}
        </dd>
      </dl>

      <div className="row">
        <button className="btn btn-sm" disabled={run.isPending} onClick={() => { onNotice(null); run.mutate(job.type); }}>
          {run.isPending ? 'Running…' : 'Run now'}
        </button>
        <button className="btn btn-sm" disabled={toggle.isPending} onClick={() => toggle.mutate()}>
          {job.enabled ? 'Pause' : 'Resume'}
        </button>
        <button className="btn btn-sm" onClick={() => setEditing(!editing)} aria-expanded={editing}>
          {editing ? 'Close' : 'Edit schedule'}
        </button>
      </div>

      {editing && <ScheduleEditor job={job} onSaved={() => { setEditing(false); onNotice(null); void refresh(); }} />}
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
 * The latest run of each job, as a read-only table of its own (Account → Jobs Monitoring History). It reads the same list as
 * the jobs page, so a run made there shows here. Only the latest result of each job is kept, so this is a record, not an archive.
 */
export function JobMonitoring() {
  const jobs = useQuery({ queryKey: JOBS_KEY, queryFn: jobApi.list });
  if (jobs.isLoading) return <Spinner />;
  if (jobs.error) return <ErrorNotice error={jobs.error} />;
  const list = jobs.data ?? [];

  return (
    <div className="card">
      <h1 style={{ marginTop: 0 }}>Jobs Monitoring History</h1>
      <p className="small muted">
        The latest run of each job. This is a record only; change a job, or run it, under <Link to="/account/jobs">Jobs</Link>.
      </p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Job</th><th>Status</th><th>Last run</th><th>Result</th><th>Handoffs</th><th>Next run</th></tr>
          </thead>
          <tbody>
            {list.map((job) => (
              <tr key={job.type}>
                <td>{job.title}</td>
                <td>
                  <span className={`badge badge-dot ${job.enabled ? 'badge-success' : 'badge-warning'}`}>
                    {job.enabled ? 'Active' : 'Paused'}
                  </span>
                </td>
                <td>{inZone(job.lastRunAt, job.timezone)}</td>
                <td>
                  {job.lastStatus
                    ? <span className={`badge ${STATUS_BADGE[job.lastStatus]}`}>{STATUS_LABEL[job.lastStatus]}</span>
                    : <span className="muted">Not run yet</span>}
                </td>
                <td style={{ whiteSpace: 'normal', maxWidth: 260 }}>{job.lastHandoffs.length ? job.lastHandoffs.join(', ') : '—'}</td>
                <td>{job.enabled ? inZone(job.nextRunAt, job.timezone) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
