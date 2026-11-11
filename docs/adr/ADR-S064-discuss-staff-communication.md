# ADR-S064 — Discuss: Odoo-style staff and department communication

- Status: accepted (built 2026-11-10, branch `phase/discuss`)
- Builds on: the ported communication module (22 Nest routes, [spec](../spec/communication.md)).
- Migration: `V443__discuss.sql`
- Extension: no NestJS counterpart. Same module `com.inventoryerp.communication`; every new route is under
  `/communication/discuss`, so the 22 Nest routes keep their exact contract.

## Context

The communication module is a faithful port: transport-independent conversations, an internal channel, Telegram and
WhatsApp Cloud, templates, rules, and an SSE inbox stream. As a staff tool it was thin:

- One flat list of every org-visible conversation, including record chatters, with no names on DMs.
- No channels to browse or join, no department channels, no 1:1 DM deduplication, no member management.
- No mentions, reactions, replies with preview, edit / delete, pinned or starred messages, attachments, search.
- No personal inbox, no notify / mute settings, no typing indicator, presence or read receipts.
- Record chatter was get-or-create only: no followers, no log notes, and any `conversation:read` holder could read
  any record's thread.
- Only `Administrator` held the communication permissions, so staff could not chat at all.

## Decision

### 1. Data model (V443)

| Change | Purpose |
|---|---|
| `Conversation.description`, `departmentId`, `directKey`, `autoJoin`, `archivedAt` | Channel topic; one channel per HR department; one DM per user pair (`"<a>:<b>"` sorted, unique per org); `#general` auto-join; archived = read-only. |
| `ConversationParticipant.notifyLevel` (all, mentions, none), `mutedUntil`, `pinned`, `addedVia` (manual, department, auto, follow) | Per-member settings. Department-synced members are removed when they leave the department; manual members stay. A user is a participant at most once (new partial unique index). |
| `Message.subtype` (comment, note, notification), `pinnedAt`, `pinnedById` | Log notes on records, pinned messages. |
| `MessageReaction` | Emoji reactions, unique per message × user × emoji. |
| `MessageStar` | Personal starred messages. |
| `MessageInboxItem` (reason mention / follower, `readAt`) | The personal inbox ("needaction"). |

Every role in every organization is granted `communication:conversation:read`, `communication:conversation:write`
and `communication:message:send`. Private conversations stay participants-only, as before.

### 2. Conversations

- **Channel** (`kind = channel`): `public` (stored as visibility `org`, anyone can browse and join) or `private`
  (`private`, invitation only). Names are unique among the org's active channels (case-insensitive).
- **Direct message** (`kind = direct`, `directKey`): exactly one per pair; `POST /discuss/chats` with one user returns
  the existing DM.
- **Group chat** (`kind = group`, private): `POST /discuss/chats` with two or more users.
- **`#general`**: created on first use of Discuss in an org (`autoJoin`). Every active user is added the first time
  they open Discuss; someone who leaves is not added back.
- **Department channels**: `POST /discuss/departments/sync` (channel:manage) creates one public channel per active
  `HrDepartment` and syncs its members (employees with a user account, plus the manager). After the first sync the
  hourly `DiscussDepartmentSyncJob` keeps membership current (off when `app.jobs.enabled=false`).
- **Roles**: the creator is `owner`. Moderators are the owner, admins, and holders of `communication:channel:manage`.
  Moderators can rename, change visibility, remove members, archive / unarchive and delete other people's messages.
  Any member can add members to a channel or group and rename a group. Nobody can leave or add members to a DM.
  When the last owner leaves, the longest-standing member becomes owner.
- **Archived** conversations refuse new messages (the Nest send route too: archived state exists only through
  Discuss) and leave the sidebar. Moderators find them with `GET /discuss/channels?archived=true`.

### 3. Messages

- Mentions: the client sends `mentionUserIds`. A mentioned user who can access the conversation gets a
  `MessageInboxItem` (reason `mention`), an in-app `Notification` (category `discuss`) and an `inbox.new` stream event.
- DMs and group chats: a member whose `notifyLevel` is `all`, who is not muted and has no open stream gets an in-app
  `Notification`.
- Record followers: a `comment` on a record gives every other follower (not muted, level not `none`) an inbox item
  (reason `follower`). A `note` notifies only mentioned users.
- Edit and delete: own messages only (moderators may delete any). Edit sets `editedAt` and adds inbox items for newly
  mentioned users; delete is soft (`deletedAt`).
