import { describe, expect, it } from 'vitest';
import type { HandoffItem } from '../api/types';
import { owed, returnFigures } from './quantity';

/** Six chairs given; `returned` and `lost` are already recorded. */
const chairs = (returned = 0, lost = 0): HandoffItem => ({
  id: 1, name: 'Chairs', outgoing: '6', returnedConfirmed: String(returned), returnedPending: '0',
  missing: String(lost), remaining: String(6 - returned - lost),
});

describe('returnFigures (mirrors the backend rules the Record return screen shows live)', () => {
  it('returning all of Remaining leaves nothing with the recipient', () => {
    expect(returnFigures(chairs(), { returnQty: '6', missingQty: '0' })).toEqual({ still: 0, problem: null });
  });

  it('a partial return leaves the rest with the recipient and marks none of it missing', () => {
    expect(returnFigures(chairs(), { returnQty: '4', missingQty: '0' })).toEqual({ still: 2, problem: null });
  });

  it('return plus missing that add up to Remaining leave nothing with the recipient', () => {
    expect(returnFigures(chairs(), { returnQty: '4', missingQty: '2' })).toEqual({ still: 0, problem: null });
  });

  it('recording nothing leaves everything with the recipient', () => {
    expect(returnFigures(chairs(), { returnQty: '0', missingQty: '0' }).still).toBe(6);
  });

  it('refuses return plus missing beyond Remaining', () => {
    expect(returnFigures(chairs(), { returnQty: '5', missingQty: '2' }).problem).toMatch(/more than Remaining/);
  });

  it('refuses a return beyond everything still owed', () => {
    expect(returnFigures(chairs(), { returnQty: '7', missingQty: '0' }).problem).toMatch(/more than is still owed/);
  });

  it('refuses negative quantities', () => {
    expect(returnFigures(chairs(), { returnQty: '-1', missingQty: '0' }).problem).toMatch(/negative/);
    expect(returnFigures(chairs(), { returnQty: '0', missingQty: '-1' }).problem).toMatch(/negative/);
  });

  it('lets units that were marked missing come back, up to what is owed', () => {
    const item = chairs(4, 2);   // 4 back, 2 missing, nothing outstanding
    expect(owed(item)).toBe(2);
    expect(returnFigures(item, { returnQty: '2', missingQty: '0' })).toEqual({ still: 0, problem: null });
    expect(returnFigures(item, { returnQty: '3', missingQty: '0' }).problem).toMatch(/more than is still owed/);
  });

  it('adds up decimals exactly (0.1 + 0.2 is 0.3)', () => {
    const item: HandoffItem = { ...chairs(), remaining: '0.3', outgoing: '0.3' };
    expect(returnFigures(item, { returnQty: '0.1', missingQty: '0.2' })).toEqual({ still: 0, problem: null });
  });
});
