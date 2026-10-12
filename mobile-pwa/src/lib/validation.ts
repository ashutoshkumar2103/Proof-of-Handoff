/**
 * What counts as an email address a handoff can be sent to — the same rule the backend applies (`EmailAddress`), mirrored here only so
 * a typo like `name@ee` is pointed out before the form is sent. The backend is what decides; keep the two in step.
 */
const ATOM = "[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+";
// One way to match a label (hyphens only between letters or digits), so a hostile address cannot make the matching slow.
const LABEL = '(?=[^.]{1,63}\\.)[A-Za-z0-9]+(?:-+[A-Za-z0-9]+)*';
const TLD = '(?:[A-Za-z]{2,63}|xn--[A-Za-z0-9-]{1,59})';
const EMAIL = new RegExp('^(?=.{1,254}$)(?=[^@]{1,64}@)' + ATOM + '(?:\\.' + ATOM + ')*@(?:' + LABEL + '\\.)+' + TLD + '$');

export const INVALID_EMAIL_MESSAGE = 'Enter a valid email address, for example name@example.com.';

export const isValidEmail = (value: string) => value.length <= 255 && EMAIL.test(value);
