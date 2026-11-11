import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { apiMessage, notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import type {
  BrowseChannel,
  ConversationDetail,
  ConversationItem,
  DepartmentSyncResult,
  DirectoryResponse,
  DiscussCounters,
  DiscussMessage,
  DiscussVisibility,
  InboxResponse,
  MessageHit,
  MessagesPage,
  NotifyLevel,
  PostMessageInput,
  RecordThread,
  SharedAttachment,
  SidebarResponse,
  UploadedAttachment,
} from './discuss-types';

const BASE = '/communication/discuss';

/** Query keys — everything lives under ['communication', 'discuss', ...]. */
export const DK = {
  all: ['communication', 'discuss'] as const,
  sidebar: () => [...DK.all, 'sidebar'] as const,
  counters: () => [...DK.all, 'counters'] as const,
  directory: () => [...DK.all, 'directory'] as const,
  presence: () => [...DK.all, 'presence'] as const,
  channels: (archived: boolean) => [...DK.all, 'channels', archived] as const,
  detail: (id: string | undefined) => [...DK.all, 'conversation', id] as const,
  messages: (id: string | undefined) => [...DK.all, 'messages', id] as const,
  pinned: (id: string | undefined) => [...DK.all, 'pinned', id] as const,
  attachments: (id: string | undefined) => [...DK.all, 'attachments', id] as const,
  inbox: () => [...DK.all, 'inbox'] as const,
  starred: () => [...DK.all, 'starred'] as const,
  search: (q: string, conversationId?: string) => [...DK.all, 'search', q, conversationId ?? null] as const,
  record: (type: string, id: string) => [...DK.all, 'record', type, id] as const,
};

function uuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return (crypto as { randomUUID(): string }).randomUUID();
  }
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

const onError = (fallback: string) => (e: unknown) => notify.error(apiMessage(e, fallback));

/** Refresh everything that lists conversations or counts unread. */
export function invalidateLists(qc: QueryClient) {
  qc.invalidateQueries({ queryKey: DK.sidebar() });
  qc.invalidateQueries({ queryKey: DK.counters() });
}

/* ── Reads ───────────────────────────────────────────────────────────────── */

export function useSidebar() {
  return useQuery({
    queryKey: DK.sidebar(),
    queryFn: async () => (await api.get<SidebarResponse>(`${BASE}/sidebar`)).data,
    refetchInterval: 60_000,
  });
}

export function useDiscussCounters(enabled = true) {
  return useQuery({
    queryKey: DK.counters(),
    queryFn: async () => (await api.get<DiscussCounters>(`${BASE}/counters`)).data,
    refetchInterval: 30_000,
    retry: false,
    enabled,
  });
}

export function useDirectory() {
  return useQuery({
    queryKey: DK.directory(),
    queryFn: async () => (await api.get<DirectoryResponse>(`${BASE}/directory`)).data,
    staleTime: 60_000,
  });
}

export function usePresence() {
  return useQuery({
    queryKey: DK.presence(),
    queryFn: async () => (await api.get<{ online: string[] }>(`${BASE}/presence`)).data,
    refetchInterval: 120_000,
  });
}

export function useBrowseChannels(archived: boolean, enabled = true) {
  return useQuery({
    queryKey: DK.channels(archived),
    queryFn: async () =>
      (await api.get<BrowseChannel[]>(`${BASE}/channels`, { params: archived ? { archived: true } : {} })).data,
    enabled,
  });
}

export function useConversationDetail(id: string | undefined) {
  return useQuery({
    queryKey: DK.detail(id),
    queryFn: async () => (await api.get<ConversationDetail>(`${BASE}/conversations/${id}`)).data,
    enabled: !!id,
    retry: false,
  });
}

/** The latest page; older pages are prepended into the same cache entry by `fetchOlderMessages`. */
export function useThreadMessages(id: string | undefined) {
  return useQuery({
    queryKey: DK.messages(id),
    queryFn: async () =>
      (await api.get<MessagesPage>(`${BASE}/conversations/${id}/messages`, { params: { limit: 40 } })).data,
    enabled: !!id,
    retry: false,
  });
}

