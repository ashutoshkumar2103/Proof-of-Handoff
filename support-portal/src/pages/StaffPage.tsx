import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { staffApi } from '../api/endpoints';
import type { StaffRole, StaffSummary } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { errorMessage, ErrorNotice, Pager, Spinner } from '../components/ui';
import { ASSIGNABLE_ROLES, describeChange, formatDate, formatDateTime, ROLE_LABELS } from '../lib/format';

type Action =
  | { kind: 'role'; staff: StaffSummary; step: 'choose' | 'confirm'; newRole: StaffRole | ''; reason: string }
  | { kind: 'deactivate' | 'reactivate'; staff: StaffSummary; reason: string };

const EMPTY_FORM = { name: '', email: '', password: '', role: 'TICKET_AGENT' as StaffRole };

/**
 * Staff management — administrators only (the backend refuses everyone else; this page is simply not offered to
 * other roles). List and search the team, create staff, change a role, deactivate/reactivate — each change
 * confirmed first and recorded in the audit trail shown below.
 */
export function StaffPage() {
  const { can } = useAuth();
  const queryClient = useQueryClient();
  const [text, setText] = useState('');
  const [search, setSearch] = useState('');
  const [role, setRole] = useState<StaffRole | ''>('');
  const [status, setStatus] = useState<'' | 'active' | 'inactive'>('');
  const [page, setPage] = useState(0);

  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);
  const [action, setAction] = useState<Action | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const active = status === '' ? undefined : status === 'active';
  const staff = useQuery({
    queryKey: ['staff', search, role, active, page],
    queryFn: () => staffApi.list({ q: search, role: role || undefined, active, page }),
  });
  const audit = useQuery({ queryKey: ['audit'], queryFn: () => staffApi.audit(), enabled: can('VIEW_AUDIT') });

  const changed = async () => {
    await queryClient.invalidateQueries({ queryKey: ['staff'] });
    await queryClient.invalidateQueries({ queryKey: ['audit'] });
  };

  async function run(work: () => Promise<string>) {
    setError(null);
    setDone(null);
    setBusy(true);
    try {
      setDone(await work());
      await changed();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const create = (e: React.FormEvent) => {
    e.preventDefault();
    void run(async () => {
      const created = await staffApi.create(form);
      setForm(EMPTY_FORM);   // the password is not kept on screen
      setCreating(false);
      return `${created.name} was created as ${ROLE_LABELS[created.role]} (${created.staffCode}). They sign in with that email and the password you set.`;
    });
  };

  const confirm = () => {
    if (!action) return;
    const current = action;
    void run(async () => {
      let message: string;
      if (current.kind === 'role') {
        if (!current.newRole) return '';
        const updated = await staffApi.changeRole(current.staff.staffCode, current.staff.role, current.newRole, current.reason);
        message = `${updated.name} is now ${ROLE_LABELS[updated.role]}. Their access changed immediately.`;
      } else {
        const updated = await staffApi.setActive(current.staff.staffCode, current.kind === 'reactivate', current.reason);
        message = updated.active
          ? `${updated.name} was reactivated and can sign in again.`
          : `${updated.name} was deactivated. They are signed out and cannot sign in.`;
      }
      setAction(null);
      return message;
    });
  };

  return (
    <div className="stack">
      <div className="spread">
        <div>
          <h1>Staff</h1>
          <p className="muted">The support team. Only administrators manage it.</p>
        </div>
        <button className="btn btn-primary" onClick={() => { setCreating(!creating); setError(null); }}>+ Create staff</button>
      </div>

      {error && <div className="notice notice-error" role="alert">{error}</div>}
      {done && <div className="notice notice-success" role="status">{done}</div>}

      {creating && (
        <form className="card" onSubmit={create}>
          <h2>Create staff</h2>
          <div className="two-col">
            <div className="field">
              <label htmlFor="staffName">Name</label>
              <input id="staffName" value={form.name} maxLength={150} required onChange={(e) => setForm({ ...form, name: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="staffEmail">Email</label>
              <input id="staffEmail" type="email" value={form.email} maxLength={255} required autoComplete="off"
                     onChange={(e) => setForm({ ...form, email: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="staffPassword">Initial password <span className="muted">(at least 12 characters)</span></label>
              <input id="staffPassword" type="password" value={form.password} minLength={12} maxLength={72} required
                     autoComplete="new-password" onChange={(e) => setForm({ ...form, password: e.target.value })} />
            </div>
            <div className="field">
              <label htmlFor="staffRole">Role</label>
              <select id="staffRole" value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as StaffRole })}>
                {ASSIGNABLE_ROLES.map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
              </select>
            </div>
          </div>
          <p className="small muted">
            Share the initial password with them yourself; it is stored only as a hash and cannot be shown again.
            Administrators are not created here.
          </p>
          <div className="row">
            <button className="btn btn-primary" disabled={busy}>{busy ? 'Creating…' : 'Create staff'}</button>
            <button type="button" className="btn btn-ghost" onClick={() => { setCreating(false); setForm(EMPTY_FORM); }}>Cancel</button>
          </div>
        </form>
      )}

      {action && (
        <div className="panel panel-warning" role="alertdialog" aria-label="Confirm staff change">
          <h3>
            {action.kind === 'role' ? 'Change role' : action.kind === 'deactivate' ? 'Deactivate staff' : 'Reactivate staff'}
          </h3>
          <p>
            <strong>{action.staff.name}</strong> ({action.staff.staffCode}, {ROLE_LABELS[action.staff.role]})
          </p>
          {action.kind === 'role' && action.step === 'choose' && (
            <div className="field">
              <label htmlFor="newRole">New role</label>
              <select id="newRole" value={action.newRole} onChange={(e) => setAction({ ...action, newRole: e.target.value as StaffRole })}>
                <option value="" disabled>Choose a role…</option>
                {ASSIGNABLE_ROLES.filter((r) => r !== action.staff.role).map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
              </select>
            </div>
          )}
          {(action.kind !== 'role' || action.step === 'choose') && (
            <div className="field">
              <label htmlFor="staffReason">Reason <span className="muted">(optional, kept in the audit trail)</span></label>
              <input id="staffReason" value={action.reason} maxLength={500}
                     onChange={(e) => setAction({ ...action, reason: e.target.value })} />
            </div>
          )}
          {action.kind === 'role' && action.step === 'confirm' && (
            <p>
              Change from <strong>{ROLE_LABELS[action.staff.role]}</strong> to{' '}
              <strong>{action.newRole && ROLE_LABELS[action.newRole]}</strong>? Their permissions change immediately, even
              if they are signed in.{action.reason.trim() && <> Reason: “{action.reason.trim()}”.</>}
            </p>
          )}
          {action.kind === 'deactivate' && (
            <p className="small">They are signed out at once and cannot sign in until reactivated. Their record and history are kept.</p>
          )}
          {action.kind === 'reactivate' && <p className="small">They will be able to sign in again with their existing password.</p>}
          <div className="row">
            {action.kind === 'role' && action.step === 'choose' ? (
              <button className="btn btn-primary" disabled={!action.newRole} onClick={() => setAction({ ...action, step: 'confirm' })}>
                Review change
              </button>
            ) : (
              <button className="btn btn-primary" disabled={busy} onClick={confirm}>{busy ? 'Saving…' : 'Confirm'}</button>
            )}
            {action.kind === 'role' && action.step === 'confirm' && (
              <button className="btn" disabled={busy} onClick={() => setAction({ ...action, step: 'choose' })}>Back</button>
            )}
            <button className="btn btn-ghost" disabled={busy} onClick={() => setAction(null)}>Cancel</button>
          </div>
        </div>
      )}

      <div className="card">
        <form className="search-bar" onSubmit={(e) => { e.preventDefault(); setPage(0); setSearch(text); }}>
          <input aria-label="Search staff" placeholder="Staff ID, name or email…" value={text} onChange={(e) => setText(e.target.value)} />
          <select aria-label="Role" value={role} onChange={(e) => { setPage(0); setRole(e.target.value as StaffRole | ''); }} style={{ maxWidth: '11rem' }}>
            <option value="">All roles</option>
            {(Object.keys(ROLE_LABELS) as StaffRole[]).map((r) => <option key={r} value={r}>{ROLE_LABELS[r]}</option>)}
          </select>
          <select aria-label="Status" value={status} onChange={(e) => { setPage(0); setStatus(e.target.value as '' | 'active' | 'inactive'); }} style={{ maxWidth: '10rem' }}>
            <option value="">Any status</option>
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </select>
          <button className="btn btn-primary">Search</button>
        </form>

        {staff.isLoading ? <Spinner /> : staff.isError ? <ErrorNotice error={staff.error} /> : (
          staff.data!.content.length === 0 ? <p className="muted center mt-3">No staff match.</p> : (
            <>
              <div className="table-wrap mt-2">
                <table>
                  <thead>
                    <tr><th>Staff ID</th><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th>Created</th><th>Actions</th></tr>
                  </thead>
                  <tbody>
                    {staff.data!.content.map((s) => (
                      <tr key={s.staffCode}>
                        <td className="small">{s.staffCode}</td>
                        <td><strong>{s.name}</strong></td>
                        <td>{s.email}</td>
                        <td><span className={`badge ${s.role === 'ADMIN' ? 'badge-primary' : ''}`}>{ROLE_LABELS[s.role]}</span></td>
                        <td>
                          <span className={`badge badge-dot ${s.active ? 'badge-success' : 'badge-danger'}`}>
                            {s.active ? 'Active' : 'Inactive'}
                          </span>
                        </td>
                        <td className="small muted">{formatDate(s.createdAt)}</td>
                        <td>
                          {s.role === 'ADMIN' ? <span className="small muted">—</span> : (
                            <div className="row" style={{ gap: '0.4rem', flexWrap: 'nowrap' }}>
                              <button className="btn btn-sm" disabled={busy}
                                      onClick={() => { setError(null); setAction({ kind: 'role', staff: s, step: 'choose', newRole: '', reason: '' }); }}>
                                Change role
                              </button>
                              <button className="btn btn-sm" disabled={busy}
                                      onClick={() => { setError(null); setAction({ kind: s.active ? 'deactivate' : 'reactivate', staff: s, reason: '' }); }}>
                                {s.active ? 'Deactivate' : 'Reactivate'}
                              </button>
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <Pager page={staff.data!} onPage={setPage} />
            </>
          )
        )}
      </div>

      {can('VIEW_AUDIT') && (
        <div className="card">
          <h2>Audit trail</h2>
          <p className="small muted">The latest changes by support staff — to the team and to customer accounts. Read-only.</p>
          {audit.isLoading ? <Spinner /> : audit.isError ? <ErrorNotice error={audit.error} /> : (
            audit.data!.content.length === 0 ? <p className="muted">Nothing recorded yet.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>When</th><th>Change</th><th>To</th><th>By</th><th>Reason</th></tr></thead>
                  <tbody>
                    {audit.data!.content.map((e, i) => (
                      <tr key={i}>
                        <td className="small muted">{formatDateTime(e.at)}</td>
                        <td><strong>{describeChange(e)}</strong></td>
                        <td className="small">{e.subjectCode}</td>
                        <td className="small">{e.staffCode}<div className="muted">{e.staffName}</div></td>
                        <td className="small">{e.reason ?? '—'}</td>
                      </tr>
                    ))}
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
