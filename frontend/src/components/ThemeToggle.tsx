import { useState } from 'react';
import { getInitialTheme, setTheme, type Theme } from '../lib/theme';

/** Light/dark theme switch, as a single icon (its label is for screen readers and the tooltip). Persists the choice and updates the whole page instantly. */
export function ThemeToggle() {
  const [theme, setThemeState] = useState<Theme>(getInitialTheme);

  function toggle() {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    setTheme(next);
    setThemeState(next);
  }

  return (
    <button
      type="button"
      className="btn btn-sm theme-icon"
      onClick={toggle}
      aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
      title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
    >
      <span aria-hidden="true">{theme === 'dark' ? '☀️' : '🌙'}</span>
    </button>
  );
}