/** Load the page before the oldest cached message and prepend it. */
export async function fetchOlderMessages(qc: QueryClient, id: string): Promise<void> {
  const current = qc.getQueryData<MessagesPage>(DK.messages(id));
  if (!current || !current.hasMore || current.messages.length === 0) return;
  const beforeSeq = current.messages[0].seq;
  const page = (
    await api.get<MessagesPage>(`${BASE}/conversations/${id}/messages`, { params: { beforeSeq, limit: 40 } })
  ).data;
  qc.setQueryData<MessagesPage>(DK.messages(id), (prev) => {
    if (!prev) return page;
    const seen = new Set(prev.messages.map((m) => m.id));
    return { messages: [...page.messages.filter((m) => !seen.has(m.id)), ...prev.messages], hasMore: page.hasMore };
  });
}

export function usePinnedMessages(id: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: DK.pinned(id),
    queryFn: async () => (await api.get<DiscussMessage[]>(`${BASE}/conversations/${id}/pinned`)).data,
    enabled: !!id && enabled,
  });
}

export function useSharedAttachments(id: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: DK.attachments(id),
    queryFn: async () => (await api.get<SharedAttachment[]>(`${BASE}/conversations/${id}/attachments`)).data,
    enabled: !!id && enabled,
  });
}

export function useInbox(enabled: boolean) {
  return useQuery({
    queryKey: DK.inbox(),
    queryFn: async () => (await api.get<InboxResponse>(`${BASE}/inbox`, { params: { limit: 50 } })).data,
    enabled,
  });
}

export function useStarred(enabled: boolean) {
  return useQuery({
    queryKey: DK.starred(),
    queryFn: async () => (await api.get<MessageHit[]>(`${BASE}/starred`)).data,
    enabled,
  });
}

export function useMessageSearch(q: string, conversationId?: string) {
  const term = q.trim();
  return useQuery({
    queryKey: DK.search(term, conversationId),
    queryFn: async () =>
      (await api.get<MessageHit[]>(`${BASE}/search`, { params: { q: term, conversationId, limit: 50 } })).data,
    enabled: term.length >= 2,
  });
}

export function useRecordThread(type: string, id: string, enabled = true) {
  return useQuery({
    queryKey: DK.record(type, id),
    queryFn: async () =>
      (await api.get<RecordThread>(`${BASE}/records/${encodeURIComponent(type)}/${encodeURIComponent(id)}`)).data,
    enabled: enabled && !!type && !!id,
    retry: false,
  });
}

/* ── Conversation writes ─────────────────────────────────────────────────── */

export function useCreateChannel() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { name: string; description?: string; visibility?: DiscussVisibility; memberIds?: string[] }) =>
      (await api.post<ConversationItem>(`${BASE}/channels`, input)).data,
    onSuccess: () => {
      invalidateLists(qc);
      qc.invalidateQueries({ queryKey: [...DK.all, 'channels'] });
    },
    onError: onError(tx('Could not create the channel')),
  });
}

export function useStartChat() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (userIds: string[]) => (await api.post<ConversationItem>(`${BASE}/chats`, { userIds })).data,
    onSuccess: () => invalidateLists(qc),
    onError: onError(tx('Could not start the chat')),
  });
}

function useDetailMutation<V>(fn: (v: V) => Promise<ConversationDetail>, fallback: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (detail) => {
      qc.setQueryData(DK.detail(detail.id), detail);
      invalidateLists(qc);
      qc.invalidateQueries({ queryKey: [...DK.all, 'channels'] });
      qc.invalidateQueries({ queryKey: DK.directory() });
    },
    onError: onError(fallback),
  });
}

export function useUpdateConversation() {
  return useDetailMutation(
    async ({ id, ...body }: { id: string; name?: string; description?: string; visibility?: DiscussVisibility }) =>
      (await api.patch<ConversationDetail>(`${BASE}/conversations/${id}`, body)).data,
    tx('Could not update the conversation'),
  );
}

export function useJoinConversation() {
  return useDetailMutation(
    async (id: string) => (await api.post<ConversationDetail>(`${BASE}/conversations/${id}/join`, {})).data,
    tx('Could not join the channel'),
  );
}

