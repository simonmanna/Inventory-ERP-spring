-- V443: Discuss — Odoo-style staff communication on top of the communication module (ADR-S064).
--
-- Extension, no NestJS counterpart. The 22 Nest routes keep their contract; the new columns all have defaults, so
-- rows the Nest code paths write stay valid.
--
-- Conversation
--   description   channel topic / purpose
--   departmentId  HrDepartment the channel belongs to (one channel per department, members synced from HR)
--   directKey     "<userA>:<userB>" (sorted) for a 1:1 direct message, so a pair has exactly one DM
--   autoJoin      every active user of the organization is a member (the #general channel)
--   archivedAt    archived channels are read-only and leave the sidebar
-- ConversationParticipant
--   notifyLevel   all | mentions | none — what counts as unread and what notifies
--   mutedUntil    no badge and no notification until then
--   pinned        shown at the top of the sidebar
--   addedVia      manual | department | auto | follow — department-synced members are removed when they leave it
-- Message
--   subtype       comment (a message) | note (an internal log note on a record) | notification (system line)
--   pinnedAt / pinnedById  pinned to the conversation

ALTER TABLE "Conversation"
    ADD COLUMN description    TEXT,
    ADD COLUMN "departmentId" TEXT,
    ADD COLUMN "directKey"    TEXT,
    ADD COLUMN "autoJoin"     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "archivedAt"   TIMESTAMP(3);

CREATE UNIQUE INDEX "Conversation_directKey_key" ON "Conversation" ("organizationId", "directKey")
    WHERE "directKey" IS NOT NULL AND "deletedAt" IS NULL;
CREATE UNIQUE INDEX "Conversation_departmentId_key" ON "Conversation" ("organizationId", "departmentId")
    WHERE "departmentId" IS NOT NULL AND "deletedAt" IS NULL;

ALTER TABLE "ConversationParticipant"
    ADD COLUMN "notifyLevel" TEXT    NOT NULL DEFAULT 'all',
    ADD COLUMN "mutedUntil"  TIMESTAMP(3),
    ADD COLUMN pinned        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN "addedVia"    TEXT    NOT NULL DEFAULT 'manual',
    ADD CONSTRAINT "ConversationParticipant_notifyLevel" CHECK ("notifyLevel" IN ('all', 'mentions', 'none')),
    ADD CONSTRAINT "ConversationParticipant_addedVia" CHECK ("addedVia" IN ('manual', 'department', 'auto', 'follow'));

-- The baseline unique index cannot stop duplicate user rows (partnerId / externalIdentityId are NULL and NULLs are
-- distinct), so a user is a participant of a conversation at most once.
DELETE FROM "ConversationParticipant" p
USING "ConversationParticipant" q
WHERE p."participantType" = 'user' AND q."participantType" = 'user'
  AND p."conversationId" = q."conversationId" AND p."userId" = q."userId"
  AND p."userId" IS NOT NULL AND p.id > q.id;
CREATE UNIQUE INDEX "ConversationParticipant_user_key" ON "ConversationParticipant" ("conversationId", "userId")
    WHERE "participantType" = 'user' AND "userId" IS NOT NULL;
CREATE INDEX "ConversationParticipant_member_idx" ON "ConversationParticipant" ("organizationId", "userId")
    WHERE "leftAt" IS NULL;

ALTER TABLE "Message"
    ADD COLUMN subtype      TEXT NOT NULL DEFAULT 'comment',
    ADD COLUMN "pinnedAt"   TIMESTAMP(3),
    ADD COLUMN "pinnedById" TEXT,
    ADD CONSTRAINT "Message_subtype" CHECK (subtype IN ('comment', 'note', 'notification'));
CREATE INDEX "Message_conversation_seq_idx" ON "Message" ("conversationId", seq);
CREATE INDEX "Message_pinned_idx" ON "Message" ("conversationId") WHERE "pinnedAt" IS NOT NULL;

CREATE TABLE "MessageReaction" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id),
    "messageId"      TEXT         NOT NULL REFERENCES "Message" (id) ON DELETE CASCADE,
    "userId"         TEXT         NOT NULL,
    emoji            TEXT         NOT NULL,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "MessageReaction_emoji" CHECK (length(emoji) BETWEEN 1 AND 16)
);
CREATE UNIQUE INDEX "MessageReaction_key" ON "MessageReaction" ("messageId", "userId", emoji);

CREATE TABLE "MessageStar" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id),
    "messageId"      TEXT         NOT NULL REFERENCES "Message" (id) ON DELETE CASCADE,
    "userId"         TEXT         NOT NULL,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "MessageStar_key" ON "MessageStar" ("messageId", "userId");
CREATE INDEX "MessageStar_user_idx" ON "MessageStar" ("organizationId", "userId", "createdAt");

-- The personal inbox ("needaction"): a message that mentions the user, or a message posted on a record the user
-- follows. readAt NULL = unread.
CREATE TABLE "MessageInboxItem" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id),
    "messageId"      TEXT         NOT NULL REFERENCES "Message" (id) ON DELETE CASCADE,
    "conversationId" TEXT         NOT NULL REFERENCES "Conversation" (id) ON DELETE CASCADE,
    "userId"         TEXT         NOT NULL,
    reason           TEXT         NOT NULL,
    "readAt"         TIMESTAMP(3),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "MessageInboxItem_reason" CHECK (reason IN ('mention', 'follower'))
);
CREATE UNIQUE INDEX "MessageInboxItem_key" ON "MessageInboxItem" ("messageId", "userId");
CREATE INDEX "MessageInboxItem_user_idx" ON "MessageInboxItem" ("organizationId", "userId", "readAt", "createdAt");

ALTER TABLE "MessageReaction" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "MessageReaction"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
ALTER TABLE "MessageStar" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "MessageStar"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));
ALTER TABLE "MessageInboxItem" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "MessageInboxItem"
    USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));

-- Staff chat for every role: read and join conversations, start chats and channels, send messages. Private
-- conversations stay private (participants only); moderation stays with communication:channel:manage.
UPDATE "Role"
SET permissions = ARRAY(SELECT DISTINCT unnest(permissions || ARRAY['communication:conversation:read',
        'communication:conversation:write', 'communication:message:send'])),
    "updatedAt" = CURRENT_TIMESTAMP
WHERE "deletedAt" IS NULL;
