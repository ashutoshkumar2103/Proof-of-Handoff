import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ThemeToggle } from '../components/ThemeToggle';

const MONTHLY = 199;

interface Plan {
  key: string;
  name: string;
  months: number;
  price: number;
  tagline: string;
  highlight?: boolean;
}

// To change prices, edit the numbers below (this is the only place prices live).
const PLANS: Plan[] = [
  { key: 'monthly', name: 'Monthly', months: 1, price: 199, tagline: 'Billed every month' },
  { key: 'quarterly', name: 'Quarterly', months: 3, price: 549, tagline: 'Billed every 3 months' },
  { key: 'half', name: 'Half-yearly', months: 6, price: 999, tagline: 'Billed every 6 months' },
  { key: 'yearly', name: 'Yearly', months: 12, price: 1999, tagline: 'Billed every year', highlight: true },
];

const FEATURES = [
  { icon: '🤝', title: 'Proof of Handoff', text: 'Record what was handed over, who received it, when, plus their typed acknowledgement and evidence.' },
  { icon: '↩️', title: 'Return Tracking', text: 'Partial and multiple returns on the same record. Remaining is computed automatically — never by hand.' },
  { icon: '🔎', title: 'Missing-item confirmation', text: 'Items reported missing must be confirmed by the recipient before a handoff can be closed.' },
  { icon: '📄', title: 'HandoffCheck', text: 'Upload two files — a quotation, PO or return sheet — and highlight every difference.' },
  { icon: '🧾', title: 'Full audit trail', text: 'Every event — created, sent, opened, accepted, returned, closed — is recorded and preserved.' },
  { icon: '🌍', title: 'Any domain', text: 'Rentals, events, construction tools, IT assets, documents, keys — one generic handoff engine.' },
];

const STEPS = [
  { n: 1, title: 'Create & send', text: 'Create a handoff, add items, and email the recipient a secure review link.' },
  { n: 2, title: 'Acknowledge', text: 'The recipient reviews the items and accepts with a typed acknowledgement.' },
  { n: 3, title: 'Track returns', text: 'Record returns as items come back — partial, multiple, with condition and notes.' },
  { n: 4, title: 'Confirm & close', text: 'Confirm any missing items, then close the handoff once everything is accounted for.' },
];

const LIFECYCLE = ['Give', 'Acknowledge', 'Active with recipient', 'Return pending', 'Partial return', 'Full return', 'Closed'];

function money(n: number) {
  return `₹${n.toLocaleString('en-IN')}`;
}

