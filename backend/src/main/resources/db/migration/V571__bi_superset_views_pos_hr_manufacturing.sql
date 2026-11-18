-- V571: `bi` views for the POS, HR and Manufacturing dashboards (ADR-S078, wave B1). Same rules as V570: no
-- app.org_id filter, every view exposes "organizationId" (the guest-token row-level security clause filters on it),
-- only superset_reader reads them.
--
-- HR views leave out personal data that a dashboard does not need: no salaries per employee, bank, tax, pension or
-- contact details, no date of birth. Payroll is summed per run and department.

-- ---- POS -----------------------------------------------------------------------------------------------------------

-- One row per POS sale: the POS Invoice pipeline (orderId set, status posted/paid/refunded), like PosReportsService.
CREATE VIEW bi.pos_sales AS
SELECT i."organizationId", i.id AS "invoiceId", i."invoiceNumber", i.status::text AS status,
       COALESCE(i."businessDate", i."issueDate"::date) AS "tradingDate", i."issueDate", i."branchId",
       b.name AS "branchName", i."orderId", o."orderNumber", o."orderType"::text AS "orderType", o."guestCount",
       i."waiterId", NULLIF(TRIM(CONCAT_WS(' ', w."firstName", w."lastName")), '') AS "waiterName", i."tableId",
       i."cashSessionId", i."paymentMode"::text AS "paymentMode", i.subtotal, i."discountTotal", i."taxAmount",
       i."totalAmount", COALESCE(i."amountRefunded", 0) AS "amountRefunded",
       i."totalAmount" - COALESCE(i."amountRefunded", 0) AS "netSales"
FROM "Invoice" i
LEFT JOIN "Order" o ON o.id = i."orderId"
LEFT JOIN "User" w ON w.id = i."waiterId"
LEFT JOIN "Branch" b ON b.id = i."branchId"
WHERE i."orderId" IS NOT NULL
  AND i.status::text IN ('posted', 'paid', 'refunded');

CREATE VIEW bi.pos_sale_lines AS
SELECT i."organizationId", i.id AS "invoiceId", i."invoiceNumber",
       COALESCE(i."businessDate", i."issueDate"::date) AS "tradingDate", i."branchId", b.name AS "branchName",
       o."orderType"::text AS "orderType", l."lineNumber", l."productId", p.code AS "productCode",
       COALESCE(p.name, l.description) AS "productName", c.name AS "categoryName", l."variantName", l.quantity,
       COALESCE(l."refundedQty", 0) AS "refundedQty", l."unitPrice", l."discountAmount", l.subtotal,
       l."taxAmount", l.total
FROM "Invoice" i
JOIN "InvoiceItem" l ON l."invoiceId" = i.id
LEFT JOIN "Order" o ON o.id = i."orderId"
LEFT JOIN "Product" p ON p.id = l."productId"
LEFT JOIN "ProductCategory" c ON c.id = p."categoryId"
LEFT JOIN "Branch" b ON b.id = i."branchId"
WHERE i."orderId" IS NOT NULL
  AND i.status::text IN ('posted', 'paid', 'refunded');

-- Till sessions with their cash count: the over/short trend per register and cashier.
CREATE VIEW bi.pos_cash_sessions AS
SELECT s."organizationId", s.id AS "cashSessionId", s.status::text AS status,
       COALESCE(s."businessDate", s."openedAt"::date) AS "businessDate", s."openedAt", s."closedAt",
       s."cashRegisterId", r.name AS "registerName", s."branchId", b.name AS "branchName", s."userId",
       NULLIF(TRIM(CONCAT_WS(' ', u."firstName", u."lastName")), '') AS "cashierName", s."openingFloat",
       s."closingExpected", s."closingCounted", s."closingDifference", s."varianceStatus", s."bankedAmount"
FROM "CashSession" s
LEFT JOIN "CashRegister" r ON r.id = s."cashRegisterId"
LEFT JOIN "User" u ON u.id = s."userId"
LEFT JOIN "Branch" b ON b.id = s."branchId";

