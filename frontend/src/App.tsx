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
import { AccountPage } from './pages/AccountPage';
import { CheckoutPage } from './pages/CheckoutPage';
import { ForgotPasswordPage } from './pages/ForgotPasswordPage';
import { ResetPasswordPage } from './pages/ResetPasswordPage';
import { SupportPage } from './pages/SupportPage';
import { NewTicketPage } from './pages/NewTicketPage';
import { TicketDetailPage } from './pages/TicketDetailPage';

function RequireAuth({ children }: { children: ReactElement }) {
  const { user, loading } = useAuth();
  if (loading) return <Spinner />;
  if (!user) return <Navigate to="/login" replace />;
  return children;
}

/** Hides support pages the plan does not include (the backend refuses them too). */
function RequireSupport({ need, children }: { need: 'contactSupport' | 'message' | 'ticket'; children: ReactElement }) {
  const { user } = useAuth();
  if (!user?.support[need]) return <Navigate to="/dashboard" replace />;
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
      {/* Not PublicOnly: a reset link must work whether or not someone is signed in in this browser. */}
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/reset-password/:token" element={<ResetPasswordPage />} />
      {/* Paying for a plan comes first; signed-in customers can use it too (it applies to their account). */}
      <Route path="/checkout" element={<CheckoutPage />} />
      {/* Public recipient review — no account required */}
      <Route path="/r/:token" element={<RecipientPage />} />

      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/handoffs/new" element={<CreateHandoffPage />} />
        <Route path="/handoffs/:id" element={<HandoffDetailPage />} />
        <Route path="/handoffs/:id/edit" element={<EditHandoffPage />} />
        <Route path="/handoff-check" element={<HandoffCheckPage />} />
        <Route path="/account" element={<AccountPage />} />
        <Route path="/support" element={<RequireSupport need="contactSupport"><SupportPage /></RequireSupport>} />
        <Route path="/support/new" element={<RequireSupport need="ticket"><NewTicketPage /></RequireSupport>} />
        <Route path="/support/message" element={<RequireSupport need="message"><NewTicketPage mode="message" /></RequireSupport>} />
        <Route path="/support/tickets/:code" element={<RequireSupport need="ticket"><TicketDetailPage /></RequireSupport>} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
