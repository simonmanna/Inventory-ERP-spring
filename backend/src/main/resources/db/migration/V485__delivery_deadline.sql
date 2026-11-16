-- Delivery deadline: the date the goods must reach the customer by (the scheduled date is when we plan to send them).
-- On the order / invoice delivery plan and on a branch cash sale. A sales order without one falls back to its
-- commitment date, then its requested date.
ALTER TABLE "SalesHandover" ADD COLUMN deadline DATE;
ALTER TABLE "SalesSubmission" ADD COLUMN "deliveryDeadline" DATE;
