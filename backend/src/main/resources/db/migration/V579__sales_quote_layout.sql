-- Quotation print layout (owner request 2026-11-19): what the quotation PDF / print shows, edited in Sales settings
-- without HTML. Stored as JSON so new options need no migration; missing keys take the defaults in QuoteLayout.java.
-- The company name printed on quotations now defaults to the company settings name (Organization.name) instead of the
-- POS receipt header; the layout's companyName overrides it.
ALTER TABLE "SalesSettings" ADD COLUMN "quoteLayout" JSONB NOT NULL DEFAULT '{}'::jsonb;
