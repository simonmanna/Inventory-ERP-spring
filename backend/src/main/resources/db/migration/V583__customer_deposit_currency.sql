-- Foreign-currency customer deposits (ADR-S080). A deposit is held in one currency: "amount", "appliedAmount" and
-- "refundedAmount" stay in that currency and "exchangeRate" is functional per unit at receipt (or the down-payment
-- invoice's rate); the functional value on the customer-deposit liability is round(amount * "exchangeRate", 6). An
-- advance is a non-monetary item (IFRIC 22): it is never revalued; it is relieved at this rate when applied or
-- refunded. "currencyCode" null = the functional currency; existing deposits are functional (rate 1).
ALTER TABLE "CustomerDeposit"
    ADD COLUMN "currencyCode" TEXT,
    ADD COLUMN "exchangeRate" NUMERIC(20, 10) NOT NULL DEFAULT 1 CHECK ("exchangeRate" > 0),
    ADD COLUMN "rateSource"   TEXT;
