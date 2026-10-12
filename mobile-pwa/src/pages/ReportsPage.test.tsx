// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HttpError } from '../api/client';
import { reportApi } from '../api/endpoints';
import type { HandoffReport, User } from '../api/types';
import { makeHandoff, makeUser, renderAt } from '../test/utils';
import { ReportsPage } from './ReportsPage';

let currentUser: User = makeUser();
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ user: currentUser }) }));
vi.mock('../api/endpoints', () => ({
  reportApi: { handoffs: vi.fn(), exportCsv: vi.fn(), assistantSuggestions: vi.fn(), ask: vi.fn() },
}));

const handoffs = vi.mocked(reportApi.handoffs);
const suggestions = vi.mocked(reportApi.assistantSuggestions);
const ask = vi.mocked(reportApi.ask);

const REPORT: HandoffReport = {
  period: { from: '2026-10-01', to: '2026-10-31', timezone: 'UTC' },
  summary: {
    created: 4, closed: 1, open: 2, overdue: 1, itemsGiven: '40', itemsReturned: '12', itemsMissing: '2', itemsStillOut: '26',
    itemsRejected: '0', itemsCancelled: '0',
  },
  handoffs: {
    content: [makeHandoff({ id: 5, publicCode: 'TO-5', title: 'Quarterly kit' }), makeHandoff({ id: 6, publicCode: 'TO-6', title: 'Draft kit', status: 'DRAFT', outgoingAt: null })],
    page: 0, size: 20, totalElements: 2, totalPages: 1, first: true, last: true,
  },
};

beforeEach(() => {
  currentUser = makeUser();
  handoffs.mockReset().mockResolvedValue(REPORT);
  suggestions.mockReset().mockResolvedValue([{ intent: 'CLOSED_COUNT', question: 'How many handoffs were closed?' }]);
  ask.mockReset();
});

