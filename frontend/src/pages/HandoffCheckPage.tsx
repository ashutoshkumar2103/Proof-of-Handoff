import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { documentCheckApi, handoffApi } from '../api/endpoints';
import type { CompareInput, CompareResult, DocFieldInput, DocLineInput, MatchStatus } from '../api/types';
import { ErrorNotice, errorMessage } from '../components/ui';
import { qty } from '../lib/format';

type Mode = 'handoff' | 'document';

export function HandoffCheckPage() {
  const [mode, setMode] = useState<Mode>('handoff');
  const [refLabel, setRefLabel] = useState('Reference (e.g. Quotation)');
  const [refLines, setRefLines] = useState<DocLineInput[]>([{ name: '', quantity: '' }]);
  const [refFields, setRefFields] = useState<DocFieldInput[]>([]);
  const [handoffId, setHandoffId] = useState<number | ''>('');
  const [tgtLabel, setTgtLabel] = useState('Document B');
  const [tgtLines, setTgtLines] = useState<DocLineInput[]>([{ name: '', quantity: '' }]);
  const [tgtFields, setTgtFields] = useState<DocFieldInput[]>([]);
  const [error, setError] = useState<string | null>(null);

  const handoffs = useQuery({ queryKey: ['handoffs', 'for-check'], queryFn: () => handoffApi.list({ size: 100 }) });

  const compare = useMutation({
    mutationFn: (body: CompareInput) => documentCheckApi.compare(body),
    onError: (e) => setError(errorMessage(e)),
    onSuccess: () => setError(null),
  });

  function run(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    const referenceLines = refLines.filter((l) => l.name.trim() !== '');
    if (referenceLines.length === 0) { setError('Add at least one reference line.'); return; }
    const body: CompareInput = {
      referenceLabel: refLabel || undefined,
      referenceLines,
      referenceFields: refFields.filter((f) => f.label.trim() !== ''),
    };
    if (mode === 'handoff') {
      if (handoffId === '') { setError('Select a handoff to compare against.'); return; }
      body.handoffId = handoffId;
    } else {
      body.targetLabel = tgtLabel || undefined;
      body.targetLines = tgtLines.filter((l) => l.name.trim() !== '');
      body.targetFields = tgtFields.filter((f) => f.label.trim() !== '');
    }
    compare.mutate(body);
  }

  return (
    <div className="stack">
      <div>
        <h1>HandoffCheck</h1>
        <p className="muted">Compare a reference document (quotation, PO, estimate, agreement) against a handoff or another document.</p>
      </div>

      <form onSubmit={run} className="stack">
        <div className="card">
          <h2>Compare against</h2>
          <div className="row">
            <label className="row small" style={{ fontWeight: 600 }}>
              <input type="radio" style={{ width: 'auto' }} checked={mode === 'handoff'} onChange={() => setMode('handoff')} /> An existing handoff
            </label>
            <label className="row small" style={{ fontWeight: 600 }}>
              <input type="radio" style={{ width: 'auto' }} checked={mode === 'document'} onChange={() => setMode('document')} /> Another document
            </label>
          </div>
          {mode === 'handoff' && (
            <div className="field mt-2">
              <label>Handoff</label>
              <select value={handoffId} onChange={(e) => setHandoffId(e.target.value ? Number(e.target.value) : '')}>
                <option value="">Select a handoff…</option>
                {(handoffs.data?.content ?? []).map((h) => (
                  <option key={h.id} value={h.id}>{h.publicCode} — {h.title}</option>
                ))}
              </select>
            </div>
          )}
        </div>

        <div className="field-row" style={{ alignItems: 'start' }}>
          <div className="card">
            <div className="field">
              <label>Reference label</label>
              <input value={refLabel} onChange={(e) => setRefLabel(e.target.value)} />
            </div>
            <LinesEditor title="Reference lines" lines={refLines} onChange={setRefLines} />
            <FieldsEditor title="Reference fields" fields={refFields} onChange={setRefFields} />
          </div>

          {mode === 'document' ? (
            <div className="card">
              <div className="field">
                <label>Target label</label>
                <input value={tgtLabel} onChange={(e) => setTgtLabel(e.target.value)} />
              </div>
              <LinesEditor title="Target lines" lines={tgtLines} onChange={setTgtLines} />
              <FieldsEditor title="Target fields" fields={tgtFields} onChange={setTgtFields} />
            </div>
          ) : (
            <div className="card">
              <h3>Target</h3>
              <p className="muted small">The selected handoff's items are read live — no data is duplicated.
                A delivery-date field is derived from the handoff's due date if set.</p>
            </div>
          )}
        </div>

        {error && <div className="notice notice-error">{error}</div>}
        <div className="row">
          <button className="btn btn-primary" disabled={compare.isPending}>{compare.isPending ? 'Comparing…' : 'Compare'}</button>
        </div>
      </form>

      {compare.isError && <ErrorNotice error={compare.error} />}
      {compare.data && <ResultView result={compare.data} />}
    </div>
  );
}

function LinesEditor({ title, lines, onChange }: { title: string; lines: DocLineInput[]; onChange: (l: DocLineInput[]) => void }) {
  const set = (i: number, p: Partial<DocLineInput>) => onChange(lines.map((l, idx) => idx === i ? { ...l, ...p } : l));
  return (
    <div className="mt-2">
      <div className="card-header"><h3>{title}</h3>
        <button type="button" className="btn btn-sm" onClick={() => onChange([...lines, { name: '', quantity: '' }])}>+ Line</button></div>
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
    MISSING_IN_TARGET: { c: 'badge-warning', t: 'Missing in target' },
    EXTRA_IN_TARGET: { c: 'badge-warning', t: 'Extra in target' },
  };
  return <span className={`badge ${map[status].c}`}>{map[status].t}</span>;
}

function ResultView({ result }: { result: CompareResult }) {
  return (
    <div className="card">
      <div className="card-header">
        <h2>Result</h2>
        {result.summary.allMatch
          ? <span className="badge badge-success">All match</span>
          : <span className="badge badge-danger">{result.summary.mismatched + result.summary.missingInTarget + result.summary.extraInTarget} difference(s)</span>}
      </div>
      <p className="muted small">{result.referenceLabel} vs {result.targetLabel} · {result.summary.matched}/{result.summary.totalLines} lines match</p>
      <div className="table-wrap">
        <table>
          <thead><tr><th>Item</th><th className="num">{result.referenceLabel}</th><th className="num">{result.targetLabel}</th><th>Result</th></tr></thead>
          <tbody>
            {result.lines.map((l, i) => (
              <tr key={i}>
                <td>{l.name}</td>
                <td className="num">{l.referenceQuantity != null ? qty(l.referenceQuantity) : '—'}</td>
                <td className="num">{l.targetQuantity != null ? qty(l.targetQuantity) : '—'}</td>
                <td><StatusChip status={l.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
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
