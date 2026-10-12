import type { ReactElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import type { HandoffSummary, User } from '../api/types';

/** An account as `/auth/me` returns it: active, on a plan that includes HandoffCheck unless a test says otherwise. */
export function makeUser(over: Partial<User> = {}): User {
  return {
    id: 1, accountCode: 'CUS-1', email: 'owner@example.test', displayName: 'Test Owner', plan: 'YEARLY', handoffPrefix: 'TO',
    subscription: { plan: 'YEARLY', status: 'ACTIVE' }, handoffCheck: true, ...over,
  };
}

export function makeHandoff(over: Partial<HandoffSummary> = {}): HandoffSummary {
  return {
    id: 1, publicCode: 'TO-1', title: 'Chairs for the hall', status: 'ACTIVE_WITH_RECIPIENT', recipientName: 'Priya', itemCount: 2,
    totalOutgoing: '9', totalReturned: '3', totalMissing: '0', totalRemaining: '6', overdue: false, outgoingAt: '2026-10-01T10:00:00Z',
    dueAt: null, createdAt: '2026-10-01T09:00:00Z', updatedAt: '2026-10-01T09:00:00Z', ...over,
  };
}

/** Renders a screen inside a fresh query cache and a router at `route`. */
export function renderAt(ui: ReactElement, route = '/') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}
