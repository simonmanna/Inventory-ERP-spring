-- V574: loss prevention operations insights, first slice (LP6a, ADR-S077 §10). The eight metrics that need no camera
-- are computed from the event ledger into summary rows, per branch and business day, with hourly rows beside the
-- daily ones. A summary is derived data: it is replaced whenever its day is computed again, unlike the ledger.

-- One row per branch and business day that has metric events. An accepted event makes its day dirty (again); the
-- insights job computes dirty days after a settling time. "version" goes up with every marking, so a day marked
-- while it was being computed stays dirty.
CREATE TABLE "LpMetricDay" (
    id               TEXT         NOT NULL PRIMARY KEY,
    "organizationId" TEXT         NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    -- '' when the events carry no branch.
    "branchId"       TEXT         NOT NULL DEFAULT '',
    "businessDate"   DATE         NOT NULL,
    state            TEXT         NOT NULL DEFAULT 'dirty' CHECK (state IN ('dirty', 'computed')),
    version          BIGINT       NOT NULL DEFAULT 1,
    "dirtyAt"        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "computedAt"     TIMESTAMP(3),
    -- The highest ledger seq the last computation read, and how many summary rows it wrote.
    "maxSeq"         BIGINT,
    "rowCount"       INTEGER,
    -- Why the last attempt failed; cleared by a successful one. A failed day stays dirty and is tried again.
    "lastError"      TEXT,
    attempts         INTEGER      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX "LpMetricDay_org_branch_date_key" ON "LpMetricDay" ("organizationId", "branchId", "businessDate");
CREATE INDEX "LpMetricDay_dirty_idx" ON "LpMetricDay" ("organizationId", "dirtyAt") WHERE state = 'dirty';

-- The summary rows. hour is 0–23 in the business time zone, or -1 for the whole day. dimension is '' for the
-- branch total, else what dimensionKey names: station, actor, table, product or session ('' key: not recorded).
-- Every metric is a count and a total; what the total adds up is stated per metric in docs/spec/loss-prevention.md.
-- p50 and p90 are kept for durations, for the row's own day and hour.
CREATE TABLE "LpMetricValue" (
    "organizationId" TEXT           NOT NULL REFERENCES "Organization" (id) ON DELETE CASCADE,
    "branchId"       TEXT           NOT NULL DEFAULT '',
    "businessDate"   DATE           NOT NULL,
    hour             SMALLINT       NOT NULL CHECK (hour BETWEEN -1 AND 23),
    metric           TEXT           NOT NULL,
    dimension        TEXT           NOT NULL DEFAULT '',
    "dimensionKey"   TEXT           NOT NULL DEFAULT '',
    count            BIGINT         NOT NULL DEFAULT 0,
    total            NUMERIC(20, 4) NOT NULL DEFAULT 0,
    p50              NUMERIC(14, 2),
    p90              NUMERIC(14, 2),
    PRIMARY KEY ("organizationId", "branchId", "businessDate", hour, metric, dimension, "dimensionKey")
);

CREATE INDEX "LpMetricValue_org_metric_date_idx" ON "LpMetricValue" ("organizationId", metric, "businessDate");

-- A payload number may be a JSON number or a decimal string, and a rule skips an event whose field it cannot read.
-- The metrics do the same: an unreadable value counts as missing rather than failing the whole day.
CREATE FUNCTION lp_num(v TEXT) RETURNS NUMERIC AS $$
BEGIN
    IF v IS NULL OR btrim(v) = '' THEN
        RETURN NULL;
    END IF;
    RETURN v::numeric;
EXCEPTION WHEN others THEN
    RETURN NULL;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['LpMetricDay', 'LpMetricValue'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I'
            || ' USING (coalesce(current_setting(''app.org_id'', true), '''') = '''''
            || ' OR "organizationId" = current_setting(''app.org_id'', true))'
            || ' WITH CHECK (coalesce(current_setting(''app.org_id'', true), '''') = '''''
            || ' OR "organizationId" = current_setting(''app.org_id'', true))', t);
    END LOOP;
END;
$$;

-- What the ledger already holds is computed by the first runs of the job.
INSERT INTO "LpMetricDay" (id, "organizationId", "branchId", "businessDate")
SELECT gen_random_uuid()::text, e."organizationId", coalesce(e."branchId", ''), e."businessDate"
FROM "LpEvent" e
WHERE e.type IN ('sale.completed', 'sale.line_voided', 'refund.issued', 'cash_session.closed',
                 'kitchen.ticket_created', 'kitchen.stage', 'stock_count.line')
GROUP BY e."organizationId", coalesce(e."branchId", ''), e."businessDate"
ON CONFLICT DO NOTHING;
