import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { jobApi } from '../api/endpoints';
import type { ExpiryJobRun, JobStatus } from '../api/types';
import { errorMessage, ErrorNotice, Spinner } from '../components/ui';

const KEY = ['expiry-job'];

/** Ready-made schedules, for convenience only: the backend checks every schedule it is given. */
const PRESETS: { label: string; cron: string }[] = [
  { label: 'Every day at 09:00', cron: '0 0 9 * * *' },
  { label: 'Weekdays at 09:00', cron: '0 0 9 * * MON-FRI' },
  { label: 'Every Monday at 09:00', cron: '0 0 9 * * MON' },
  { label: 'Every 6 hours', cron: '0 0 */6 * * *' },
];
const CUSTOM = 'custom';

const STATUS_LABEL: Record<JobStatus, string> = { SENT: 'Reminders sent', NOTHING_TO_REPORT: 'Nobody to remind', FAILED: 'Some failed' };
const STATUS_BADGE: Record<JobStatus, string> = { SENT: 'badge-success', NOTHING_TO_REPORT: 'badge-primary', FAILED: 'badge-danger' };

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

function timezones(): string[] {
  const supported = (Intl as unknown as { supportedValuesOf?: (key: string) => string[] }).supportedValuesOf;
  return supported ? supported('timeZone') : [];
}

/**
 * The subscription-expiry reminder job — administrators and managers only (the backend refuses everyone else; the page
 * is simply not offered to other roles). It emails customers whose subscription is about to end, once per end date.
 * It can be paused, resumed, scheduled and run on demand, and it never changes a plan.
 */
export function JobsPage() {
  const qc = useQueryClient();
  const job = useQuery({ queryKey: KEY, queryFn: jobApi.expiry });
  const [result, setResult] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);
  const [editing, setEditing] = useState(false);
  const refresh = () => qc.invalidateQueries({ queryKey: KEY });

  const run = useMutation({
    mutationFn: jobApi.runExpiry,
    onSuccess: (r: ExpiryJobRun) => {
      setResult({
        kind: r.status === 'FAILED' ? 'error' : 'success',
        text: r.status === 'NOTHING_TO_REPORT'
          ? 'Nobody needed a reminder right now.'
          : `${r.reminded} customer${r.reminded === 1 ? ' was' : 's were'} reminded${r.accountCodes.length ? ` (${r.accountCodes.join(', ')})` : ''}.`
            + (r.failed ? ` ${r.message ?? `${r.failed} could not be emailed; they will be tried again at the next run.`}` : ''),
      });
      void refresh();
    },
    onError: (e) => setResult({ kind: 'error', text: errorMessage(e) }),
  });
  const toggle = useMutation({
    mutationFn: (enabled: boolean) => jobApi.setExpiryEnabled(enabled),
    onSuccess: () => { setResult(null); void refresh(); },
    onError: (e) => setResult({ kind: 'error', text: errorMessage(e) }),
  });

  if (job.isLoading) return <Spinner />;
  if (job.error) return <ErrorNotice error={job.error} />;
  const j = job.data!;

  return (
    <div className="stack">
      <div>
        <h1>Jobs</h1>
        <p className="muted">Scheduled work for the whole platform.</p>
      </div>

      <div className="card">
        <div className="card-header">
          <h2>Subscription expiry reminder</h2>
          <span className={`badge badge-dot ${j.enabled ? 'badge-success' : 'badge-warning'}`}>{j.enabled ? 'Active' : 'Paused'}</span>
        </div>
        <p className="small muted">
          Emails each customer whose subscription ends within {j.windowDays} day{j.windowDays === 1 ? '' : 's'}, once per end date —
          renewing moves the end date, so the next period is reminded about again. It only sends emails: it never changes a
          plan or an end date. It starts switched off.
        </p>
        {result && (
          <div className={`notice ${result.kind === 'error' ? 'notice-error' : 'notice-success'} mb-2`}
               role={result.kind === 'error' ? 'alert' : 'status'}>{result.text}</div>
        )}

        <dl className="job-facts">
          <dt>Schedule</dt>
          <dd><code>{j.cronExpression}</code> · {j.timezone}</dd>
          <dt>Reminds</dt>
          <dd>{j.windowDays} day{j.windowDays === 1 ? '' : 's'} before a subscription ends</dd>
          <dt>Next runs</dt>
          <dd>{j.enabled && j.upcomingRuns.length
            ? j.upcomingRuns.map((t) => inZone(t, j.timezone)).join('  ·  ')
            : 'Paused — it will not run until you resume it.'}</dd>
          <dt>Last run</dt>
          <dd>
            {j.lastRunAt ? inZone(j.lastRunAt, j.timezone) : 'Not run yet'}
            {j.lastStatus && <> · <span className={`badge ${STATUS_BADGE[j.lastStatus]}`}>{STATUS_LABEL[j.lastStatus]}</span></>}
            {j.lastStatus && j.lastCount != null && <> · {j.lastCount} customer{j.lastCount === 1 ? '' : 's'} reminded</>}
            {j.lastDetail && <div className="small muted">{j.lastDetail}</div>}
          </dd>
        </dl>

        <div className="row">
          <button className="btn btn-primary btn-sm" disabled={run.isPending} onClick={() => { setResult(null); run.mutate(); }}>
            {run.isPending ? 'Running…' : 'Run now'}
          </button>
          <button className="btn btn-sm" disabled={toggle.isPending} onClick={() => toggle.mutate(!j.enabled)}>
            {j.enabled ? 'Pause' : 'Resume'}
          </button>
          <button className="btn btn-sm" onClick={() => setEditing(!editing)} aria-expanded={editing}>
            {editing ? 'Close' : 'Edit schedule'}
          </button>
        </div>

        {editing && (
          <ScheduleEditor key={`${j.cronExpression}|${j.timezone}|${j.windowDays}`} cron={j.cronExpression} zone={j.timezone} windowDays={j.windowDays}
                          onSaved={() => { setEditing(false); setResult(null); void refresh(); }} />
        )}
      </div>
    </div>
  );
}

