-- ADR-S083 O6: protocol-2 clients never re-key a rejected op, so the push refuses a device sequence number already
-- used by another opId of the same device (SEQ_REUSED). The applied-op ledger has its (organizationId, deviceId,
-- deviceSeq) index since V593; this gives the dead letters the same lookup.
CREATE INDEX IF NOT EXISTS "SyncOpDeadLetter_device_seq_idx"
    ON "SyncOpDeadLetter" ("organizationId", "deviceId", "deviceSeq");
