import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { handoffApi } from '../api/endpoints';
import type { AssistantDraft, AssistantPreview, CreateHandoffInput, HandoffDetail, ItemInput } from '../api/types';
import { useSubscriptionGate } from '../auth/useSubscriptionGate';
import { emptyFormValue, HandoffForm } from '../components/HandoffForm';
import { errorMessage, Gated, Spinner, useTransient } from '../components/ui';
import { localPartsToIso } from '../lib/format';
import { useSpeechInput } from '../lib/speech';

const MAX_TEXT = 1000;
const EXAMPLES = [
  'Create a handoff for Rahul with 2 pens, 3 markers, 6 pencils and 2 erasers.',
  'Duplicate quotation 12 with the same person.',
  'Make a draft for Rahul with the same items as quotation 12, but change the marker quantity to 10.',
];

/** One key for each preview, so that saving it twice (a double click, a retry after a lost answer) gives one draft. */
const newKey = (): string => (typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `k${Date.now()}${Math.random().toString(16).slice(2)}`);

/** What the form would send for these values, so that saving without the form (when the customer asked for it in their words) sends the same. */
function saveInput(d: AssistantDraft): { header: Omit<CreateHandoffInput, 'items'>; items: ItemInput[] } {
  return {
    header: {
      title: d.title.trim(),
      category: d.category || undefined,
      purpose: d.purpose || undefined,
      senderName: d.senderName.trim(),
      senderOrganization: d.senderOrganization || undefined,
      recipientName: d.recipientName.trim(),
      recipientEmail: d.recipientEmail.trim(),
      recipientPhone: d.recipientPhone || undefined,
      dueAt: localPartsToIso(d.dueDate, ''),
    },
    items: d.items.filter((i) => i.name.trim() !== '')
      .map((i) => ({ name: i.name.trim(), quantity: i.quantity, unit: i.unit || undefined, condition: 'GOOD' as const })),
  };
}

type Note = { kind: 'success' | 'info' | 'warning' | 'error'; text: string };
const NOTICE_CLASS: Record<Note['kind'], string> = { success: 'notice-success', info: 'notice-info', warning: 'notice-warning', error: 'notice-error' };

/**
 * The AI Handoff Creator: describe a handoff in your own words (typed, or spoken and transcribed by the browser), review what was understood, edit
 * anything, and save it as a DRAFT. It only ever prepares and saves a draft: there is no submit here, and nothing is sent to a recipient. The page
 * mirrors no business rule; the backend understands the request, takes the recipient and items as they were said (the customer's earlier handoffs
 * are looked at only when the request itself names one, to duplicate it), asks only when something is genuinely unclear and refuses what is not valid. The review step is the ordinary handoff form, with the one difference that the recipient may
 * not be known yet (such a draft cannot be submitted until it is). Typing always works, whatever the microphone or the AI does.
 *
 * Every message about what the customer just did (understood, saved, refused, unavailable) is the app's usual temporary message: it clears itself
 * after five seconds. What stays is what is still true: a question that is waiting for an answer, and a form that failed to save.
 */