- Reactions toggle per user and emoji (1–16 characters). Any member can pin; stars are personal.
- Attachments: `POST /discuss/attachments` (multipart, `message:send`) stores the file through `FilesService` as a
  draft owned by the uploader (`ownerType = MessageDraft`). Sending with `attachmentIds` checks that each file is the
  caller's own unattached draft, writes `MessageAttachment` and moves the file to `ownerType = Message`. Views carry a
  one-hour signed URL, so readers need no `files:read`.
- Unread counts use the global `seq` (not `occurredAt`): messages by others after the member's `lastReadMessageId`.
  `badge` follows the notify level: `all` → unread, `mentions` → unread mentions, `none` or muted → 0.

### 4. Record chatter

Only registered record types are accepted. Each needs its read permission, unless the caller already follows the
record:

| contextType | Permission |
|---|---|
| `SalesOrder` | `sales:order:read` |
| `SalesQuotation` | `sales:quotation:read` |
| `PurchaseOrder` | `purchase_order:read` |
| `Invoice` | `invoice:read` |
| `Expense` | `expense:read` |

The thread is the Nest context conversation (`resolve-context`), so both APIs share it. Following = being an active
participant with `addedVia = follow`. Posting auto-follows. Discuss reads of a registered context conversation also
need the record permission.

### 5. Realtime

The existing `/communication/stream` carries new event types: `message.updated`, `message.deleted`,
`conversation.updated`, `typing`, `read`, `presence` and `inbox.new`. `message.created` gains a `discuss` field (the
enriched message) on Discuss sends. Presence is "has an open stream on this API instance"; like the stream itself it
is per process. A multi-instance deployment needs sticky sessions (documented in OPERATIONS).

## API (all under `/api/v1/communication/discuss`)

`DiscussMessage`:

```json
{ "id": "…", "conversationId": "…", "seq": "123", "subtype": "comment", "senderType": "user",
  "author": { "id": "…", "name": "Sarah Cashier", "initials": "SC" },
  "body": "…", "occurredAt": "…", "editedAt": null, "pinnedAt": null,
  "replyTo": { "id": "…", "body": "first 160 chars", "authorName": "…" },
  "attachments": [{ "id": "…", "fileId": "…", "filename": "a.png", "mimeType": "image/png", "byteSize": 1234,
                    "kind": "image", "url": "/api/v1/files/…/download?token=…" }],
  "reactions": [{ "emoji": "👍", "count": 2, "userIds": ["…"] }],
  "mentions": [{ "userId": "…", "name": "…" }],
  "starred": false }
```

`ConversationItem`:

```json
{ "id": "…", "kind": "channel|direct|group", "name": "display title", "description": null,
  "visibility": "public|private", "departmentId": null, "autoJoin": false, "archived": false,
  "contextType": null, "contextId": null,
  "role": "owner|admin|member|null", "notifyLevel": "all", "mutedUntil": null, "muted": false, "pinned": false,
  "unread": 3, "mentionUnread": 1, "badge": 3, "memberCount": 5,
  "members": [{ "id": "…", "name": "…" }], "directUserId": null,
  "lastMessage": { "id": "…", "body": "…", "occurredAt": "…", "authorName": "…" }, "updatedAt": "…" }
```

`members` lists up to 5 *other* members, for chats. `directUserId` is the other user of a DM.

