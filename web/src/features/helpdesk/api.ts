import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import type { DmsDocument } from '@/features/dms/api';

/** Helpdesk & correspondence (ADR-S045) client. */

export type Priority = 'low' | 'normal' | 'high' | 'urgent';
export type Channel = 'email' | 'portal' | 'phone' | 'walk_in' | 'whatsapp' | 'letter' | 'other';

export interface HdStage {
  id: string;
  teamId: string;
  name: string;
  sequence: number;
  isClosed: boolean;
  folded: boolean;
}

export interface HdTeam {
  id: string;
  code: string;
  name: string;
  description: string | null;
  email: string | null;
  assignment: 'manual' | 'round_robin' | 'balanced';
  leaderId: string | null;
  isActive: boolean;
  memberIds: string[];
  stages: HdStage[];
  openCount?: number;
}

export interface HdMessage {
  id: string;
  kind: 'note' | 'reply' | 'inbound' | 'system';
  body: string;
  author: string | null;
  toEmail: string | null;
  emailSent: boolean;
  createdAt: string;
}

export interface HdTicket {
  id: string;
  number: string;
  teamId: string;
  teamName: string;
  stageId: string;
  stageName: string;
  stageClosed: boolean;
  typeId: string | null;
  typeName: string | null;
  priority: Priority;
  channel: Channel;
  direction: 'inbound' | 'outbound';
  partnerId: string | null;
  partnerName: string | null;
  contactName: string | null;
  contactEmail: string | null;
  contactPhone: string | null;
  subject: string;
  description: string | null;
  assigneeId: string | null;
  assigneeName: string | null;
  tags: string[];
  relatedResType: string | null;
  relatedResId: string | null;
  related?: { label: string; webPath: string | null; typeLabel: string } | null;
  parentTicketId: string | null;
  resolution: string | null;
  firstResponseAt: string | null;
  closedAt: string | null;
  letterNumber: string | null;
  externalRef: string | null;
  ourRef: string | null;
  letterDate: string | null;
  receivedAt: string | null;
  sentAt: string | null;
  dispatchMethod: string | null;
  trackingNumber: string | null;
  confidentiality: 'normal' | 'confidential' | 'secret';
  replyDueOn: string | null;
  repliedByTicketId: string | null;
  replyOverdue: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
  messages?: HdMessage[];
  attachments?: DmsDocument[];
  stages?: HdStage[];
  parent?: { id: string; number: string; letterNumber: string | null; subject: string } | null;
  children?: Array<{ id: string; number: string; letterNumber: string | null; subject: string; direction: string }>;
}

export interface HdPage {
  data: HdTicket[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
}

export interface HdTemplate {
  id: string;
  code: string;
  name: string;
  subject: string | null;
  body: string;
  closingLine: string | null;
  signatureName: string | null;
  signatureTitle: string | null;
  isActive: boolean;
}

export interface HdType {
  id: string;
  name: string;
  isActive: boolean;
}

export interface HdDashboard {
  open: number;
  unassigned: number;
  mine: number;
  urgent: number;
  closedLast30Days: number;
  lettersAwaitingReply: number;
  lettersOverdue: number;
  avgFirstResponseHours: number | null;
  byChannel: Array<{ channel: string; count: number }>;
  byTeam: Array<{ id: string; name: string; open: number }>;
  byPriority: Array<{ priority: string; count: number }>;
  recent: HdTicket[];
}

const KEY = ['helpdesk'] as const;

function useHdMutation<TVars, TResult>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEY });
      qc.invalidateQueries({ queryKey: ['dms'] });
    },
  });
}

export const useTeams = () =>
  useQuery({ queryKey: [...KEY, 'teams'], queryFn: async () => (await api.get<HdTeam[]>('/helpdesk/teams')).data });

export const useTicketTypes = () =>
  useQuery({ queryKey: [...KEY, 'types'], queryFn: async () => (await api.get<HdType[]>('/helpdesk/types')).data });

export const useLetterTemplates = () =>
  useQuery({
    queryKey: [...KEY, 'templates'],
    queryFn: async () => (await api.get<HdTemplate[]>('/helpdesk/letter-templates')).data,
  });

export const useTickets = (params: Record<string, string | number | boolean | undefined>) =>
  useQuery({
    queryKey: [...KEY, 'tickets', params],
    queryFn: async () => (await api.get<HdPage>('/helpdesk/tickets', { params })).data,
  });

export const useLetters = (params: Record<string, string | number | boolean | undefined>) =>
  useQuery({
    queryKey: [...KEY, 'letters', params],
    queryFn: async () => (await api.get<HdPage>('/helpdesk/letters', { params })).data,
  });

export const useTicket = (id?: string) =>
  useQuery({
    queryKey: [...KEY, 'ticket', id],
    enabled: !!id,
    queryFn: async () => (await api.get<HdTicket>(`/helpdesk/tickets/${id}`)).data,
  });

export const useHelpdeskDashboard = () =>
  useQuery({ queryKey: [...KEY, 'dashboard'], queryFn: async () => (await api.get<HdDashboard>('/helpdesk/dashboard')).data });

