// @vitest-environment jsdom
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { HandoffForm, emptyFormValue } from './HandoffForm';
import type { HandoffFormSubmit } from './HandoffForm';
import { isoToLocalParts, localPartsToIso } from '../lib/format';

/** Fills what the form insists on, so a test can focus on the date and time. */
async function fillRequired(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Title'), 'Spare laptops');
  await user.type(screen.getByLabelText('Recipient name'), 'Dev Patel');
  await user.type(screen.getByLabelText('Recipient email'), 'dev@example.test');
  await user.type(screen.getByLabelText('Your name (sender)'), 'Sender');
  await user.type(screen.getByLabelText(/^Item 1/), 'Laptop');
}

function setup(initial = emptyFormValue()) {
  const onSubmit = vi.fn<(v: HandoffFormSubmit) => void>();
  render(<HandoffForm initial={initial} onSubmit={onSubmit} canSend />);
  return onSubmit;
}

describe('HandoffForm expected return', () => {
  it('names the date and the time as two separate, optional fields', () => {
    setup();
    const date = screen.getByLabelText(/Expected return date/);
    const time = screen.getByLabelText(/Expected return time/);
    expect(date).toHaveAttribute('type', 'date');
    expect(time).toHaveAttribute('type', 'time');
    expect(screen.getAllByText('(optional)').length).toBeGreaterThanOrEqual(2);
    expect(time).toBeDisabled();   // a time with no date means nothing: it waits for the date
  });

  it('sends no due date when both are empty', async () => {
    const user = userEvent.setup();
    const onSubmit = setup();
    await fillRequired(user);
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(onSubmit.mock.calls[0][0].header.dueAt).toBeNull();
  });

  it('keeps the date-only behaviour: a date alone is the start of that local day', async () => {
    const user = userEvent.setup();
    const onSubmit = setup();
    await fillRequired(user);
    await user.type(screen.getByLabelText(/Expected return date/), '2026-11-20');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    const dueAt = onSubmit.mock.calls[0][0].header.dueAt!;
    expect(dueAt).toBe(new Date('2026-11-20T00:00').toISOString());   // local midnight, exactly what the desktop form sends
  });

  it('combines a date and a time in the phone’s own time zone', async () => {
    const user = userEvent.setup();
    const onSubmit = setup();
    await fillRequired(user);
    await user.type(screen.getByLabelText(/Expected return date/), '2026-11-21');
    await user.type(screen.getByLabelText(/Expected return time/), '15:30');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(onSubmit.mock.calls[0][0].header.dueAt).toBe(new Date('2026-11-21T15:30').toISOString());
  });

  it('shows the date and time of an existing handoff when it is edited, and sends them back unchanged', async () => {
    const user = userEvent.setup();
    const due = new Date('2026-12-05T08:15').toISOString();
    const parts = isoToLocalParts(due);
    const onSubmit = setup(emptyFormValue({
      title: 'Existing', recipientName: 'R', recipientEmail: 'r@example.test', senderName: 'S',
      items: [{ name: 'Thing', quantity: '1' }], dueDate: parts.date, dueTime: parts.time,
    }));
    expect(screen.getByLabelText(/Expected return date/)).toHaveValue('2026-12-05');
    expect(screen.getByLabelText(/Expected return time/)).toHaveValue('08:15');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(onSubmit.mock.calls[0][0].header.dueAt).toBe(due);
  });

  it('refuses an item without a quantity above zero, without sending anything', async () => {
    const user = userEvent.setup();
    const onSubmit = setup();
    await fillRequired(user);
    await user.clear(screen.getByLabelText('Quantity'));
    await user.type(screen.getByLabelText('Quantity'), '0');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent(/quantity above zero/);
  });
});

describe('HandoffForm recipient email', () => {
  it('points out an address that cannot be reached, keeps the typing, and sends nothing', async () => {
    const user = userEvent.setup();
    const onSubmit = setup();
    await fillRequired(user);
    await user.clear(screen.getByLabelText('Recipient email'));
    await user.type(screen.getByLabelText('Recipient email'), 'ashut@ee');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid email address, for example name@example.com.');
    expect(screen.getByLabelText('Recipient email')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Recipient email')).toHaveFocus();
    expect(screen.getByLabelText('Recipient email')).toHaveValue('ashut@ee');
  });

  it('clears the message as soon as the address is edited, and then sends', async () => {
    const user = userEvent.setup();
    const onSubmit = setup();
    await fillRequired(user);
    await user.clear(screen.getByLabelText('Recipient email'));
    await user.type(screen.getByLabelText('Recipient email'), 'ashut@ee');
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    await user.type(screen.getByLabelText('Recipient email'), '.com');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(onSubmit.mock.calls[0][0].header.recipientEmail).toBe('ashut@ee.com');
  });
});

describe('local date and time conversion', () => {
  it('round-trips a date and time, and treats an empty date as no due date', () => {
    expect(localPartsToIso('', '12:00')).toBeNull();
    const iso = localPartsToIso('2026-03-09', '07:05')!;
    expect(isoToLocalParts(iso)).toEqual({ date: '2026-03-09', time: '07:05' });
  });
});
