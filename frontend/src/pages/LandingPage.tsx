import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { publicApi } from '../api/endpoints';
import type { SubscriptionPlan } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ThemeToggle } from '../components/ThemeToggle';
import { Logo } from '../components/Logo';
import { formatMoney, supportHighlights } from '../lib/format';

interface Plan {
  plan: SubscriptionPlan;
  name: string;
  tagline: string;
  /** Whether the plan includes HandoffCheck. Display only: mirrors SubscriptionPlan.includesHandoffCheck(), which the backend enforces. */
  handoffCheck: boolean;
  highlight?: boolean;
}

// How each plan is presented. Prices and what a plan includes from support are deliberately NOT here: they
// come from the backend (`SubscriptionPlan`, the one place both live), so this page always matches what
// support sees and what the backend enforces. The one exception is `handoffCheck`, which the public plan list
// does not carry yet: it is shown from here, and if a plan's HandoffCheck inclusion ever changes in
// `SubscriptionPlan` this has to change with it.
const PLANS: Plan[] = [
  { plan: 'MONTHLY', name: 'Monthly', tagline: 'Billed every month', handoffCheck: false },
  { plan: 'QUARTERLY', name: 'Quarterly', tagline: 'Billed every 3 months', handoffCheck: false },
  { plan: 'HALF_YEARLY', name: 'Half-yearly', tagline: 'Billed every 6 months', handoffCheck: true },
  { plan: 'YEARLY', name: 'Yearly', tagline: 'Billed every year', handoffCheck: true, highlight: true },
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

// Questions people ask before they sign up. Answers describe what the product does today; plans and prices are not repeated here
// (they come from the backend, above), and the acknowledgement is never described as a legal signature.
const FAQS = [
  { q: 'What is HandOffly?', a: 'HandOffly keeps one record of everything you hand to someone: what was given, who received it, their acknowledgement, and every return until the handoff is closed. It works for rentals, events, tools, IT assets, documents, keys and anything else you give out and expect back.' },
  { q: 'Is it an inventory system?', a: 'No. HandOffly does not manage stock or warehouses. It tracks each handoff from the moment you give something to the moment it is fully returned.' },
  { q: 'Does the person receiving the items need an account?', a: 'No. You email them a secure link that opens only that one handoff. They review the items and accept or decline there, with nothing to install or sign up for. The link expires.' },
  { q: 'Is the recipient’s acknowledgement a legal signature?', a: 'It is a typed acknowledgement: the recipient types their name to confirm they reviewed and accepted the items, and HandOffly records it with the time. It is a record of that confirmation, not a legally binding e-signature. If you need a signature with legal force, check what your own situation requires.' },
  { q: 'What if only part of the items come back?', a: 'Record each return as it happens, as many times as you need, with the condition and a note. Returns are kept as separate entries on the same handoff, the original quantities are never changed, and HandOffly works out what is still outstanding.' },
  { q: 'What happens to items that are missing?', a: 'Items reported missing must be confirmed by the recipient before the handoff can be closed, so nothing is closed with an open question.' },
  { q: 'Can the history be changed or deleted?', a: 'No. Every event (created, sent, opened, accepted, returned, closed) is added to an audit trail and never edited. Once a handoff is closed it is read-only. A draft that was never sent can be deleted.' },
  { q: 'What is HandoffCheck?', a: 'HandoffCheck compares two files, such as a quotation and a purchase order, or what you gave and what came back, and highlights every difference. It is included in the Half-yearly and Yearly plans.' },
  { q: 'What does the AI Handoff Creator do?', a: 'You describe a handoff in your own words, typed or spoken, for example “Create a handoff for Rahul with 2 pens and 3 markers”. It prepares a draft for you to review and edit. It only ever prepares a draft: nothing is sent to anyone until you submit it yourself. It is available on every plan.' },
  { q: 'What happens when my plan ends?', a: 'Your records stay. You can still open your handoffs, record returns, close handoffs and download PDFs. To create new handoffs or send drafts, you need an active plan.' },
  { q: 'How is my account protected?', a: 'Passwords are stored hashed, recipient links are single-handoff and expire, and you are signed out automatically after 15 minutes of inactivity so an unattended screen does not stay open.' },
  { q: 'How do I get help?', a: 'Every account can reach us at the contact address in the footer. The support options that come with each plan, such as messages, tickets and calls, are listed in the pricing plans above.' },
];

const LIFECYCLE = ['Give', 'Acknowledge', 'Active with recipient', 'Partial return', 'Full return', 'Closed'];

/** Two columns of questions side by side (stacked on a phone), so the section stays short. */
const FAQ_SPLIT = Math.ceil(FAQS.length / 2);
const FAQ_COLUMNS = [FAQS.slice(0, FAQ_SPLIT), FAQS.slice(FAQ_SPLIT)];

export function LandingPage() {
  const { user } = useAuth();
  // One question is open at a time: opening another closes the one that was open.
  const [openFaq, setOpenFaq] = useState<string | null>(null);
  // General contact address for everyone, whatever their plan; hidden if it cannot be loaded.
  const contact = useQuery({ queryKey: ['public-contact'], queryFn: publicApi.contact, retry: false });
  const prices = useQuery({ queryKey: ['public-plans'], queryFn: publicApi.plans, retry: false });
  const priceOf = (plan: SubscriptionPlan) => prices.data?.find((p) => p.plan === plan);
  const monthly = priceOf('MONTHLY');

  return (
    <div className="landing">
      <header className="landing-header">
        <div className="landing-header-inner">
          <Link to="/" className="brand"><Logo /></Link>
          <nav className="landing-nav">
            <a href="#features">Features</a>
            <a href="#how">How it works</a>
            <a href="#pricing">Pricing</a>
            <a href="#faq">FAQs</a>
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
          <p className="muted">
            Pay less when you commit longer.
            {monthly && <> Compared to {formatMoney(monthly.amount, monthly.currency)}/month.</>}
          </p>
        </div>
        {prices.isLoading ? <p className="muted center">Loading prices…</p> : !monthly ? (
          <p className="muted center">Prices are unavailable right now. Please try again shortly.</p>
        ) : (
          <div className="pricing-grid">
            {PLANS.map((p) => {
              const price = priceOf(p.plan);
              if (!price) return null;
              const money = (n: number) => formatMoney(n, price.currency);
              const regular = monthly.amount * price.months;
              const save = regular - price.amount;
              const pct = Math.round((save / regular) * 100);
              const perMonth = Math.round(price.amount / price.months);
              return (
                <div key={p.plan} className={`card price-card ${p.highlight ? 'featured' : ''}`}>
                  {p.highlight && <div className="price-ribbon">Best value</div>}
                  <h3>{p.name}</h3>
                  <div className="price">
                    {money(price.amount)}
                    <span className="price-period"> /{price.months === 1 ? 'mo' : price.months === 12 ? 'yr' : `${price.months} mo`}</span>
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
                    <li>AI Handoff Creator</li>
                    {p.handoffCheck
                      ? <li>HandoffCheck comparison</li>
                      : <li className="off">HandoffCheck comparison — not included</li>}
                    {/* the AI Report Assistant goes with the plans that include HandoffCheck (see AI_REPORT_PLAN_MESSAGE), so it follows the same flag */}
                    {p.handoffCheck
                      ? <li>AI Report Assistant</li>
                      : <li className="off">AI Report Assistant — not included</li>}
                    {supportHighlights(price.support).map((line) => <li key={line}>{line}</li>)}
                  </ul>
                  <Link to={`/checkout?plan=${p.plan}`} className={`btn btn-block ${p.highlight ? 'btn-primary' : ''}`}>
                    Choose {p.name}
                  </Link>
                </div>
              );
            })}
          </div>
        )}
      </section>

      {/* FAQs */}
      <section id="faq" className="landing-section alt">
        <div className="section-head">
          <h2>Frequently asked questions</h2>
          <p className="muted">Straight answers before you start.</p>
        </div>
        <div className="faq-columns">
          {FAQ_COLUMNS.map((column, c) => (
            <div key={c} className="faq-list">
              {column.map((f) => (
                <details key={f.q} className="faq-item" open={openFaq === f.q}>
                  <summary onClick={(e) => { e.preventDefault(); setOpenFaq(openFaq === f.q ? null : f.q); }}>{f.q}</summary>
                  <p className="muted">{f.a}</p>
                </details>
              ))}
            </div>
          ))}
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
            <div className="brand"><Logo /></div>
            <p className="muted small">Universal proof-of-handoff and return tracking.</p>
          </div>
          <div className="footer-cols">
            <div>
              <h4>Product</h4>
              <a href="#features">Features</a>
              <a href="#how">How it works</a>
              <a href="#pricing">Pricing</a>
              <a href="#faq">FAQs</a>
            </div>
            <div>
              <h4>Account</h4>
              <Link to="/login">Sign in</Link>
              <Link to="/register">Sign up</Link>
            </div>
            {contact.data?.email && (
              <div>
                <h4>Contact</h4>
                <a href={`mailto:${contact.data.email}`}>{contact.data.email}</a>
              </div>
            )}
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
