-- CRM enhancements T3a (post-parity, ADR-S084): public website lead forms and online quotation acceptance.

-- A website form posts enquiries as leads. The public key is not a secret (it sits in the website's HTML); abuse is
-- limited by rate limiting, a honeypot field, an optional origin allow-list and turning the form off.
CREATE TABLE "CrmLeadForm" (
    id                 TEXT         NOT NULL PRIMARY KEY,
    "organizationId"   TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    name               TEXT         NOT NULL,
    "publicKey"        TEXT         NOT NULL UNIQUE,
    "sourceId"         TEXT REFERENCES "CrmSource" (id) ON DELETE SET NULL,
    "teamId"           TEXT REFERENCES "CrmTeam" (id) ON DELETE SET NULL,
    "ownerId"          TEXT,
    "tagIds"           TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    "allowedOrigins"   TEXT[]       NOT NULL DEFAULT ARRAY[]::TEXT[],
    "thankYouMessage"  TEXT,
    "isActive"         BOOLEAN      NOT NULL DEFAULT true,
    submissions        INTEGER      NOT NULL DEFAULT 0,
    "lastSubmittedAt"  TIMESTAMP(3),
    "createdById"      TEXT,
    "createdAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CrmLeadForm_org_idx" ON "CrmLeadForm" ("organizationId");
ALTER TABLE "CrmLeadForm" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "CrmLeadForm" USING (("organizationId" = current_setting('app.org_id', true)));

-- A customer link to view, download, accept or decline one quotation (sales module; licence customerPortal). Only the SHA-256 of the token is stored; one
-- live link per quotation. The decision is kept with the typed signer name, note and client address as evidence.
CREATE TABLE "SalesQuoteLink" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "quotationId"    TEXT         NOT NULL REFERENCES "SalesQuotation" (id) ON DELETE CASCADE,
    "tokenHash"      TEXT         NOT NULL UNIQUE,
    "expiresAt"      TIMESTAMP(3) NOT NULL,
    "revokedAt"      TIMESTAMP(3),
    "firstViewedAt"  TIMESTAMP(3),
    "lastViewedAt"   TIMESTAMP(3),
    "viewCount"      INTEGER      NOT NULL DEFAULT 0,
    decision         TEXT CHECK (decision IN ('accepted', 'declined')),
    "decidedAt"      TIMESTAMP(3),
    "signerName"     TEXT,
    "signerNote"     TEXT,
    "signerIp"       TEXT,
    "createdById"    TEXT,
    "createdAt"      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX "SalesQuoteLink_live_key" ON "SalesQuoteLink" ("quotationId") WHERE "revokedAt" IS NULL;
ALTER TABLE "SalesQuoteLink" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SalesQuoteLink" USING (("organizationId" = current_setting('app.org_id', true)));
