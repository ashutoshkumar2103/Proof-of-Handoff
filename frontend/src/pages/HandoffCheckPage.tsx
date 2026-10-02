import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { documentCheckApi, handoffApi } from '../api/endpoints';
import type {
  CompareInput, CompareResult, DocFieldInput, DocLineInput, ImportMatchState, MatchStatus, ReturnPrefill, SubscriptionPlan,
} from '../api/types';
import { ErrorNotice, Gated, Spinner, errorMessage, useTransient } from '../components/ui';
import { UpgradePlans } from '../components/UpgradePlans';
import { owed } from '../components/ReturnForm';
import { isPlanRequired, saveBlob } from '../api/client';
import { useSubscriptionGate } from '../auth/useSubscriptionGate';
import { HANDOFFCHECK_PLAN_MESSAGE, PLAN_LABELS, qty } from '../lib/format';

/**
 * Two separate journeys share this page. `?returnFor=<handoffId>` is return-import mode for
 * that handoff (feeds the return form); with no param it is the standalone File A vs File B check.
 * Both need an active subscription: the gate checks it while the page is open and shows the dialog. The standalone comparison
 * is also a feature of some plans only (Half-Yearly and Yearly): other plans see it locked, with the message and the plans that
 * unlock it, which they can pay the difference for right there. Return-import mode is part of Returns, not that tool, and stays
 * on every plan.
 */
export function HandoffCheckPage() {
  const gate = useSubscriptionGate();
  const [params] = useSearchParams();
  const [upgraded, setUpgraded] = useTransient<string>();
  const returnFor = Number(params.get('returnFor'));
  if (gate.state === 'checking') return <Spinner />;
  if (!gate.shown) return null;   // never shown as usable without a subscription: all the customer sees is the dialog
  // The plan is read from the backend's latest answer, so a plan changed by support shows on the next check, with no sign-in.
  if (returnFor <= 0 && gate.state === 'active' && gate.account?.handoffCheck === false) {
    // An active subscription always has a plan. After an upgrade the account is asked again at once, so the page unlocks by itself.
    return <HandoffCheckLocked plan={gate.account.plan!} onUpgraded={(plan) => {
      setUpgraded(`Your ${PLAN_LABELS[plan]} plan is now active — HandoffCheck is unlocked.`);
      gate.refresh();
    }} />;
  }
  // A request the backend refused: a subscription that ended is the gate's dialog; a plan without HandoffCheck (it changed
  // since the last check) means ask again — the locked view follows. Anything else is left to the caller as an ordinary error.
  const handleRefusal: HandleRefusal = (error) => {
    if (gate.handle(error)) return true;
    if (!isPlanRequired(error)) return false;
    gate.recheck();
    return true;
  };
  return (
    <Gated blocked={gate.blocked}>
      {upgraded && <div className="notice notice-success mb-2" role="status">{upgraded}</div>}
      {returnFor > 0
        ? <ReturnImport handoffId={returnFor} handleRefusal={handleRefusal} />
        : <FileCompare handleRefusal={handleRefusal} />}
    </Gated>
  );
}

/** Takes a request's error and returns true if it was a refusal the page deals with itself (so there is no error to print). */
type HandleRefusal = (error: unknown) => boolean;

/**
 * HandoffCheck as a feature the customer's plan does not include: still there to see, not usable, with the plans that unlock it —
 * shown here, not on another page — and the way to pay the difference for one.
 */
function HandoffCheckLocked({ plan, onUpgraded }: { plan: SubscriptionPlan; onUpgraded: (plan: SubscriptionPlan) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="stack">
      <div>
        <h1>HandoffCheck <span aria-hidden="true">🔒</span></h1>
        <p className="muted">Compare two files and find differences.</p>
      </div>
      <div className="card">
        <p style={{ margin: 0 }}><strong>{HANDOFFCHECK_PLAN_MESSAGE}</strong></p>
        <p className="muted small">
          You are on the {PLAN_LABELS[plan]} plan. Move to a plan that includes it and pay only the difference.
        </p>
        <button type="button" className="btn btn-primary btn-sm" aria-expanded={open} onClick={() => setOpen(!open)}>
          {open ? 'Hide the plans' : 'See the plans'}
        </button>
      </div>
      {open && <UpgradePlans onUpgraded={onUpgraded} />}
    </div>
  );
}

