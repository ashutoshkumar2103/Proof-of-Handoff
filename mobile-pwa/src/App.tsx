import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { AccountPage } from './pages/AccountPage';
import { DashboardPage } from './pages/DashboardPage';
import { EditHandoffPage } from './pages/EditHandoffPage';
import { HandoffDetailPage } from './pages/HandoffDetailPage';
import { LoginPage } from './pages/LoginPage';
import { NewHandoffPage } from './pages/NewHandoffPage';
import { RecipientPage } from './pages/RecipientPage';
import { ReportsPage } from './pages/ReportsPage';
import { ReturnPage } from './pages/ReturnPage';

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      {/* The recipient's secure link: public, and it opens exactly one handoff. */}
      <Route path="/r/:token" element={<RecipientPage />} />
      <Route element={<AppShell />}>
        <Route index element={<DashboardPage />} />
        <Route path="new" element={<NewHandoffPage />} />
        <Route path="h/:id" element={<HandoffDetailPage />} />
        <Route path="h/:id/edit" element={<EditHandoffPage />} />
        <Route path="h/:id/return" element={<ReturnPage />} />
        <Route path="reports" element={<ReportsPage />} />
        <Route path="account" element={<AccountPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