export function useLeaveConversation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await api.post(`${BASE}/conversations/${id}/leave`, {})).data,
    onSuccess: (_d, id) => {
      qc.removeQueries({ queryKey: DK.detail(id) });
      qc.removeQueries({ queryKey: DK.messages(id) });
      invalidateLists(qc);
      qc.invalidateQueries({ queryKey: [...DK.all, 'channels'] });
      qc.invalidateQueries({ queryKey: DK.directory() });
    },
    onError: onError(tx('Could not leave the conversation')),
  });
}

export function useAddMembers() {
  return useDetailMutation(
    async ({ id, userIds }: { id: string; userIds: string[] }) =>
      (await api.post<ConversationDetail>(`${BASE}/conversations/${id}/members`, { userIds })).data,
    tx('Could not add members'),
  );
}

export function useRemoveMember() {
  return useDetailMutation(
    async ({ id, userId }: { id: string; userId: string }) =>
      (await api.delete<ConversationDetail>(`${BASE}/conversations/${id}/members/${userId}`)).data,
    tx('Could not remove the member'),
  );
}

export function useArchiveConversation() {
  return useDetailMutation(
    async ({ id, archived }: { id: string; archived: boolean }) =>
      (await api.post<ConversationDetail>(`${BASE}/conversations/${id}/${archived ? 'archive' : 'unarchive'}`, {})).data,
    tx('Could not change the archive state'),
  );
}

export function useConversationSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, ...body }: { id: string; notifyLevel?: NotifyLevel; muteMinutes?: number; pinned?: boolean }) =>
      (await api.patch<ConversationItem>(`${BASE}/conversations/${id}/settings`, body)).data,
    onSuccess: (_item, vars) => {
      invalidateLists(qc);
      qc.invalidateQueries({ queryKey: DK.detail(vars.id) });
    },
    onError: onError(tx('Could not save the settings')),
  });
}

/** Fire-and-forget typing signal; errors are ignored on purpose. */
export function sendTyping(conversationId: string, typing: boolean): void {
  void api.post(`${BASE}/conversations/${conversationId}/typing`, { typing }).catch(() => undefined);
}

export function useMarkThreadRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, messageId }: { id: string; messageId?: string }) =>
      (await api.post(`${BASE}/conversations/${id}/read`, messageId ? { messageId } : {})).data,
    onSuccess: () => {
      invalidateLists(qc);
      qc.invalidateQueries({ queryKey: DK.inbox() });
    },
  });
}

/* ── Message writes ──────────────────────────────────────────────────────── */

function appendToThread(qc: QueryClient, msg: DiscussMessage) {
  qc.setQueryData<MessagesPage>(DK.messages(msg.conversationId), (prev) => {
    if (!prev) return prev;
    if (prev.messages.some((m) => m.id === msg.id)) return prev;
    return { ...prev, messages: [...prev.messages, msg] };
  });
}

/** Replace a message in every cache that holds it (thread, pinned). */
export function replaceMessage(qc: QueryClient, msg: DiscussMessage) {
  qc.setQueryData<MessagesPage>(DK.messages(msg.conversationId), (prev) => {
    if (!prev) return prev;
    return {
      ...prev,
      messages: prev.messages.map((m) => (m.id === msg.id ? { ...msg, starred: msg.starred ?? m.starred } : m)),
    };
  });
}

export function removeMessage(qc: QueryClient, conversationId: string, messageId: string) {
  qc.setQueryData<MessagesPage>(DK.messages(conversationId), (prev) =>
    prev ? { ...prev, messages: prev.messages.filter((m) => m.id !== messageId) } : prev,
  );
}

export function useSendDiscussMessage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ conversationId, ...body }: PostMessageInput & { conversationId: string }) =>
      (
        await api.post<DiscussMessage>(`${BASE}/conversations/${conversationId}/messages`, body, {
          headers: { 'Idempotency-Key': uuid() },
        })
      ).data,
    onSuccess: (msg) => {
      appendToThread(qc, msg);
      invalidateLists(qc);
    },
    onError: onError(tx('Message not sent')),
  });
}

