-- ADR-S083 O2: cross-batch reference resolution. A client id minted by a device for an offline shift-open is looked
-- up among that device's applied cash_session.open results; this partial index keeps the lookup to those few rows
-- instead of the device's whole history. (Lookups by opId use "SyncAppliedOp_organizationId_opId_key".)
CREATE INDEX "SyncAppliedOp_session_open_idx" ON "SyncAppliedOp" ("organizationId", "deviceId")
    WHERE "opType" = 'cash_session.open' AND state = 'applied';
