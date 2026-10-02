// Types mirroring the backend's support API. The portal is a separate app and shares no code with the customer app.

export type StaffRole = 'ADMIN' | 'MANAGER' | 'TICKET_AGENT';

/** What a role allows. The backend decides and enforces; the portal only shows the controls a member may use. */
export type Permission =
  | 'VIEW_DASHBOARD' | 'WORK_TICKETS' | 'VIEW_CUSTOMERS' | 'MANAGE_CUSTOMERS' | 'MANAGE_STAFF' | 'VIEW_AUDIT';
export type SubscriptionPlan = 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'YEARLY';
/** A plan's list price (the one source: the backend). One payment of `amount` covers `months` months. */
export interface PlanPrice {
  plan: SubscriptionPlan;
  months: number;
  amount: number;
  currency: string;
}

/** How urgently the desk attends to a customer — derived from their plan by the backend, never set by hand. */
export type SupportPriority = 'NORMAL' | 'PRIORITY' | 'HIGHEST';

/** What a plan includes from support, as the backend derives it (read-only here). */
export interface SupportEntitlements {
  contactSupport: boolean;
  message: boolean;
  ticket: boolean;
  call: boolean;
  priority: SupportPriority;
}

export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'WAITING_FOR_CUSTOMER' | 'RESOLVED' | 'CLOSED';
export type TicketCategory = 'GENERAL' | 'HANDOFF' | 'ACCOUNT' | 'BILLING' | 'TECHNICAL';

/** A member of the support team — a separate identity from any customer. */
export interface Staff {
  staffCode: string;
  name: string;
  email: string;
  role: StaffRole;
  permissions: Permission[];
}

/** A staff member as an administrator sees them in the staff list. */
export interface StaffSummary {
  staffCode: string;
  name: string;
  email: string;
  role: StaffRole;
  active: boolean;
  createdAt: string;
}

export interface StaffAuthResponse {
  token: string;
  expiresAt: string;
  staff: Staff;
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

export interface Dashboard {
  open: number;
  inProgress: number;
  waitingForCustomer: number;
  /** A customer metric: absent for staff who may not see customers (ticket agents). */
  priorityCustomers?: number | null;
}

export interface CustomerSummary {
  accountCode: string;
  name: string;
  email: string;
  phone?: string | null;
  plan: SubscriptionPlan;
  handoffPrefix: string;
  createdAt: string;
}

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

export type AuditType =
  | 'PLAN_CHANGED' | 'HANDOFF_PREFIX_CHANGED'
  | 'STAFF_CREATED' | 'STAFF_DEACTIVATED' | 'STAFF_REACTIVATED' | 'STAFF_ROLE_CHANGED';

/** One recorded change (read-only history): to a customer (Account ID) or to a staff member (Staff ID). */
export interface AuditEvent {
  type: AuditType;
  /** The Account ID or Staff ID the change was made to. */
  subjectCode: string;
  previousValue?: string | null;
  newValue: string;
  staffCode: string;
  staffName: string;
  reason?: string | null;
  at: string;
}

export interface CustomerProfile {
  customer: CustomerSummary;
  entitlements: SupportEntitlements;
  nextHandoffReference: string;
  openTickets: number;
  recentTickets: TicketSummary[];
  recentChanges: AuditEvent[];
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

export interface ApiError {
  status: number;
  code?: string;
  detail?: string;
  errors?: { field: string; message: string }[];
}
