// Types mirroring the backend API DTOs. Kept in one place as the frontend's contract.

export type SubscriptionPlan = 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'YEARLY';

/** A plan's list price (public): one payment of `amount` covers `months` months, with what it includes from support. */
export interface PlanPrice {
  plan: SubscriptionPlan;
  months: number;
  amount: number;
  currency: string;
  support: SupportEntitlements;
}

/** Proof that a plan was paid for. The token applies it to an account once; it is only ever shown here. */
export interface PaymentReceipt {
  token: string;
  plan: SubscriptionPlan;
  amount: number;
  currency: string;
  expiresAt: string;
}

/**
 * A plan the signed-in customer can move up to from their active plan, priced as what is left to pay: `credit` (what the plan
 * they are on counts as already paid) comes off the plan's price, and `handoffCheck` says whether the plan includes HandoffCheck.
 * The backend works it all out and charges exactly `amountDue`.
 */
export interface UpgradeOption {
  /** The plan the customer is on, whose price is the `credit`. */
  from: SubscriptionPlan;
  price: PlanPrice;
  credit: number;
  amountDue: number;
  handoffCheck: boolean;
}

export type HandoffStatus =
  | 'DRAFT'
  | 'OUTGOING_SENT'
  | 'AWAITING_RECIPIENT'
  | 'ACTIVE_WITH_RECIPIENT'
  | 'RETURN_PENDING'
  | 'PARTIALLY_RETURNED'
  | 'FULLY_RETURNED'
  | 'CLOSED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'DISPUTED'
  | 'OVERDUE';

export type ItemCondition = 'GOOD' | 'DAMAGED' | 'MISSING' | 'OTHER' | 'RECOVERED';
export type ActorType = 'USER' | 'RECIPIENT' | 'SYSTEM';
export type AttachmentKind = 'EVIDENCE' | 'REFERENCE_DOCUMENT';
export type HandoffAction =
  | 'EDIT' | 'DELETE' | 'SUBMIT' | 'RESEND_LINK' | 'CANCEL'
  | 'RECORD_RETURN' | 'CONFIRM_RETURN' | 'REQUEST_MISSING_CONFIRMATION'
  | 'DISPUTE' | 'CLOSE' | 'ADD_ATTACHMENT';

/** What the customer's plan includes in the support area. The backend decides and enforces it; the UI only follows. */
export type SupportPriority = 'NORMAL' | 'PRIORITY' | 'HIGHEST';

export interface SupportEntitlements {
  contactSupport: boolean;
  /** Send support a message from the app (without the ticket workflow). */
  message: boolean;
  ticket: boolean;
  call: boolean;
  priority: SupportPriority;
  /** Present only when the plan includes calls and a number is configured. */
  supportPhone?: string | null;
}

/** Whether the subscription is currently paid up; it follows from the plan's last day. */
export type SubscriptionStatus = 'ACTIVE' | 'INACTIVE';

export interface SubscriptionSummary {
  /** Null for an account nothing has been activated on yet (it signed up without paying): status is INACTIVE, never "ended". */
  plan: SubscriptionPlan | null;
  status: SubscriptionStatus;
  /** Null for accounts whose start was never recorded. */
  startedAt?: string | null;
  /** Null: no end date. */
  validUntil?: string | null;
}

export interface User {
  id: number;
  /** The customer-facing Account ID, e.g. CUS-42. */
  accountCode: string;
  email: string;
  displayName: string;
  organization?: string | null;
  phone?: string | null;
  /** Null until a plan has been paid for or activated by support. */
  plan: SubscriptionPlan | null;
  /** Set by support; shown, never edited here. */
  handoffPrefix: string;
  /** What the plan includes — only while the subscription is active (the backend decides). */
  support: SupportEntitlements;
  subscription: SubscriptionSummary;
  /** Whether the plan includes HandoffCheck (while the subscription is active) — the backend decides, from the plan alone. */
  handoffCheck: boolean;
}

/** What a customer may edit about themselves (not the Account ID, email, plan or prefix). */
export interface ProfileInput {
  displayName: string;
  organization?: string;
  phone?: string;
}

