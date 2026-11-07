package com.inventoryerp.manufacturing.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports of processing (ADR-S060): stock by stage and lot, WIP, lot genealogy and stage margin. Production stage,
 * product category and the stage a product was sold at stay separate dimensions. Profit comes from actual COGS on the
 * stock ledger, never the product's current cost price or a standard.
 */
@Service
public class ProcessingReportService {

    private static final int MAX_DEPTH = 12;
    private static final List<String> SALE_REFS = List.of("fleet_trip", "sales_invoice", "cash_sale", "sales_delivery");

    private final MfgDb db;

    public ProcessingReportService(MfgDb db) {
        this.db = db;
    }

    /** Stock per lot with what is free to sell or process: reservations for jobs are subtracted per stock cell. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lots(String productId, String search) {
        List<Object> params = new ArrayList<>(List.of(MfgDb.org()));
        StringBuilder w = new StringBuilder();
        if (productId != null && !productId.isBlank()) {
            w.append(" AND b.\"productId\" = ?");
            params.add(productId);
        }
        if (search != null && !search.isBlank()) {
            w.append(" AND (b.\"batchNumber\" ILIKE ? OR p.name ILIKE ? OR p.code ILIKE ?)");
            String q = "%" + search.trim() + "%";
            params.addAll(List.of(q, q, q));
        }
        return SqlRows.list(db.jdbc().sql("""
                SELECT b.id, b."productId", p.code AS "productCode", p.name AS "productName", p."canBeSold",
                       p."manufacturingRole"::text AS stage, c.name AS "categoryName", u.name AS "uomName",
                       b."batchNumber", b."locationId", l.name AS "locationName", b.quantity, b."unitCost",
                       b.quantity * b."unitCost" AS value,
                       COALESCE((SELECT sum(r.quantity) FROM "StockReservation" r WHERE r."organizationId" = b."organizationId"
                           AND r."productId" = b."productId" AND r."locationId" = b."locationId" AND r.status = 'active'), 0) AS reserved,
                       (SELECT o."jobId" FROM "ProcessingJobOutput" o WHERE o."organizationId" = b."organizationId"
                           AND o."productId" = b."productId" AND o."batchNumber" = b."batchNumber" LIMIT 1) AS "jobId"
                FROM "InventoryBatch" b
                JOIN "Product" p ON p.id = b."productId"
                LEFT JOIN "ProductCategory" c ON c.id = p."categoryId"
                LEFT JOIN "UnitOfMeasure" u ON u.id = p."uomId"
                LEFT JOIN "InventoryLocation" l ON l.id = b."locationId"
                WHERE b."organizationId" = ? AND b.quantity > 0 AND b."isActive" = true"""
                + w + " ORDER BY p.name, b.\"receivedAt\" LIMIT 500").params(params));
    }

    /** Jobs holding value in WIP now. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> wip() {
        return SqlRows.list(db.jdbc().sql("""
                SELECT j.id, j.code, j."jobDate", t.name AS "templateName", o."orderCode",
                       %s AS status,
                       s."materialCost" + s."transferredInCost" + s."conversionCost" - s."outputCost" - s."scrapCost" AS "wipValue"
                FROM "ProcessingJob" j
                JOIN "ProcessingTemplate" t ON t.id = j."templateId"
                JOIN "ProductionOrder" o ON o.id = j."orderId"
                JOIN "ProductionOrderStage" s ON s.id = j."stageId"
                WHERE j."organizationId" = ? AND j."deletedAt" IS NULL AND o.status::text NOT IN ('completed', 'cancelled')
                ORDER BY j."jobDate" DESC, j.code DESC""".formatted(ProcessingJobService.STATUS_SQL)).param(MfgDb.org()));
    }

    /** Lot tree: {@code backward} to the source lots, {@code forward} to the later lots and what was sold. */
    @Transactional(readOnly = true)
    public Map<String, Object> genealogy(String productId, String batchNumber, String direction) {
        if (productId == null || batchNumber == null) {
            throw ApiException.badRequest("productId and batchNumber are required");
        }
        String dir = direction == null ? "backward" : direction;
        if (!List.of("backward", "forward").contains(dir)) {
            throw ApiException.badRequest("direction must be backward or forward");
        }
        return Obj.of("direction", dir, "root", node(productId, batchNumber, dir, 0, new HashSet<>()));
    }

