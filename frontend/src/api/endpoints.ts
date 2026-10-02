import { api, downloadBlob, downloadFile, postForFile, upload } from './client';
import type {
  AuthResponse, CompareInput, CompareResult, CreateHandoffInput, CreateReturnInput, CreateTicketInput, DocLineInput,
  DashboardResponse, HandoffDetail, HandoffStatus, HandoffSummary, Page, RecipientView, RegisterInput,
  ChangePasswordInput, HandoffTemplate, ItemImportPreview, PaymentReceipt, PlanPrice, ProfileInput, ResetPasswordInput, ReturnEvent, ReturnImportResult, SubscriptionPlan, SupportMessageInput, TicketAttachment,
  TicketDetail, TicketSummary, UpgradeOption, User, Attachment, AttachmentKind, Job, JobRun, JobType,
} from './types';

// --- Auth ---
export const authApi = {
  register: (body: RegisterInput) =>
    api<AuthResponse>('/auth/register', { method: 'POST', body, auth: false }),
  login: (body: { email: string; password: string }) =>
    api<AuthResponse>('/auth/login', { method: 'POST', body, auth: false }),
  /** The signed-in account as it is right now, subscription status included. */
  me: (signal?: AbortSignal) => api<User>('/auth/me', { signal }),
  updateProfile: (body: ProfileInput) => api<User>('/auth/me', { method: 'PUT', body }),
  /** Needs the current password; resolves to a fresh session (every other session has ended). */
  changePassword: (body: ChangePasswordInput) =>
    api<AuthResponse>('/auth/change-password', { method: 'POST', body }),
  /** Always succeeds for a well-formed email: it never says whether an account has it. */
  forgotPassword: (email: string) =>
    api<void>('/auth/forgot-password', { method: 'POST', body: { email }, auth: false }),
  /** From a reset link: no current password, the link's token is the proof. */
  resetPassword: (body: ResetPasswordInput) =>
    api<void>('/auth/reset-password', { method: 'POST', body, auth: false }),
};

// --- Customer jobs (the signed-in customer's own reminders and weekly summary) ---
export const jobApi = {
  list: () => api<Job[]>('/account/jobs'),
  updateSchedule: (type: JobType, body: { cronExpression: string; timezone: string }) =>
    api<Job>(`/account/jobs/${type}/schedule`, { method: 'PUT', body }),
  setEnabled: (type: JobType, enabled: boolean) =>
    api<Job>(`/account/jobs/${type}/enabled`, { method: 'PUT', body: { enabled } }),
  /** Runs one job now; its schedule and whether it is on stay as they are. */
  run: (type: JobType) => api<JobRun>(`/account/jobs/${type}/run`, { method: 'POST' }),
  /** Runs all four jobs once, now. */
  runAll: () => api<JobRun[]>('/account/jobs/run-all', { method: 'POST' }),
};

