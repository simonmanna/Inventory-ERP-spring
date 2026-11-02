-- V239: Helpdesk and correspondence (ADR-S045; Spring-native, no Nest reference). One ticket model for e-mail,
-- portal, phone, walk-in, WhatsApp and letters (incoming / outgoing correspondence register). Files (letter scans,
-- generated letters, attachments) live in the DMS, linked to the ticket (resType helpdesk_ticket).

CREATE TABLE "HdTeam" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    code                 TEXT NOT NULL,
    name                 TEXT NOT NULL,
    description          TEXT,
    email                TEXT,
    assignment           TEXT NOT NULL DEFAULT 'manual' CHECK (assignment IN ('manual', 'round_robin', 'balanced')),
    "leaderId"           TEXT,
    "lastAssignedUserId" TEXT,
    "isActive"           BOOLEAN NOT NULL DEFAULT true,
    "createdAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "HdTeam_code" UNIQUE ("organizationId", code)
);

CREATE TABLE "HdTeamMember" (
    "teamId" TEXT NOT NULL REFERENCES "HdTeam" (id) ON DELETE CASCADE,
    "userId" TEXT NOT NULL,
    PRIMARY KEY ("teamId", "userId")
);

CREATE TABLE "HdStage" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "teamId"         TEXT NOT NULL REFERENCES "HdTeam" (id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    sequence         INTEGER NOT NULL DEFAULT 10,
    "isClosed"       BOOLEAN NOT NULL DEFAULT false,
    folded           BOOLEAN NOT NULL DEFAULT false,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "HdStage_team_idx" ON "HdStage" ("teamId", sequence);

CREATE TABLE "HdTicketType" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    name             TEXT NOT NULL,
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "HdTicketType_name" UNIQUE ("organizationId", name)
);

CREATE TABLE "HdTicket" (
    id                  TEXT PRIMARY KEY,
    "organizationId"    TEXT NOT NULL REFERENCES "Organization" (id),
    number              TEXT NOT NULL,
    "teamId"            TEXT NOT NULL REFERENCES "HdTeam" (id),
    "stageId"           TEXT NOT NULL REFERENCES "HdStage" (id),
    "typeId"            TEXT REFERENCES "HdTicketType" (id),
    priority            TEXT NOT NULL DEFAULT 'normal' CHECK (priority IN ('low', 'normal', 'high', 'urgent')),
    channel             TEXT NOT NULL DEFAULT 'portal'
                        CHECK (channel IN ('email', 'portal', 'phone', 'walk_in', 'whatsapp', 'letter', 'other')),
    direction           TEXT NOT NULL DEFAULT 'inbound' CHECK (direction IN ('inbound', 'outbound')),
    "partnerId"         TEXT,
    "contactName"       TEXT,
    "contactEmail"      TEXT,
    "contactPhone"      TEXT,
    subject             TEXT NOT NULL,
    description         TEXT,
    "assigneeId"        TEXT,
    tags                TEXT[] NOT NULL DEFAULT '{}',
    "relatedResType"    TEXT,
    "relatedResId"      TEXT,
    "parentTicketId"    TEXT REFERENCES "HdTicket" (id),
    resolution          TEXT,
    "firstResponseAt"   TIMESTAMP(3),
    "closedAt"          TIMESTAMP(3),
    rating              INTEGER CHECK (rating BETWEEN 1 AND 5),
    -- correspondence register (channel = letter)
    "letterNumber"      TEXT,
    "externalRef"       TEXT,
    "ourRef"            TEXT,
    "letterDate"        DATE,
    "receivedAt"        TIMESTAMP(3),
    "sentAt"            TIMESTAMP(3),
    "dispatchMethod"    TEXT CHECK ("dispatchMethod" IN ('post', 'registered_post', 'courier', 'hand', 'email', 'fax')),
    "trackingNumber"    TEXT,
    "dispatchedById"    TEXT,
    confidentiality     TEXT NOT NULL DEFAULT 'normal' CHECK (confidentiality IN ('normal', 'confidential', 'secret')),
    "replyDueOn"        DATE,
    "repliedByTicketId" TEXT REFERENCES "HdTicket" (id),
    version             INTEGER NOT NULL DEFAULT 0,
    "createdById"       TEXT,
    "createdAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"         TIMESTAMP(3),
    CONSTRAINT "HdTicket_number" UNIQUE ("organizationId", number)
);
CREATE UNIQUE INDEX "HdTicket_letter_number" ON "HdTicket" ("organizationId", "letterNumber")
    WHERE "letterNumber" IS NOT NULL;
CREATE INDEX "HdTicket_board_idx" ON "HdTicket" ("organizationId", "teamId", "stageId");
CREATE INDEX "HdTicket_assignee_idx" ON "HdTicket" ("organizationId", "assigneeId");
CREATE INDEX "HdTicket_related_idx" ON "HdTicket" ("organizationId", "relatedResType", "relatedResId");
CREATE INDEX "HdTicket_letters_idx" ON "HdTicket" ("organizationId", direction, "letterDate")
    WHERE channel = 'letter';

CREATE TABLE "HdTicketMessage" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "ticketId"       TEXT NOT NULL REFERENCES "HdTicket" (id) ON DELETE CASCADE,
    -- note: internal; reply: to the customer; inbound: from the customer; system: stage / assignment changes
    kind             TEXT NOT NULL CHECK (kind IN ('note', 'reply', 'inbound', 'system')),
    body             TEXT NOT NULL,
    "authorId"       TEXT,
    "authorName"     TEXT,
    "toEmail"        TEXT,
    "emailSent"      BOOLEAN NOT NULL DEFAULT false,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "HdTicketMessage_ticket_idx" ON "HdTicketMessage" ("ticketId", "createdAt");

CREATE TABLE "HdLetterTemplate" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    code             TEXT NOT NULL,
    name             TEXT NOT NULL,
    subject          TEXT,
    body             TEXT NOT NULL,
    "closingLine"    TEXT,
    "signatureName"  TEXT,
    "signatureTitle" TEXT,
    "isActive"       BOOLEAN NOT NULL DEFAULT true,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "HdLetterTemplate_code" UNIQUE ("organizationId", code)
);
