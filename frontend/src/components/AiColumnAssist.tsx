import { useState } from 'react';
import { documentCheckApi } from '../api/endpoints';
import type { AiColumnSuggestion, AiMapping, DocLineInput } from '../api/types';
import { errorMessage } from './ui';

/** The spreadsheet types a column can be suggested for (the backend refuses the rest). */
const COLUMN_FILES = /\.(csv|xlsx)$/i;
const FALLBACK = 'AI assistance is temporarily unavailable. You can carry on with the rows as they were read, or edit them by hand.';

type Phase = 'idle' | 'asking' | 'suggested' | 'applying' | 'applied' | 'dismissed';

function Column({ label, s }: { label: string; s: AiColumnSuggestion }) {
  return (
    <li>
      <strong>{label}</strong> → “{s.columnName}” <span className="muted">({Math.round(s.confidence * 100)}% sure)</span>
      <div className="small muted">
        {s.reason}{s.sampleValues.length > 0 && <> · e.g. {s.sampleValues.join(', ')}</>}
      </div>
    </li>
  );
}

/**
 * HandoffCheck's optional AI Assist for a spreadsheet: only when the customer clicks it, the backend is asked which columns look like
 * the item and the quantity. What comes back is a suggestion to look at — nothing is read or changed until the customer accepts, and
 * then the file is read by the ordinary reading, with those columns. If AI is unavailable, or says nothing useful, a short note says so
 * and everything else on the page works as it did. Nothing here asks by itself: a request is made when the customer clicks AI Assist,
 * or Try again after a slow or unusable answer.
 */
export function AiColumnAssist({ file, handleRefusal, onAccepted }: {
  file: File;
  handleRefusal: (error: unknown) => boolean;
  onAccepted: (lines: DocLineInput[]) => void;
}) {
  const [phase, setPhase] = useState<Phase>('idle');
  const [result, setResult] = useState<AiMapping | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [canRetry, setCanRetry] = useState(false);

  if (!COLUMN_FILES.test(file.name)) return null;

  async function ask() {
    setPhase('asking');
    setNote(null);
    setCanRetry(false);
    try {
      const answer = await documentCheckApi.suggestColumns(file);
      setResult(answer);
      if (answer.available && answer.item && answer.quantity) {
        setPhase('suggested');
      } else {
        setNote(answer.message ?? FALLBACK);
        setCanRetry(answer.canRetry);
        setPhase('dismissed');
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

  async function accept() {
    if (!result?.item || !result.quantity) return;
    setPhase('applying');
    try {
      const lines = await documentCheckApi.extract(file, {
        itemColumn: result.item.column, quantityColumn: result.quantity.column, headerRow: result.headerRow ?? -1,
      });
      onAccepted(lines.map((l) => ({ name: l.name, quantity: String(l.quantity ?? '') })));
      setNote(null);
      setPhase('applied');
    } catch (e) {
      if (!handleRefusal(e)) setNote(errorMessage(e));
      setPhase('dismissed');
    }
  }

  return (
    <div className="mt-2 mb-2" aria-live="polite">
      {(phase === 'idle' || phase === 'asking') && (
        <div className="row">
          <button type="button" className="btn btn-sm" disabled={phase === 'asking'} onClick={() => void ask()}>
            {phase === 'asking' ? 'Asking AI…' : 'AI Assist: choose columns'}
          </button>
          <span className="small muted">
            {phase === 'asking' ? 'Asking AI — this can take a few seconds…' : 'Optional: suggests which columns hold the item and the quantity. You decide.'}
          </span>
        </div>
      )}

      {(phase === 'suggested' || phase === 'applying') && result?.item && result.quantity && (
        <div className="notice notice-info">
          <strong>AI suggests reading this file like this</strong>
          <ul style={{ margin: '0.4rem 0', paddingLeft: '1.2rem' }}>
            <Column label="Item name" s={result.item} />
            <Column label="Quantity" s={result.quantity} />
          </ul>
          <p className="small" style={{ margin: '0 0 0.5rem' }}>
            This finds {result.itemsFound} item{result.itemsFound === 1 ? '' : 's'}. Nothing changes until you accept, and you can still edit every row.
          </p>
          <div className="row">
            <button type="button" className="btn btn-sm btn-primary" disabled={phase === 'applying'} onClick={() => void accept()}>
              {phase === 'applying' ? 'Reading…' : 'Accept'}
            </button>
            <button type="button" className="btn btn-sm btn-ghost" disabled={phase === 'applying'}
                    onClick={() => { setPhase('dismissed'); setNote(null); }}>
              Reject
            </button>
          </div>
        </div>
      )}

      {phase === 'applied' && (
        <p className="small muted" style={{ margin: 0 }}>Read with the columns AI suggested. Check the rows below; you can edit any of them.</p>
      )}
      {phase === 'dismissed' && note && (
        <div className="notice notice-info small">
          {note}
          {canRetry && <> <button type="button" className="btn btn-sm btn-ghost" onClick={() => void ask()}>Try again</button></>}
        </div>
      )}
    </div>
  );
}
