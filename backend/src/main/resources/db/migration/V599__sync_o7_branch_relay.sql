-- ADR-S083 O7: branch relay node. A server started with erp.sync.mode=branch accepts the branch's tills on the LAN,
-- serves them a mirror of HQ's pull scopes and relays their ops to HQ. These tables stay empty at HQ.

-- Every op a till pushed to the branch, committed before the till is answered (C3 relay_state).
CREATE TABLE "SyncRelayOutbox" (
    id text NOT NULL,
    "organizationId" text NOT NULL,
    "opId" text NOT NULL,
    "payloadHash" text NOT NULL,
    -- The branch's own sequence towards HQ (HQ sees the branch as one protocol-2 device).
    "relaySeq" integer NOT NULL,
    "deviceId" text NOT NULL,
    "deviceSeq" integer NOT NULL,
    "opType" text NOT NULL,
    "actorUserId" text NOT NULL,
    "occurredAt" text NOT NULL,
    payload jsonb NOT NULL,
    "originChain" jsonb DEFAULT '[]'::jsonb NOT NULL,
    "supersedesOpId" text,
    -- Set when the branch already knows HQ must hold the op (revoked/expired till, reused sequence).
    "holdCode" text,
    "cashSessionId" text,
    "relayState" text NOT NULL,
    "hqStatus" text,
    "hqHttpStatus" integer,
    "hqResult" jsonb,
    "failureCode" text,
    "failureMessage" text,
    "hqDeadLetterStatus" text,
    attempts integer DEFAULT 0 NOT NULL,
    "nextAttemptAt" timestamp(3) without time zone,
    "lastAttemptAt" timestamp(3) without time zone,
    "acceptedAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "relayedAt" timestamp(3) without time zone,
    CONSTRAINT "SyncRelayOutbox_pkey" PRIMARY KEY (id),
    CONSTRAINT "SyncRelayOutbox_relayState_check"
        CHECK ("relayState" IN ('accepted_local', 'pending_hq', 'hq_posted', 'hq_rejected'))
);
CREATE UNIQUE INDEX "SyncRelayOutbox_organizationId_opId_key" ON "SyncRelayOutbox" ("organizationId", "opId");
CREATE UNIQUE INDEX "SyncRelayOutbox_organizationId_relaySeq_key" ON "SyncRelayOutbox" ("organizationId", "relaySeq");
CREATE INDEX "SyncRelayOutbox_pending_idx" ON "SyncRelayOutbox" ("organizationId", "relaySeq")
    WHERE "relayState" IN ('accepted_local', 'pending_hq');
CREATE INDEX "SyncRelayOutbox_device_seq_idx" ON "SyncRelayOutbox" ("organizationId", "deviceId", "deviceSeq");
CREATE INDEX "SyncRelayOutbox_session_idx" ON "SyncRelayOutbox" ("organizationId", "cashSessionId");
ALTER TABLE "SyncRelayOutbox" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SyncRelayOutbox" USING (coalesce(current_setting('app.org_id', true), '') = ''
    OR "organizationId" = current_setting('app.org_id', true));

-- HQ's pull scopes as the branch last received them (read-only master data at the branch, S6).
CREATE TABLE "SyncBranchMirror" (
    "organizationId" text NOT NULL,
    scope text NOT NULL,
    "rowId" text NOT NULL,
    "row" jsonb NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    ord integer DEFAULT 0 NOT NULL,
    "storedAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT "SyncBranchMirror_pkey" PRIMARY KEY ("organizationId", scope, "rowId")
);
CREATE INDEX "SyncBranchMirror_stored_idx" ON "SyncBranchMirror" ("organizationId", scope, "storedAt");
ALTER TABLE "SyncBranchMirror" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SyncBranchMirror" USING (coalesce(current_setting('app.org_id', true), '') = ''
    OR "organizationId" = current_setting('app.org_id', true));

-- One row: cursors towards HQ, the relay sequence and HQ's high-water mark for it, HQ's last pull metadata.
CREATE TABLE "SyncBranchState" (
    "organizationId" text NOT NULL,
    "hqCursor" text,
    "feedCursor" text,
    "relaySeq" integer DEFAULT 0 NOT NULL,
    "hqSeqHighWater" integer DEFAULT 0 NOT NULL,
    meta jsonb DEFAULT '{}'::jsonb NOT NULL,
    "lastPullAt" timestamp(3) without time zone,
    "lastFeedAt" timestamp(3) without time zone,
    "lastRelayAt" timestamp(3) without time zone,
    "lastHqContactAt" timestamp(3) without time zone,
    "lastError" text,
    "lastErrorAt" timestamp(3) without time zone,
    "updatedAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT "SyncBranchState_pkey" PRIMARY KEY ("organizationId")
);
ALTER TABLE "SyncBranchState" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SyncBranchState" USING (coalesce(current_setting('app.org_id', true), '') = ''
    OR "organizationId" = current_setting('app.org_id', true));

-- HQ: the relay feed reads a branch device's ledger rows and dead letters changed since its cursor.
CREATE INDEX IF NOT EXISTS "SyncAppliedOp_device_committed_idx" ON "SyncAppliedOp" ("organizationId", "deviceId",
    "committedAt");
