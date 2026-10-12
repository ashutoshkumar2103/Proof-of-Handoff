import type { HandoffItem } from '../api/types';
import { qty } from './format';

/** What the person typed for one item on the Record return screen. */
export interface ReturnRow {
  returnQty: string;
  missingQty: string;
}

const num = (value: string) => Number(value) || 0;
/** Quantities have up to three decimals: compared as whole thousandths, so 0.1 + 0.2 is 0.3. */
const milli = (n: number) => Math.round(n * 1000);

/** What is still outstanding with the recipient (the backend's `remaining`). */
export const remaining = (i: HandoffItem) => num(i.remaining);
/** What is currently recorded as missing. */
export const missing = (i: HandoffItem) => num(i.missing);
/** Everything not yet returned: outstanding plus missing (a return beyond the outstanding units brings missing ones back). */
export const owed = (i: HandoffItem) => remaining(i) + missing(i);

/**
 * What a row's quantities come to, worked out the way the backend (ReturnService) judges them, for the live "Still with recipient"
 * and for the check before sending. This only shows the person the outcome early: the backend still decides, and refuses anything
 * that does not hold. Returns clear outstanding units first, so what stays with the recipient is Remaining − Return − Missing;
 * only the units beyond Remaining bring previously-missing ones back, and Missing can only mark what would still be outstanding.
 * A lower Return quantity never marks anything missing by itself.
 */
export function returnFigures(i: HandoffItem, row: ReturnRow): { still: number; problem: string | null } {
  const rq = num(row.returnQty);
  const mq = num(row.missingQty);
  const outstandingLeft = Math.max(0, milli(remaining(i)) - milli(rq)) / 1000;
  const still = (milli(outstandingLeft) - milli(mq)) / 1000;
  let problem: string | null = null;
  if (rq < 0) problem = 'Return quantity can’t be negative.';
  else if (mq < 0) problem = 'Missing can’t be negative.';
  else if (milli(rq) > milli(owed(i))) problem = `Return quantity is more than is still owed (${qty(owed(i))}).`;
  else if (milli(mq) > milli(outstandingLeft)) problem = `Return plus Missing is more than Remaining (${qty(remaining(i))}).`;
  return { still, problem };
}
