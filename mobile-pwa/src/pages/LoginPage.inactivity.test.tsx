// @vitest-environment jsdom
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderAt } from '../test/utils';
import { LoginPage } from './LoginPage';

const state = vi.hoisted(() => ({ inactivityLogout: true }));
vi.mock('../auth/AuthContext', () => ({
  useAuth: () => ({ user: null, loading: false, login: vi.fn(), inactivityLogout: state.inactivityLogout }),
}));

describe('LoginPage after an inactivity logout', () => {
  it('says why the person was signed out, and says nothing otherwise', () => {
    state.inactivityLogout = true;
    const { unmount } = renderAt(<LoginPage />, '/login');
    expect(screen.getByRole('status')).toHaveTextContent('You were logged out after 15 minutes of inactivity for security.');
    unmount();

    state.inactivityLogout = false;
    renderAt(<LoginPage />, '/login');
    expect(screen.queryByText(/inactivity/)).not.toBeInTheDocument();
  });
});
