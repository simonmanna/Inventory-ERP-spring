-- Deposit receipt header (owner request 2026-11-19): the business name, detail lines, logo, title, signature line and
-- footer printed on customer deposit receipts, edited in Sales settings. Stored as JSON so new options need no
-- migration; missing keys take the defaults in DepositReceiptLayout.java (company settings name and details, instead
-- of the POS receipt header).
ALTER TABLE "SalesSettings" ADD COLUMN "depositReceiptLayout" JSONB NOT NULL DEFAULT '{}'::jsonb;
