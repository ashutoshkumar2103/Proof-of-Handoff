import compact from '../assets/brand/logo-compact.png';
import compactDark from '../assets/brand/logo-compact-dark.png';
import full from '../assets/brand/logo-full.png';
import fullDark from '../assets/brand/logo-full-dark.png';

/**
 * The HandOffly logo, from the approved image. `compact` is the symbol and wordmark side by side, for the bar and wherever space is short;
 * `full` adds the tagline and descriptor, for roomy places such as the sign-in page. Both are transparent PNGs, so they sit on the page
 * rather than on a white box; the dark-theme variant (lighter wordmark text, same symbol) is shown when the dark theme is on, by the same
 * switch as the colour tokens (see .logo in styles.css), so there is nothing to re-render when the theme changes.
 */
const FILES = {
  compact: { light: compact, dark: compactDark, width: 604, height: 132, alt: 'HandOffly' },
  full: { light: full, dark: fullDark, width: 720, height: 633, alt: 'HandOffly — Handoffs Made Simple' },
};

export function Logo({ variant = 'compact' }: { variant?: 'compact' | 'full' }) {
  const f = FILES[variant];
  return (
    <>
      <img className={`logo logo-${variant} logo-light`} src={f.light} width={f.width} height={f.height} alt={f.alt} />
      <img className={`logo logo-${variant} logo-dark`} src={f.dark} width={f.width} height={f.height} alt={f.alt} />
    </>
  );
}
