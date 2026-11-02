-- V238: Document management (DMS, ADR-S044; Spring-native, no Nest reference). Workspaces, folders, documents with
-- versions, links to any ERP record, tags, workspace access rules, activity, document requests and external share
-- links. File bytes stay in the kernel "File" vault; DMS rows only reference "File".id.

CREATE TABLE "DmsWorkspace" (
    id                   TEXT PRIMARY KEY,
    "organizationId"     TEXT NOT NULL REFERENCES "Organization" (id),
    code                 TEXT NOT NULL,
    name                 TEXT NOT NULL,
    description          TEXT,
    "parentId"           TEXT REFERENCES "DmsWorkspace" (id),
    icon                 TEXT,
    color                TEXT,
    -- permission needed on top of dms:document:read to see the workspace (e.g. sales:order:read for "sales")
    "requiredPermission" TEXT,
    "requiresApproval"   BOOLEAN NOT NULL DEFAULT false,
    "isSystem"           BOOLEAN NOT NULL DEFAULT false,
    "sortOrder"          INTEGER NOT NULL DEFAULT 0,
    "archivedAt"         TIMESTAMP(3),
    "createdById"        TEXT,
    "createdAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DmsWorkspace_code" UNIQUE ("organizationId", code)
);

CREATE TABLE "DmsFolder" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "workspaceId"    TEXT NOT NULL REFERENCES "DmsWorkspace" (id),
    "parentId"       TEXT REFERENCES "DmsFolder" (id),
    name             TEXT NOT NULL,
    "sortOrder"      INTEGER NOT NULL DEFAULT 0,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "DmsFolder_ws_idx" ON "DmsFolder" ("organizationId", "workspaceId", "parentId");
CREATE UNIQUE INDEX "DmsFolder_name" ON "DmsFolder" ("workspaceId", COALESCE("parentId", ''), lower(name));

CREATE TABLE "DmsDocument" (
    id                 TEXT PRIMARY KEY,
    "organizationId"   TEXT NOT NULL REFERENCES "Organization" (id),
    "workspaceId"      TEXT NOT NULL REFERENCES "DmsWorkspace" (id),
    "folderId"         TEXT REFERENCES "DmsFolder" (id),
    name               TEXT NOT NULL,
    description        TEXT,
    "fileId"           TEXT NOT NULL REFERENCES "File" (id),
    "contentType"      TEXT,
    "byteSize"         INTEGER NOT NULL DEFAULT 0,
    "currentVersion"   INTEGER NOT NULL DEFAULT 1,
    "ownerUserId"      TEXT,
    "partnerId"        TEXT,
    status             TEXT NOT NULL DEFAULT 'active'
                       CHECK (status IN ('active', 'pending_approval', 'approved', 'rejected', 'archived')),
    "approvalRequestId" TEXT,
    "expiresOn"        DATE,
    "reminderLeadDays" INTEGER NOT NULL DEFAULT 30 CHECK ("reminderLeadDays" BETWEEN 0 AND 365),
    "expiryNotifiedAt" TIMESTAMP(3),
    "expiredNotifiedAt" TIMESTAMP(3),
    "lockedById"       TEXT,
    "lockedAt"         TIMESTAMP(3),
    -- native: uploaded through DMS; linked: an attachment owned by another module (read-only here)
    "sourceKind"       TEXT NOT NULL DEFAULT 'native' CHECK ("sourceKind" IN ('native', 'linked')),
    "legacyOwnerType"  TEXT,
    "contentText"      TEXT,
    "textStatus"       TEXT NOT NULL DEFAULT 'pending' CHECK ("textStatus" IN ('pending', 'done', 'skipped', 'failed')),
    "searchVector"     TSVECTOR GENERATED ALWAYS AS (
                         to_tsvector('simple'::regconfig, coalesce(name, '') || ' ' || coalesce(description, '') || ' '
                           || coalesce(left("contentText", 200000), ''))) STORED,
    version            INTEGER NOT NULL DEFAULT 0,
    "createdById"      TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deletedAt"        TIMESTAMP(3)
);
CREATE INDEX "DmsDocument_ws_idx" ON "DmsDocument" ("organizationId", "workspaceId", "folderId");
CREATE INDEX "DmsDocument_search_idx" ON "DmsDocument" USING GIN ("searchVector");
CREATE INDEX "DmsDocument_expiry_idx" ON "DmsDocument" ("expiresOn") WHERE "expiresOn" IS NOT NULL;
CREATE INDEX "DmsDocument_text_idx" ON "DmsDocument" ("textStatus") WHERE "textStatus" = 'pending';
-- a legacy (module-owned) file is surfaced at most once
CREATE UNIQUE INDEX "DmsDocument_linked_file" ON "DmsDocument" ("fileId") WHERE "sourceKind" = 'linked';

