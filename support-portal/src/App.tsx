import { Navigate, Route, Routes } from 'react-router-dom';
import type { ReactElement } from 'react';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { Spinner } from './components/ui';
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { CustomersPage } from './pages/CustomersPage';
import { CustomerPage } from './pages/CustomerPage';
import { TicketsPage } from './pages/TicketsPage';
import { TicketPage } from './pages/TicketPage';
import { StaffPage } from './pages/StaffPage';
import { JobsPage } from './pages/JobsPage';
import type { Permission } from './api/types';

/** Everything except the login page needs a signed-in member of staff. */
function RequireStaff({ children }: { children: ReactElement }) {
  const { staff, loading } = useAuth();
  if (loading) return <Spinner />;
  if (!staff) return <Navigate to="/login" replace />;
  return children;
}

/** A page for members who hold a permission; everyone else goes to the dashboard (the backend refuses them too). */
function RequirePermission({ permission, children }: { permission: Permission; children: ReactElement }) {
  const { can } = useAuth();
  return can(permission) ? children : <Navigate to="/dashboard" replace />;
}

function PublicOnly({ children }: { children: ReactElement }) {
  const { staff, loading } = useAuth();
  if (loading) return <Spinner />;
  if (staff) return <Navigate to="/dashboard" replace />;
  return children;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<PublicOnly><LoginPage /></PublicOnly>} />
      <Route element={<RequireStaff><Layout /></RequireStaff>}>
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/customers" element={<RequirePermission permission="VIEW_CUSTOMERS"><CustomersPage /></RequirePermission>} />
        <Route path="/customers/:accountId" element={<RequirePermission permission="VIEW_CUSTOMERS"><CustomerPage /></RequirePermission>} />
        <Route path="/tickets" element={<TicketsPage />} />
        <Route path="/tickets/:ticketId" element={<TicketPage />} />
        <Route path="/jobs" element={<RequirePermission permission="MANAGE_CUSTOMERS"><JobsPage /></RequirePermission>} />
        <Route path="/staff" element={<RequirePermission permission="MANAGE_STAFF"><StaffPage /></RequirePermission>} />
      </Route>
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
