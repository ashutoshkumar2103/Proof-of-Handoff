// Types mirroring the backend API DTOs. Kept in one place as the frontend's contract.

export type Role = 'USER' | 'ADMIN';

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

export interface User {
  id: number;
  email: string;
  displayName: string;
  organization?: string | null;
  role: Role;
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
  lines: { name: string; referenceQuantity?: string | null; targetQuantity?: string | null; status: MatchStatus }[];
  fields: { label: string; referenceValue?: string | null; targetValue?: string | null; status: MatchStatus }[];
}

export interface ApiError {
  status: number;
  code?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
}
