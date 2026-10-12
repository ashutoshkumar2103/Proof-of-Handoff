// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Route, Routes } from 'react-router-dom';
import { makeUser, renderAt } from '../test/utils';
import { AppShell } from './AppShell';

const auth = { user: makeUser(), loading: false, unreachable: false, retry: vi.fn() };
vi.mock('../auth/AuthContext', () => ({ useAuth: () => auth }));

function shellAt(route: string) {
  return renderAt(
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<p>handoffs page</p>} />
        <Route path="new" element={<p>new page</p>} />
        <Route path="account" element={<p>account page</p>} />
        <Route path="reports" element={<p>reports page</p>} />
      </Route>
    </Routes>,
    route,
  );
}

describe('bottom navigation', () => {
  it('keeps the three destinations, each with an outline icon and a label, and no stand-in dot or emoji', () => {
    shellAt('/');
    const nav = screen.getByRole('navigation', { name: 'Main' });
    const links = [...nav.querySelectorAll('a')];
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['/', '/new', '/account']);
    expect(links.map((a) => a.textContent)).toEqual(['Handoffs', 'New', 'Account']);
    for (const a of links) expect(a.querySelector('svg.icon')).not.toBeNull();   // one icon each, from the same set
    expect(nav.textContent).not.toMatch(/[●＋▤]/);
  });

  it('draws the Account entry as the standard user-circle outline', () => {
    shellAt('/account');
    const icon = screen.getByRole('link', { name: 'Account' }).querySelector('svg')!;
    expect(icon.querySelectorAll('circle')).toHaveLength(2);   // the ring and the head
    expect(icon).toHaveAttribute('fill', 'none');              // an outline, not a filled disc
    expect(icon).toHaveAttribute('aria-hidden', 'true');       // the label is the accessible name
  });

  it.each([
    ['/', 'Handoffs'],
    ['/new', 'New'],
    ['/account', 'Account'],
  ])('marks only the current page as active (%s)', (route, label) => {
    shellAt(route);
    const nav = screen.getByRole('navigation', { name: 'Main' });
    const current = nav.querySelectorAll('[aria-current="page"]');
    expect(current).toHaveLength(1);
    expect(current[0]).toHaveTextContent(label);
    expect(current[0]).toHaveClass('active');
  });

  it('reserves room below the page for the fixed bar and the device safe area', () => {
    // jsdom has no layout, so this reads the rule itself: the page is padded by the bar's height plus the bottom inset
    const css = readFileSync('src/styles.css', 'utf8');
    const page = css.match(/\.page \{[^}]*\}/)![0];
    expect(page).toContain('var(--nav-h)');
    expect(page).toContain('env(safe-area-inset-bottom)');
    expect(css.match(/\.bottomnav \{[^}]*\}/)![0]).toContain('env(safe-area-inset-bottom)');
  });
});

describe('Reports entry', () => {
  it('is in the top bar, so the bottom bar keeps its three destinations, and shows as current on the reports page', () => {
    shellAt('/reports');
    const link = screen.getByRole('link', { name: 'Reports' });
    expect(link).toHaveAttribute('href', '/reports');
    expect(link).toHaveAttribute('aria-current', 'page');
    expect(screen.getByText('reports page')).toBeInTheDocument();
  });

  it('is not marked on other pages', () => {
    shellAt('/');
    expect(screen.getByRole('link', { name: 'Reports' })).not.toHaveAttribute('aria-current');
  });
});
