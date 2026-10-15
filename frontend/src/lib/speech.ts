import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Voice input with the browser's own speech recognition (nothing of ours, no extra service): the words it hears are handed to the caller as text to
 * edit. It is never assumed to exist: some browsers have none, and a person may refuse the microphone, so every outcome has a short plain message and
 * the caller always has typing to fall back on. (A browser may send the audio to its own speech service; the page says so.)
 */
interface Alternative { transcript: string }
interface SpeechResult { isFinal: boolean; 0: Alternative }
interface RecognitionEvent { resultIndex: number; results: ArrayLike<SpeechResult> }
interface Recognition {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((e: RecognitionEvent) => void) | null;
  onerror: ((e: { error: string }) => void) | null;
  onend: (() => void) | null;
  start(): void;
  stop(): void;
  abort(): void;
}
type RecognitionConstructor = new () => Recognition;

function constructorOf(): RecognitionConstructor | null {
  const w = window as unknown as { SpeechRecognition?: RecognitionConstructor; webkitSpeechRecognition?: RecognitionConstructor };
  return w.SpeechRecognition ?? w.webkitSpeechRecognition ?? null;
}

export const speechSupported = (): boolean => constructorOf() !== null;

const BLOCKED = 'The microphone is blocked. Allow it in your browser to speak, or type your request instead.';
const ERRORS: Record<string, string> = {
  'not-allowed': BLOCKED,
  'service-not-allowed': BLOCKED,
  'no-speech': 'I did not hear anything. Try again, or type your request instead.',
  'audio-capture': 'No microphone was found. You can type your request instead.',
  network: 'Voice input needs an internet connection. You can type your request instead.',
  'language-not-supported': 'Voice input does not support this language here. You can type your request instead.',
};
const FALLBACK = 'Voice input stopped working. You can type your request instead.';

export interface SpeechInput {
  supported: boolean;
  listening: boolean;
  /** What is being heard right now, not yet final: shown so the person can see it is working. */
  interim: string;
  /** Why listening stopped, when it was not the person's doing. Cleared when they try again. */
  error: string | null;
  start: () => void;
  stop: () => void;
}

/** @param onText called with each finished phrase, to be added to the text the person is editing */
export function useSpeechInput(onText: (phrase: string) => void): SpeechInput {
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState('');
  const [error, setError] = useState<string | null>(null);
  const recognition = useRef<Recognition | null>(null);
  const handler = useRef(onText);
  handler.current = onText;

  const stop = useCallback(() => { recognition.current?.stop(); }, []);

  const start = useCallback(() => {
    const Ctor = constructorOf();
    if (!Ctor || recognition.current) return;
    setError(null);
    setInterim('');
    try {
      const r = new Ctor();
      r.lang = navigator.language || 'en-US';
      r.continuous = true;
      r.interimResults = true;
      r.onresult = (e) => {
        let heard = '';
        for (let i = e.resultIndex; i < e.results.length; i++) {
          const result = e.results[i];
          if (result.isFinal) handler.current(result[0].transcript.trim());
          else heard += result[0].transcript;
        }
        setInterim(heard);
      };
      r.onerror = (e) => {
        if (e.error !== 'aborted') setError(ERRORS[e.error] ?? FALLBACK);   // "aborted" is the person stopping it
      };
      r.onend = () => { recognition.current = null; setListening(false); setInterim(''); };
      recognition.current = r;
      r.start();
      setListening(true);
    } catch {
      recognition.current = null;
      setListening(false);
      setError(FALLBACK);
    }
  }, []);

  useEffect(() => () => { recognition.current?.abort(); }, []);   // leaving the page ends listening

  return { supported: speechSupported(), listening, interim, error, start, stop };
}
