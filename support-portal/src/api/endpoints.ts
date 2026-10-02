import { api, downloadBlob } from './client';
import type {
  AuditEvent, CustomerProfile, CustomerSummary, Dashboard, Page, PlanPrice, Staff, StaffAuthResponse, StaffRole,
  StaffSummary, SubscriptionPlan, TicketDetail, TicketStatus, TicketSummary,
} from './types';

// Support staff have their own identity and their own login — customer credentials do not work here.
export const authApi = {
  login: (body: { email: string; password: string }) =>
    api<StaffAuthResponse>('/support/auth/login', { method: 'POST', body, auth: false }),
  me: () => api<Staff>('/support/auth/me'),
};

export interface TicketFilters {
  /** Empty or missing = every status. */
  status?: TicketStatus[];
  priorityOnly?: boolean;
  accountCode?: string;
  page?: number;
  size?: number;
}

export interface StaffFilters {
  q?: string;
  role?: StaffRole;
  active?: boolean;
  page?: number;
}

/** Staff management — administrators only (the backend refuses everyone else). */
export const staffApi = {
  list: (f: StaffFilters) => {
    const sp = new URLSearchParams({ page: String(f.page ?? 0), size: '20' });
    if (f.q?.trim()) sp.set('q', f.q.trim());
    if (f.role) sp.set('role', f.role);
    if (f.active !== undefined) sp.set('active', String(f.active));
    return api<Page<StaffSummary>>(`/support/staff?${sp}`);
  },
  create: (body: { name: string; email: string; password: string; role: StaffRole }) =>
    api<StaffSummary>('/support/staff', { method: 'POST', body }),
  /** `fromRole` is the role the administrator was looking at; a change on a stale view is refused. */
  changeRole: (staffCode: string, fromRole: StaffRole, toRole: StaffRole, reason?: string) =>
    api<StaffSummary>(`/support/staff/${staffCode}/role`,
      { method: 'PUT', body: { fromRole, toRole, reason: reason?.trim() || undefined } }),
  setActive: (staffCode: string, active: boolean, reason?: string) =>
    api<StaffSummary>(`/support/staff/${staffCode}/active`,
      { method: 'PUT', body: { active, reason: reason?.trim() || undefined } }),
  /** The whole audit trail, newest first. */
  audit: (page = 0) => api<Page<AuditEvent>>(`/support/audit?page=${page}&size=20`),
};

export const supportApi = {
  dashboard: () => api<Dashboard>('/support/dashboard'),
  /** What each plan costs — so staff know what to charge when they change a customer's plan. */
  planPrices: () => api<PlanPrice[]>('/public/plans', { auth: false }),

  customers: (q: string, page = 0) => {
    const sp = new URLSearchParams({ page: String(page), size: '20' });
    if (q.trim()) sp.set('q', q.trim());
    return api<Page<CustomerSummary>>(`/support/customers?${sp}`);
  },
  customer: (accountCode: string) => api<CustomerProfile>(`/support/customers/${accountCode}`),
  /** The prefix that NEW handoffs of this customer get. */
  setPrefix: (accountCode: string, prefix: string) =>
    api<CustomerProfile>(`/support/customers/${accountCode}/prefix`, { method: 'PUT', body: { prefix } }),
  /** An explicit, confirmed plan change; entitlements follow the plan. `fromPlan` is the plan the staff member was looking at. */
  changePlan: (accountCode: string, fromPlan: SubscriptionPlan, toPlan: SubscriptionPlan, reason?: string) =>
    api<CustomerProfile>(`/support/customers/${accountCode}/plan`,
      { method: 'PUT', body: { fromPlan, toPlan, reason: reason?.trim() || undefined } }),

  tickets: (f: TicketFilters) => {
    const sp = new URLSearchParams({ page: String(f.page ?? 0), size: String(f.size ?? 20) });
    (f.status ?? []).forEach((s) => sp.append('status', s));
    if (f.priorityOnly) sp.set('priorityOnly', 'true');
    if (f.accountCode) sp.set('accountCode', f.accountCode);
    return api<Page<TicketSummary>>(`/support/tickets?${sp}`);
  },
  ticket: (code: string) => api<TicketDetail>(`/support/tickets/${code}`),
  setStatus: (code: string, status: TicketStatus) =>
    api<TicketDetail>(`/support/tickets/${code}/status`, { method: 'PUT', body: { status } }),
  reply: (code: string, body: string) =>
    api<TicketDetail>(`/support/tickets/${code}/messages`, { method: 'POST', body: { body } }),
  downloadAttachment: (code: string, attachmentId: number) =>
    downloadBlob(`/support/tickets/${code}/attachments/${attachmentId}/content`),
};