export function LandingPage() {
  const { user } = useAuth();

  return (
    <div className="landing">
      <header className="landing-header">
        <div className="landing-header-inner">
          <Link to="/" className="brand">Hand<span>Offly</span></Link>
          <nav className="landing-nav">
            <a href="#features">Features</a>
            <a href="#how">How it works</a>
            <a href="#pricing">Pricing</a>
          </nav>
          <div className="row" style={{ gap: '0.5rem' }}>
            <ThemeToggle />
            {user ? (
              <Link to="/dashboard" className="btn btn-primary btn-sm">Go to dashboard</Link>
            ) : (
              <>
                <Link to="/login" className="btn btn-sm">Sign in</Link>
                <Link to="/register" className="btn btn-primary btn-sm">Sign up</Link>
              </>
            )}
          </div>
        </div>
      </header>

      {/* Hero */}
      <section className="hero">
        <div className="hero-inner">
          <div className="hero-copy">
            <span className="badge badge-primary">Proof-of-Handoff & Return Tracking</span>
            <h1>Proof of every handoff.<br />Tracked until it's returned.</h1>
            <p className="muted">
              Record what you hand over, who received it, and their acknowledgement — then track
              returns, partials and missing items until everything is accounted for. For rentals,
              events, tools, IT assets, documents and keys.
            </p>
            <div className="row" style={{ marginTop: '1.25rem' }}>
              {user ? (
                <Link to="/dashboard" className="btn btn-primary">Go to dashboard</Link>
              ) : (
                <>
                  <Link to="/register" className="btn btn-primary">Get started free</Link>
                  <Link to="/login" className="btn">Sign in</Link>
                </>
              )}
            </div>
          </div>
          <div className="hero-visual">
            <div className="card hero-card">
              <div className="spread">
                <strong>HO-1042 · Wedding Rental</strong>
                <span className="badge badge-warning badge-dot">Partially returned</span>
              </div>
              <div className="table-wrap mt-2">
                <table>
                  <thead><tr><th>Item</th><th className="num">Out</th><th className="num">Back</th><th className="num">Missing</th></tr></thead>
                  <tbody>
                    <tr><td>Chairs</td><td className="num">500</td><td className="num">500</td><td className="num muted">0</td></tr>
                    <tr><td>Tables</td><td className="num">80</td><td className="num">78</td><td className="num remaining-open">2</td></tr>
                    <tr><td>Bedsheets</td><td className="num">200</td><td className="num">200</td><td className="num muted">0</td></tr>
                  </tbody>
                </table>
              </div>
              <p className="small muted mt-2" style={{ margin: 0 }}>Remaining computed automatically · 2 missing awaiting confirmation</p>
            </div>
          </div>
        </div>
        <div className="lifecycle-strip">
          {LIFECYCLE.map((s, i) => (
            <span key={s} className="lifecycle-chip">
              {s}{i < LIFECYCLE.length - 1 && <span className="lifecycle-arrow">→</span>}
            </span>
          ))}
        </div>
      </section>

      {/* Features */}
      <section id="features" className="landing-section">
        <div className="section-head">
          <h2>Everything a handoff needs</h2>
          <p className="muted">One record from give to full return — not a one-time document.</p>
        </div>
        <div className="feature-grid">
          {FEATURES.map((f) => (
            <div key={f.title} className="card feature-card">
              <div className="feature-icon">{f.icon}</div>
              <h3>{f.title}</h3>
              <p className="muted small">{f.text}</p>
            </div>
          ))}
        </div>
      </section>

      {/* How it works */}
      <section id="how" className="landing-section alt">
        <div className="section-head">
          <h2>How it works</h2>
          <p className="muted">Four simple steps, fully tracked and audited.</p>
        </div>
        <div className="steps">
          {STEPS.map((s) => (
            <div key={s.n} className="step">
              <div className="step-num">{s.n}</div>
              <h3>{s.title}</h3>
              <p className="muted small">{s.text}</p>
            </div>
          ))}
        </div>
      </section>

      {/* Pricing */}
      <section id="pricing" className="landing-section">
        <div className="section-head">
          <h2>Simple pricing</h2>
          <p className="muted">Pay less when you commit longer. Compared to {money(MONTHLY)}/month.</p>
        </div>
        <div className="pricing-grid">
          {PLANS.map((p) => {
            const regular = MONTHLY * p.months;
            const save = regular - p.price;
            const pct = Math.round((save / regular) * 100);
            const perMonth = Math.round(p.price / p.months);
            return (
              <div key={p.key} className={`card price-card ${p.highlight ? 'featured' : ''}`}>
                {p.highlight && <div className="price-ribbon">Best value</div>}
                <h3>{p.name}</h3>
                <div className="price">
                  {money(p.price)}
                  <span className="price-period"> /{p.months === 1 ? 'mo' : p.months === 12 ? 'yr' : `${p.months} mo`}</span>
                </div>
                <div className="price-permonth muted small">≈ {money(perMonth)}/month · {p.tagline}</div>
                {save > 0 ? (
                  <div className="price-save">
                    <span className="price-regular">{money(regular)}</span>
                    <span className="badge badge-success">Save {money(save)} ({pct}%)</span>
                  </div>
                ) : (
                  <div className="price-save"><span className="muted small">Pay monthly · cancel anytime</span></div>
                )}
                <ul className="price-features">
                  <li>Unlimited handoffs</li>
                  <li>Returns & missing tracking</li>
                  <li>Attachments & evidence</li>
                  <li>HandoffCheck comparison</li>
                  {p.months >= 6 && <li>Priority support</li>}
                </ul>
                <Link to="/register" className={`btn btn-block ${p.highlight ? 'btn-primary' : ''}`}>
                  Choose {p.name}
                </Link>
              </div>
            );
          })}
        </div>
      </section>

      {/* CTA */}
      <section className="landing-cta">
        <h2>Start tracking your handoffs today</h2>
        <p className="muted">Create your first handoff in under a minute.</p>
        <div className="row" style={{ justifyContent: 'center', marginTop: '1rem' }}>
          <Link to={user ? '/dashboard' : '/register'} className="btn btn-primary">
            {user ? 'Go to dashboard' : 'Get started free'}
          </Link>
        </div>
      </section>

      {/* Footer */}
      <footer className="landing-footer">
        <div className="footer-inner">
          <div className="footer-brand">
            <div className="brand">Hand<span>Offly</span></div>
            <p className="muted small">Universal proof-of-handoff and return tracking.</p>
          </div>
          <div className="footer-cols">
            <div>
              <h4>Product</h4>
              <a href="#features">Features</a>
              <a href="#how">How it works</a>
              <a href="#pricing">Pricing</a>
            </div>
            <div>
              <h4>Account</h4>
              <Link to="/login">Sign in</Link>
              <Link to="/register">Sign up</Link>
            </div>
            <div>
              <h4>Legal</h4>
              <a href="#">Terms</a>
              <a href="#">Privacy</a>
            </div>
          </div>
        </div>
        <div className="footer-bottom muted small">
          © {new Date().getFullYear()} HandOffly · Universal proof-of-handoff & return tracking.
        </div>
      </footer>
    </div>
  );
}
