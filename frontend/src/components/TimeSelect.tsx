/**
 * Hour + minute dropdowns for picking a time. Replaces the native <input type="time">
 * spinner, whose scroll wheel jumps too many values at once. Value is an "HH:mm" string.
 */
export function TimeSelect({ value, onChange, disabled }: {
  value: string;
  onChange: (v: string) => void;
  disabled?: boolean;
}) {
  const [hh, mm] = value && value.includes(':') ? value.split(':') : ['', ''];
  const hours = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, '0'));
  const minutes = Array.from({ length: 60 }, (_, i) => String(i).padStart(2, '0'));

  const setHour = (h: string) => onChange(h === '' ? '' : `${h}:${mm || '00'}`);
  const setMinute = (m: string) => onChange(`${hh || '00'}:${m || '00'}`);

  return (
    <div className="time-select">
      <select aria-label="Hour" value={hh} disabled={disabled} onChange={(e) => setHour(e.target.value)}>
        <option value="">HH</option>
        {hours.map((h) => <option key={h} value={h}>{h}</option>)}
      </select>
      <span className="time-colon">:</span>
      <select aria-label="Minute" value={mm} disabled={disabled} onChange={(e) => setMinute(e.target.value)}>
        <option value="">MM</option>
        {minutes.map((m) => <option key={m} value={m}>{m}</option>)}
      </select>
    </div>
  );
}
