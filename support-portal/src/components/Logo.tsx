import compact from '../assets/brand/logo-compact.png';
import compactDark from '../assets/brand/logo-compact-dark.png';

/**
 * The HandOffly logo (symbol and wordmark, from the approved image) as a transparent PNG, so it sits on the page rather than on a white box. The
 * dark variant (lighter wordmark text, same symbol) is shown when the OS is in dark mode, the same switch as this portal's colour tokens
 * (see .logo in index.css).
 */
export function Logo() {
  return (
    <>
      <img className="logo logo-light" src={compact} width={604} height={132} alt="HandOffly" />
      <img className="logo logo-dark" src={compactDark} width={604} height={132} alt="HandOffly" />
    </>
  );
}
