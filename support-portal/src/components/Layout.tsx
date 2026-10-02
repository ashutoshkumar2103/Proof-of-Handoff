import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ROLE_LABELS } from '../lib/format';

export function Layout() {
  const { staff, can, logout } = useAuth();
  return (
    <div className="app-shell">
      <header className="topbar">
        <NavLink to="/dashboard" className="brand">Hand<span>Offly</span> Support</NavLink>
        <nav>
          <NavLink to="/dashboard">Dashboard</NavLink>
          {can('VIEW_CUSTOMERS') && <NavLink to="/customers">Customers</NavLink>}
          <NavLink to="/tickets">Tickets</NavLink>
          {can('MANAGE_CUSTOMERS') && <NavLink to="/jobs">Jobs</NavLink>}
          {can('MANAGE_STAFF') && <NavLink to="/staff">Staff</NavLink>}
        </nav>
        {staff && (
          <div className="row">
            <span className="small muted" title={`${staff.staffCode} · ${staff.email}`}>
              {staff.name} · {ROLE_LABELS[staff.role]}
            </span>
            <button className="btn btn-sm" onClick={logout}>Sign out</button>
          </div>
        )}
      </header>
      <main className="container">
        <Outlet />
      </main>
    </div>
  );
}
