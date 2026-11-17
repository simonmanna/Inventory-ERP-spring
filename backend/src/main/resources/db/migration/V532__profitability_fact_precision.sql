-- Review 2026-11-17 (costing review, profitability): segment facts and reconciliation lines keep the journal's
-- precision (JournalLine amounts are numeric(20,6)), so a run's reconciliation — segments + unallocated + out of
-- scope = GL net income — is exact; screens round for display. Stored at two decimals, a ledger with sub-cent cost
-- postings (average-cost issues, production reversals) showed a false difference of a cent.
ALTER TABLE "ProfitabilityFact" ALTER COLUMN amount TYPE numeric(20, 6);
ALTER TABLE "ProfitabilityReconLine" ALTER COLUMN amount TYPE numeric(20, 6);