| Method | Path | Permission | Body / query | Returns |
|---|---|---|---|---|
| GET | `/sidebar` | conversation:read | | `{ me, counters: {unread, inbox, starred}, channels: [Item], chats: [Item] }` |
| GET | `/counters` | conversation:read | | `{ unread, inbox, starred }` |
| GET | `/directory` | conversation:read | | `{ users: [{id, name, email, initials, departmentId, departmentName, online}], departments: [{id, name, code, memberCount, channelId, joined}] }` |
| GET | `/presence` | conversation:read | | `{ online: [userId] }` |
| GET | `/channels` | conversation:read | `archived` | `[{id, name, description, visibility, departmentId, memberCount, joined, archived, lastActivityAt}]` |
| POST | `/channels` | conversation:write | `{name, description?, visibility?, memberIds?}` | 201 `Item` |
| POST | `/chats` | conversation:write | `{userIds}` (1–20) | 201 `Item` |
| GET | `/conversations/:id` | conversation:read | | `Detail` = `Item` + `{members: [{userId, name, email, initials, role, online, lastReadSeq, addedVia}], canManage, canPost, createdAt, pinnedCount}` |
| PATCH | `/conversations/:id` | conversation:write | `{name?, description?, visibility?}` | `Detail` |
| POST | `/conversations/:id/join` | conversation:read | | `Detail` |
| POST | `/conversations/:id/leave` | conversation:read | | `{ok}` |
| POST | `/conversations/:id/members` | conversation:write | `{userIds}` | `Detail` |
| DELETE | `/conversations/:id/members/:userId` | conversation:write | | `Detail` |
| PATCH | `/conversations/:id/settings` | conversation:read | `{notifyLevel?, muteMinutes?, pinned?}` (`muteMinutes`: 0 = unmute, -1 = until unmuted) | `Item` |
| POST | `/conversations/:id/archive` · `/unarchive` | conversation:write | | `Detail` |
| POST | `/conversations/:id/typing` | message:send | `{typing}` | `{ok}` |
| POST | `/conversations/:id/read` | conversation:read | `{messageId?}` | `{ok}` |
| GET | `/conversations/:id/messages` | conversation:read | `beforeSeq`, `afterSeq`, `limit` (1–100, default 40) | `{messages: [DiscussMessage] ascending, hasMore}` |
| POST | `/conversations/:id/messages` | message:send | `{body, replyToMessageId?, mentionUserIds?, attachmentIds?, subtype?}`; optional `Idempotency-Key` | 201 `DiscussMessage` |
| GET | `/conversations/:id/pinned` | conversation:read | | `[DiscussMessage]` |
| GET | `/conversations/:id/attachments` | conversation:read | | `[attachment + {messageId, occurredAt, authorName}]` |
| PATCH | `/messages/:id` | message:send | `{body}` | `DiscussMessage` |
| DELETE | `/messages/:id` | message:send | | `{ok}` |
| POST | `/messages/:id/reactions` | message:send | `{emoji}` | `DiscussMessage` |
| POST | `/messages/:id/pin` | message:send | | `DiscussMessage` |
| POST | `/messages/:id/star` | conversation:read | | `{starred}` |
| POST | `/attachments` | message:send | multipart `file` | 201 `{id, filename, mimeType, byteSize, kind, url}` |
| GET | `/inbox` | conversation:read | `limit` (default 50), `unreadOnly` | `{items: [{id, reason, readAt, createdAt, conversation: {id, name, kind, contextType, contextId}, message}], unread}` |
| POST | `/inbox/read` | conversation:read | `{ids?}` (absent = all) | `{ok, unread}` |
| GET | `/starred` | conversation:read | | `[{conversation: {id, name, kind}, message}]` |
| GET | `/search` | conversation:read | `q` (≥ 2 chars), `conversationId?`, `limit` | `[{conversation: {id, name, kind}, message}]` |
| POST | `/departments/sync` | channel:manage | | `{created, updated, membersAdded, membersRemoved}` |
| GET | `/records/:type/:id` | conversation:read | | `{conversationId, following, followers: [{userId, name}]}` |
| POST | `/records/:type/:id/messages` | message:send | as conversation messages | 201 `DiscussMessage` |
| POST | `/records/:type/:id/follow` · `/unfollow` | conversation:read | | `{conversationId, following, followers}` |

Errors (verbatim):

- "You do not have access to this conversation."
- "Conversation not found."
- "Message not found."
- "This conversation is archived."
- "Only channels can be joined."
- "You cannot leave a direct conversation."
- "Members cannot be added to a direct conversation."
- "Members cannot be removed from a direct conversation."
- "Join the conversation first." (settings of a conversation the caller is not in)
- "A chat can have at most 20 other members."
- "A message can have at most 10 attachments."
- "Only a channel moderator can do this."
- "A channel named '<name>' already exists."
- "You can only edit your own messages."
- "You can only delete your own messages."
- "Message body is required."
- "Attachment <id> is not available."
- "Unknown user(s): <ids>"
- "Search needs at least 2 characters."
- "Unsupported record type '<type>'."

## Web

`/communication` becomes Discuss: a sidebar (Inbox, Starred, Channels, Direct messages, presence dots, badges), a
thread with day separators, grouped messages, replies, reactions, pins, stars, edit / delete, attachments with image
previews, @mention autocomplete, an emoji picker, a typing indicator, "seen by" in chats, a member panel, channel
settings, browse / create channel and new chat dialogs, search, and desktop notifications. The app header gets a
Discuss button with the unread + inbox badge. The `RecordChatter` component (Send message / Log note, followers,
attachments) is mounted on sales order, purchase order, invoice and expense detail pages.

## Consequences

- Staff of every role can chat once the module is on (`ENABLE_COMMUNICATION=true`). Roles created after V443 need the
  three permissions granted in the Roles UI.
- Presence and typing are in-process. A horizontally scaled API needs sticky sessions for `/communication/stream`.
- Message search is `ILIKE` over the caller's conversations (bounded, newest first). Full-text search is a later step
  if volume needs it.
