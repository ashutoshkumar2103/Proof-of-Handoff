/** The person's choice: follow the phone (default), or force light or dark. */
export type ThemeChoice = 'system' | 'light' | 'dark';

const STORAGE_KEY = 'handoffly.mobile.theme';

export function getThemeChoice(): ThemeChoice {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
  } catch { /* storage may be unavailable: follow the phone */ }
  return 'system';
}

/** Applies a choice to the page (the CSS variables switch on `data-theme`; "system" leaves it to prefers-color-scheme) and remembers it. */
export function setThemeChoice(choice: ThemeChoice): void {
  const root = document.documentElement;
  if (choice === 'system') root.removeAttribute('data-theme');
  else root.setAttribute('data-theme', choice);
  try {
    if (choice === 'system') localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, choice);
  } catch { /* not remembered */ }
}