interface ImportRow { name: string; qty: string; itemId: number | ''; match: ImportMatchState }
const round3 = (n: number) => Math.round(n * 1000) / 1000;

/**
 * Return-import mode: upload a file, match its lines to THIS handoff's items, let the user
 * correct matches/quantities, then go back to the same return form with Return qty prefilled.
 * Nothing is recorded here — the existing return form and API do that.
 */
function ReturnImport({ handoffId, handleRefusal }: { handoffId: number; handleRefusal: HandleRefusal }) {
  const navigate = useNavigate();
  const handoff = useQuery({ queryKey: ['handoff', handoffId], queryFn: () => handoffApi.get(handoffId) });
  const [rows, setRows] = useState<ImportRow[] | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const extract = useMutation({
    mutationFn: (file: File) => documentCheckApi.returnImport(handoffId, file),
    onSuccess: (res) => {
      setError(null);
      setFileName(res.fileName ?? null);
      setRows(res.rows.map((r) => ({
        name: r.importedName, qty: String(r.importedQuantity), itemId: r.itemId ?? '', match: r.match,
      })));
    },
    onError: (e) => { if (!handleRefusal(e)) setError(errorMessage(e)); setRows(null); },
  });

  if (handoff.isLoading) return <Spinner />;
  if (handoff.isError) return <ErrorNotice error={handoff.error} />;
  const h = handoff.data!;
  const back = `/handoffs/${handoffId}`;
  const canReturn = h.availableActions.includes('RECORD_RETURN');
  const patch = (i: number, p: Partial<ImportRow>) =>
    setRows((rs) => rs && rs.map((r, idx) => (idx === i ? { ...r, ...p } : r)));

  // Quantity per handoff item (a repeated item in the file adds up) vs what is still owed.
  const totals = new Map<number, number>();
  (rows ?? []).forEach((r) => {
    if (r.itemId !== '' && Number(r.qty) > 0) totals.set(r.itemId, round3((totals.get(r.itemId) ?? 0) + Number(r.qty)));
  });
  const itemOwed = (id: number) => owed(h.items.find((i) => i.id === id)!);
  const state = (r: ImportRow): 'ignored' | 'invalid' | 'exceeds' | 'ok' => {
    if (r.itemId === '') return 'ignored';
    if (!(Number(r.qty) >= 0) || r.qty.trim() === '') return 'invalid';
    return (totals.get(r.itemId) ?? 0) > itemOwed(r.itemId) ? 'exceeds' : 'ok';
  };
  const states = (rows ?? []).map(state);
  const blocked = states.includes('invalid') || states.includes('exceeds');
  const usable = totals.size > 0;
  const notInFile = h.items.filter((i) => owed(i) > 0 && !totals.has(i.id));

  function carryToReturnForm() {
    const returnPrefill: ReturnPrefill = {};
    totals.forEach((q, id) => { returnPrefill[id] = String(q); });
    navigate(back, { state: { returnPrefill } });
  }

  return (
    <div className="stack">
      <div>
        <Link to={back} className="small muted">← Back to {h.publicCode}</Link>
        <h1 style={{ marginTop: 4 }}>Import returned quantities</h1>
        <p className="muted">
          {h.publicCode} · {h.title}. Upload a CSV, Excel (.xlsx) or text PDF listing what came back. Quantities are
          matched to this handoff's items and prefilled into the return form — nothing is recorded until you submit it.
        </p>
      </div>

      {!canReturn && <div className="notice notice-error">Returns can't be recorded on this handoff right now.</div>}
      {error && <div className="notice notice-error">{error}</div>}

      <div className="card">
        <UploadZone label="Returned items file" fileName={fileName}
                    disabled={!canReturn || extract.isPending} onFile={(file) => extract.mutate(file)} />
        <p className="muted small" style={{ margin: 0 }}>
          {extract.isPending ? 'Reading file…' : 'Supported: Excel (.xlsx), CSV, text-based PDF.'}
        </p>
      </div>

      {rows && (
        <div className="card">
          <div className="card-header">
            <h2>Review imported quantities</h2>
            {fileName && <span className="muted small">{fileName}</span>}
          </div>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>In file</th><th className="num">Imported qty</th><th>Handoff item</th><th className="num">Remaining</th><th>Result</th></tr>
              </thead>
              <tbody>
                {rows.map((r, i) => {
                  const s = states[i];
                  const item = r.itemId === '' ? undefined : h.items.find((x) => x.id === r.itemId);
                  return (
                    <tr key={i}>
                      <td>{r.name}</td>
                      <td className="num">
                        <input type="number" min="0" step="0.001" value={r.qty} style={{ width: 88 }}
                               onChange={(e) => patch(i, { qty: e.target.value })} />
                      </td>
                      <td>
                        <select value={r.itemId}
                                onChange={(e) => patch(i, { itemId: e.target.value ? Number(e.target.value) : '' })}>
                          <option value="">— Not in this handoff (ignore) —</option>
                          {h.items.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                        </select>
                      </td>
                      <td className="num">{item ? qty(String(owed(item))) : '—'}</td>
                      <td>
                        {s === 'ok' && <span className="badge badge-success">Will prefill</span>}
                        {s === 'exceeds' && <span className="badge badge-danger">Imported quantity exceeds remaining quantity.</span>}
                        {s === 'invalid' && <span className="badge badge-danger">Enter a valid quantity.</span>}
                        {s === 'ignored' && (
                          <span className="badge badge-warning">
                            {r.match === 'AMBIGUOUS' ? 'Ambiguous — choose the item' : 'Not in this handoff — ignored'}
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {states.includes('ignored') && (
            <p className="muted small mt-2">
              Rows not matched to an item are ignored — no new handoff item is ever created.
            </p>
          )}
          {notInFile.length > 0 && (
            <p className="muted small">
              Not in the file (Return qty won't be prefilled): {notInFile.map((i) => i.name).join(', ')}.
            </p>
          )}
          <div className="row mt-2">
            <button className="btn btn-primary" disabled={blocked || !usable} onClick={carryToReturnForm}>
              Continue to return form
            </button>
            <Link className="btn btn-ghost" to={back}>Cancel</Link>
          </div>
          {blocked && <p className="small mt-2" style={{ color: 'var(--danger)' }}>Fix the highlighted rows to continue.</p>}
          {!blocked && !usable && <p className="muted small mt-2">Match at least one row with a quantity above 0 to continue.</p>}
        </div>
      )}
    </div>
  );
}

/** One side of the standalone comparison. `lines` is null until extraction succeeds (or manual entry is chosen). */
interface Side { file: File | null; lines: DocLineInput[] | null; fields: DocFieldInput[]; error: string | null }
const emptySide: Side = { file: null, lines: null, fields: [], error: null };
const blankLine = (): DocLineInput => ({ name: '', quantity: '' });
const SIDE_LABELS = ['File A', 'File B'] as const;
type Step = 'upload' | 'review' | 'result';

/**
 * Standalone HandoffCheck: File A vs File B. Upload both, review/correct what was read,
 * compare. Not connected to any handoff or return, and nothing is stored.
 */
function FileCompare({ handleRefusal }: { handleRefusal: HandleRefusal }) {
  const [step, setStep] = useState<Step>('upload');
  const [sides, setSides] = useState<[Side, Side]>([emptySide, emptySide]);
  const [error, setError] = useState<string | null>(null);
  const [exporting, setExporting] = useState<'PDF' | 'CSV' | null>(null);

  const patchSide = (i: 0 | 1, p: Partial<Side>) =>
    setSides((s) => (i === 0 ? [{ ...s[0], ...p }, s[1]] : [s[0], { ...s[1], ...p }]));

  // Each file is read independently so one unreadable file still lets the other be reviewed.
  const extract = useMutation({
    mutationFn: () => Promise.all(sides.map(async (s): Promise<Partial<Side>> => {
      try {
        const lines = await documentCheckApi.extract(s.file!);
        return { lines: lines.map((l) => ({ name: l.name, quantity: String(l.quantity ?? '') })), fields: [], error: null };
      } catch (e) {
        handleRefusal(e);   // if it is the subscription refusal the dialog takes over; the per-file message is only the fallback
        return { lines: null, fields: [], error: errorMessage(e) };
      }
    })),
    onSuccess: ([a, b]) => {
      setSides((s) => [{ ...s[0], ...a }, { ...s[1], ...b }]);
      setError(null);
      setStep('review');
    },
  });

  const compare = useMutation({
    mutationFn: (body: CompareInput) => documentCheckApi.compare(body),
    onSuccess: () => { setError(null); setStep('result'); },
    onError: (e) => { if (!handleRefusal(e)) setError(errorMessage(e)); },
  });

  function runCompare() {
    const rows = (s: Side) => (s.lines ?? []).filter((l) => l.name.trim() !== '');
    const fields = (s: Side) => s.fields.filter((f) => f.label.trim() !== '');
    const [a, b] = sides;
    if (rows(a).length === 0) { setError('File A needs at least one item row.'); return; }
    if (rows(b).length === 0) { setError('File B needs at least one item row.'); return; }
    compare.mutate({
      referenceLabel: SIDE_LABELS[0], referenceLines: rows(a), referenceFields: fields(a),
      targetLabel: SIDE_LABELS[1], targetLines: rows(b), targetFields: fields(b),
    });
  }

  /** The comparison on screen, as a file: the very request it was made from is sent again, so they cannot differ. */
  async function download(format: 'PDF' | 'CSV') {
    if (!compare.variables) return;
    setError(null);
    setExporting(format);
    try {
      const { blob, filename } = await documentCheckApi.exportComparison(
        compare.variables, sides[0].file?.name, sides[1].file?.name, format);
      saveBlob(blob, filename ?? `handoffcheck-comparison.${format.toLowerCase()}`);
    } catch (e) {
      if (!handleRefusal(e)) setError(errorMessage(e));
    } finally {
      setExporting(null);
    }
  }

  function reset() {
    setSides([emptySide, emptySide]);
    setError(null);
    compare.reset();
    extract.reset();
    setStep('upload');
  }

  return (
    <div className="stack">
      <div>
        <h1>HandoffCheck</h1>
        <p className="muted">Compare two files and find differences.</p>
      </div>

      {error && <div className="notice notice-error">{error}</div>}

      {step === 'upload' && (
        <>
          <div className="field-row" style={{ alignItems: 'start' }}>
            {sides.map((_, i) => (
              <div className="card" key={i}>
                <h2>{SIDE_LABELS[i]}</h2>
                <UploadZone label={`${SIDE_LABELS[i]} document`} fileName={sides[i].file?.name}
                            onFile={(file) => patchSide(i as 0 | 1, { file })} />
                <p className="muted small" style={{ margin: 0 }}>
                  Supported: Excel (.xlsx), CSV, text-based PDF. Scanned or image-only PDFs can't be read.
                </p>
              </div>
            ))}
          </div>
          <div className="row">
            <button className="btn btn-primary" disabled={!sides[0].file || !sides[1].file || extract.isPending}
                    onClick={() => extract.mutate()}>
              {extract.isPending ? 'Reading files…' : 'Compare Files'}
            </button>
          </div>
        </>
      )}

      {step === 'review' && (
        <>
          <p className="muted" style={{ margin: 0 }}>
            Check what was read from each file. Fix any item or quantity, add or remove rows, then compare.
          </p>
          <div className="field-row" style={{ alignItems: 'start' }}>
            {sides.map((s, i) => (
              <ReviewSide key={i} title={`Review ${SIDE_LABELS[i]}`} side={s}
                          onChange={(p) => patchSide(i as 0 | 1, p)} />
            ))}
          </div>
          <div className="row">
            <button className="btn btn-primary" onClick={runCompare}
                    disabled={compare.isPending || sides.some((s) => s.lines === null)}>
              {compare.isPending ? 'Comparing…' : 'Compare'}
            </button>
            <button className="btn btn-ghost" onClick={reset}>Choose different files</button>
          </div>
        </>
      )}

      {step === 'result' && compare.data && (
        <>
          <ResultView result={compare.data} fileNames={[sides[0].file?.name, sides[1].file?.name]} />
          <div className="row">
            <button className="btn btn-primary" disabled={exporting !== null} onClick={() => download('PDF')}>
              {exporting === 'PDF' ? 'Preparing PDF…' : 'Download comparison PDF'}
            </button>
            <button className="btn" disabled={exporting !== null} onClick={() => download('CSV')}>
              {exporting === 'CSV' ? 'Preparing CSV…' : 'Download comparison CSV'}
            </button>
          </div>
          <div className="row">
            <button className="btn" onClick={() => setStep('review')}>Edit data</button>
            <button className="btn btn-ghost" onClick={reset}>Compare other files</button>
          </div>
        </>
      )}
    </div>
  );
}

/** Click-or-drop file picker for the formats the backend can read. */
function UploadZone({ label, fileName, disabled, onFile }: {
  label: string; fileName?: string | null; disabled?: boolean; onFile: (file: File) => void;
}) {
  const [over, setOver] = useState(false);
  return (
    <label className={`upload-zone${fileName ? ' has-file' : ''}${over ? ' is-over' : ''}${disabled ? ' is-disabled' : ''}`}
           onDragOver={(e) => { e.preventDefault(); if (!disabled) setOver(true); }}
           onDragLeave={() => setOver(false)}
           onDrop={(e) => {
             e.preventDefault();
             setOver(false);
             const file = e.dataTransfer.files?.[0];
             if (file && !disabled) onFile(file);
           }}>
      <input type="file" accept=".csv,.xlsx,.pdf" aria-label={label} disabled={disabled}
             onChange={(e) => {
               const file = e.target.files?.[0];
               if (file) onFile(file);
               e.target.value = '';
             }} />
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round"
           strokeLinejoin="round" aria-hidden="true">
        <path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5" /><path d="M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
      </svg>
      <strong>{fileName ?? 'Upload document'}</strong>
      <span className="small">{fileName ? 'Click or drop to replace' : 'Click to choose a file, or drop it here'}</span>
    </label>
  );
}

/** Editable view of what was read from one file; manual entry is offered only when reading failed. */
function ReviewSide({ title, side, onChange }: { title: string; side: Side; onChange: (p: Partial<Side>) => void }) {
  return (
    <div className="card">
      <h2>{title}</h2>
      <p className="muted small">{side.file?.name}</p>
      {side.lines === null ? (
        <>
          <div className="notice notice-error">Couldn't read this file: {side.error}</div>
          <p className="muted small mt-2">Choose a different file, or enter the data manually.</p>
          <button type="button" className="btn" onClick={() => onChange({ lines: [blankLine()] })}>
            Enter data manually
          </button>
        </>
      ) : (
        <>
          <LinesEditor title="Items" lines={side.lines} onChange={(lines) => onChange({ lines })} />
          <FieldsEditor title="Fields" fields={side.fields} onChange={(fields) => onChange({ fields })} />
        </>
      )}
    </div>
  );
}

function LinesEditor({ title, lines, onChange }: { title: string; lines: DocLineInput[]; onChange: (l: DocLineInput[]) => void }) {
  const set = (i: number, p: Partial<DocLineInput>) => onChange(lines.map((l, idx) => idx === i ? { ...l, ...p } : l));
  return (
    <div className="mt-2">
      <div className="card-header"><h3>{title}</h3>
        <button type="button" className="btn btn-sm" onClick={() => onChange([...lines, blankLine()])}>+ Row</button></div>
      {lines.map((l, i) => (
        <div key={i} className="row" style={{ marginBottom: 6 }}>
          <input placeholder="Item name" value={l.name} onChange={(e) => set(i, { name: e.target.value })} className="grow" />
          <input placeholder="Qty" type="number" step="0.001" value={l.quantity ?? ''} style={{ width: 90 }}
                 onChange={(e) => set(i, { quantity: e.target.value })} />
          {lines.length > 1 && <button type="button" className="btn btn-sm btn-ghost" onClick={() => onChange(lines.filter((_, idx) => idx !== i))}>×</button>}
        </div>
      ))}
    </div>
  );
}

function FieldsEditor({ title, fields, onChange }: { title: string; fields: DocFieldInput[]; onChange: (f: DocFieldInput[]) => void }) {
  const set = (i: number, p: Partial<DocFieldInput>) => onChange(fields.map((f, idx) => idx === i ? { ...f, ...p } : f));
  return (
    <div className="mt-2">
      <div className="card-header"><h3>{title} <span className="muted small">(optional)</span></h3>
        <button type="button" className="btn btn-sm" onClick={() => onChange([...fields, { label: '', value: '' }])}>+ Field</button></div>
      {fields.map((f, i) => (
        <div key={i} className="row" style={{ marginBottom: 6 }}>
          <input placeholder="Label (e.g. Delivery date)" value={f.label} onChange={(e) => set(i, { label: e.target.value })} className="grow" />
          <input placeholder="Value" value={f.value ?? ''} onChange={(e) => set(i, { value: e.target.value })} className="grow" />
          <button type="button" className="btn btn-sm btn-ghost" onClick={() => onChange(fields.filter((_, idx) => idx !== i))}>×</button>
        </div>
      ))}
    </div>
  );
}

function StatusChip({ status }: { status: MatchStatus }) {
  const map: Record<MatchStatus, { c: string; t: string }> = {
    MATCH: { c: 'badge-success', t: 'Match' },
    MISMATCH: { c: 'badge-danger', t: 'Mismatch' },
    MISSING_IN_TARGET: { c: 'badge-warning', t: 'Missing' },
    EXTRA_IN_TARGET: { c: 'badge-warning', t: 'Extra' },
  };
  return <span className={`badge ${map[status].c}`}>{map[status].t}</span>;
}

/** Target minus reference, signed, so a shortfall reads as -5. */
const signed = (d?: string | null) => (d == null ? '—' : Number(d) > 0 ? `+${qty(String(d))}` : qty(String(d)));

function ResultView({ result, fileNames }: { result: CompareResult; fileNames?: (string | undefined)[] }) {
  const s = result.summary;
  const named = (label: string, i: number) => (fileNames?.[i] ? `${label} (${fileNames[i]})` : label);
  return (
    <div className="card">
      <div className="card-header">
        <h2>Comparison result</h2>
        {s.allMatch
          ? <span className="badge badge-success">All match</span>
          : <span className="badge badge-danger">{s.mismatched + s.missingInTarget + s.extraInTarget} difference(s)</span>}
      </div>
      <p className="muted small" style={{ marginBottom: 6 }}>{named(result.referenceLabel, 0)} vs {named(result.targetLabel, 1)}</p>
      <div className="row small" style={{ marginBottom: 8 }}>
        <span className="badge badge-success">Matches {s.matched}</span>
        <span className="badge badge-danger">Mismatches {s.mismatched}</span>
        <span className="badge badge-warning">Missing {s.missingInTarget}</span>
        <span className="badge badge-warning">Extra {s.extraInTarget}</span>
      </div>
      <div className="table-wrap">
        <table>
          <thead><tr><th>Item</th><th className="num">{result.referenceLabel}</th><th className="num">{result.targetLabel}</th><th className="num">Difference</th><th>Status</th></tr></thead>
          <tbody>
            {result.lines.map((l, i) => (
              <tr key={i}>
                <td>{l.name}</td>
                <td className="num">{l.referenceQuantity != null ? qty(l.referenceQuantity) : '—'}</td>
                <td className="num">{l.targetQuantity != null ? qty(l.targetQuantity) : '—'}</td>
                <td className="num">{signed(l.difference)}</td>
                <td><StatusChip status={l.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small mt-2">
        Missing = in {result.referenceLabel} but not in {result.targetLabel}. Extra = in {result.targetLabel} but not in {result.referenceLabel}.
      </p>
      {result.fields.length > 0 && (
        <div className="table-wrap mt-2">
          <table>
            <thead><tr><th>Field</th><th>{result.referenceLabel}</th><th>{result.targetLabel}</th><th>Result</th></tr></thead>
            <tbody>
              {result.fields.map((f, i) => (
                <tr key={i}>
                  <td>{f.label}</td><td>{f.referenceValue ?? '—'}</td><td>{f.targetValue ?? '—'}</td>
                  <td><StatusChip status={f.status} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