describe('Reports', () => {
  it('lists the period’s handoffs with the same compact cards, asking the backend for this month by default', async () => {
    renderAt(<ReportsPage />, '/reports');
    expect(await screen.findByText('Quarterly kit')).toBeInTheDocument();
    expect(screen.getByText('Quarterly kit').closest('a')).toHaveAttribute('href', '/h/5');
    expect(screen.getByText('2 handoffs · page 1 of 1')).toBeInTheDocument();
    const query = handoffs.mock.calls[0][0];
    expect(query.from).toMatch(/^\d{4}-\d{2}-01$/);
    expect(query.sort).toBe('created,desc');
    expect(query.status).toBeUndefined();
  });

  it('filters by status and by overdue through the backend, never by counting on the phone', async () => {
    const user = userEvent.setup();
    renderAt(<ReportsPage />, '/reports');
    await screen.findByText('Quarterly kit');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Show handoffs with status' }), 'CLOSED');
    await waitFor(() => expect(handoffs).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'CLOSED' }), 0, 20, expect.anything()));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Show handoffs with status' }), 'OVERDUE');
    await waitFor(() => expect(handoffs).toHaveBeenLastCalledWith(expect.objectContaining({ overdue: true }), 0, 20, expect.anything()));
    await user.selectOptions(screen.getByRole('combobox', { name: 'Sort handoffs' }), 'expectedReturn,asc');
    await waitFor(() => expect(handoffs).toHaveBeenLastCalledWith(expect.objectContaining({ sort: 'expectedReturn,asc' }), 0, 20, expect.anything()));
  });

  it('shows the summary totals exactly as the backend worked them out', async () => {
    renderAt(<ReportsPage />, '/reports?view=summary');
    const tile = (label: string) => screen.getByText(label).closest<HTMLElement>('.tile')!;
    expect(within(await waitFor(() => tile('Handoffs created'))).getByText('4')).toBeInTheDocument();
    expect(within(tile('Items given')).getByText('40')).toBeInTheDocument();
    expect(within(tile('Items missing')).getByText('2')).toBeInTheDocument();
    expect(handoffs).toHaveBeenCalledWith(expect.anything(), 0, 1, expect.anything());   // only the totals are read
  });

  it('exports the same filters as CSV through the backend', async () => {
    const user = userEvent.setup();
    const exportCsv = vi.mocked(reportApi.exportCsv).mockResolvedValue({ blob: new Blob(['a,b']), filename: 'handoff-report.csv' });
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    renderAt(<ReportsPage />, '/reports');
    await screen.findByText('Quarterly kit');
    await user.click(screen.getByRole('button', { name: /Export CSV/ }));
    await waitFor(() => expect(exportCsv).toHaveBeenCalledWith(expect.objectContaining({ timezone: expect.any(String), sort: 'created,desc' })));
  });

  it('says so when the period has nothing in it', async () => {
    handoffs.mockResolvedValue({ ...REPORT, summary: { ...REPORT.summary, created: 0 } });
    renderAt(<ReportsPage />, '/reports');
    expect(await screen.findByText('No handoffs in this period')).toBeInTheDocument();
  });

  it('shows a failed load as an alert with a retry', async () => {
    handoffs.mockRejectedValueOnce(new HttpError({ status: 500, detail: 'The server had a problem.' }));
    renderAt(<ReportsPage />, '/reports');
    expect(await screen.findByRole('alert')).toHaveTextContent('The server had a problem.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('switches between the list and the summary', async () => {
    const user = userEvent.setup();
    renderAt(<ReportsPage />, '/reports');
    await screen.findByText('Quarterly kit');
    await user.click(screen.getByRole('button', { name: 'Summary' }));
    expect(await screen.findByText('Handoffs created')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Summary' })).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('Reports access', () => {
  it('does not ask the backend at all when the plan has ended, and says why', () => {
    currentUser = makeUser({ subscription: { plan: 'MONTHLY', status: 'INACTIVE' }, handoffCheck: false });
    renderAt(<ReportsPage />, '/reports');
    expect(screen.getByRole('status')).toHaveTextContent('Your subscription has ended');
    expect(handoffs).not.toHaveBeenCalled();
  });

  it('says the same, in its own words, for an account that never had a plan', () => {
    currentUser = makeUser({ plan: null, subscription: { plan: null, status: 'INACTIVE' }, handoffCheck: false });
    renderAt(<ReportsPage />, '/reports');
    expect(screen.getByRole('status')).toHaveTextContent('No active plan is associated with this account');
    expect(handoffs).not.toHaveBeenCalled();
  });

  it('shows the backend’s own refusal if the plan ended while the page was open', async () => {
    handoffs.mockRejectedValueOnce(new HttpError({ status: 403, code: 'subscription_expired', detail: 'Your subscription has ended.' }));
    renderAt(<ReportsPage />, '/reports');
    expect(await screen.findByRole('alert')).toHaveTextContent('Your subscription has ended.');
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();   // a refusal is not retried
  });
});

describe('AI Report Assistant', () => {
  it('is available on a plan that includes it, answers a ready-made question without free text, and is not closed by an outside tap', async () => {
    const user = userEvent.setup();
    ask.mockResolvedValue({ kind: 'ANSWER', answer: 'Overall, 1 handoff is closed.', basis: 'All your handoffs', entries: [], entryTotal: 0, canRetry: false });
    renderAt(<ReportsPage />, '/reports');
    await screen.findByText('Quarterly kit');
    await user.click(screen.getByRole('button', { name: /Ask AI/ }));
    const dialog = await screen.findByRole('dialog', { name: 'AI Report Assistant' });
    await user.click(await within(dialog).findByRole('button', { name: 'How many handoffs were closed?' }));
    await user.click(within(dialog).getByRole('button', { name: 'Ask' }));

    expect(await within(dialog).findByText('Overall, 1 handoff is closed.')).toBeInTheDocument();
    expect(ask).toHaveBeenCalledWith(expect.any(String), { suggestion: 'CLOSED_COUNT' });   // by intent: no AI is used for it

    await user.click(document.querySelector('.sheet-overlay')!);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('is locked, with the plan message, on a plan without HandoffCheck, and asks nothing', async () => {
    const user = userEvent.setup();
    currentUser = makeUser({ plan: 'MONTHLY', subscription: { plan: 'MONTHLY', status: 'ACTIVE' }, handoffCheck: false });
    renderAt(<ReportsPage />, '/reports');
    await screen.findByText('Quarterly kit');
    await user.click(screen.getByRole('button', { name: /Ask AI/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('The AI Report Assistant is available on Half-Yearly and Yearly plans.')).toBeInTheDocument();
    expect(within(dialog).queryByRole('textbox')).not.toBeInTheDocument();
    expect(suggestions).not.toHaveBeenCalled();
    expect(ask).not.toHaveBeenCalled();
  });
});
