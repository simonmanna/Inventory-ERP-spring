-- V71 (Phase 4, FIN-1): a reason on a statement line excluded from matching.
--
-- Bank charges and transfers reach the books through journals, not Payment rows, so the reconciliation screen clears
-- them as status 'excluded' with a note. Status stays free text ('unmatched', 'matched', 'excluded').

ALTER TABLE "BankStatementLine" ADD COLUMN note TEXT;