-- Order lines cancelled or voided before billing, valued at the line's unit price.
CREATE VIEW bi.pos_voided_items AS
SELECT x."organizationId", o."orderNumber", o."orderType"::text AS "orderType", o."branchId",
       b.name AS "branchName", COALESCE(x."cancelledAt", x."updatedAt") AS "voidedAt",
       COALESCE(x."cancelledAt", x."updatedAt")::date AS "voidDate", x."productId", p.code AS "productCode",
       COALESCE(p.name, x.description) AS "productName", x."unitPrice",
       CASE WHEN x.cancelled THEN x.quantity ELSE x."voidedQty" END AS "voidedQty",
       CASE WHEN x.cancelled THEN x.quantity ELSE x."voidedQty" END * x."unitPrice" AS "voidedValue",
       x."cancelReason", x."voidedBy",
       NULLIF(TRIM(CONCAT_WS(' ', v."firstName", v."lastName")), '') AS "voidedByName"
FROM "OrderItem" x
JOIN "Order" o ON o.id = x."orderId"
LEFT JOIN "Product" p ON p.id = x."productId"
LEFT JOIN "User" v ON v.id = x."voidedBy"
LEFT JOIN "Branch" b ON b.id = o."branchId"
WHERE x.cancelled OR COALESCE(x."voidedQty", 0) > 0;

-- ---- HR ------------------------------------------------------------------------------------------------------------

CREATE VIEW bi.hr_employees AS
SELECT e."organizationId", e.id AS "employeeId", e."employeeCode",
       TRIM(CONCAT_WS(' ', e."firstName", e."lastName")) AS "employeeName", e."departmentId",
       d.name AS "departmentName", e."positionId", p.name AS "positionName", e."branchId", b.name AS "branchName",
       e."employmentType"::text AS "employmentType", e."employmentStatus"::text AS "employmentStatus",
       e."isActive", e.gender, e."hireDate"::date AS "hireDate", e."terminationDate"::date AS "terminationDate",
       ROUND((COALESCE(e."terminationDate"::date, CURRENT_DATE) - e."hireDate"::date) / 365.25, 1)
           AS "tenureYears"
FROM "HrEmployee" e
LEFT JOIN "HrDepartment" d ON d.id = e."departmentId"
LEFT JOIN "HrPosition" p ON p.id = e."positionId"
LEFT JOIN "Branch" b ON b.id = e."branchId"
WHERE e."deletedAt" IS NULL;

CREATE VIEW bi.hr_attendance AS
SELECT a."organizationId", a."date"::date AS "attendanceDate", a."employeeId", e."employeeCode",
       TRIM(CONCAT_WS(' ', e."firstName", e."lastName")) AS "employeeName", d.name AS "departmentName",
       e."branchId", a.status::text AS status, a.source::text AS source, a."workedMinutes", a."overtimeMinutes",
       a."lateMinutes", a."earlyLeaveMinutes", a."overtimeApproved"
FROM "HrAttendance" a
JOIN "HrEmployee" e ON e.id = a."employeeId"
LEFT JOIN "HrDepartment" d ON d.id = e."departmentId"
WHERE a."deletedAt" IS NULL;

CREATE VIEW bi.hr_leave AS
SELECT r."organizationId", r."requestCode", r."employeeId", e."employeeCode",
       TRIM(CONCAT_WS(' ', e."firstName", e."lastName")) AS "employeeName", d.name AS "departmentName",
       t.name AS "leaveType", t."isPaid", r."startDate"::date AS "startDate", r."endDate"::date AS "endDate", r.days,
       r.status::text AS status
FROM "HrLeaveRequest" r
JOIN "HrEmployee" e ON e.id = r."employeeId"
LEFT JOIN "HrDepartment" d ON d.id = e."departmentId"
LEFT JOIN "HrLeaveType" t ON t.id = r."leaveTypeId"
WHERE r."deletedAt" IS NULL;

