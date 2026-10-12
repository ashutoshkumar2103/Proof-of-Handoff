// @vitest-environment jsdom
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HttpError } from '../api/client';
import { handoffApi } from '../api/endpoints';
import type { DashboardResponse, HandoffSummary, Page } from '../api/types';
import { makeHandoff, makeUser, renderAt } from '../test/utils';
import { DashboardPage } from './DashboardPage';

vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ user: makeUser() }) }));
vi.mock('../api/endpoints', () => ({ handoffApi: { dashboard: vi.fn(), list: vi.fn() } }));

const dashboard = vi.mocked(handoffApi.dashboard);
const list = vi.mocked(handoffApi.list);

const COUNTS: DashboardResponse = {
  total: 6, overdueCount: 1,
  statusCounts: { DRAFT: 1, AWAITING_RECIPIENT: 2, ACTIVE_WITH_RECIPIENT: 2, CLOSED: 1 },
};

const page = (content: HandoffSummary[]): Page<HandoffSummary> =>
  ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1, first: true, last: true });

beforeEach(() => {
  dashboard.mockReset().mockResolvedValue(COUNTS);
  list.mockReset().mockResolvedValue(page([
    makeHandoff({ id: 1, publicCode: 'TO-1', title: 'Chairs for the hall' }),
    makeHandoff({ id: 2, publicCode: 'TO-2', title: 'Late projector', overdue: true }),
    makeHandoff({ id: 3, publicCode: null, title: 'A draft', status: 'DRAFT', outgoingAt: null, itemCount: 3 }),
  ]));
});

