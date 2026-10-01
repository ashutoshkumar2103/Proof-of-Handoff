import { useState } from 'react';

/**
 * Password field with a show/hide (eye) toggle. Reused by login and register so the
 * behaviour stays consistent in one place.
 */
export function PasswordInput({ id, value, onChange, autoComplete, placeholder, minLength, required }: {
  id: string;
  value: string;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  autoComplete?: string;
  placeholder?: string;
  minLength?: number;
  required?: boolean;
}) {
  const [visible, setVisible] = useState(false);
  return (
    <div style={{ position: 'relative' }}>
      <input
        id={id}
        type={visible ? 'text' : 'password'}
        value={value}
        onChange={onChange}
        autoComplete={autoComplete}
        placeholder={placeholder}
        minLength={minLength}
        required={required}
        style={{ paddingRight: '2.75rem' }}
      />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        aria-label={visible ? 'Hide password' : 'Show password'}
        title={visible ? 'Hide password' : 'Show password'}
        style={{
          position: 'absolute', right: 6, top: '50%', transform: 'translateY(-50%)',
          width: 'auto', border: 'none', background: 'transparent', cursor: 'pointer',
          padding: '0.25rem 0.4rem', fontSize: '1.05rem', lineHeight: 1, color: 'var(--text-muted)',
        }}
      >
        {visible ? '🙈' : '👁️'}
      </button>
    </div>
  );
}
