import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { create } from 'zustand';
import { useAuthStore } from '@/stores/auth.store';
import { getApiBaseUrl } from '@/lib/api';
import { COMM_PREFIX } from './api';
import type { MessageView, StreamEvent } from './types';
import { DK, invalidateLists, removeMessage, replaceMessage } from './discuss-api';
import type {
  ConversationDetail,
  DiscussMessage,
  DiscussStreamEvent,
  MessagesPage,
  SidebarResponse,
} from './discuss-types';

/** Channel status/QR pushed over the same stream — consumed by the Channels page. */
export interface ChannelStatusEvent {
  type: 'channel.status';
  channelId: string;
  status: string;
  qrPngDataUrl?: string;
  expiresAt?: string;
}

/**
 * Live inbox stream. Mirrors features/tables/sse.ts: EventSource cannot send an
 * Authorization header, so the access token rides as a query param (the
 * `/communication/stream` path is allow-listed in the API's main.ts). Falls back
 * to react-query polling when the stream drops.
 */
export function useCommunicationStream(activeConversationId?: string) {
  const qc = useQueryClient();

  useEffect(() => {
    const token = useAuthStore.getState().accessToken;
    if (!token) return;

    const url = new URL(`${getApiBaseUrl()}/communication/stream`, window.location.origin);
    url.searchParams.set('access_token', token);
    const es = new EventSource(url.toString());

    es.onmessage = (evt) => {
      let msg: StreamEvent;
      try {
        msg = JSON.parse(evt.data);
      } catch {
        return;
      }
      if (msg.type === 'message.created' && msg.conversationId) {
        // Append to the open conversation's cache if we have it; always refresh
        // the conversation list (unread + last-message preview).
        if (msg.message && msg.conversationId === activeConversationId) {
          qc.setQueryData<MessageView[]>([...COMM_PREFIX, 'messages', msg.conversationId], (prev) => {
            if (!prev) return prev;
            if (prev.some((m) => m.id === msg.message!.id)) return prev;
            return [...prev, msg.message!];
          });
        }
        qc.invalidateQueries({ queryKey: [...COMM_PREFIX, 'conversations'] });
      } else if (msg.type === 'delivery.status' && msg.conversationId) {
        qc.invalidateQueries({ queryKey: [...COMM_PREFIX, 'messages', msg.conversationId] });
      }
    };

    es.onerror = () => {
      // EventSource auto-reconnects; poll fallback keeps data fresh meanwhile.
    };

    return () => es.close();
  }, [qc, activeConversationId]);
}

/**
 * Subscribes to `channel.status` events (connection state + the pairing QR PNG,
 * rendered server-side so the web needs no QR library). Used by the Channels
 * admin page while a WhatsApp channel is being linked.
 */
export function useChannelStatusStream(onStatus: (e: ChannelStatusEvent) => void): void {
  const qc = useQueryClient();
  useEffect(() => {
    const token = useAuthStore.getState().accessToken;
    if (!token) return;
    const url = new URL(`${getApiBaseUrl()}/communication/stream`, window.location.origin);
    url.searchParams.set('access_token', token);
    const es = new EventSource(url.toString());
    es.onmessage = (evt) => {
      let msg: ChannelStatusEvent;
      try {
        msg = JSON.parse(evt.data);
      } catch {
        return;
      }
      if (msg.type === 'channel.status') {
        onStatus(msg);
        qc.invalidateQueries({ queryKey: [...COMM_PREFIX, 'channels'] });
      }
    };
    es.onerror = () => {
      /* auto-reconnects */
    };
    return () => es.close();
  }, [qc, onStatus]);
}

/* ── Discuss (ADR-S064) ──────────────────────────────────────────────────── */

/**
 * Live Discuss stream: keeps the thread, sidebar, counters, typing and presence state fresh from the same
 * `/communication/stream`. Mount it once on the Discuss page.
 */
export function useDiscussStream(activeConversationId?: string) {
  const qc = useQueryClient();
  const activeRef = useRef(activeConversationId);
  activeRef.current = activeConversationId;

  useEffect(() => {
    const token = useAuthStore.getState().accessToken;
    if (!token) return;
    const url = new URL(`${getApiBaseUrl()}/communication/stream`, window.location.origin);
    url.searchParams.set('access_token', token);
    const es = new EventSource(url.toString());
    const me = useAuthStore.getState().user?.id;

    es.onmessage = (evt) => {
      let e: DiscussStreamEvent;
      try {
        e = JSON.parse(evt.data);
      } catch {
        return;
      }
      switch (e.type) {
        case 'message.created': {
          if (e.discuss) {
            const msg = e.discuss;
            qc.setQueryData<MessagesPage>(DK.messages(e.conversationId), (prev) => {
              if (!prev || prev.messages.some((m) => m.id === msg.id)) return prev;
              return { ...prev, messages: [...prev.messages, msg] };
            });
            if (msg.author) useDiscussLive.getState().setTyping(e.conversationId, msg.author.id, msg.author.name, false);
            maybeNotifyDesktop(qc, msg, me, activeRef.current);
          } else {
            qc.invalidateQueries({ queryKey: DK.messages(e.conversationId) });
          }
          invalidateLists(qc);
          break;
        }
        case 'message.updated':
          replaceMessage(qc, e.message);
          qc.invalidateQueries({ queryKey: DK.pinned(e.conversationId) });
          break;
        case 'message.deleted':
          removeMessage(qc, e.conversationId, e.messageId);
          qc.invalidateQueries({ queryKey: DK.pinned(e.conversationId) });
          invalidateLists(qc);
          break;
        case 'conversation.updated':
          invalidateLists(qc);
          qc.invalidateQueries({ queryKey: DK.detail(e.conversationId) });
          qc.invalidateQueries({ queryKey: [...DK.all, 'channels'] });
          break;
        case 'typing':
          if (e.userId !== me) useDiscussLive.getState().setTyping(e.conversationId, e.userId, e.name, e.typing);
          break;
        case 'read':
          qc.setQueryData<ConversationDetail>(DK.detail(e.conversationId), (prev) =>
            prev
              ? { ...prev, members: prev.members.map((m) => (m.userId === e.userId ? { ...m, lastReadSeq: e.seq } : m)) }
              : prev,
          );
          if (e.userId === me) invalidateLists(qc);
          break;
        case 'presence':
          useDiscussLive.getState().setOnline(e.userId, e.online);
          break;
        case 'inbox.new':
          qc.invalidateQueries({ queryKey: DK.inbox() });
          qc.invalidateQueries({ queryKey: DK.counters() });
          break;
        default:
          break;
      }
    };
    es.onerror = () => {
      /* auto-reconnects; react-query polling covers the gap */
    };
    return () => es.close();
  }, [qc]);
}

