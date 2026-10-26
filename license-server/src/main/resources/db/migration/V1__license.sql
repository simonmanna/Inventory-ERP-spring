-- V1: the licence authority's own schema.
--
-- This database is independent of the ERP's. It is greenfield, so it uses idiomatic snake_case
-- identifiers rather than the ERP's quoted camelCase (a Prisma artefact frozen by ADR-S001).
-- Enumerations are TEXT + CHECK rather than Postgres enum types: new statuses and periods ship as
-- an ordinary migration instead of an ALTER TYPE that cannot run in a transaction.

CREATE TABLE customer (
    id          TEXT PRIMARY KEY,
    name        TEXT        NOT NULL,
    email       TEXT,
    phone       TEXT,
    country     TEXT,
    tax_id      TEXT,
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX customer_email_key ON customer (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX customer_name_idx ON customer (lower(name));

CREATE TABLE product (
    code        TEXT PRIMARY KEY,
    name        TEXT        NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE plan (
    id                TEXT PRIMARY KEY,
    code              TEXT        NOT NULL UNIQUE,
    name              TEXT        NOT NULL,
    product_code      TEXT        NOT NULL REFERENCES product (code),
    period            TEXT        NOT NULL CHECK (period IN ('DAY', 'MONTH', 'YEAR', 'TRIAL', 'PERPETUAL')),
    period_count      INT         NOT NULL CHECK (period_count >= 1),
    price_minor       BIGINT      NOT NULL DEFAULT 0 CHECK (price_minor >= 0),
    currency          TEXT        NOT NULL DEFAULT 'USD',
    max_devices       INT         CHECK (max_devices IS NULL OR max_devices >= 0),
    max_users         INT         CHECK (max_users IS NULL OR max_users >= 0),
    max_organizations INT         CHECK (max_organizations IS NULL OR max_organizations >= 0),
    instance_limit    INT         NOT NULL DEFAULT 1 CHECK (instance_limit >= 1),
    features          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    grace_days        INT         NOT NULL DEFAULT 7 CHECK (grace_days >= 0),
    is_trial          BOOLEAN     NOT NULL DEFAULT false,
    is_active         BOOLEAN     NOT NULL DEFAULT true,
    sort_order        INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX plan_active_idx ON plan (is_active, sort_order);

CREATE TABLE license (
    id                TEXT PRIMARY KEY,
    key               TEXT        NOT NULL UNIQUE,
    customer_id       TEXT        NOT NULL REFERENCES customer (id),
    plan_id           TEXT        NOT NULL REFERENCES plan (id),
    status            TEXT        NOT NULL CHECK (status IN ('pending', 'active', 'suspended', 'expired', 'revoked')),
    issued_at         TIMESTAMPTZ,
    starts_at         TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ,
    grace_days        INT         NOT NULL DEFAULT 7 CHECK (grace_days >= 0),
    auto_renew        BOOLEAN     NOT NULL DEFAULT false,
    instance_limit    INT         CHECK (instance_limit IS NULL OR instance_limit >= 1),
    max_devices       INT         CHECK (max_devices IS NULL OR max_devices >= 0),
    max_users         INT         CHECK (max_users IS NULL OR max_users >= 0),
    max_organizations INT         CHECK (max_organizations IS NULL OR max_organizations >= 0),
    feature_overrides JSONB,
    notes             TEXT,
    created_by        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Optimistic locking: two admins deciding on the same licence is a 409, not a lost decision.
    version           BIGINT      NOT NULL DEFAULT 0,
    -- A dated licence must have both ends; only PERPETUAL may leave expires_at open.
    CONSTRAINT license_dates_ck CHECK (starts_at IS NOT NULL OR status = 'pending')
);
CREATE INDEX license_customer_idx ON license (customer_id);
CREATE INDEX license_status_idx ON license (status);
CREATE INDEX license_expiry_idx ON license (expires_at) WHERE status = 'active';

CREATE TABLE license_activation (
    id            TEXT PRIMARY KEY,
    license_id    TEXT        NOT NULL REFERENCES license (id) ON DELETE CASCADE,
    fingerprint   TEXT        NOT NULL,
    instance_name TEXT,
    hostname      TEXT,
    app_version   TEXT,
    status        TEXT        NOT NULL CHECK (status IN ('active', 'deactivated')),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deactivated_at TIMESTAMPTZ,
    UNIQUE (license_id, fingerprint)
);
CREATE INDEX license_activation_license_idx ON license_activation (license_id, status);
CREATE INDEX license_activation_last_seen_idx ON license_activation (last_seen_at) WHERE status = 'active';

CREATE TABLE license_heartbeat (
    id            BIGSERIAL PRIMARY KEY,
    activation_id TEXT        NOT NULL REFERENCES license_activation (id) ON DELETE CASCADE,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    device_count  INT,
    user_count    INT,
    org_count     INT,
    app_version   TEXT,
    mode          TEXT,
    ip            TEXT
);
CREATE INDEX license_heartbeat_activation_idx ON license_heartbeat (activation_id, at DESC);
CREATE INDEX license_heartbeat_at_idx ON license_heartbeat (at);

-- Deliberately WITHOUT foreign keys on license_id / activation_id.
--
-- An event records that something was *attempted*, so it is written in its own transaction and has
-- to survive the rollback of whatever it was recording: a refused activation rolls back, and the
-- evidence that it happened - and from which fingerprint - must stay. A foreign key would both fail
-- on the not-yet-committed row and delete the history later. The columns are plain references.
CREATE TABLE license_event (
    id            BIGSERIAL PRIMARY KEY,
    license_id    TEXT,
    activation_id TEXT,
    type          TEXT        NOT NULL,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor         TEXT,
    detail        JSONB
);
CREATE INDEX license_event_license_idx ON license_event (license_id, at DESC);
CREATE INDEX license_event_type_idx ON license_event (type, at DESC);

CREATE TABLE signing_key (
    id                  TEXT PRIMARY KEY,
    kid                 TEXT        NOT NULL UNIQUE,
    algorithm           TEXT        NOT NULL DEFAULT 'Ed25519',
    public_key          TEXT        NOT NULL,
    private_key_cipher  TEXT        NOT NULL,
    active_from         TIMESTAMPTZ NOT NULL DEFAULT now(),
    retired_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- At most one key may be current at a time.
CREATE UNIQUE INDEX signing_key_one_active ON signing_key ((retired_at IS NULL)) WHERE retired_at IS NULL;

CREATE TABLE admin_user (
    id                  TEXT PRIMARY KEY,
    email               TEXT        NOT NULL UNIQUE,
    password_hash       TEXT        NOT NULL,
    display_name        TEXT,
    role                TEXT        NOT NULL CHECK (role IN ('owner', 'support')),
    totp_secret_cipher  TEXT,
    totp_enrolled_at    TIMESTAMPTZ,
    is_active           BOOLEAN     NOT NULL DEFAULT true,
    failed_login_count  INT         NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE admin_refresh_token (
    id          TEXT PRIMARY KEY,
    admin_id    TEXT        NOT NULL REFERENCES admin_user (id) ON DELETE CASCADE,
    token_hash  TEXT        NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX admin_refresh_token_admin_idx ON admin_refresh_token (admin_id);

CREATE TABLE admin_audit (
    id         BIGSERIAL PRIMARY KEY,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    admin_id   TEXT,
    admin_email TEXT,
    action     TEXT        NOT NULL,
    target     TEXT,
    ip         TEXT,
    detail     JSONB
);
CREATE INDEX admin_audit_at_idx ON admin_audit (at DESC);

-- ShedLock: renewal reminders and retention sweeps run on one instance only.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
