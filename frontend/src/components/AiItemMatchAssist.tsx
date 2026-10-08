import { useEffect, useRef, useState } from 'react';
import { documentCheckApi } from '../api/endpoints';
import type { AiItemMatch, NameMatch } from '../api/types';

const FALLBACK = 'AI assistance is temporarily unavailable. You can carry on with the rows as they were read, or edit them by hand.';

type Phase = 'idle' | 'asking' | 'suggested' | 'dismissed';

const same = (a: NameMatch, b: NameMatch) => a.from === b.from && a.to === b.to;

/**
 * HandoffCheck's optional AI Assist for item names, on the review step of two files: only when the customer clicks it, the backend is
 * asked — in one request, with the item names only — which names of File A and File B look like the same item spelled differently
 * ("Exam Pad" / "Exam Ped", "Book" / "Books"). What comes back is a suggestion to look at: nothing in the rows changes, and nothing is
 * matched, until the customer accepts, and then only the ones ticked. The likely ones are ticked to begin with; a less sure one is
 * labelled "possible" and left unticked, so it is never applied without being chosen. The comparison itself stays the ordinary deterministic
 * one, which simply treats the accepted names as one item. If AI is unavailable a short note says so and everything else works as before.
 */
export function AiItemMatchAssist({ namesA, namesB, accepted, askNow = 0, handleRefusal, onAccept, onClear }: {
  namesA: string[];
  namesB: string[];
  accepted: NameMatch[];
  /** A number that goes up each time the page wants the question asked now (the customer chose "check item names" elsewhere): 0 = never. */
  askNow?: number;
  handleRefusal: (error: unknown) => boolean;
  onAccept: (matches: NameMatch[]) => void;
  onClear: () => void;
}) {
  const [phase, setPhase] = useState<Phase>('idle');
  const [matches, setMatches] = useState<AiItemMatch[]>([]);
  const [chosen, setChosen] = useState<boolean[]>([]);
  const [note, setNote] = useState<string | null>(null);
  const [canRetry, setCanRetry] = useState(false);

  const asked = useRef(0);
  useEffect(() => {
    if (askNow > asked.current) {   // once for each request, however often this renders
      asked.current = askNow;
      void ask();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [askNow]);

  async function ask() {
    setPhase('asking');
    setNote(null);
    setCanRetry(false);
    try {
      const answer = await documentCheckApi.suggestItemMatches(namesA, namesB);
      if (!answer.available) {
        setNote(answer.message ?? FALLBACK);
        setCanRetry(answer.canRetry);
        setPhase('dismissed');
      } else if (answer.matches.length === 0) {
        setNote('AI found no item names that look like the same item spelled differently.');
        setPhase('dismissed');
      } else {
        setMatches(answer.matches);
        setChosen(answer.matches.map((m) => m.certain));
        setPhase('suggested');
      }
    } catch (e) {
      // A refused subscription is the page's own dialog; anything else is just "AI is not available" — never the raw error.
      if (!handleRefusal(e)) {
        setNote(FALLBACK);
        setCanRetry(true);
      }
      setPhase('dismissed');
    }
  }

  function accept() {
    const picked = matches.filter((_, i) => chosen[i]).map((m): NameMatch => ({ from: m.from, to: m.to }));
    onAccept([...accepted.filter((a) => !picked.some((p) => same(a, p))), ...picked]);
    setNote(null);
    setPhase('idle');
  }

  const picks = chosen.filter(Boolean).length;
  return (
    <div className="card" aria-live="polite">
      {phase !== 'suggested' && (
        <div className="row">
          <button type="button" className="btn btn-sm btn-primary" disabled={phase === 'asking'} onClick={() => void ask()}>
            {phase === 'asking' ? 'Asking AI…' : 'AI Assist: match item names'}
          </button>
          <span className="small">
            {phase === 'asking'
              ? 'Asking AI — this can take a few seconds…'
              : <>Spelled differently in the two files? (“Exam Pad” and “Exam Ped”, “Book” and “Books”.) <span className="muted">AI Assist suggests which names are the same item, so they compare as one row. Optional: you decide.</span></>}
          </span>
        </div>
      )}

      {accepted.length > 0 && phase !== 'suggested' && (
        <div className="notice notice-success small mt-2">
          Comparing {accepted.length} name{accepted.length === 1 ? '' : 's'} as the item you accepted:{' '}
          {accepted.map((m, i) => <span key={i}>{i > 0 && ', '}“{m.from}” as “{m.to}”</span>)}.{' '}
          <button type="button" className="btn btn-sm btn-ghost" onClick={onClear}>Undo</button>
        </div>
      )}

      {phase === 'suggested' && (
        <div className="notice notice-info">
          <strong>AI suggests these names are the same item</strong>
          <ul style={{ listStyle: 'none', margin: '0.5rem 0', padding: 0 }}>
            {matches.map((m, i) => (
              <li key={i} style={{ marginBottom: '0.4rem' }}>
                <label style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-start' }}>
                  <input type="checkbox" checked={chosen[i] ?? false} style={{ width: 'auto', flex: 'none', marginTop: 4 }}
                         onChange={(e) => setChosen((c) => c.map((v, j) => (j === i ? e.target.checked : v)))} />
                  <span>
                    {m.fromFile}: <strong>{m.from}</strong> → {m.toFile}: <strong>{m.to}</strong>{' '}
                    {m.certain
                      ? <span className="muted">({Math.round(m.confidence * 100)}% sure)</span>
                      : <span className="badge badge-warning">Possible match · {Math.round(m.confidence * 100)}% sure</span>}
                    <span className="small muted" style={{ display: 'block' }}>{m.reason}</span>
                  </span>
                </label>
              </li>
            ))}
          </ul>
          <p className="small" style={{ margin: '0 0 0.5rem' }}>
            Nothing changes until you accept, and your rows stay as they were read: the ticked names are only compared as one item. Check the
            “possible” ones carefully.
          </p>
          <div className="row">
            <button type="button" className="btn btn-sm btn-primary" disabled={picks === 0} onClick={accept}>
              Accept{picks > 0 ? ` (${picks})` : ''}
            </button>
            <button type="button" className="btn btn-sm btn-ghost" onClick={() => { setPhase('idle'); setNote(null); }}>Reject</button>
          </div>
        </div>
      )}

      {phase === 'dismissed' && note && (
        <div className="notice notice-info small mt-2">
          {note}
          {canRetry && <> <button type="button" className="btn btn-sm btn-ghost" onClick={() => void ask()}>Try again</button></>}
        </div>
      )}
    </div>
  );
}
