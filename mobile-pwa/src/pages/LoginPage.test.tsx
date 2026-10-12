// @vitest-environment jsdom
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderAt } from '../test/utils';
import { LoginPage } from './LoginPage';

const login = vi.fn();
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ user: null, loading: false, login }) }));

describe('LoginPage', () => {
  beforeEach(() => login.mockReset());

  it('puts the password and email in boxes of the same kind, with the eye inside the password box', () => {
    renderAt(<LoginPage />, '/login');
    const email = screen.getByLabelText('Email');
    const password = screen.getByLabelText('Password');
    expect(email.closest('.field')).toBeInTheDocument();
    expect(password.closest('.field')).toBeInTheDocument();
    // the eye is inside the same wrapper as the password input (so it can never sit outside the box), and there is no "Show" word
    const wrapper = password.closest('.input-icon');
    expect(wrapper).toContainElement(screen.getByRole('button', { name: 'Show password' }));
    expect(screen.queryByText('Show')).not.toBeInTheDocument();
  });

  it('shows and hides the password with an accessible toggle that changes its icon and state', async () => {
    const user = userEvent.setup();
    renderAt(<LoginPage />, '/login');
    const password = screen.getByLabelText('Password');
    expect(password).toHaveAttribute('type', 'password');

    const show = screen.getByRole('button', { name: 'Show password' });
    expect(show).toHaveAttribute('aria-pressed', 'false');
    const eyeBefore = show.innerHTML;
    await user.click(show);

    expect(password).toHaveAttribute('type', 'text');
    const hide = screen.getByRole('button', { name: 'Hide password' });
    expect(hide).toHaveAttribute('aria-pressed', 'true');
    expect(hide.innerHTML).not.toBe(eyeBefore);   // eye-off, not the same drawing
    await user.click(hide);
    expect(password).toHaveAttribute('type', 'password');
  });

  it('can be operated from the keyboard', async () => {
    const user = userEvent.setup();
    renderAt(<LoginPage />, '/login');
    await user.tab();   // email
    await user.tab();   // password
    await user.tab();   // the eye
    expect(screen.getByRole('button', { name: 'Show password' })).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text');
  });

  it('signs in with the typed email and password, and shows the backend message when it is refused', async () => {
    const user = userEvent.setup();
    login.mockRejectedValueOnce(new Error('Invalid email or password.'));
    renderAt(<LoginPage />, '/login');
    await user.type(screen.getByLabelText('Email'), 'me@example.test');
    await user.type(screen.getByLabelText('Password'), 'secret-pass');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(login).toHaveBeenCalledWith('me@example.test', 'secret-pass');
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password.');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled());   // the loading state ended
  });
});
