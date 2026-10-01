import { Navigate, Route, Routes } from 'react-router-dom';
import type { ReactElement } from 'react';
import { useAuth } from './auth/AuthContext';
import { Layout } from './components/Layout';
import { Spinner } from './components/ui';
import { LandingPage } from './pages/LandingPage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { DashboardPage } from './pages/DashboardPage';
import { CreateHandoffPage } from './pages/CreateHandoffPage';
import { EditHandoffPage } from './pages/EditHandoffPage';
import { HandoffDetailPage } from './pages/HandoffDetailPage';
import { RecipientPage } from './pages/RecipientPage';
import { HandoffCheckPage } from './pages/HandoffCheckPage';

function RequireAuth({ children }: { children: ReactElement }) {
  const { user, loading } = useAuth();
  if (loading) return <Spinner />;
  if (!user) return <Navigate to="/login" replace />;
  return children;
}

function PublicOnly({ children }: { children: ReactElement }) {
  const { user, loading } = useAuth();
  if (loading) return <Spinner />;
  if (user) return <Navigate to="/dashboard" replace />;
  return children;
}

export function App() {
  return (
    <Routes>
      {/* Public marketing landing page */}
      <Route path="/" element={<LandingPage />} />
      <Route path="/login" element={<PublicOnly><LoginPage /></PublicOnly>} />
      <Route path="/register" element={<PublicOnly><RegisterPage /></PublicOnly>} />
      {/* Public recipient review — no account required */}
      <Route path="/r/:token" element={<RecipientPage />} />

      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/handoffs/new" element={<CreateHandoffPage />} />
        <Route path="/handoffs/:id" element={<HandoffDetailPage />} />
        <Route path="/handoffs/:id/edit" element={<EditHandoffPage />} />
        <Route path="/handoff-check" element={<HandoffCheckPage />} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
