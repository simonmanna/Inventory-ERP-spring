-- ADR-S083 O5: sales orders, quotations and customer payments recorded on an offline device say so. "occurredAt" is
-- when the rep recorded it; the document's own dates stay the server's. Defaulted / nullable: existing rows unaffected.
ALTER TABLE "SalesOrder" ADD COLUMN "recordedOffline" boolean DEFAULT false NOT NULL;
ALTER TABLE "SalesOrder" ADD COLUMN "occurredAt" timestamp(3) without time zone;
ALTER TABLE "SalesQuotation" ADD COLUMN "recordedOffline" boolean DEFAULT false NOT NULL;
ALTER TABLE "SalesQuotation" ADD COLUMN "occurredAt" timestamp(3) without time zone;
ALTER TABLE "Payment" ADD COLUMN "recordedOffline" boolean DEFAULT false NOT NULL;
ALTER TABLE "Payment" ADD COLUMN "occurredAt" timestamp(3) without time zone;
-- Payments carry no creator of their own: the rep who recorded an offline payment (the op's actor).
ALTER TABLE "Payment" ADD COLUMN "offlineActorId" text;

-- The offline sales review queue: an order or quote priced differently from what the rep saw (beyond the tolerance),
-- an order the server could not confirm, the rep's offline exposure cap exceeded, a payment whose allocations could
-- not all be applied. Each is reviewed and resolved by a person; nothing here changes a document by itself.
CREATE TABLE "SyncReviewItem" (
    id text NOT NULL PRIMARY KEY,
    "organizationId" text NOT NULL,
    kind text NOT NULL,
    status text DEFAULT 'open' NOT NULL,
    "documentType" text NOT NULL,
    "documentId" text,
    "documentNumber" text,
    "partnerId" text,
    "opId" text,
    "opType" text,
    "deviceId" text,
    "actorUserId" text,
    "occurredAt" timestamp(3) without time zone,
    "expectedAmount" numeric(20, 6),
    "actualAmount" numeric(20, 6),
    "variancePercent" numeric(20, 6),
    reason text NOT NULL,
    details jsonb,
    "createdAt" timestamp(3) without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    "resolvedAt" timestamp(3) without time zone,
    "resolvedById" text,
    "resolutionNote" text,
    CONSTRAINT "SyncReviewItem_kind_check" CHECK (kind IN ('price_variance', 'confirm_refused', 'offline_cap',
        'payment_allocation')),
    CONSTRAINT "SyncReviewItem_status_check" CHECK (status IN ('open', 'resolved'))
);
CREATE INDEX "SyncReviewItem_open_idx" ON "SyncReviewItem" ("organizationId", status, kind, "createdAt");
CREATE INDEX "SyncReviewItem_document_idx" ON "SyncReviewItem" ("organizationId", "documentType", "documentId");
ALTER TABLE "SyncReviewItem" ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON "SyncReviewItem" USING (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true))
    WITH CHECK (coalesce(current_setting('app.org_id', true), '') = '' OR "organizationId" = current_setting('app.org_id', true));

-- Offline payments per rep and day (the payment reconciliation report).
CREATE INDEX "Payment_offline_idx" ON "Payment" ("organizationId", "offlineActorId", "occurredAt") WHERE "recordedOffline";

-- Payment rows are evidence (guard_payment, V1). The O5 stamp is the one change allowed after posting, and only once:
-- "recordedOffline" false -> true with "occurredAt" and "offlineActorId" set from nothing. Every other rule is exactly as in V1, and the
-- stamp itself is immutable afterwards.
CREATE OR REPLACE FUNCTION public.guard_payment()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
BEGIN
  IF evidence_purge_enabled() THEN RETURN COALESCE(NEW, OLD); END IF;
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Payment % cannot be deleted; void it', OLD."paymentNumber" USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF OLD."journalEntryId" IS NOT NULL AND NEW."journalEntryId" IS DISTINCT FROM OLD."journalEntryId" THEN
    RAISE EXCEPTION 'Payment % journal link is immutable', OLD."paymentNumber" USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF OLD.status = 'cancelled' AND NEW.status <> 'cancelled' THEN
    RAISE EXCEPTION 'Voided payment % cannot be restored', OLD."paymentNumber" USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF (NEW."recordedOffline" IS DISTINCT FROM OLD."recordedOffline" OR NEW."occurredAt" IS DISTINCT FROM OLD."occurredAt"
      OR NEW."offlineActorId" IS DISTINCT FROM OLD."offlineActorId")
     AND NOT (OLD."recordedOffline" = false AND OLD."occurredAt" IS NULL AND OLD."offlineActorId" IS NULL
              AND NEW."recordedOffline" = true) THEN
    RAISE EXCEPTION 'Payment % offline stamp is immutable', OLD."paymentNumber" USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF evidence_row_changed(to_jsonb(OLD), to_jsonb(NEW), ARRAY['status','journalEntryId','allocatedAmount','unallocatedAmount','refundedAmount','voidedAt','voidedById','voidReason','voidedAllocations','updatedAt','updatedBy','recordedOffline','occurredAt','offlineActorId']) THEN
    RAISE EXCEPTION 'Payment % amount, tender, account and drawer are immutable', OLD."paymentNumber" USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NEW;
END
$function$;
