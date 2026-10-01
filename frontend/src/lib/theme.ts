export type Theme = 'light' | 'dark';

const STORAGE_KEY = 'handoffly.theme';

/** The user's saved choice, or their OS preference if they haven't chosen. */
export function getInitialTheme(): Theme {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
  } catch {
    /* localStorage may be unavailable — fall through to system preference */
  }
  const prefersDark = typeof window !== 'undefined'
    && window.matchMedia?.('(prefers-color-scheme: dark)').matches;
  return prefersDark ? 'dark' : 'light';
}

/** Applies the theme to the document root so the CSS variables switch. */
export function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute('data-theme', theme);
}

/** Persists and applies a theme choice. */
export function setTheme(theme: Theme): void {
  applyTheme(theme);
  try {
    localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    /* ignore persistence failures (private mode, etc.) */
  }
}
