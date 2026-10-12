import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { reportApi } from '../api/endpoints';
import type { ReportAssistantAnswer, ReportAssistantSuggestion } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { AI_REPORT_PLAN_MESSAGE, ZONE } from '../lib/format';
import { useTransient } from '../lib/useTransient';
import { Icon } from './Icon';
import { errorMessage } from './ui';

const MAX_QUESTION = 300;

/**
 * "Ask AI Reports": a question about the customer's own handoffs, in their own words (a period too if they want one). The backend
 * works out the answer from the report's figures and the AI only works out what was asked, so the answer is never the AI's own number.
 * Nothing is asked of the AI until Ask is pressed; the ready-made questions use no AI at all. Plans that include HandoffCheck only
 * (the backend enforces it; the button only shows a lock). Read-only; one question, one answer: closing forgets both.
 */
export function ReportAssistantButton() {
  const { user } = useAuth();
  const eligible = !!user?.handoffCheck;
  const [open, setOpen] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);

  function close() {
    setOpen(false);
    trigger.current?.focus();
  }

  return (
    <>
      <button ref={trigger} type="button" className="btn btn-sm btn-primary" aria-haspopup="dialog" onClick={() => setOpen(true)}>
        <Icon name={eligible ? 'sparkles' : 'lock'} />Ask AI
      </button>
      {open && <AssistantSheet eligible={eligible} onClose={close} />}
    </>
  );
}

function AssistantSheet({ eligible, onClose }: { eligible: boolean; onClose: () => void }) {
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
  useEffect(() => {   // the page behind does not scroll while the sheet is open
    const before = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = before; };
  }, []);

  async function ask(what: { question?: string; suggestion?: string }) {
    setPending(true);
    setNotice(null);
    try {
      const result = await reportApi.ask(ZONE, what);
      if (result.kind === 'UNAVAILABLE') { setAnswer(null); setNotice(result.answer); } else setAnswer(result);
    } catch (e) {
      setNotice(errorMessage(e));   // a refusal (a plan, a bad period) is the server's own plain sentence; the report is untouched either way
    } finally {
      setPending(false);
    }
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!question.trim() || pending) return;
    // A ready-made question left as it was goes by its intent, which needs no AI; anything else is the customer's own words.
    void ask(picked && question === picked.question ? { suggestion: picked.intent } : { question: question.trim() });
  }

  // Closed only by Close or Escape, never by a tap outside it: someone in the middle of a question must not lose it to a stray tap.
  return (
    <div className="sheet-overlay">
      <div className="sheet" role="dialog" aria-modal="true" aria-labelledby="assistant-title">
        <div className="spread spread-nowrap">
          <h2 className="sheet-title" id="assistant-title">AI Report Assistant</h2>
          <button type="button" className="icon-btn" aria-label="Close" onClick={onClose}><Icon name="x" /></button>
        </div>

        {!eligible ? (
          <p className="muted">{AI_REPORT_PLAN_MESSAGE}</p>
        ) : (
          <>
            <p className="muted small">
              Ask anything about your handoffs, in your own words. Mention dates to look at a period, for example “missing items between 1 Oct and 10 Oct”.
            </p>
            <form className="search" onSubmit={submit}>
              <input ref={input} aria-label="Your question" value={question} maxLength={MAX_QUESTION} disabled={pending}
                     placeholder="Ask your question…"
                     onChange={(e) => { setQuestion(e.target.value); if (picked && e.target.value !== picked.question) setPicked(null); }} />
              <button className="btn btn-primary" disabled={pending || !question.trim()}>{pending ? 'Asking…' : 'Ask'}</button>
            </form>

            {suggestions.data && suggestions.data.length > 0 && (
              <div className="chips-wrap" role="group" aria-label="Examples you can try">
                {suggestions.data.map((s) => (
                  <button key={s.intent} type="button" className="chip" disabled={pending}
                          onClick={() => { setQuestion(s.question); setPicked(s); input.current?.focus(); }}>
                    {s.question}
                  </button>
                ))}
              </div>
            )}

            {pending && <p className="muted small" role="status">Asking — this can take a few seconds…</p>}
            {notice && <div className="notice notice-info" role="status">{notice}</div>}

            {answer && !pending && (
              <div className="answer" role="status" aria-live="polite">
                <p><strong>{answer.answer}</strong></p>
                {answer.kind === 'ANSWER' && answer.entries.length > 0 && (
                  <ul className="lines">
                    {answer.entries.map((e, i) => (
                      <li key={`${e.reference}-${i}`}><span><strong>{e.reference}</strong> · {e.title}</span><span className="muted small">{e.recipient} · {e.detail}</span></li>
                    ))}
                    {answer.entryTotal > answer.entries.length && <li className="muted">and {answer.entryTotal - answer.entries.length} more</li>}
                  </ul>
                )}
                {answer.kind === 'ANSWER' && answer.basis && <p className="muted small">Based on: {answer.basis}</p>}
              </div>
            )}
          </>
        )}
        <div className="sheet-actions"><button type="button" className="btn btn-negative" onClick={onClose}>Close</button></div>
      </div>
    </div>
  );
}
