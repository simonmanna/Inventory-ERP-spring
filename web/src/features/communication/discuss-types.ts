/**
 * Discuss (ADR-S064) wire types — `/communication/discuss/*`. Shapes mirror the ADR contract exactly.
 */

export type ConversationKind = 'channel' | 'direct' | 'group';
export type NotifyLevel = 'all' | 'mentions' | 'none';
export type DiscussVisibility = 'public' | 'private';
export type MemberRole = 'owner' | 'admin' | 'member';
export type MessageSubtype = 'comment' | 'note' | 'notification';

export interface DiscussAuthor {
  id: string;
  name: string;
  initials: string;
}

export interface DiscussAttachment {
  id: string;
  fileId: string;
  filename: string;
  mimeType: string | null;
  byteSize: number | null;
  kind: 'image' | 'file';
  url: string;
}

export interface DiscussReaction {
  emoji: string;
  count: number;
  userIds: string[];
}

export interface DiscussMessage {
  id: string;
  conversationId: string;
  /** Global sequence (BigInt as string). Compare with `seqNum()`. */
  seq: string;
  subtype: MessageSubtype;
  senderType: string;
  author: DiscussAuthor | null;
  body: string;
  occurredAt: string;
  editedAt: string | null;
  pinnedAt: string | null;
  replyTo: { id: string; body: string; authorName: string | null } | null;
  attachments: DiscussAttachment[];
  reactions: DiscussReaction[];
  mentions: { userId: string; name: string }[];
  /** Per-viewer; absent on stream events. */
  starred?: boolean;
}

export interface ConversationItem {
  id: string;
  kind: ConversationKind;
  name: string;
  description: string | null;
  visibility: DiscussVisibility;
  departmentId: string | null;
  autoJoin: boolean;
  archived: boolean;
  contextType: string | null;
  contextId: string | null;
  role: MemberRole | null;
  notifyLevel: NotifyLevel;
  mutedUntil: string | null;
  muted: boolean;
  pinned: boolean;
  unread: number;
  mentionUnread: number;
  badge: number;
  memberCount: number;
  members: { id: string; name: string }[];
  directUserId: string | null;
  lastMessage: { id: string; body: string; occurredAt: string; authorName: string | null } | null;
  updatedAt: string;
}

export interface ConversationMember {
  userId: string;
  name: string;
  email: string | null;
  initials: string;
  role: MemberRole;
  online: boolean;
  lastReadSeq: string | null;
  addedVia: 'manual' | 'department' | 'auto' | 'follow';
}

export interface ConversationDetail extends Omit<ConversationItem, 'members'> {
  members: ConversationMember[];
  canManage: boolean;
  canPost: boolean;
  createdAt: string;
  pinnedCount: number;
}

export interface DiscussCounters {
  unread: number;
  inbox: number;
  starred: number;
}

export interface SidebarResponse {
  me: { id: string; name: string };
  counters: DiscussCounters;
  channels: ConversationItem[];
  chats: ConversationItem[];
}

export interface DirectoryUser {
  id: string;
  name: string;
  email: string | null;
  initials: string;
  departmentId: string | null;
  departmentName: string | null;
  online: boolean;
}

export interface DirectoryDepartment {
  id: string;
  name: string;
  code: string | null;
  memberCount: number;
  channelId: string | null;
  joined: boolean;
}

export interface DirectoryResponse {
  users: DirectoryUser[];
  departments: DirectoryDepartment[];
}

export interface BrowseChannel {
  id: string;
  name: string;
  description: string | null;
  visibility: DiscussVisibility;
  departmentId: string | null;
  memberCount: number;
  joined: boolean;
  archived: boolean;
  lastActivityAt: string | null;
}

export interface MessagesPage {
  messages: DiscussMessage[];
  hasMore: boolean;
}

export interface ConversationRef {
  id: string;
  name: string;
  kind: ConversationKind;
  contextType?: string | null;
  contextId?: string | null;
}

export interface InboxItem {
  id: string;
  reason: 'mention' | 'follower';
  readAt: string | null;
  createdAt: string;
  conversation: ConversationRef;
  message: DiscussMessage;
}

export interface InboxResponse {
  items: InboxItem[];
  unread: number;
}

export interface MessageHit {
  conversation: ConversationRef;
  message: DiscussMessage;
}

export interface SharedAttachment extends DiscussAttachment {
  messageId: string;
  occurredAt: string;
  authorName: string | null;
}

export interface UploadedAttachment {
  id: string;
  filename: string;
  mimeType: string | null;
  byteSize: number | null;
  kind: 'image' | 'file';
  url: string;
}

export interface RecordThread {
  conversationId: string | null;
  following: boolean;
  followers: { userId: string; name: string }[];
}

export interface PostMessageInput {
  body: string;
  replyToMessageId?: string;
  mentionUserIds?: string[];
  attachmentIds?: string[];
  subtype?: 'comment' | 'note';
}

export interface DepartmentSyncResult {
  created: number;
  updated: number;
  membersAdded: number;
  membersRemoved: number;
}

/** Events the `/communication/stream` SSE carries for Discuss. */
export type DiscussStreamEvent =
  | { type: 'message.created'; conversationId: string; messageId: string; discuss?: DiscussMessage }
  | { type: 'message.updated'; conversationId: string; message: DiscussMessage }
  | { type: 'message.deleted'; conversationId: string; messageId: string }
  | { type: 'conversation.updated'; conversationId: string }
  | { type: 'typing'; conversationId: string; userId: string; name: string; typing: boolean }
  | { type: 'read'; conversationId: string; userId: string; seq: string }
  | { type: 'presence'; userId: string; online: boolean }
  | { type: 'inbox.new'; conversationId: string; messageId: string; reason: 'mention' | 'follower' };

/** Seq strings are BigInt — compare numerically without losing order. */
export function seqNum(seq: string | null | undefined): bigint {
  try {
    return seq ? BigInt(seq) : 0n;
  } catch {
    return 0n;
  }
}
