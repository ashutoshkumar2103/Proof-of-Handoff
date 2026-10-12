// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { handoffApi } from '../api/endpoints';
import type { HandoffDetail } from '../api/types';
import { ConfirmProvider } from '../components/ConfirmSheet';
import { renderAt } from '../test/utils';
import { HandoffDetailPage } from './HandoffDetailPage';

vi.mock('../api/endpoints', () => ({
  handoffApi: { get: vi.fn(), cancel: vi.fn(), dispute: vi.fn(), close: vi.fn(), remove: vi.fn(), submit: vi.fn() },
  returnApi: { confirm: vi.fn() },
}));

const get = vi.mocked(handoffApi.get);
const cancel = vi.mocked(handoffApi.cancel);
const dispute = vi.mocked(handoffApi.dispute);

const DETAIL: HandoffDetail = {
  id: 7, publicCode: 'TO-7', title: 'Chairs', status: 'ACTIVE_WITH_RECIPIENT', senderName: 'S', recipientName: 'Priya', recipientEmail: 'p@example.test',
  createdAt: '2026-10-01T09:00:00Z', updatedAt: '2026-10-01T09:00:00Z', totalOutgoing: '4', totalReturned: '0', totalRemaining: '4', totalMissing: '0',
  overdue: false, availableActions: ['CANCEL', 'DISPUTE', 'RECORD_RETURN'], items: [], returns: [], attachments: [], events: [],
};

function open() {
  return renderAt(
    <ConfirmProvider>
      <Routes><Route path="/h/:id" element={<HandoffDetailPage />} /></Routes>
    </ConfirmProvider>,
    '/h/7',
  );
}

beforeEach(() => {
  get.mockReset().mockResolvedValue(DETAIL);
  cancel.mockReset().mockResolvedValue({ ...DETAIL, status: 'CANCELLED', availableActions: [] });
  dispute.mockReset().mockResolvedValue({ ...DETAIL, status: 'DISPUTED' });
});

describe('cancelling and disputing a handoff need a reason', () => {
  async function openMore(user: ReturnType<typeof userEvent.setup>) {
    await screen.findByText('Chairs');
    await user.click(screen.getByText('More actions'));
  }

  it('will not cancel without one, says so, and cancels with the reason that is typed', async () => {
    const user = userEvent.setup();
    open();
    await openMore(user);
    await user.click(screen.getByRole('button', { name: 'Cancel handoff' }));

    const dialog = await screen.findByRole('dialog', { name: 'Cancel this handoff?' });
    expect(dialog).toHaveTextContent('Reason (required)');
    await user.click(dialog.querySelector('[type=submit]') as HTMLElement);   // confirm with the box empty

    expect(await screen.findByText('This is required.')).toBeInTheDocument();
    expect(cancel).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();   // the sheet stays open

    await user.type(screen.getByLabelText('Reason (required)'), '  Sent to the wrong person ');
    await user.click(screen.getByRole('dialog').querySelector('[type=submit]') as HTMLElement);
    await waitFor(() => expect(cancel).toHaveBeenCalledWith(7, 'Sent to the wrong person'));
  });

  it('will not mark it disputed without one either, and sends the reason when it is typed', async () => {
    const user = userEvent.setup();
    open();
    await openMore(user);
    await user.click(screen.getByRole('button', { name: 'Mark disputed' }));

    const dialog = await screen.findByRole('dialog', { name: 'Mark as disputed?' });
    expect(dialog).toHaveTextContent('Reason (required)');
    await user.click(dialog.querySelector('[type=submit]') as HTMLElement);
    expect(await screen.findByText('This is required.')).toBeInTheDocument();
    expect(dispute).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText('Reason (required)'), 'Quantity does not match');
    await user.click(screen.getByRole('dialog').querySelector('[type=submit]') as HTMLElement);
    await waitFor(() => expect(dispute).toHaveBeenCalledWith(7, 'Quantity does not match'));
  });

  it('can still be backed out of without a reason', async () => {
    const user = userEvent.setup();
    open();
    await openMore(user);
    await user.click(screen.getByRole('button', { name: 'Cancel handoff' }));
    await screen.findByRole('dialog');
    await user.click(screen.getByRole('button', { name: 'Keep' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(cancel).not.toHaveBeenCalled();
  });
});