describe('Dashboard', () => {
  it('shows compact cards with reference, title, recipient, status and the quantities that matter', async () => {
    renderAt(<DashboardPage />);
    const card = (await screen.findByText('Chairs for the hall')).closest('a')!;
    expect(card).toHaveAttribute('href', '/h/1');
    expect(within(card).getByText('TO-1')).toBeInTheDocument();
    expect(within(card).getByText(/To Priya/)).toBeInTheDocument();
    expect(within(card).getByText('Active with recipient')).toBeInTheDocument();
    for (const label of ['Given', 'Returned', 'Missing', 'Remaining']) expect(within(card).getByText(label)).toBeInTheDocument();
    expect(within(card).getByText('9')).toBeInTheDocument();   // given
    expect(within(card).getByText('6')).toBeInTheDocument();   // remaining
  });

  it('shows a draft by its item count rather than figures that would all read zero', async () => {
    renderAt(<DashboardPage />);
    const draft = (await screen.findByText('A draft')).closest('a')!;
    expect(within(draft).getByText('3 items · not sent yet')).toBeInTheDocument();
    expect(within(draft).queryByText('Remaining')).not.toBeInTheDocument();
  });

  it('says a draft’s reference is assigned on submission, and never shows a made-up or empty one', async () => {
    renderAt(<DashboardPage />);
    const draft = (await screen.findByText('A draft')).closest('a')!;
    expect(within(draft).getByText('Reference assigned on submission')).toBeInTheDocument();
    expect(within(draft).getByText('Draft')).toBeInTheDocument();
    expect(draft.textContent).not.toMatch(/null|undefined/);
  });

  it('offers every real status in one compact dropdown, with the backend’s counts', async () => {
    renderAt(<DashboardPage />);
    const select = await screen.findByRole('combobox', { name: 'Filter by status' });
    await within(select).findByRole('option', { name: 'All handoffs (6)' });
    const names = within(select).getAllByRole('option').map((o) => o.textContent);
    expect(names).toEqual([
      'All handoffs (6)', 'Overdue (1)', 'Draft (1)', 'Awaiting recipient (2)', 'Active with recipient (2)', 'Partially returned (0)',
      'Fully returned (0)', 'Closed (1)', 'Rejected (0)', 'Cancelled (0)', 'Disputed (0)',
    ]);
  });

  it('asks the backend for the chosen status', async () => {
    const user = userEvent.setup();
    renderAt(<DashboardPage />);
    await screen.findByText('Chairs for the hall');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by status' }), 'DRAFT');
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ status: ['DRAFT'], q: undefined })));
  });

  it('searches and filters together, and clears the search without losing the filter', async () => {
    const user = userEvent.setup();
    renderAt(<DashboardPage />);
    await screen.findByText('Chairs for the hall');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by status' }), 'AWAITING_RECIPIENT');
    await user.type(screen.getByRole('searchbox', { name: 'Search handoffs' }), 'priya');
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ status: ['AWAITING_RECIPIENT'], q: 'priya' })));

    await user.clear(screen.getByRole('searchbox', { name: 'Search handoffs' }));
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ status: ['AWAITING_RECIPIENT'], q: undefined })));
  });

  it('keeps only the late ones for the Overdue view', async () => {
    const user = userEvent.setup();
    renderAt(<DashboardPage />);
    await screen.findByText('Chairs for the hall');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by status' }), 'OVERDUE');
    await waitFor(() => expect(screen.queryByText('Chairs for the hall')).not.toBeInTheDocument());
    expect(screen.getByText('Late projector')).toBeInTheDocument();
  });

  it('shows Needs attention only where something needs it, and a tap on it filters the list', async () => {
    const user = userEvent.setup();
    renderAt(<DashboardPage />);
    const group = await screen.findByRole('region', { name: 'Needs attention' });
    const overdue = within(group).getByRole('button', { name: /1\s*Overdue/ });
    expect(within(group).getByRole('button', { name: /2\s*Awaiting recipient/ })).toBeInTheDocument();
    await user.click(overdue);
    expect(overdue).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('combobox', { name: 'Filter by status' })).toHaveValue('OVERDUE');
    await user.click(overdue);   // the same chip lets go
    expect(screen.getByRole('combobox', { name: 'Filter by status' })).toHaveValue('ALL');
  });

  it('leaves the attention area out when nothing is late or waiting', async () => {
    dashboard.mockResolvedValue({ total: 2, overdueCount: 0, statusCounts: { DRAFT: 2 } });
    renderAt(<DashboardPage />);
    await screen.findByText('Chairs for the hall');
    expect(screen.queryByRole('region', { name: 'Needs attention' })).not.toBeInTheDocument();
  });

  it('leaves creating a handoff to the bottom bar: no second New button in the page header', async () => {
    renderAt(<DashboardPage />);
    await screen.findByText('Chairs for the hall');
    expect(screen.queryByRole('link', { name: /^New$/ })).not.toBeInTheDocument();
  });

  it('shows a loading state, then the list', async () => {
    renderAt(<DashboardPage />);
    expect(screen.getAllByRole('status')[0]).toHaveTextContent('Loading');
    expect(await screen.findByText('Chairs for the hall')).toBeInTheDocument();
  });

  it('says so when nothing matches, and offers a way back', async () => {
    const user = userEvent.setup();
    list.mockResolvedValue(page([]));
    renderAt(<DashboardPage />);
    expect(await screen.findByText('No handoffs in this view.')).toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Filter by status' }), 'CLOSED');
    await user.click(await screen.findByRole('button', { name: 'Show all handoffs' }));
    expect(screen.getByRole('combobox', { name: 'Filter by status' })).toHaveValue('ALL');
  });

  it('welcomes a customer with no handoffs yet', async () => {
    dashboard.mockResolvedValue({ total: 0, overdueCount: 0, statusCounts: {} });
    renderAt(<DashboardPage />);
    expect(await screen.findByText('No handoffs yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Create your first handoff' })).toHaveAttribute('href', '/new');
  });

  it('shows a failed load as an alert with a retry, and retries', async () => {
    const user = userEvent.setup();
    list.mockRejectedValueOnce(new HttpError({ status: 500, detail: 'The server had a problem.' }));
    renderAt(<DashboardPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('The server had a problem.');
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Chairs for the hall')).toBeInTheDocument();
  });
});