export function AiHandoffCreatorPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const gate = useSubscriptionGate();
  const { handle } = gate;

  const [text, setText] = useState('');
  const [preview, setPreview] = useState<AssistantPreview | null>(null);
  const [version, setVersion] = useState(0);   // a new preview starts the form afresh
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [asking, setAsking] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saveKey, setSaveKey] = useState(newKey);
  const [saved, setSaved] = useState<HandoffDetail | null>(null);
  const [note, setNote] = useTransient<Note>();   // what the last action said, for five seconds: the text and the preview stay either way

  const speech = useSpeechInput((phrase) => setText((t) => (`${t} ${phrase}`.trim()).slice(0, MAX_TEXT)));
  // Why the microphone stopped is the result of pressing Speak, so it is a temporary message too.
  useEffect(() => { if (speech.error) setNote({ kind: 'warning', text: speech.error }); }, [speech.error, setNote]);

  if (gate.state === 'checking') return <Spinner />;
  if (!gate.shown) return null;   // never shown as usable without a subscription: all the customer sees is the dialog

  async function run(body: { text: string; intent?: unknown; choices?: Record<string, string> }, fresh: boolean) {
    if (gate.blocked) return;
    speech.stop();
    setAsking(true);
    setNote(null);
    try {
      const result = await handoffApi.assistantInterpret(body);
      if (result.status === 'UNAVAILABLE' || result.status === 'UNSUPPORTED') {
        setNote({ kind: result.status === 'UNSUPPORTED' ? 'info' : 'error', text: result.message });   // the text, and any preview, stay as they were
        return;
      }
      if (fresh) setAnswers({});
      const key = newKey();
      setPreview(result);
      setVersion((v) => v + 1);
      setSaveKey(key);
      setSaveError(null);
      if (result.status !== 'READY') return;   // a question that is still open stays on screen instead (below); it is saved when it is answered
      if (!result.saveRequested || !result.draft) {
        setNote({ kind: 'info', text: result.message });
      } else if (result.draft.items.some((i) => !(Number(i.quantity) > 0))) {
        // The customer asked for it to be saved, but a draft with an item that has no quantity is not one the backend will take.
        setNote({ kind: 'warning', text: 'You asked me to save the draft, but an item has no quantity yet, so it is not saved. Add it and press Save Draft.' });
      } else {
        const { header, items } = saveInput(result.draft);
        await persist(header, items, key, true);   // the same call the Save Draft button makes
      }
    } catch (err) {
      if (!handle(err)) setNote({ kind: 'error', text: errorMessage(err) });
    } finally {
      setAsking(false);
    }
  }

  /** Saves the draft, with the key of its preview so that saving it twice gives one draft. The preview stays if it fails, to be corrected and saved again. */
  async function persist(header: Omit<CreateHandoffInput, 'items'>, items: ItemInput[], key: string, asked = false) {
    if (gate.blocked) return;
    setSaveError(null);
    setSaving(true);
    try {
      const created = await handoffApi.assistantSave({ ...header, items, saveKey: key });
      await qc.invalidateQueries({ queryKey: ['dashboard'] });
      await qc.invalidateQueries({ queryKey: ['handoffs'] });
      setSaved(created);   // only now, with the backend's own answer
      const missing = !created.recipientEmail || !created.recipientName;
      setNote({ kind: 'success', text: `Draft ${created.publicCode ?? created.draftCode} saved${asked ? ' as you asked' : ''}. It has not been sent to anyone.${missing ? ' Add the recipient before you submit it.' : ''}` });
    } catch (err) {
      if (!handle(err)) setSaveError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  }

  const createDraft = () => { void run({ text }, true); };
  const answerQuestions = () => { if (preview) void run({ text, intent: preview.intent, choices: answers }, false); };
  const cancelPreview = () => { setPreview(null); setAnswers({}); setSaveError(null); setNote(null); };

  const draft = preview?.draft ?? null;
  const questions = preview?.clarifications ?? [];
  const needsInput = preview?.status === 'NEEDS_INPUT';
  const allAnswered = questions.every((q) => answers[q.id]);

  const message = note && (
    <div className={`notice ${NOTICE_CLASS[note.kind]}`} role={note.kind === 'error' || note.kind === 'warning' ? 'alert' : 'status'}>{note.text}</div>
  );

  if (saved) {
    return (
      <div className="stack page-narrow">
        <h1>AI Handoff Creator</h1>
        {message}
        <div className="card">
          <p style={{ margin: 0 }}>Draft <strong>{saved.publicCode ?? saved.draftCode}</strong> · {saved.title}</p>
          <div className="row mt-2">
            <button className="btn btn-primary" onClick={() => navigate(`/handoffs/${saved.id}`)}>Open the draft</button>
            <button className="btn" onClick={() => { setSaved(null); setPreview(null); setText(''); setAnswers({}); }}>Create another</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="stack page-narrow">
      <h1>AI Handoff Creator</h1>
      <p className="muted">
        Describe the handoff in your own words. You will see what was understood and can change anything before saving it as a draft.
        Nothing is saved or sent until you save, and a draft is never submitted from here.
      </p>
      {message}

      <Gated blocked={gate.blocked}>
        {/* the gate is a fieldset, which has no spacing of its own: the stack gives the sections below their usual gaps */}
        <div className="stack">
          <div className="card">
            <div className="field">
              <label htmlFor="ai-handoff-text">Your request <span className="muted">(you can edit it, including what you speak)</span></label>
              <textarea id="ai-handoff-text" rows={4} value={text} maxLength={MAX_TEXT} disabled={asking}
                        placeholder={EXAMPLES[0]} onChange={(e) => setText(e.target.value)} />
              {speech.interim && <div className="small muted" aria-live="polite">Hearing: {speech.interim}</div>}
            </div>
            <div className="row">
              <button type="button" className="btn btn-primary" disabled={asking || text.trim() === ''} onClick={createDraft}>
                {asking ? 'Understanding…' : 'Create Draft'}
              </button>
              {speech.supported ? (
                <button type="button" className={`btn${speech.listening ? ' btn-negative' : ''}`} aria-pressed={speech.listening} disabled={asking}
                        onClick={speech.listening ? speech.stop : speech.start}>
                  {speech.listening ? '■ Stop listening' : '🎤 Speak'}
                </button>
              ) : (
                <span className="small muted">Voice input isn't available in this browser. Type your request instead.</span>
              )}
              <span className="small muted grow" style={{ textAlign: 'right' }}>{text.length}/{MAX_TEXT}</span>
            </div>
            {speech.supported && !speech.listening && (
              <p className="small muted mt-2">Speaking uses your browser's own speech recognition, which may send your voice to its speech service.</p>
            )}
            {!preview && (
              <div className="small muted mt-2">
                For example:
                <ul style={{ margin: '0.3rem 0 0', paddingLeft: '1.2rem' }}>{EXAMPLES.map((e) => <li key={e}>{e}</li>)}</ul>
              </div>
            )}
          </div>

          {asking && !preview && <Spinner label="Working out your draft…" />}

          {preview && draft && (
            <>
              {/* Still true until it is answered, so it stays; the "here is your draft" message is the temporary one above. */}
              {needsInput && <div className="notice notice-warning" role="status">{preview.message}{preview.saveRequested && ' As you asked, it is saved as soon as you answer.'}</div>}

              {questions.map((q) => (
                <div key={q.id} className="card" role="radiogroup" aria-labelledby={`ai-q-${q.id}`}>
                  <p id={`ai-q-${q.id}`} style={{ margin: '0 0 0.5rem', fontWeight: 600 }}>{q.question}</p>
                  {q.options.map((o) => (
                    // the radio is not full width (the global input rule would make it so), as in the HandoffCheck AI suggestions
                    <label key={o.id} style={{ display: 'flex', gap: '0.6rem', alignItems: 'center', fontWeight: 400, padding: '0.3rem 0', marginBottom: 0, cursor: 'pointer' }}>
                      <input type="radio" name={q.id} checked={answers[q.id] === o.id} style={{ width: 'auto', flex: 'none', margin: 0 }}
                             onChange={() => setAnswers({ ...answers, [q.id]: o.id })} />
                      <span>{o.label}</span>
                    </label>
                  ))}
                </div>
              ))}
              {questions.length > 0 && (
                <div className="row">
                  <button type="button" className="btn btn-primary" disabled={asking || !allAnswered} onClick={answerQuestions}>
                    {asking ? 'Updating…' : 'Use my answers'}
                  </button>
                  <button type="button" className="btn" disabled={asking} onClick={cancelPreview}>Cancel</button>
                </div>
              )}

              {preview.notes.length > 0 && (
                <div className="card">
                  <h2>Please check</h2>
                  <ul style={{ margin: 0, paddingLeft: '1.2rem' }}>{preview.notes.map((n) => <li key={n.field + n.text} className="small">{n.text}</li>)}</ul>
                </div>
              )}

              <Gated blocked={needsInput}>
                <HandoffForm
                  key={version}
                  incompleteOk
                  initial={emptyFormValue({
                    title: draft.title,
                    category: draft.category,
                    purpose: draft.purpose,
                    senderName: draft.senderName,
                    senderOrganization: draft.senderOrganization,
                    recipientName: draft.recipientName,
                    recipientEmail: draft.recipientEmail,
                    recipientPhone: draft.recipientPhone,
                    dueDate: draft.dueDate,
                    ...(draft.items.length > 0 ? {
                      items: draft.items.map((i) => ({ name: i.name, quantity: i.quantity, unit: i.unit, condition: 'GOOD' as const, serialNumber: '', notes: '' })),
                    } : {}),
                  })}
                  submitLabel="Save Draft"
                  busy={saving}
                  error={saveError}
                  // Cancel sits beside Save Draft, as the buttons of the other forms do. While a question is open the form is locked and Cancel is by the answers.
                  actions={needsInput ? undefined : <button type="button" className="btn" disabled={saving} onClick={cancelPreview}>Cancel</button>}
                  onSubmit={({ header, items }) => persist(header, items, saveKey)}
                />
              </Gated>
            </>
          )}
        </div>
      </Gated>
    </div>
  );
}
