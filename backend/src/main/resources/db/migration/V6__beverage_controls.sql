-- V6: beverage count controls (ADR-S004).
--
-- Deliberate deviation from the NestJS reference (docs/spec/beverage.md, "Deviations"):
--   1. "reasonCode" classifies a bottle-count line's variance (spillage, comp, staff drink, ...) next to the
--      existing free-text "reason", so variance can be reported by cause. NULL = not classified (Nest rows).
--   2. "unweighedReason" / "unweighedCount" record a supervisor-approved exception when a count is submitted
--      while bottles that hold stock were left unweighed. Only enforced when the organization opts in with
--      the setting beverage.requireAllBottlesWeighed = true; otherwise submit behaves exactly as in Nest.

ALTER TABLE "BottleCountLine"
    ADD COLUMN "reasonCode" TEXT
        CHECK ("reasonCode" IN ('spillage', 'breakage', 'complimentary', 'staff_drink', 'unrecorded_sale',
                                'bottle_transfer', 'measurement_error', 'unexplained'));

ALTER TABLE "BottleCountSession"
    ADD COLUMN "unweighedReason" TEXT,
    ADD COLUMN "unweighedCount"  INTEGER NOT NULL DEFAULT 0 CHECK ("unweighedCount" >= 0);
