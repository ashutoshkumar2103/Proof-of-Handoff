import { describe, expect, it } from 'vitest';
import { isValidEmail } from './validation';

// The same examples as the backend's HandoffRecipientEmailTest: the phone and the server must agree on what is a valid recipient.
const REJECTED = [
  'ashut@ee', 'a@b', 'name@localhost', 'name@example.', 'name@.example.com', 'name@example..com', 'name@example.c', 'name@example.123',
  'name@-example.com', 'name@example-.com', 'name example@example.com', 'name@exa mple.com', 'name@@example.com', 'name@example@example.com',
  '@example.com', 'name@', '.name@example.com', 'name.@example.com', 'na..me@example.com', 'näme@example.com',
  `${'a'.repeat(65)}@example.com`, `name@${'a'.repeat(64)}.com`, 'name@example.com,other@example.com', '<name@example.com>', 'Name <name@example.com>', '',
];
const ACCEPTED = [
  'name@example.com', 'first.last@example.com', 'first.last+tag@sub.example.co.uk', "o'brien@example.org", 'user_name-1@ex-ample.io',
  'a@b.co', 'hire@example.test', 'x@xn--80ak6aa92e.xn--p1ai', 'A.B@EXAMPLE.COM',
];

describe('isValidEmail', () => {
  it.each(ACCEPTED)('accepts %s', (email) => expect(isValidEmail(email)).toBe(true));
  it.each(REJECTED)('rejects %s', (email) => expect(isValidEmail(email)).toBe(false));

  it('answers a hostile, very long address at once (the pattern cannot be made to backtrack)', () => {
    const hostile = ['name@' + 'a'.repeat(5000) + '.com', 'name@' + 'a-'.repeat(3000) + 'x', 'a'.repeat(5000) + '@example.com', 'name@' + 'a.'.repeat(3000) + '!'];
    const started = Date.now();
    for (const email of hostile) expect(isValidEmail(email)).toBe(false);
    expect(Date.now() - started).toBeLessThan(500);
  });
});