-- Payroll cost per run and department; drafts and reversed runs left out.
CREATE VIEW bi.hr_payroll_costs AS
SELECT r."organizationId", r.id AS "runId", r."runNumber", r.status::text AS status, p."periodCode",
       p."startDate"::date AS "periodStart", p."endDate"::date AS "periodEnd",
       COALESCE(r."paymentDate"::date, p."paymentDate", p."endDate"::date) AS "paymentDate",
       COALESCE(d.name, '(no department)') AS "departmentName", COUNT(*) AS employees,
       SUM(it."grossPay") AS "grossPay", SUM(it."overtimePay") AS "overtimePay",
       SUM(it."taxAmount") AS "taxAmount", SUM(it."pensionAmount") AS "pensionAmount",
       SUM(it."socialSecurityAmount") AS "socialSecurityAmount", SUM(it."totalDeductions") AS "totalDeductions",
       SUM(it."netPay") AS "netPay"
FROM "HrPayrollRun" r
JOIN "HrPayrollPeriod" p ON p.id = r."periodId"
JOIN "HrPayrollItem" it ON it."runId" = r.id AND it."deletedAt" IS NULL
JOIN "HrEmployee" e ON e.id = it."employeeId"
LEFT JOIN "HrDepartment" d ON d.id = e."departmentId"
WHERE r."deletedAt" IS NULL
  AND r.status::text IN ('CALCULATED', 'APPROVED', 'PAID')
GROUP BY r."organizationId", r.id, r."runNumber", r.status, p."periodCode", p."startDate", p."endDate",
         r."paymentDate", p."paymentDate", d.name;

-- ---- Manufacturing -------------------------------------------------------------------------------------------------

CREATE VIEW bi.production_orders AS
SELECT o."organizationId", o.id AS "productionOrderId", o."orderCode", o.status::text AS status, o."orderKind",
       o."outputProductId", p.code AS "productCode", p.name AS "productName", o."locationId",
       l.name AS "locationName", l."branchId", b.name AS "branchName", o."plannedQty", o."producedQty",
       o."scrapQty", CASE WHEN o."plannedQty" > 0 THEN ROUND(100 * o."producedQty" / o."plannedQty", 2) END
           AS "yieldPercent",
       o."scheduledFor", o."startedAt", o."completedAt", o."completedAt"::date AS "completedDate",
       ROUND(EXTRACT(EPOCH FROM (o."completedAt" - o."startedAt")) / 3600, 2) AS "leadTimeHours",
       o."materialCost", o."overheadCost", o."byproductCredit", o."totalCost", o."outputUnitCost",
       o."yieldVariance", o."createdAt"
FROM "ProductionOrder" o
LEFT JOIN "Product" p ON p.id = o."outputProductId"
LEFT JOIN "InventoryLocation" l ON l.id = o."locationId"
LEFT JOIN "Branch" b ON b.id = l."branchId";

CREATE VIEW bi.production_materials AS
SELECT m."organizationId", o."orderCode", o.status::text AS status, o."completedAt",
       o."completedAt"::date AS "completedDate", m."productId", p.code AS "productCode",
       COALESCE(p.name, m."productName") AS "productName", m.unit, m."qtyPlanned", m."qtyConsumed",
       COALESCE(m."qtyConsumed", 0) - COALESCE(m."qtyPlanned", 0) AS "qtyVariance", m."unitCost", m."totalCost"
FROM "ProductionMaterial" m
JOIN "ProductionOrder" o ON o.id = m."orderId"
LEFT JOIN "Product" p ON p.id = m."productId";

CREATE VIEW bi.production_variances AS
SELECT v."organizationId", o."orderCode", v.kind, v."productId", p.code AS "productCode", p.name AS "productName",
       v."standardQty", v."actualQty", v."standardCost", v."actualCost", v.amount, v."createdAt",
       v."createdAt"::date AS "varianceDate"
FROM "ProductionVariance" v
JOIN "ProductionOrder" o ON o.id = v."orderId"
LEFT JOIN "Product" p ON p.id = v."productId";

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'superset_reader') THEN
        GRANT USAGE ON SCHEMA bi TO superset_reader;
        GRANT SELECT ON ALL TABLES IN SCHEMA bi TO superset_reader;
    END IF;
END $$;