// --- Handoffs (owner) ---
export const handoffApi = {
  dashboard: () => api<DashboardResponse>('/handoffs/dashboard'),
  list: (params: { status?: HandoffStatus[]; q?: string; page?: number; size?: number; sort?: string }) => {
    const sp = new URLSearchParams();
    (params.status ?? []).forEach((s) => sp.append('status', s));
    if (params.q) sp.set('q', params.q);
    sp.set('page', String(params.page ?? 0));
    sp.set('size', String(params.size ?? 20));
    if (params.sort) sp.set('sort', params.sort);
    return api<Page<HandoffSummary>>(`/handoffs?${sp.toString()}`);
  },
  get: (id: number) => api<HandoffDetail>(`/handoffs/${id}`),
  /** What a duplicate of this handoff starts from. Creates nothing. */
  template: (id: number) => api<HandoffTemplate>(`/handoffs/${id}/template`),
  create: (body: CreateHandoffInput) => api<HandoffDetail>('/handoffs', { method: 'POST', body }),
  update: (id: number, body: Omit<CreateHandoffInput, 'items'>) =>
    api<HandoffDetail>(`/handoffs/${id}`, { method: 'PATCH', body }),
  replaceItems: (id: number, items: CreateHandoffInput['items']) =>
    api<HandoffDetail>(`/handoffs/${id}/items`, { method: 'PUT', body: { items } }),
  remove: (id: number) => api<void>(`/handoffs/${id}`, { method: 'DELETE' }),
  submit: (id: number) => api<HandoffDetail>(`/handoffs/${id}/submit`, { method: 'POST' }),
  resendLink: (id: number) => api<HandoffDetail>(`/handoffs/${id}/resend-link`, { method: 'POST' }),
  cancel: (id: number, reason?: string) =>
    api<HandoffDetail>(`/handoffs/${id}/cancel`, { method: 'POST', body: { reason } }),
  dispute: (id: number, reason?: string) =>
    api<HandoffDetail>(`/handoffs/${id}/dispute`, { method: 'POST', body: { reason } }),
  close: (id: number, reason?: string) =>
    api<HandoffDetail>(`/handoffs/${id}/close`, { method: 'POST', body: { reason } }),
  requestMissingConfirmation: (id: number) =>
    api<HandoffDetail>(`/handoffs/${id}/request-missing-confirmation`, { method: 'POST' }),
  /** The Proof-of-Handoff PDF, generated on demand by the backend. */
  downloadPdf: async (id: number) => {
    const { blob, filename } = await downloadFile(`/handoffs/${id}/pdf`);
    return { blob, filename: filename ?? 'Proof-of-Handoff.pdf' };
  },
  /** Emails the PDF; an empty `to` means the handoff's own recipient. */
  emailPdf: (id: number, to?: string) =>
    api<{ sentTo: string; delivered: boolean }>(`/handoffs/${id}/email-pdf`, { method: 'POST', body: { to } }),
};

// --- Returns (owner) ---
export const returnApi = {
  create: (handoffId: number, body: CreateReturnInput) =>
    api<ReturnEvent>(`/handoffs/${handoffId}/returns`, { method: 'POST', body }),
  confirm: (handoffId: number, returnId: number) =>
    api<ReturnEvent>(`/handoffs/${handoffId}/returns/${returnId}/confirm`, { method: 'POST' }),
};

// --- Attachments (owner) ---
export const attachmentApi = {
  list: (handoffId: number) => api<Attachment[]>(`/handoffs/${handoffId}/attachments`),
  upload: (handoffId: number, file: File, kind: AttachmentKind) => {
    const form = new FormData();
    form.append('file', file);
    form.append('kind', kind);
    return upload<Attachment>(`/handoffs/${handoffId}/attachments`, form);
  },
  remove: (handoffId: number, attachmentId: number) =>
    api<void>(`/handoffs/${handoffId}/attachments/${attachmentId}`, { method: 'DELETE' }),
};

// --- Recipient (public, token-scoped) ---
export const recipientApi = {
  view: (token: string) => api<RecipientView>(`/r/${token}`, { auth: false }),
  accept: (token: string, acknowledgementName: string) =>
    api<RecipientView>(`/r/${token}/accept`, { method: 'POST', auth: false, body: { acknowledgementName } }),
  reject: (token: string, acknowledgementName: string, reason: string) =>
    api<RecipientView>(`/r/${token}/reject`, { method: 'POST', auth: false, body: { acknowledgementName, reason } }),
  confirmMissing: (token: string, acknowledgementName: string) =>
    api<RecipientView>(`/r/${token}/confirm-missing`,
      { method: 'POST', auth: false, body: { acknowledgementName } }),
  requestReturnWait: (token: string, acknowledgementName: string, reason: string) =>
    api<RecipientView>(`/r/${token}/request-return-wait`,
      { method: 'POST', auth: false, body: { acknowledgementName, reason } }),
};