CREATE TABLE "DmsDocumentVersion" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "documentId"     TEXT NOT NULL REFERENCES "DmsDocument" (id) ON DELETE CASCADE,
    "versionNo"      INTEGER NOT NULL,
    "fileId"         TEXT NOT NULL REFERENCES "File" (id),
    filename         TEXT NOT NULL,
    "contentType"    TEXT,
    "byteSize"       INTEGER NOT NULL DEFAULT 0,
    note             TEXT,
    "uploadedById"   TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DmsDocumentVersion_no" UNIQUE ("documentId", "versionNo")
);

CREATE TABLE "DmsDocumentLink" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "documentId"     TEXT NOT NULL REFERENCES "DmsDocument" (id) ON DELETE CASCADE,
    "resType"        TEXT NOT NULL,
    "resId"          TEXT NOT NULL,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DmsDocumentLink_unique" UNIQUE ("documentId", "resType", "resId")
);
CREATE INDEX "DmsDocumentLink_res_idx" ON "DmsDocumentLink" ("organizationId", "resType", "resId");

CREATE TABLE "DmsTagCategory" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    name             TEXT NOT NULL,
    "sortOrder"      INTEGER NOT NULL DEFAULT 0,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DmsTagCategory_name" UNIQUE ("organizationId", name)
);

CREATE TABLE "DmsTag" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "categoryId"     TEXT REFERENCES "DmsTagCategory" (id) ON DELETE SET NULL,
    name             TEXT NOT NULL,
    color            TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DmsTag_name" UNIQUE ("organizationId", name)
);

CREATE TABLE "DmsDocumentTag" (
    "documentId" TEXT NOT NULL REFERENCES "DmsDocument" (id) ON DELETE CASCADE,
    "tagId"      TEXT NOT NULL REFERENCES "DmsTag" (id) ON DELETE CASCADE,
    PRIMARY KEY ("documentId", "tagId")
);
CREATE INDEX "DmsDocumentTag_tag_idx" ON "DmsDocumentTag" ("tagId");

CREATE TABLE "DmsAccessRule" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "workspaceId"    TEXT NOT NULL REFERENCES "DmsWorkspace" (id) ON DELETE CASCADE,
    "userId"         TEXT,
    "roleId"         TEXT,
    level            TEXT NOT NULL CHECK (level IN ('read', 'write', 'manage')),
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "DmsAccessRule_subject" CHECK (("userId" IS NULL) <> ("roleId" IS NULL))
);
CREATE INDEX "DmsAccessRule_ws_idx" ON "DmsAccessRule" ("workspaceId");

CREATE TABLE "DmsDocumentActivity" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "documentId"     TEXT NOT NULL REFERENCES "DmsDocument" (id) ON DELETE CASCADE,
    action           TEXT NOT NULL,
    detail           JSONB,
    "actorId"        TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "DmsDocumentActivity_doc_idx" ON "DmsDocumentActivity" ("documentId", "createdAt");

CREATE TABLE "DmsRequest" (
    id                    TEXT PRIMARY KEY,
    "organizationId"      TEXT NOT NULL REFERENCES "Organization" (id),
    title                 TEXT NOT NULL,
    note                  TEXT,
    "workspaceId"         TEXT NOT NULL REFERENCES "DmsWorkspace" (id),
    "requestedFromUserId" TEXT NOT NULL,
    "requestedById"       TEXT,
    "resType"             TEXT,
    "resId"               TEXT,
    "dueOn"               DATE,
    status                TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'fulfilled', 'cancelled')),
    "fulfilledDocumentId" TEXT REFERENCES "DmsDocument" (id),
    "fulfilledAt"         TIMESTAMP(3),
    "overdueNotifiedAt"   TIMESTAMP(3),
    "createdAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"           TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "DmsRequest_org_idx" ON "DmsRequest" ("organizationId", status);

CREATE TABLE "DmsShareLink" (
    id               TEXT PRIMARY KEY,
    "organizationId" TEXT NOT NULL REFERENCES "Organization" (id),
    "tokenHash"      TEXT NOT NULL UNIQUE,
    name             TEXT,
    -- documents: the listed documents; folder: every document of the folder (or workspace root when folderId null)
    scope            TEXT NOT NULL CHECK (scope IN ('documents', 'folder')),
    "documentIds"    TEXT[] NOT NULL DEFAULT '{}',
    "workspaceId"    TEXT REFERENCES "DmsWorkspace" (id),
    "folderId"       TEXT REFERENCES "DmsFolder" (id),
    "allowDownload"  BOOLEAN NOT NULL DEFAULT true,
    "allowUpload"    BOOLEAN NOT NULL DEFAULT false,
    "passwordHash"   TEXT,
    "expiresAt"      TIMESTAMP(3) NOT NULL,
    "maxDownloads"   INTEGER,
    "downloadCount"  INTEGER NOT NULL DEFAULT 0,
    "uploadCount"    INTEGER NOT NULL DEFAULT 0,
    "lastAccessedAt" TIMESTAMP(3),
    "revokedAt"      TIMESTAMP(3),
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "DmsShareLink_org_idx" ON "DmsShareLink" ("organizationId", "createdAt" DESC);