export function usePostRecordMessage(type: string, id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: PostMessageInput) =>
      (
        await api.post<DiscussMessage>(
          `${BASE}/records/${encodeURIComponent(type)}/${encodeURIComponent(id)}/messages`,
          body,
          { headers: { 'Idempotency-Key': uuid() } },
        )
      ).data,
    onSuccess: (msg) => {
      appendToThread(qc, msg);
      qc.invalidateQueries({ queryKey: DK.record(type, id) });
      qc.invalidateQueries({ queryKey: DK.messages(msg.conversationId) });
    },
    onError: onError(tx('Message not sent')),
  });
}

export function useFollowRecord(type: string, id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (follow: boolean) =>
      (
        await api.post<RecordThread>(
          `${BASE}/records/${encodeURIComponent(type)}/${encodeURIComponent(id)}/${follow ? 'follow' : 'unfollow'}`,
          {},
        )
      ).data,
    onSuccess: (thread) => qc.setQueryData(DK.record(type, id), thread),
    onError: onError(tx('Could not change following')),
  });
}

export function useEditMessage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: string }) =>
      (await api.patch<DiscussMessage>(`${BASE}/messages/${id}`, { body })).data,
    onSuccess: (msg) => replaceMessage(qc, msg),
    onError: onError(tx('Could not edit the message')),
  });
}

export function useDeleteMessage() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id }: { id: string; conversationId: string }) =>
      (await api.delete(`${BASE}/messages/${id}`)).data,
    onSuccess: (_d, vars) => {
      removeMessage(qc, vars.conversationId, vars.id);
      qc.invalidateQueries({ queryKey: DK.pinned(vars.conversationId) });
      invalidateLists(qc);
    },
    onError: onError(tx('Could not delete the message')),
  });
}

export function useToggleReaction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, emoji }: { id: string; emoji: string }) =>
      (await api.post<DiscussMessage>(`${BASE}/messages/${id}/reactions`, { emoji })).data,
    onSuccess: (msg) => replaceMessage(qc, msg),
    onError: onError(tx('Could not react')),
  });
}

export function useTogglePin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => (await api.post<DiscussMessage>(`${BASE}/messages/${id}/pin`, {})).data,
    onSuccess: (msg) => {
      replaceMessage(qc, msg);
      qc.invalidateQueries({ queryKey: DK.pinned(msg.conversationId) });
      qc.invalidateQueries({ queryKey: DK.detail(msg.conversationId) });
    },
    onError: onError(tx('Could not pin the message')),
  });
}

export function useToggleStar() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id }: { id: string; conversationId: string }) =>
      (await api.post<{ starred: boolean }>(`${BASE}/messages/${id}/star`, {})).data,
    onSuccess: (res, vars) => {
      qc.setQueryData<MessagesPage>(DK.messages(vars.conversationId), (prev) =>
        prev
          ? { ...prev, messages: prev.messages.map((m) => (m.id === vars.id ? { ...m, starred: res.starred } : m)) }
          : prev,
      );
      qc.invalidateQueries({ queryKey: DK.starred() });
      qc.invalidateQueries({ queryKey: DK.counters() });
      qc.invalidateQueries({ queryKey: DK.sidebar() });
    },
    onError: onError(tx('Could not star the message')),
  });
}

export async function uploadAttachment(file: File): Promise<UploadedAttachment> {
  const fd = new FormData();
  fd.append('file', file);
  return (
    await api.post<UploadedAttachment>(`${BASE}/attachments`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120_000,
    })
  ).data;
}

/* ── Inbox, departments ──────────────────────────────────────────────────── */

export function useMarkInboxRead() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (ids?: string[]) =>
      (await api.post<{ ok: boolean; unread: number }>(`${BASE}/inbox/read`, ids ? { ids } : {})).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: DK.inbox() });
      invalidateLists(qc);
    },
  });
}

export function useSyncDepartments() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => (await api.post<DepartmentSyncResult>(`${BASE}/departments/sync`, {})).data,
    onSuccess: (r) => {
      notify.success(
        tx('Departments synced'),
        tx('{{created}} created, {{added}} members added, {{removed}} removed', {
          created: r.created,
          added: r.membersAdded,
          removed: r.membersRemoved,
        }),
      );
      invalidateLists(qc);
      qc.invalidateQueries({ queryKey: DK.directory() });
      qc.invalidateQueries({ queryKey: [...DK.all, 'channels'] });
    },
    onError: onError(tx('Could not sync departments')),
  });
}