function ScheduleEditor({ cron: initialCron, zone: initialZone, windowDays: initialWindow, onSaved }: {
  cron: string; zone: string; windowDays: number; onSaved: () => void;
}) {
  const [choice, setChoice] = useState(PRESETS.find((p) => p.cron === initialCron)?.cron ?? CUSTOM);
  const [cron, setCron] = useState(initialCron);
  const [zone, setZone] = useState(initialZone);
  const [windowDays, setWindowDays] = useState(String(initialWindow));
  const [error, setError] = useState<string | null>(null);
  const zones = timezones();

  const save = useMutation({
    mutationFn: () => jobApi.updateExpiry({ cronExpression: cron.trim(), timezone: zone.trim(), windowDays: Number(windowDays) }),
    onSuccess: onSaved,
    onError: (e) => setError(errorMessage(e)),
  });

  return (
    <form className="job-editor" onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate(); }}>
      {error && <div className="notice notice-error mb-2" role="alert">{error}</div>}
      <div className="field">
        <label htmlFor="preset">When</label>
        <select id="preset" value={choice} onChange={(e) => { setChoice(e.target.value); if (e.target.value !== CUSTOM) setCron(e.target.value); }}>
          {PRESETS.map((p) => <option key={p.cron} value={p.cron}>{p.label}</option>)}
          <option value={CUSTOM}>Custom (cron expression)</option>
        </select>
      </div>
      <div className="field-row">
        <div className="field">
          <label htmlFor="cron">Cron expression</label>
          <input id="cron" value={cron} maxLength={100} spellCheck={false} required
                 onChange={(e) => { setCron(e.target.value); setChoice(CUSTOM); }} />
        </div>
        <div className="field">
          <label htmlFor="zone">Timezone</label>
          <input id="zone" value={zone} maxLength={60} list="job-timezones" spellCheck={false} required
                 onChange={(e) => setZone(e.target.value)} />
          <datalist id="job-timezones">{zones.map((z) => <option key={z} value={z} />)}</datalist>
        </div>
        <div className="field">
          <label htmlFor="window">Remind this many days before (1–90)</label>
          <input id="window" type="number" min={1} max={90} value={windowDays} required
                 onChange={(e) => setWindowDays(e.target.value)} />
        </div>
      </div>
      <p className="small muted">
        Six fields: <code>second minute hour day-of-month month day-of-week</code>, for example <code>0 0 9 * * *</code> for every
        day at 09:00. The job can run at most once an hour, and the schedule is checked when you save it.
      </p>
      <button className="btn btn-primary btn-sm" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save schedule'}</button>
    </form>
  );
}