export const useCreateTicket = () =>
  useHdMutation(async (body: Record<string, unknown>) => (await api.post<HdTicket>('/helpdesk/tickets', body)).data);

export const useUpdateTicket = () =>
  useHdMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<HdTicket>(`/helpdesk/tickets/${id}`, body)).data);

export const useAssignTicket = () =>
  useHdMutation(async ({ id, assigneeId }: { id: string; assigneeId: string | null }) =>
    (await api.post<HdTicket>(`/helpdesk/tickets/${id}/assign`, { assigneeId })).data);

export const useMoveStage = () =>
  useHdMutation(async ({ id, stageId, resolution }: { id: string; stageId: string; resolution?: string }) =>
    (await api.post<HdTicket>(`/helpdesk/tickets/${id}/stage`, { stageId, resolution })).data);

export const useCloseTicket = () =>
  useHdMutation(async ({ id, resolution }: { id: string; resolution?: string }) =>
    (await api.post<HdTicket>(`/helpdesk/tickets/${id}/close`, { resolution })).data);

export const useReopenTicket = () =>
  useHdMutation(async (id: string) => (await api.post<HdTicket>(`/helpdesk/tickets/${id}/reopen`)).data);

export const useAddMessage = () =>
  useHdMutation(async ({ id, kind, body, sendEmail }: { id: string; kind: string; body: string; sendEmail?: boolean }) =>
    (await api.post<HdTicket>(`/helpdesk/tickets/${id}/messages`, { kind, body, sendEmail })).data);

export const useRegisterIncomingLetter = () =>
  useHdMutation(async ({ fields, files }: { fields: Record<string, string | undefined>; files: File[] }) => {
    const fd = new FormData();
    Object.entries(fields).forEach(([k, v]) => v && fd.append(k, v));
    files.forEach((f) => fd.append('file', f));
    return (await api.post<HdTicket>('/helpdesk/letters/incoming', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }, timeout: 300_000,
    })).data;
  });

export interface LetterBody {
  templateId?: string;
  subject?: string;
  body?: string;
  closingLine?: string;
  signatureName?: string;
  signatureTitle?: string;
}

export const useCreateOutgoingLetter = () =>
  useHdMutation(async ({ ticket, letter }: { ticket: Record<string, unknown>; letter: LetterBody }) =>
    (await api.post<HdTicket>('/helpdesk/letters/outgoing', { ticket, letter })).data);

export const useReplyLetter = () =>
  useHdMutation(async ({ id, letter }: { id: string; letter: LetterBody }) =>
    (await api.post<HdTicket>(`/helpdesk/tickets/${id}/reply-letter`, { letter })).data);

export const useRenderLetter = () =>
  useHdMutation(async ({ id, letter }: { id: string; letter: LetterBody }) =>
    (await api.post<HdTicket>(`/helpdesk/tickets/${id}/render-letter`, letter)).data);

export const useDispatchLetter = () =>
  useHdMutation(async ({ id, method, trackingNumber, sentAt }: { id: string; method: string; trackingNumber?: string; sentAt?: string }) =>
    (await api.post<HdTicket>(`/helpdesk/tickets/${id}/dispatch`, { method, trackingNumber, sentAt })).data);

export const useCreateTeam = () =>
  useHdMutation(async (body: Record<string, unknown>) => (await api.post<HdTeam>('/helpdesk/teams', body)).data);

export const useUpdateTeam = () =>
  useHdMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<HdTeam>(`/helpdesk/teams/${id}`, body)).data);

export const useCreateStage = () =>
  useHdMutation(async ({ teamId, body }: { teamId: string; body: Record<string, unknown> }) =>
    (await api.post<HdStage>(`/helpdesk/teams/${teamId}/stages`, body)).data);

export const useUpdateStage = () =>
  useHdMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<HdStage>(`/helpdesk/stages/${id}`, body)).data);

export const useDeleteStage = () =>
  useHdMutation(async (id: string) => (await api.delete(`/helpdesk/stages/${id}`)).data);

export const useCreateType = () =>
  useHdMutation(async (name: string) => (await api.post<HdType>('/helpdesk/types', { name })).data);

export const useCreateTemplate = () =>
  useHdMutation(async (body: Record<string, unknown>) => (await api.post<HdTemplate>('/helpdesk/letter-templates', body)).data);

export const useUpdateTemplate = () =>
  useHdMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<HdTemplate>(`/helpdesk/letter-templates/${id}`, body)).data);

export const PRIORITY_CLASS: Record<Priority, string> = {
  low: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
  normal: 'bg-sky-100 text-sky-800 dark:bg-sky-900/50 dark:text-sky-200',
  high: 'bg-amber-100 text-amber-800 dark:bg-amber-900/50 dark:text-amber-200',
  urgent: 'bg-red-100 text-red-800 dark:bg-red-900/50 dark:text-red-200',
};

export const CHANNELS: Channel[] = ['portal', 'email', 'phone', 'walk_in', 'whatsapp', 'letter', 'other'];
export const DISPATCH_METHODS = ['post', 'registered_post', 'courier', 'hand', 'email', 'fax'] as const;
