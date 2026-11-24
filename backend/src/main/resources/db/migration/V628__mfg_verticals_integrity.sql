-- Integrity fix 2026-11-24 (MV-2a): at most one stockpile survey per stockpile x product waits for approval.
-- Two open surveys of the same pile both applied their variance against the same book stock (double correction).
-- The service refuses a second submission with a clean 400 under an advisory lock; this index is the backstop.
CREATE UNIQUE INDEX "CrushingStockpileSurvey_one_submitted_key"
    ON "CrushingStockpileSurvey" ("organizationId", "stockpileId", "productId")
    WHERE status = 'submitted' AND "deletedAt" IS NULL;