// --- Support tickets (customer side; the backend only allows plans that include tickets) ---
export const ticketApi = {
  list: (page = 0, size = 10) => api<Page<TicketSummary>>(`/tickets?page=${page}&size=${size}`),
  get: (code: string) => api<TicketDetail>(`/tickets/${code}`),
  create: (body: CreateTicketInput) => api<TicketDetail>('/tickets', { method: 'POST', body }),
  reply: (code: string, body: string) =>
    api<TicketDetail>(`/tickets/${code}/messages`, { method: 'POST', body: { body } }),
  upload: (code: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return upload<TicketAttachment>(`/tickets/${code}/attachments`, form);
  },
  download: (code: string, attachmentId: number) =>
    downloadBlob(`/tickets/${code}/attachments/${attachmentId}/content`),
};

// --- Support messages (customer side; any plan with Contact Support) ---
export const supportMessageApi = {
  /** Sends a message (and an optional file) in one request; resolves to the reference to quote. */
  send: (input: SupportMessageInput) => {
    const form = new FormData();
    form.append('subject', input.subject);
    form.append('message', input.message);
    if (input.handoffReference) form.append('handoffReference', input.handoffReference);
    if (input.file) form.append('file', input.file);
    return upload<{ reference: string }>('/support-messages', form);
  },
};

// --- Payments (the backend decides the plan from a payment it recorded; the client only carries the token) ---
export const paymentApi = {
  /**
   * Demo provider (no money): pays for a plan with a demo test card, before there is an account. Refused unless
   * the backend enables it; a card that is not a demo test card is refused (and never kept).
   */
  payDemo: (plan: SubscriptionPlan, card: { cardNumber: string; expiry: string; cvc: string }) =>
    api<PaymentReceipt>('/public/payments/demo', { method: 'POST', body: { plan, ...card }, auth: false }),
  /** The signed-in customer applies a paid-for plan to their own account; resolves to the account as it now is. */
  redeem: (token: string) => api<User>('/payments/redeem', { method: 'POST', body: { token } }),
  /** The plans the signed-in customer can move up to from their active plan, each priced as what is left to pay. */
  upgradeOptions: () => api<UpgradeOption[]>('/payments/upgrades'),
  /**
   * The signed-in customer pays the difference to move up to a dearer plan and has it at once; resolves to the account as it now is.
   * `expectedAmount` is what they were shown, not a price: the backend works the price out and refuses (409) if it is no longer that.
   */
  upgrade: (plan: SubscriptionPlan, expectedAmount: number, card: { cardNumber: string; expiry: string; cvc: string }) =>
    api<User>('/payments/upgrade', { method: 'POST', body: { expectedAmount, payment: { plan, ...card } } }),
};

// --- Public (no sign-in) ---
export const publicApi = {
  /** The general contact address shown on the public site. */
  contact: () => api<{ email: string }>('/public/contact', { auth: false }),
  /** The plans' list prices — the one place prices live; the pricing page only displays them. */
  plans: () => api<PlanPrice[]>('/public/plans', { auth: false }),
};

// --- HandoffCheck ---
export const documentCheckApi = {
  compare: (body: CompareInput) => api<CompareResult>('/handoff-check', { method: 'POST', body }),
  /**
   * A finished standalone comparison as a PDF or CSV. It sends the same request the comparison was made from, so
   * the file is the comparison that was shown. Nothing is stored.
   */
  exportComparison: (comparison: CompareInput, fileAName: string | undefined, fileBName: string | undefined,
                     format: 'PDF' | 'CSV') =>
    postForFile(`/handoff-check/export?format=${format}`, { fileAName, fileBName, comparison }),
  /** New Handoff: read an item list (CSV or Excel) into rows to review. Nothing is stored or created. */
  importItems: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return upload<ItemImportPreview>('/handoff-check/import-items', form);
  },
  /** Standalone mode: read one file into editable item/quantity lines (nothing is stored). */
  extract: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return upload<DocLineInput[]>('/handoff-check/extract', form);
  },
  returnImport: (handoffId: number, file: File) => {
    const form = new FormData();
    form.append('handoffId', String(handoffId));
    form.append('file', file);
    return upload<ReturnImportResult>('/handoff-check/return-import', form);
  },
};
