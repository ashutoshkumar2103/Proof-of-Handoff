import { api, downloadFile, upload } from './client';
import type {
  AuthResponse, CompareInput, CompareResult, CreateHandoffInput, CreateReturnInput, DocLineInput,
  DashboardResponse, HandoffDetail, HandoffStatus, HandoffSummary, Page, RecipientView,
  ReturnEvent, ReturnImportResult, User, Attachment, AttachmentKind,
} from './types';

// --- Auth ---
export const authApi = {
  register: (body: { email: string; password: string; displayName: string; organization?: string }) =>
    api<AuthResponse>('/auth/register', { method: 'POST', body, auth: false }),
  login: (body: { email: string; password: string }) =>
    api<AuthResponse>('/auth/login', { method: 'POST', body, auth: false }),
  me: () => api<User>('/auth/me'),
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

// --- HandoffCheck ---
export const documentCheckApi = {
  compare: (body: CompareInput) => api<CompareResult>('/handoff-check', { method: 'POST', body }),
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
