import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { registerSW } from 'virtual:pwa-register';
import { App } from './App';
import { HttpError } from './api/client';
import { AuthProvider } from './auth/AuthContext';
import { ConfirmProvider } from './components/ConfirmSheet';
import { getThemeChoice, setThemeChoice } from './lib/theme';
import './styles.css';

setThemeChoice(getThemeChoice());

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // A refusal from the backend (not found, not allowed) will not change by asking again; only a lost connection is worth a retry.
      retry: (count, err) => (err instanceof HttpError ? err.error.status === 0 && count < 2 : count < 2),
      refetchOnWindowFocus: true,
    },
  },
});

// Keeps the app shell up to date; the service worker never touches /api, so nothing private is stored by it.
registerSW({ immediate: true });

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <ConfirmProvider>
            <App />
          </ConfirmProvider>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
