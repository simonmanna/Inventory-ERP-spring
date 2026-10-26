-- V4: the install's own licence state (ADR-S002).
--
-- These two tables are the documented exception to "every table is filtered by organizationId"
-- (AGENTS.md §8): a licence binds the *installation*, not a tenant. One deployment holds one
-- licence however many organizations live in its database, which is why a multi-organization
-- licence has an `organizations` limit rather than a row per organization.

CREATE TABLE "LicenseInstallation" (
    -- Exactly one row. The constant primary key is what enforces that.
    id                    TEXT        NOT NULL PRIMARY KEY DEFAULT 'installation',
    -- This installation's own identity, generated once on first boot. It lives here rather than in
    -- "Setting" because that table is organization-scoped and under row-level security: a row with a
    -- NULL organizationId would be invisible to the very queries that need it.
    "installId"           TEXT        NOT NULL,
    -- sha256("installId" + Postgres cluster system_identifier), lower hex.
    fingerprint           TEXT        NOT NULL,
    -- NULL until someone activates. Stored so the install can re-check in by itself.
    "licenseKey"          TEXT,
    -- The signed licence. NULL means unlicensed (trial or, once the trial is over, read-only).
    token                 TEXT,
    "tokenKid"            TEXT,
    -- The verified claims, kept so the UI and the guard never re-parse the token per request.
    claims                JSONB,
    -- Enforcement mode last computed: UNLICENSED, TRIAL, ACTIVE, GRACE, READ_ONLY, REVOKED.
    mode                  TEXT        NOT NULL DEFAULT 'UNLICENSED',
    -- The licence's server-side status, as the authority last reported it.
    status                TEXT,
    "startsAt"            TIMESTAMPTZ,
    "expiresAt"           TIMESTAMPTZ,
    "graceUntil"          TIMESTAMPTZ,
    "refreshAfter"        TIMESTAMPTZ,
    "lastCheckAt"         TIMESTAMPTZ,
    "lastCheckOk"         BOOLEAN     NOT NULL DEFAULT false,
    "lastCheckError"      TEXT,
    -- Highest server time ever seen. A local clock behind this is treated as tampering.
    "serverTimeHighWater" TIMESTAMPTZ,
    -- JWKS last fetched from the authority, cached so a restart with no network can still verify a
    -- token signed by a key that was rotated in after this release was built.
    "trustedKeys"         JSONB,
    -- Set on first boot; the trial runs from here when no licence has ever been activated.
    "createdAt"           TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt"           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "LicenseInstallation_singleton" CHECK (id = 'installation')
);

-- Local, append-only record of every mode change. Independent of the central server's audit so a
-- lockout can be explained from the install itself, with no network and no access to the authority.
CREATE TABLE "LicenseAuditLocal" (
    id         BIGSERIAL   PRIMARY KEY,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    "fromMode" TEXT,
    "toMode"   TEXT        NOT NULL,
    reason     TEXT,
    detail     JSONB
);
CREATE INDEX "LicenseAuditLocal_at_idx" ON "LicenseAuditLocal" (at DESC);
