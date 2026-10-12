// The part of the HandOffly API contract this app uses (the backend's DTOs). Defined here because the apps share no code.

export type SubscriptionPlan = 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'YEARLY';
export type SubscriptionStatus = 'ACTIVE' | 'INACTIVE';

export type HandoffStatus =
  | 'DRAFT' | 'OUTGOING_SENT' | 'AWAITING_RECIPIENT' | 'ACTIVE_WITH_RECIPIENT' | 'RETURN_PENDING'
  | 'PARTIALLY_RETURNED' | 'FULLY_RETURNED' | 'CLOSED' | 'REJECTED' | 'CANCELLED' | 'DISPUTED' | 'OVERDUE';

export type ItemCondition = 'GOOD' | 'DAMAGED' | 'MISSING' | 'OTHER' | 'RECOVERED';
export type ActorType = 'USER' | 'RECIPIENT' | 'SYSTEM';
export type AttachmentKind = 'EVIDENCE' | 'REFERENCE_DOCUMENT';

/** What the backend says the signed-in customer may do with a handoff right now. */
export type HandoffAction =
  | 'EDIT' | 'DELETE' | 'SUBMIT' | 'RESEND_LINK' | 'CANCEL'
  | 'RECORD_RETURN' | 'CONFIRM_RETURN' | 'REQUEST_MISSING_CONFIRMATION'
  | 'DISPUTE' | 'CLOSE' | 'ADD_ATTACHMENT';

export interface ApiError {
  status: number;
  code?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
}

export interface SubscriptionSummary {
  /** Null for an account nothing has been activated on yet. */
  plan: SubscriptionPlan | null;
  status: SubscriptionStatus;
  startedAt?: string | null;
  validUntil?: string | null;
}

export interface User {
  id: number;
  accountCode: string;
  email: string;
  displayName: string;
  organization?: string | null;
  phone?: string | null;
  plan: SubscriptionPlan | null;
  handoffPrefix: string;
  subscription: SubscriptionSummary;
  /** Whether the plan includes HandoffCheck and the AI Report Assistant (while the subscription is active): the backend decides, from the plan alone. */
  handoffCheck: boolean;
}

export interface AuthResponse {
  token: string;
  expiresAt: string;
  user: User;
}

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

export interface HandoffItem {
  id: number;
  name: string;
  unit?: string | null;
  condition?: ItemCondition | null;
  serialNumber?: string | null;
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
  lines: ReturnLine[];
}

export interface Attachment {
  id: number;
  kind: AttachmentKind;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
}

export interface AuditEvent {
  id: number;
  actorType: ActorType;
  actorRef?: string | null;
  message: string;
  at: string;
}

export interface HandoffSummary {
  id: number;
  /** Issued when the draft is first submitted: null for a draft that has not been submitted yet. */
  publicCode: string | null;
  title: string;
  status: HandoffStatus;
  recipientName: string;
  itemCount: number;
  totalOutgoing: string;
  totalReturned: string;
  totalMissing: string;
  totalRemaining: string;
  overdue: boolean;
  /** Set once it has been sent: a draft has given nothing. */
  outgoingAt?: string | null;
  dueAt?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface HandoffDetail {
  id: number;
  /** Issued when the draft is first submitted: null for a draft that has not been submitted yet. */
  publicCode: string | null;
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
  overdue: boolean;
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
  quantity: string;
  unit?: string;
  condition?: ItemCondition;
  serialNumber?: string;
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

/** The reusable part of a handoff (the backend decides what that is): what a duplicate starts from. */
export interface HandoffTemplate {
  title: string;
  category?: string | null;
  purpose?: string | null;
  senderName: string;
  senderOrganization?: string | null;
  items: { name: string; quantity: string; unit?: string | null }[];
}

export interface ReturnLineInput {
  itemId: number;
  quantity: string;
  condition?: ItemCondition;
  note?: string;
}

export interface CreateReturnInput {
  note?: string;
  lines: ReturnLineInput[];
}

/** What the recipient's secure link shows (public, scoped to exactly one handoff). */
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
    lines: { itemName: string; quantity: string; condition: ItemCondition; note?: string | null }[];
  }[];
  attachments: (Attachment)[];
}

/** Totals for every handoff created in the period, whatever its status. `open` and `overdue` are as of now. */
export interface ReportSummary {
  created: number;
  closed: number;
  open: number;
  overdue: number;
  itemsGiven: string;
  itemsReturned: string;
  itemsMissing: string;
  itemsStillOut: string;
  itemsRejected: string;
  itemsCancelled: string;
}

/** The days a report covers, as the backend applied them. */
export interface ReportPeriod {
  from: string;
  to: string;
  timezone: string;
}

export interface HandoffReport {
  period: ReportPeriod;
  summary: ReportSummary;
  handoffs: Page<HandoffSummary>;
}

/** What a report is asked: the days (yyyy-mm-dd) and the zone they are days in, and optionally one status or only the overdue. */
export interface ReportQuery {
  from: string;
  to: string;
  timezone: string;
  status?: HandoffStatus;
  overdue?: boolean;
  sort?: string;
}

export interface ReportAssistantSuggestion { intent: string; question: string }

/** The Report Assistant's answer; UNAVAILABLE means the AI was not available (the report itself is fine). */
export interface ReportAssistantAnswer {
  kind: 'ANSWER' | 'UNSUPPORTED' | 'UNAVAILABLE';
  answer: string;
  basis?: string | null;
  value?: number | null;
  entries: { reference: string; title: string; recipient: string; detail: string }[];
  entryTotal: number;
  canRetry: boolean;
}