interface TypingEntry {
  name: string;
  at: number;
}

interface DiscussLiveState {
  /** conversationId → userId → typing entry. */
  typing: Record<string, Record<string, TypingEntry>>;
  /** Presence overrides from the stream (userId → online). */
  online: Record<string, boolean>;
  setTyping: (conversationId: string, userId: string, name: string, typing: boolean) => void;
  setOnline: (userId: string, online: boolean) => void;
  seedOnline: (userIds: string[]) => void;
}

/** Ephemeral realtime state (typing, presence). Not persisted. */
export const useDiscussLive = create<DiscussLiveState>((set) => ({
  typing: {},
  online: {},
  setTyping: (conversationId, userId, name, typing) =>
    set((s) => {
      const conv = { ...(s.typing[conversationId] ?? {}) };
      if (typing) conv[userId] = { name, at: Date.now() };
      else delete conv[userId];
      return { typing: { ...s.typing, [conversationId]: conv } };
    }),
  setOnline: (userId, online) => set((s) => ({ online: { ...s.online, [userId]: online } })),
  seedOnline: (userIds) =>
    set(() => {
      const online: Record<string, boolean> = {};
      for (const id of userIds) online[id] = true;
      return { online };
    }),
}));

/** Names currently typing in a conversation (entries older than 6 s expire). */
export function useTypingNames(conversationId: string | undefined): string[] {
  const entries = useDiscussLive((s) => (conversationId ? s.typing[conversationId] : undefined));
  const [, tick] = useState(0);
  useEffect(() => {
    if (!entries || Object.keys(entries).length === 0) return;
    const t = window.setInterval(() => tick((n) => n + 1), 2000);
    return () => window.clearInterval(t);
  }, [entries]);
  if (!entries) return [];
  const now = Date.now();
  return Object.values(entries)
    .filter((e) => now - e.at < 6000)
    .map((e) => e.name);
}

export function useIsOnline(userId: string | null | undefined): boolean {
  return useDiscussLive((s) => (userId ? !!s.online[userId] : false));
}

/* Desktop notifications — permission is only requested from an explicit user click. */

export function desktopNotificationsSupported(): boolean {
  return typeof window !== 'undefined' && 'Notification' in window;
}

export async function requestDesktopNotifications(): Promise<NotificationPermission | 'unsupported'> {
  if (!desktopNotificationsSupported()) return 'unsupported';
  return Notification.requestPermission();
}

function maybeNotifyDesktop(
  qc: ReturnType<typeof useQueryClient>,
  msg: DiscussMessage,
  me: string | undefined,
  activeId: string | undefined,
) {
  if (!desktopNotificationsSupported() || Notification.permission !== 'granted') return;
  if (!msg.author || msg.author.id === me) return;
  const visibleAndActive = document.visibilityState === 'visible' && msg.conversationId === activeId;
  if (visibleAndActive) return;
  const sidebar = qc.getQueryData<SidebarResponse>(DK.sidebar());
  const conv = sidebar?.chats.find((c) => c.id === msg.conversationId)
    ?? sidebar?.channels.find((c) => c.id === msg.conversationId);
  if (conv?.muted || conv?.notifyLevel === 'none') return;
  const isChat = conv?.kind === 'direct' || conv?.kind === 'group';
  const mentioned = !!me && msg.mentions.some((m) => m.userId === me);
  if (!isChat && !mentioned) return;
  try {
    const n = new Notification(conv && conv.kind !== 'direct' ? `${msg.author.name} · ${conv.name}` : msg.author.name, {
      body: msg.body.length > 140 ? `${msg.body.slice(0, 140)}…` : msg.body,
      tag: `discuss-${msg.conversationId}`,
    });
    n.onclick = () => {
      window.focus();
      window.location.assign(`/communication?c=${encodeURIComponent(msg.conversationId)}`);
      n.close();
    };
  } catch {
    /* some browsers refuse page-level notifications (e.g. Android Chrome) */
  }
}
