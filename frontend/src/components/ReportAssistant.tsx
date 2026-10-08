import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { reportApi } from '../api/endpoints';
import type { ReportAssistantAnswer, ReportAssistantSuggestion } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { AI_REPORT_PLAN_MESSAGE } from '../lib/format';
import { errorMessage, useTransient } from './ui';

const MAX_QUESTION = 300;
const FALLBACK = 'AI assistance is temporarily unavailable. Your normal report is still available.';

/**
 * The "Ask AI Reports" action of the Summary Report and Quotation List pages, and the modal it opens. In the modal the customer asks a question
 * about their handoffs — whatever they like, in their own words, and a period too if they want one ("between 1 Oct and 10 Oct"); the ready-made
 * questions are only examples — and the answer appears there. Nothing about the page it was opened from limits it: it is about all their handoffs
 * unless the question says otherwise. The report's backend works out the answer, and the AI only works out what was asked, so the answer is the
 * report's own figure, never the AI's. Nothing is asked of the AI until the customer presses Ask;
 * the ready-made questions use no AI at all. One question, one answer: closing the modal forgets both. Read-only.
 */
export function ReportAssistant() {
  const { user } = useAuth();
  const eligible = !!user?.handoffCheck;   // the plans that include HandoffCheck: the backend enforces it, this only shows or locks
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);

  function close() {
    setOpen(false);
    trigger.current?.focus();
  }

  return (
    <>
      <button ref={trigger} type="button" className="btn btn-primary" aria-haspopup="dialog" onClick={() => setOpen(true)}>
        <span aria-hidden="true">✦</span> Ask AI Reports{eligible ? '' : ' 🔒'}
      </button>
      {open && <AssistantModal eligible={eligible} onClose={close} />}
    </>
  );
}

function AssistantModal({ eligible, onClose }: { eligible: boolean; onClose: () => void }) {
  const [question, setQuestion] = useState('');
  const [picked, setPicked] = useState<ReportAssistantSuggestion | null>(null);   // a ready-made question the box still holds as it was
  const [answer, setAnswer] = useState<ReportAssistantAnswer | null>(null);
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useTransient<string>();
  const input = useRef<HTMLInputElement>(null);

  const suggestions = useQuery({
    queryKey: ['report-assistant-suggestions'], queryFn: reportApi.assistantSuggestions, enabled: eligible, staleTime: Infinity, retry: false,
  });

  useEffect(() => { input.current?.focus(); }, []);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);
  useEffect(() => {   // the page behind does not scroll while the modal is open
    const before = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = before; };
  }, []);

  async function ask(what: { question?: string; suggestion?: string }) {
    setPending(true);
    setNotice(null);
    try {
      const result = await reportApi.ask(what);
      if (result.kind === 'UNAVAILABLE') {
        setAnswer(null);
        setNotice(result.answer);
      } else {
        setAnswer(result);
      }
    } catch (e) {
      setNotice(errorMessage(e) || FALLBACK);   // a refusal (a plan, a bad period) is the server's own plain sentence; the report is untouched either way
    } finally {
      setPending(false);
    }
  }

  function change(value: string) {
    setQuestion(value);
    if (picked && value !== picked.question) setPicked(null);   // edited: it is the customer's own question now
  }

  function choose(s: ReportAssistantSuggestion) {
    setQuestion(s.question);
    setPicked(s);
    input.current?.focus();
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!question.trim() || pending) return;
    // A ready-made question left as it was goes by its intent, which needs no AI; anything else is the customer's own words.
    void ask(picked && question === picked.question ? { suggestion: picked.intent } : { question: question.trim() });
  }

  // Closed by Escape, the Close button or a click outside: nothing typed here is worth keeping.
  return (
    <div className="modal-overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal modal-wide report-assistant" role="dialog" aria-modal="true" aria-labelledby="report-assistant-title">
        <div className="spread" style={{ flexWrap: 'nowrap', alignItems: 'flex-start' }}>
          <h3 className="modal-title" id="report-assistant-title">AI Report Assistant{eligible ? '' : ' 🔒'}</h3>
          <button type="button" className="btn btn-sm btn-ghost" aria-label="Close" onClick={onClose}>✕</button>
        </div>

        {!eligible ? (
          <p className="muted" style={{ margin: 0 }}>{AI_REPORT_PLAN_MESSAGE}</p>
        ) : (
          <>
            <p className="muted small" style={{ margin: '0 0 0.6rem' }}>
              Ask anything about your handoffs, in your own words. Mention dates to look at a period, for example “missing items between 1 Oct and 10 Oct”.
            </p>

            <form className="row report-assistant-form" onSubmit={submit}>
              <input ref={input} aria-label="Ask about this report" className="grow" value={question} maxLength={MAX_QUESTION}
                     disabled={pending} placeholder="Ask your question…" onChange={(e) => change(e.target.value)} />
              <button className="btn btn-primary" disabled={pending || !question.trim()}>{pending ? 'Asking…' : 'Ask'}</button>
            </form>

            {suggestions.data && suggestions.data.length > 0 && (
              <div className="row report-assistant-suggestions" style={{ marginTop: '0.6rem' }} aria-label="Examples you can try">
                {suggestions.data.map((s) => (
                  <button key={s.intent} type="button" className="btn btn-sm" disabled={pending} onClick={() => choose(s)}>
                    {s.question}
                  </button>
                ))}
              </div>
            )}

            {pending && <p className="muted small mt-2" role="status">Asking — this can take a few seconds…</p>}
            {notice && <div className="notice notice-info small mt-2" role="status">{notice}</div>}

            {answer && !pending && (
              <div className="report-assistant-answer mt-2" role="status" aria-live="polite">
                <p style={{ margin: 0, fontWeight: 600 }}>{answer.answer}</p>
                {answer.kind === 'ANSWER' && answer.entries.length > 0 && (
                  <ul className="small" style={{ margin: '0.5rem 0 0', paddingLeft: '1.2rem' }}>
                    {answer.entries.map((e, i) => (
                      <li key={`${e.reference}-${i}`}><strong>{e.reference}</strong> · {e.title} · {e.recipient} · <span className="muted">{e.detail}</span></li>
                    ))}
                    {answer.entryTotal > answer.entries.length && <li className="muted">and {answer.entryTotal - answer.entries.length} more</li>}
                  </ul>
                )}
                {answer.kind === 'ANSWER' && answer.basis && (
                  <p className="muted small" style={{ margin: '0.5rem 0 0' }}>
                    Based on: {answer.basis}
                  </p>
                )}
              </div>
            )}
          </>
        )}

        <div className="modal-actions">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}