    private Map<String, Object> node(String productId, String batch, String dir, int depth, Set<String> seen) {
        Map<String, Object> n = new LinkedHashMap<>();
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT name, code FROM \"Product\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(productId, MfgDb.org()));
        n.put("productId", productId);
        n.put("productName", p == null ? null : p.get("name"));
        n.put("batchNumber", batch);
        List<Map<String, Object>> children = new ArrayList<>();
        n.put("children", children);
        if (depth >= MAX_DEPTH || !seen.add(dir + "|" + productId + "|" + batch)) {
            n.put("truncated", true);
            return n;
        }
        boolean back = "backward".equals(dir);
        String near = back ? "output" : "source";
        String far = back ? "source" : "output";
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT g.*, j.code AS \"jobCode\" FROM \"LotGenealogyLink\" g"
                + " JOIN \"ProcessingJob\" j ON j.id = g.\"jobId\" WHERE g.\"organizationId\" = ? AND g.\"reversedAt\" IS NULL"
                + " AND g.\"" + near + "ProductId\" = ? AND g.\"" + near + "BatchNumber\" IS NOT DISTINCT FROM ? ORDER BY"
                + " g.\"createdAt\"").params(MfgDb.org(), productId, batch))) {
            Map<String, Object> child = node(str(l, far + "ProductId"), str(l, far + "BatchNumber"), dir, depth + 1, seen);
            child.put("jobId", l.get("jobId"));
            child.put("jobCode", l.get("jobCode"));
            child.put("sourceQty", l.get("sourceQty"));
            child.put("attributedQty", l.get("attributedQty"));
            child.put("sharePct", l.get("sharePct"));
            child.put("attributedCost", l.get("attributedCost"));
            child.put("allocationMethod", l.get("allocationMethod"));
            children.add(child);
        }
        if (!back) {
            n.put("sales", sales(productId, batch));
        }
        return n;
    }

    /** What left a lot to customers: exact quantity and actual cost from the ledger rows of the sale documents. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sales(String productId, String batch) {
        return SqlRows.list(db.jdbc().sql("""
                SELECT g."referenceType", g."referenceId", g."ledgerCode", g."createdAt", g."unitCost",
                       -g."quantityChange" AS quantity, abs(g."totalValue") AS cost,
                       COALESCE(d."documentNumber", t.code) AS "documentNumber",
                       COALESCE(dp.name, tp.name) AS "partnerName"
                FROM "InventoryLedger" g
                JOIN "InventoryBatch" b ON b.id = g."batchId"
                LEFT JOIN "Document" d ON g."referenceType" = 'sales_invoice' AND d.id = g."referenceId"
                LEFT JOIN "Partner" dp ON dp.id = d."partnerId"
                LEFT JOIN "FleetLoad" t ON g."referenceType" = 'fleet_trip' AND t.id = g."referenceId"
                LEFT JOIN "FleetDeliveryOrder" o ON o.id = t."deliveryOrderId"
                LEFT JOIN "Partner" tp ON tp.id = o."partnerId"
                WHERE g."organizationId" = ? AND g."productId" = ? AND b."batchNumber" = ? AND g."quantityChange" < 0
                  AND g."referenceType" = ANY(?::text[])
                ORDER BY g."createdAt\"""").params(MfgDb.org(), productId, batch, SALE_REFS.toArray(String[]::new)));
    }

    /**
     * Revenue, actual COGS and margin per product and stage for a period, in base currency. COGS is the ledger value of
     * the sale movements, so it equals what was posted.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> stageMargin(String from, String to) {
        LocalDate f = day(from, LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1));
        LocalDate t = day(to, LocalDate.now(ZoneOffset.UTC));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("""
                WITH cogs AS (
                  SELECT g."productId", sum(-g."quantityChange") AS qty, sum(abs(g."totalValue")) AS cost
                  FROM "InventoryLedger" g
                  WHERE g."organizationId" = ? AND g."quantityChange" < 0 AND g."referenceType" = ANY(?::text[])
                    AND g."createdAt" >= ? AND g."createdAt" < ?
                  GROUP BY g."productId"),
                rev AS (
                  SELECT l."productId", sum(CASE WHEN d."documentType" = 'credit_note' THEN -1 ELSE 1 END * l.subtotal
                      * COALESCE(NULLIF(d."exchangeRate", 0), 1)) AS revenue
                  FROM "DocumentLine" l JOIN "Document" d ON d.id = l."documentId"
                  WHERE d."organizationId" = ? AND d."documentType" IN ('sales_invoice', 'credit_note')
                    AND d.status IN ('posted', 'paid') AND l."productId" IS NOT NULL
                    AND d."issueDate" >= ? AND d."issueDate" < ?
                  GROUP BY l."productId")
                SELECT p.id AS "productId", p.code AS "productCode", p.name AS "productName",
                       p."manufacturingRole"::text AS stage, c.name AS "categoryName", COALESCE(cogs.qty, 0) AS quantity,
                       COALESCE(rev.revenue, 0) AS revenue, COALESCE(cogs.cost, 0) AS cogs
                FROM "Product" p
                LEFT JOIN "ProductCategory" c ON c.id = p."categoryId"
                LEFT JOIN cogs ON cogs."productId" = p.id
                LEFT JOIN rev ON rev."productId" = p.id
                WHERE p."organizationId" = ? AND (cogs."productId" IS NOT NULL OR rev."productId" IS NOT NULL)
                  AND p.id IN (SELECT "productId" FROM "ProcessingTemplateInput" WHERE "organizationId" = p."organizationId"
                               UNION SELECT "productId" FROM "ProcessingTemplateOutput" WHERE "organizationId" = p."organizationId")
                ORDER BY p."manufacturingRole"::text, p.name""")
                .params(MfgDb.org(), SALE_REFS.toArray(String[]::new), Date.valueOf(f), Date.valueOf(t.plusDays(1)),
                        MfgDb.org(), Date.valueOf(f), Date.valueOf(t.plusDays(1)), MfgDb.org()))) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            BigDecimal revenue = dec(r, "revenue");
            BigDecimal gross = revenue.subtract(dec(r, "cogs"));
            m.put("grossProfit", gross);
            m.put("marginPct", revenue.signum() == 0 ? null : gross.multiply(BigDecimal.valueOf(100)).divide(revenue, 2,
                    RoundingMode.HALF_UP));
            rows.add(m);
        }
        return rows;
    }

    /** Management view: flows, stock, WIP and yield per template and stage for a period. */
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard(String from, String to) {
        LocalDate f = day(from, LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1));
        LocalDate t = day(to, LocalDate.now(ZoneOffset.UTC));
        Date start = Date.valueOf(f);
        Date end = Date.valueOf(t.plusDays(1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", f.toString());
        out.put("to", t.toString());
        out.put("jobs", SqlRows.list(db.jdbc().sql("""
                SELECT %s AS status, count(*) AS count
                FROM "ProcessingJob" j
                LEFT JOIN "ProductionOrder" o ON o.id = j."orderId"
                LEFT JOIN "ProductionOrderStage" s ON s.id = j."stageId"
                WHERE j."organizationId" = ? AND j."deletedAt" IS NULL AND j."jobDate" >= ? AND j."jobDate" < ?
                GROUP BY 1""".formatted(ProcessingJobService.STATUS_SQL)).params(MfgDb.org(), start, end)));
        out.put("stockByStage", SqlRows.list(db.jdbc().sql("""
                SELECT p."manufacturingRole"::text AS stage, count(DISTINCT p.id) AS products,
                       sum(CASE WHEN b.quantity IS NOT NULL THEN b.quantity * b."unitCost" ELSE 0 END) AS value,
                       sum(COALESCE(b.quantity, 0)) AS quantity
                FROM "InventoryBatch" b JOIN "Product" p ON p.id = b."productId"
                WHERE b."organizationId" = ? AND b.quantity > 0 AND b."isActive" = true
                  AND p.id IN (SELECT "productId" FROM "ProcessingTemplateInput" WHERE "organizationId" = p."organizationId"
                               UNION SELECT "productId" FROM "ProcessingTemplateOutput" WHERE "organizationId" = p."organizationId")
                GROUP BY 1 ORDER BY 1""").param(MfgDb.org())));
        BigDecimal wip = wip().stream().map(r -> dec(r, "wipValue")).reduce(BigDecimal.ZERO, BigDecimal::add);
        out.put("wipValue", wip);
        out.put("yieldByTemplate", SqlRows.list(db.jdbc().sql("""
                SELECT t.code, t.name, t."yieldBasisUnit", t."expectedYieldPct", count(DISTINCT j.id) AS jobs,
                       COALESCE(sum(i.basis), 0) AS "inputBasis", COALESCE(sum(u.basis), 0) AS "outputBasis",
                       COALESCE(sum(ls.basis), 0) AS "lossBasis", COALESCE(sum(u.value), 0) AS "outputValue"
                FROM "ProcessingJob" j
                JOIN "ProcessingTemplate" t ON t.id = j."templateId"
                JOIN "ProductionOrder" o ON o.id = j."orderId" AND o."reversedAt" IS NULL
                LEFT JOIN LATERAL (SELECT sum("basisQty") AS basis FROM "ProcessingJobInput" x WHERE x."jobId" = j.id AND x.role = 'traceable') i ON true
                LEFT JOIN LATERAL (SELECT sum("basisQty") AS basis, sum(value) AS value FROM "ProcessingJobOutput" x WHERE x."jobId" = j.id) u ON true
                LEFT JOIN LATERAL (SELECT sum("basisQty") AS basis FROM "ProcessingJobLoss" x WHERE x."jobId" = j.id AND x."reversedAt" IS NULL) ls ON true
                WHERE j."organizationId" = ? AND j."jobDate" >= ? AND j."jobDate" < ?
                GROUP BY t.code, t.name, t."yieldBasisUnit", t."expectedYieldPct" ORDER BY t.code""")
                .params(MfgDb.org(), start, end)));
        out.put("margin", stageMargin(f.toString(), t.toString()));
        out.put("recentJobs", SqlRows.list(db.jdbc().sql("""
                SELECT j.id, j.code, j."jobDate", t.name AS "templateName", %s AS status
                FROM "ProcessingJob" j
                JOIN "ProcessingTemplate" t ON t.id = j."templateId"
                LEFT JOIN "ProductionOrder" o ON o.id = j."orderId"
                LEFT JOIN "ProductionOrderStage" s ON s.id = j."stageId"
                WHERE j."organizationId" = ? AND j."deletedAt" IS NULL
                ORDER BY j."jobDate" DESC, j.code DESC LIMIT 8""".formatted(ProcessingJobService.STATUS_SQL)).param(MfgDb.org())));
        return out;
    }

    private static LocalDate day(String text, LocalDate fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(text.substring(0, Math.min(10, text.length())));
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("Dates must be YYYY-MM-DD");
        }
    }
}