export interface ChangePasswordInput {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export interface ResetPasswordInput {
  token: string;
  newPassword: string;
  confirmPassword: string;
}

export interface RegisterInput {
  email: string;
  password: string;
  displayName: string;
  organization?: string;
  phone?: string;
}

export interface AuthResponse {
  token: string;
  expiresAt: string;
  user: User;
}

export interface HandoffItem {
  id: number;
  name: string;
  description?: string | null;
  sku?: string | null;
  serialNumber?: string | null;
  assetNumber?: string | null;
  unit?: string | null;
  condition?: ItemCondition | null;
  notes?: string | null;
  outgoing: string;
  returnedConfirmed: string;
  missing: string;
  returnedPending: string;
  remaining: string;
}

export interface ReturnLine {
  id: number;
  itemId: number;
  itemName: string;
  quantity: string;
  condition: ItemCondition;
  note?: string | null;
}

export interface ReturnEvent {
  id: number;
  occurredAt: string;
  enteredByType: ActorType;
  enteredByRef?: string | null;
  note?: string | null;
  confirmed: boolean;
  confirmedAt?: string | null;
  confirmedByRef?: string | null;
  createdAt: string;
  lines: ReturnLine[];
}

export interface Attachment {
  id: number;
  kind: AttachmentKind;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  uploadedByType: ActorType;
  uploadedByRef?: string | null;
  createdAt: string;
}

export interface AuditEvent {
  id: number;
  type: string;
  actorType: ActorType;
  actorRef?: string | null;
  message: string;
  at: string;
}

export interface HandoffSummary {
  id: number;
  publicCode: string;
  title: string;
  category?: string | null;
  status: HandoffStatus;
  senderName: string;
  recipientName: string;
  recipientEmail: string;
  itemCount: number;
  totalOutgoing: string;
  totalReturned: string;
  totalMissing: string;
  totalRemaining: string;
  overdue: boolean;
  outgoingAt?: string | null;
  dueAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

/** The reusable part of a handoff (the server decides what that is): what a duplicate starts from. */
export interface HandoffTemplate {
  title: string;
  category?: string | null;
  purpose?: string | null;
  senderName: string;
  senderOrganization?: string | null;
  items: { name: string; quantity: string; unit?: string | null }[];
}

export interface HandoffDetail {
  id: number;
  publicCode: string;
  title: string;
  purpose?: string | null;
  category?: string | null;
  status: HandoffStatus;
  senderName: string;
  senderOrganization?: string | null;
  recipientName: string;
  recipientEmail: string;
  recipientPhone?: string | null;
  acknowledgementName?: string | null;
  acknowledgedAt?: string | null;
  rejectionReason?: string | null;
  outgoingAt?: string | null;
  acceptanceAt?: string | null;
  dueAt?: string | null;
  createdAt: string;
  updatedAt: string;
  totalOutgoing: string;
  totalReturned: string;
  totalRemaining: string;
  totalMissing: string;
  fullyReturned: boolean;
  overdue: boolean;
  hasUnconfirmedMissing: boolean;
  missingConfirmationRequestedAt?: string | null;
  missingConfirmedAt?: string | null;
  missingConfirmedByName?: string | null;
  returnWaitRequestedAt?: string | null;
  returnWaitReason?: string | null;
  returnWaitRequestedByName?: string | null;
  availableActions: HandoffAction[];
  items: HandoffItem[];
  returns: ReturnEvent[];
  attachments: Attachment[];
  events: AuditEvent[];
}

export interface DashboardResponse {
  statusCounts: Partial<Record<HandoffStatus, number>>;
  overdueCount: number;
  total: number;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}

export interface ItemInput {
  name: string;
  description?: string;
  sku?: string;
  serialNumber?: string;
  assetNumber?: string;
  quantity: string;
  unit?: string;
  condition?: ItemCondition;
  notes?: string;
}

export interface CreateHandoffInput {
  title: string;
  purpose?: string;
  category?: string;
  senderName: string;
  senderOrganization?: string;
  recipientName: string;
  recipientEmail: string;
  recipientPhone?: string;
  dueAt?: string | null;
  items: ItemInput[];
}

export interface ReturnLineInput {
  itemId: number;
  quantity: string;
  condition?: ItemCondition;
  note?: string;
}

export interface CreateReturnInput {
  occurredAt?: string | null;
  note?: string;
  lines: ReturnLineInput[];
}

// Recipient view (public)
export interface RecipientView {
  publicCode: string;
  title: string;
  purpose?: string | null;
  category?: string | null;
  senderName: string;
  senderOrganization?: string | null;
  recipientName: string;
  status: HandoffStatus;
  awaitingResponse: boolean;
  canRecordReturns: boolean;
  acknowledgementName?: string | null;
  acknowledgedAt?: string | null;
  rejectionReason?: string | null;
  outgoingAt?: string | null;
  dueAt?: string | null;
  totalOutgoing: string;
  totalRemaining: string;
  missingToConfirm: boolean;
  missingConfirmedAt?: string | null;
  missingConfirmedByName?: string | null;
  returnWaitRequestedAt?: string | null;
  returnWaitReason?: string | null;
  returnWaitRequestedByName?: string | null;
  missingItems: { itemName: string; quantity: string }[];
  items: HandoffItem[];
  returns: {
    id: number;
    occurredAt: string;
    note?: string | null;
    confirmed: boolean;
    enteredByType: ActorType;
    lines: { itemName: string; quantity: string; condition: ItemCondition; note?: string | null }[];
  }[];
  attachments: { id: number; kind: AttachmentKind; originalFilename: string; contentType: string; sizeBytes: number }[];
}

// HandoffCheck
export interface DocLineInput { name: string; quantity?: string | null }
export interface DocFieldInput { label: string; value?: string | null }
export interface CompareInput {
  referenceLabel?: string;
  referenceLines: DocLineInput[];
  referenceFields?: DocFieldInput[];
  handoffId?: number | null;
  targetLabel?: string;
  targetLines?: DocLineInput[];
  targetFields?: DocFieldInput[];
}
export type MatchStatus = 'MATCH' | 'MISMATCH' | 'MISSING_IN_TARGET' | 'EXTRA_IN_TARGET';
export interface CompareResult {
  referenceLabel: string;
  targetLabel: string;
  summary: {
    totalLines: number; matched: number; mismatched: number;
    missingInTarget: number; extraInTarget: number; allMatch: boolean;
  };
  lines: {
    name: string; referenceQuantity?: string | null; targetQuantity?: string | null;
    difference?: string | null; status: MatchStatus;
  }[];
  fields: { label: string; referenceValue?: string | null; targetValue?: string | null; status: MatchStatus }[];
}

// Return-import mode: file lines matched to one handoff's items (nothing is persisted).
export type ImportMatchState = 'MATCHED' | 'AMBIGUOUS' | 'UNMATCHED';
export interface ReturnImportRow {
  importedName: string;
  importedQuantity: string;
  match: ImportMatchState;
  itemId?: number | null;
  itemName?: string | null;
  owedQuantity?: string | null;
}
export interface ReturnImportResult {
  fileName?: string | null;
  rows: ReturnImportRow[];
}
/** Return quantities (by handoff item id) carried from HandoffCheck to the return form. */
export type ReturnPrefill = Record<number, string>;

// Support tickets (customer side)
export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING_FOR_CUSTOMER' | 'RESOLVED' | 'CLOSED';
export type TicketCategory = 'GENERAL' | 'HANDOFF' | 'ACCOUNT' | 'BILLING' | 'TECHNICAL';

export interface TicketSummary {
  ticketCode: string;
  accountCode: string;
  customerName: string;
  customerEmail: string;
  customerPhone?: string | null;
  plan: SubscriptionPlan;
  priority: SupportPriority;
  contactMethod: 'TICKET' | 'MESSAGE';
  category: TicketCategory;
  subject: string;
  handoffReference?: string | null;
  status: TicketStatus;
  messageCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface TicketMessage {
  id: number;
  author: 'CUSTOMER' | 'SUPPORT';
  authorName: string;
  body: string;
  createdAt: string;
}

export interface TicketAttachment {
  id: number;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
}

export interface TicketDetail {
  ticket: TicketSummary;
  description: string;
  messages: TicketMessage[];
  attachments: TicketAttachment[];
}

export interface CreateTicketInput {
  subject: string;
  category: TicketCategory;
  description: string;
  handoffReference?: string;
  phone?: string;
}

export interface SupportMessageInput {
  subject: string;
  message: string;
  handoffReference?: string;
  file?: File | null;
}

/** An item list read from a file, to review before it is added to a new handoff. */
export interface ItemImportPreview {
  fileName?: string | null;
  lines: { name: string; quantity: string; duplicate: boolean }[];
  /** Rows that had something in them but could not become an item. */
  skippedRows: number;
}

export interface ApiError {
  status: number;
  code?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
}

export type JobType = 'RETURN_REMINDER' | 'OVERDUE_REMINDER' | 'MISSING_ITEM_REMINDER' | 'WEEKLY_SUMMARY';
export type JobStatus = 'SENT' | 'NOTHING_TO_REPORT' | 'FAILED';

/** One of the customer's jobs: how it is set up, its next runs, and how its latest run went. */
export interface Job {
  type: JobType;
  title: string;
  description: string;
  enabled: boolean;
  /** Six fields: second, minute, hour, day of month, month, day of week. */
  cronExpression: string;
  timezone: string;
  nextRunAt?: string | null;
  upcomingRuns: string[];
  lastRunAt?: string | null;
  lastStatus?: JobStatus | null;
  /** References of the handoffs the latest run told the customer about. */
  lastHandoffs: string[];
  lastDetail?: string | null;
}

export interface JobRun {
  type: JobType;
  title: string;
  status: JobStatus;
  handoffs: string[];
  runAt: string;
  message?: string | null;
}
