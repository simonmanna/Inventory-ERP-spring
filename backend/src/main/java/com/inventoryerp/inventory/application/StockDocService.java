package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryLookups.dec;
import static com.inventoryerp.inventory.application.InventoryLookups.str;
import static com.inventoryerp.inventory.application.StockService.NOW;

import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.inventory.application.StockMove.Issued;
import com.inventoryerp.inventory.domain.InventoryLocation;
import com.inventoryerp.inventory.domain.StockAdjustment;
import com.inventoryerp.inventory.domain.StockAdjustmentItem;
import com.inventoryerp.inventory.domain.StockOut;
import com.inventoryerp.inventory.domain.StockOutItem;
import com.inventoryerp.inventory.domain.StockTransfer;
import com.inventoryerp.inventory.domain.StockTransferItem;
import com.inventoryerp.inventory.domain.StockTransferReceipt;
import com.inventoryerp.inventory.domain.WasteItem;
import com.inventoryerp.inventory.domain.WasteRecord;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stock document wrappers around the engine (port of {@code stock-doc.service.ts}): stock-outs, waste,
 * adjustments and transfers (immediate, or transit with dispatch / receive / recall). Posting happens once on
 * approve, claimed atomically ({@code postedAt IS NULL} + status), inside one transaction with the engine
 * movements and GL. The approval-engine gate runs in its own transaction, like the Nest call outside
 * {@code $transaction}, so a pending request survives the refusal.
 */
@Service
public class StockDocService {

    /** Quantities within this of each other are equal (Decimal(20,6) columns). */
    static final BigDecimal QTY_EPSILON = new BigDecimal("0.000001");

    /** Shared line fields of stock-out / waste / adjustment / transfer bodies. */
    public record DocLine(String productId, String variantId, String unit, String batchNumber, BigDecimal qty,
            String distStrategy, Boolean isExpiry, BigDecimal qtyActual, BigDecimal qtyRequested) {}

    /** Header fields of a document body. */
    public record DocInput(String locationId, String fromLocationId, String toLocationId, String mode,
            String responsibleById, String approvedById, String category, String reason, String notes,
            List<DocLine> items) {}

    /** One transit receipt line. */
    public record ReceiveLine(String itemId, BigDecimal received, BigDecimal damaged, BigDecimal shortQty) {}

    /** Waste list / summary filters. */
    public record WasteQuery(String status, String category, String locationId, String from, String to) {}

    private final JdbcClient jdbc;
    private final InventoryLookups lookups;
    private final SequenceService seq;
    private final StockService stock;
    private final ApprovalsService approvals;
    private final SegregationOfDutiesService sod;
    private final StockPostingService stockPosting;
    private final AuditService audit;
    private final StaffAttribution staff;
    private final LocationScope scope;
    private final TransactionTemplate newTx;
    private final TransactionTemplate tx;

    public StockDocService(JdbcClient jdbc, InventoryLookups lookups, SequenceService seq, StockService stock,
            ApprovalsService approvals, SegregationOfDutiesService sod, StockPostingService stockPosting,
            AuditService audit, StaffAttribution staff, LocationScope scope, PlatformTransactionManager txm) {
        this.scope = scope;
        this.jdbc = jdbc;
        this.lookups = lookups;
        this.seq = seq;
        this.stock = stock;
        this.approvals = approvals;
        this.sod = sod;
        this.stockPosting = stockPosting;
        this.audit = audit;
        this.staff = staff;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx = new TransactionTemplate(txm);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * Lines in the stock engine's lock order (productId, variantKey): every multi-line posting feeds the engine in this
     * order so two documents over the same items lock their quants in the same sequence and cannot deadlock. The
     * documents' own rows and responses keep the entered order.
     */
    static <T extends Map<String, Object>> List<T> lockOrder(List<T> lines) {
        List<T> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing((T i) -> String.valueOf(i.get("productId")))
                .thenComparing(i -> i.get("variantId") == null ? "" : String.valueOf(i.get("variantId"))));
        return sorted;
    }

    /** Approval-engine gate; throws while a request is pending. */
    private void gateApproval(String entityType, String entityId, Map<String, Object> snapshot, String label) {
        Map<String, Object> gate = newTx.execute(s -> approvals.checkOrRequestApproval(entityType, entityId, snapshot));
        if (gate != null && Boolean.TRUE.equals(gate.get("needsApproval"))) {
            throw ApiException.badRequest("Approval required before posting " + label + ". Pending approval request "
                    + gate.get("requestId") + ".");
        }
    }

    private Map<String, String> names(List<DocLine> items) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        items.forEach(i -> ids.add(i.productId()));
        return lookups.productNames(ids);
    }

    // ================================================================== StockOut

    /** Create a pending stock-out (internal use / testing / sample / comp). */
    @Transactional
    public Map<String, Object> createStockOut(DocInput dto) {
        scope.assertAllowed(dto.locationId());
        lookups.requireLocation(dto.locationId());
        staff.assertActiveStaff(org(), StaffAttribution.people(dto.responsibleById(), dto.approvedById()));
        Map<String, String> names = names(dto.items());
        String outCode = seq.next("stock_out", "SO-", 5);
        String id = UUID.randomUUID().toString();
        String user = TenantContext.userId();
        jdbc.sql("INSERT INTO \"StockOut\" (id, \"organizationId\", \"outCode\", \"locationId\", category, status, reason,"
                        + " notes, \"performedById\", \"responsibleById\", \"approvedById\", \"createdBy\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?::\"StockOutCategory\", 'pending', ?, ?, ?, ?, ?, ?, " + NOW
                        + ", " + NOW + ")")
                .params(id, org(), outCode, dto.locationId(), dto.category() != null ? dto.category() : "general_use",
                        dto.reason(), dto.notes(), user, dto.responsibleById(), dto.approvedById(), user)
                .update();
        for (DocLine i : dto.items()) {
            jdbc.sql("INSERT INTO \"StockOutItem\" (id, \"organizationId\", \"stockOutId\", \"productId\", \"variantId\","
                            + " \"productName\", unit, qty, \"batchNumber\", \"distStrategy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, i.productId(), i.variantId(),
                            names.getOrDefault(i.productId(), "Unknown"), i.unit(), i.qty(), i.batchNumber(),
                            i.distStrategy() != null ? i.distStrategy() : "FEFO")
                    .update();
        }
        return withItems(stockOut(id));
    }

    /** Approve + post a stock-out (ISSUE per line, category-mapped move type). */
    @Transactional
    public Map<String, Object> approveStockOut(String id) {
        Map<String, Object> doc = stockOut(id);
        if (doc == null) {
            throw ApiException.notFound("Stock-out not found");
        }
        scope.assertAllowed(str(doc, "locationId"));
        List<Map<String, Object>> items = items("StockOutItem", StockOutItem.class, "stockOutId", id);
        assertPostable(str(doc, "status"), doc.get("postedAt"));
        gateApproval("stock_out", id, Obj.of("amount", JsNumbers.js(dec(doc, "totalValue")), "lines", items.size(),
                "category", doc.get("category")), "stock-out");
        if (claim("StockOut", id, "approved") == 0) {
            throw ApiException.badRequest("Stock-out was posted or cancelled concurrently");
        }
        Map<String, String> catToMove = Map.of("sample", "promo_sample", "complimentary", "promo_sample",
                "kitchen_testing", "internal_use", "training", "internal_use", "damaged", "waste", "expired",
                "expiry_write_off");
        BigDecimal total = Money.ZERO;
        String user = TenantContext.userId();
        for (Map<String, Object> item : lockOrder(items)) {
            StockMove m = StockMove.of(str(item, "productId"), str(doc, "locationId"), dec(item, "qty"))
                    .variant(str(item, "variantId")).moveType(catToMove.getOrDefault(str(doc, "category"), "issue"))
                    .strategy(item.get("distStrategy") != null ? str(item, "distStrategy") : "FEFO")
                    .batch(str(item, "batchNumber")).source("stock_out", str(doc, "outCode")).notes(str(doc, "reason"))
                    .people(str(doc, "responsibleById"), user != null ? user : str(doc, "approvedById"));
            Issued res = stock.issue(m);
            total = total.add(res.totalValue());
            jdbc.sql("UPDATE \"StockOutItem\" SET \"unitCost\" = ?, \"totalCost\" = ? WHERE id = ?")
                    .params(res.unitCost(), res.totalValue(), item.get("id")).update();
        }
        jdbc.sql("UPDATE \"StockOut\" SET status = 'completed', \"approvedById\" = ?, \"approvedAt\" = " + NOW
                        + ", \"postedAt\" = " + NOW + ", \"totalValue\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(user, total, id).update();
        return withItems(stockOut(id));
    }

    // ================================================================== Waste

    /** Create a pending waste record valued at the current running average. */
    @Transactional
    public Map<String, Object> createWaste(DocInput dto) {
        scope.assertAllowed(dto.locationId());
        lookups.requireLocation(dto.locationId());
        staff.assertActiveStaff(org(), StaffAttribution.people(dto.responsibleById(), dto.approvedById()));
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        dto.items().forEach(i -> ids.add(i.productId()));
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, name, \"costPrice\" FROM \"Product\" WHERE id IN"
                        + " (:ids) AND \"organizationId\" = :org AND \"deletedAt\" IS NULL")
                .param("ids", List.copyOf(ids)).param("org", org()))) {
            byId.put((String) p.get("id"), p);
        }
        for (DocLine i : dto.items()) {
            if (!byId.containsKey(i.productId())) {
                throw ApiException.notFound("Product " + i.productId() + " not found");
            }
        }
        Map<String, BigDecimal> avg = new HashMap<>();
        if (!byId.isEmpty()) {
            for (Map<String, Object> s : SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantKey\", \"runningAverageCost\""
                            + " FROM \"StockItem\" WHERE \"organizationId\" = :org AND \"locationId\" = :loc"
                            + " AND \"productId\" IN (:ids)")
                    .param("org", org()).param("loc", dto.locationId()).param("ids", List.copyOf(byId.keySet())))) {
                avg.put(s.get("productId") + ":" + s.get("variantKey"), dec(s, "runningAverageCost"));
            }
        }
        BigDecimal total = Money.ZERO;
        List<Object[]> lines = new ArrayList<>();
        for (DocLine i : dto.items()) {
            Map<String, Object> product = byId.get(i.productId());
            BigDecimal avgCost = avg.get(i.productId() + ":" + (i.variantId() == null ? "" : i.variantId()));
            BigDecimal unitCost = avgCost != null && avgCost.signum() > 0 ? avgCost : dec(product, "costPrice");
            BigDecimal totalCost = unitCost.multiply(i.qty());
            total = total.add(totalCost);
            String batch = i.batchNumber() == null || i.batchNumber().trim().isEmpty() ? null : i.batchNumber().trim();
            lines.add(new Object[] {i.productId(), i.variantId(), product.get("name"), i.unit(), i.qty(), unitCost,
                    totalCost, batch, i.isExpiry() != null && i.isExpiry()});
        }
        String wasteCode = seq.next("waste_doc", "WST-", 5);
        String id = UUID.randomUUID().toString();
        String user = TenantContext.userId();
        jdbc.sql("INSERT INTO \"WasteRecord\" (id, \"organizationId\", \"wasteCode\", \"locationId\", category, status,"
                        + " notes, \"reportedById\", \"responsibleById\", \"approvedById\", \"createdBy\", \"totalValue\","
                        + " \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?::\"WasteCategory\", 'pending', ?, ?, ?, ?, ?, ?, "
                        + NOW + ", " + NOW + ")")
                .params(id, org(), wasteCode, dto.locationId(), dto.category() != null ? dto.category() : "other",
                        dto.notes(), user, dto.responsibleById(), dto.approvedById(), user, total)
                .update();
        for (Object[] l : lines) {
            jdbc.sql("INSERT INTO \"WasteItem\" (id, \"organizationId\", \"wasteId\", \"productId\", \"variantId\","
                            + " \"productName\", unit, qty, \"unitCost\", \"totalCost\", \"batchNumber\", \"isExpiry\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, l[0], l[1], l[2], l[3], l[4], l[5], l[6], l[7], l[8])
                    .update();
        }
        return withLocation(withItems(waste(id)), true);
    }

    /** Approve + post a waste record (WASTE / EXPIRY_WRITE_OFF per line). */
    @Transactional
    public Map<String, Object> approveWaste(String id) {
        Map<String, Object> doc = waste(id);
        if (doc == null) {
            throw ApiException.notFound("Waste record not found");
        }
        scope.assertAllowed(str(doc, "locationId"));
        List<Map<String, Object>> items = items("WasteItem", WasteItem.class, "wasteId", id);
        assertPostable(str(doc, "status"), doc.get("postedAt"));
        gateApproval("waste", id, Obj.of("amount", JsNumbers.js(dec(doc, "totalValue")), "lines", items.size(),
                "category", doc.get("category")), "waste " + doc.get("wasteCode"));
        if (claim("WasteRecord", id, "approved") == 0) {
            throw ApiException.badRequest("Waste record was posted or cancelled concurrently");
        }
        BigDecimal total = Money.ZERO;
        String user = TenantContext.userId();
        for (Map<String, Object> item : lockOrder(items)) {
            String batch = str(item, "batchNumber");
            StockMove m = StockMove.of(str(item, "productId"), str(doc, "locationId"), dec(item, "qty"))
                    .variant(str(item, "variantId"))
                    .moveType(Boolean.TRUE.equals(item.get("isExpiry")) ? "expiry_write_off" : "waste")
                    .strategy(batch != null ? "MANUAL" : "FEFO").batch(batch).source("waste", str(doc, "wasteCode"))
                    .notes(str(doc, "notes"))
                    .people(str(doc, "responsibleById"), user != null ? user : str(doc, "approvedById"));
            Issued res = stock.issue(m);
            total = total.add(res.totalValue());
            jdbc.sql("UPDATE \"WasteItem\" SET \"unitCost\" = ?, \"totalCost\" = ? WHERE id = ?")
                    .params(res.unitCost(), res.totalValue(), item.get("id")).update();
        }
        jdbc.sql("UPDATE \"WasteRecord\" SET status = 'completed', \"approvedById\" = ?, \"approvedAt\" = " + NOW
                        + ", \"postedAt\" = " + NOW + ", \"totalValue\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(user, total, id).update();
        return withLocation(withItems(waste(id)), true);
    }

    /** Discard a not-yet-posted waste record. */
    @Transactional
    public Map<String, Object> cancelWaste(String id) {
        Map<String, Object> doc = waste(id);
        if (doc == null) {
            throw ApiException.notFound("Waste record not found");
        }
        scope.assertAllowed(str(doc, "locationId"));
        assertPostable(str(doc, "status"), doc.get("postedAt"));
        if (cancel("WasteRecord", id) == 0) {
            throw ApiException.badRequest("Waste record was posted or cancelled concurrently");
        }
        return withLocation(withItems(waste(id)), true);
    }

    /** Waste records (newest first, max 500) with items and the location summary. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listWaste(WasteQuery q) {
        List<Object> params = new ArrayList<>();
        String where = wasteWhere(q, "createdAt", params);
        List<Map<String, Object>> rows = SqlRows.list(WasteRecord.class, jdbc.sql("SELECT * FROM \"WasteRecord\" WHERE"
                + where + " ORDER BY \"createdAt\" DESC LIMIT 500").params(params));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (scope.permits(str(r, "locationId"))) {
                out.add(withLocation(withItems(r), false));
            }
        }
        return out;
    }

    /** One waste record with its ledger rows and posted journals. */
    @Transactional(readOnly = true)
    public Map<String, Object> getWaste(String id) {
        Map<String, Object> doc = waste(id);
        if (doc == null) {
            throw ApiException.notFound("Waste record not found");
        }
        scope.assertAllowed(str(doc, "locationId"));
        doc = withLocation(withItems(doc), false);
        List<Map<String, Object>> journals = new ArrayList<>();
        List<Map<String, Object>> ledger = new ArrayList<>();
        if (doc.get("postedAt") != null) {
            for (Map<String, Object> je : SqlRows.list(jdbc.sql("SELECT id, \"entryNumber\", \"postingDate\","
                            + " status::text AS status, description FROM \"JournalEntry\" WHERE \"organizationId\" = ?"
                            + " AND \"sourceType\" IN ('waste', 'waste_reversal') AND \"sourceId\" = ?"
                            + " ORDER BY \"entryNumber\" ASC")
                    .params(org(), doc.get("wasteCode")))) {
                List<Map<String, Object>> lines = new ArrayList<>();
                for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT l.debit, l.credit, a.code, a.name FROM"
                                + " \"JournalLine\" l JOIN \"Account\" a ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ?")
                        .param(je.get("id")))) {
                    lines.add(Obj.of("debit", l.get("debit"), "credit", l.get("credit"), "account",
                            Obj.of("code", l.get("code"), "name", l.get("name"))));
                }
                je.put("lines", lines);
                journals.add(je);
            }
            ledger = SqlRows.list(jdbc.sql("SELECT id, \"ledgerCode\", \"productId\", type::text AS type,"
                            + " \"quantityChange\", \"balanceAfter\", \"unitCost\", \"totalValue\", \"createdAt\""
                            + " FROM \"InventoryLedger\" WHERE \"organizationId\" = ? AND \"referenceType\" IN"
                            + " ('waste', 'waste_reversal') AND \"referenceId\" = ? ORDER BY \"createdAt\" ASC")
                    .params(org(), doc.get("wasteCode")));
        }
        doc.put("journalEntries", journals);
        doc.put("ledger", ledger);
        return doc;
    }

    /** Posted damages & waste totals by category / product / location, plus pending. */
    @Transactional(readOnly = true)
    public Map<String, Object> wasteSummary(WasteQuery q) {
        List<Object> params = new ArrayList<>();
        String where = wasteWhere(new WasteQuery(null, q.category(), q.locationId(), q.from(), q.to()), "postedAt",
                params, "w.");
        List<Map<String, Object>> posted = SqlRows.list(jdbc.sql("SELECT w.id, w.category::text AS category,"
                + " w.\"totalValue\", w.\"locationId\", l.code, l.name FROM \"WasteRecord\" w JOIN \"InventoryLocation\" l"
                + " ON l.id = w.\"locationId\" WHERE" + where + " AND w.status = 'completed'").params(params));
        List<Object> pParams = new ArrayList<>();
        String pWhere = wasteWhere(new WasteQuery(null, q.category(), q.locationId(), null, null), "createdAt", pParams);
        Map<String, Object> pending = SqlRows.one(jdbc.sql("SELECT count(*) AS n, sum(\"totalValue\") AS v FROM"
                + " \"WasteRecord\" WHERE" + pWhere + " AND status IN ('pending', 'draft')").params(pParams));

        Map<String, Map<String, Object>> byCategory = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byLocation = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byProduct = new LinkedHashMap<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal expiryValue = BigDecimal.ZERO;
        // The posted records' lines in one query (was one per record), grouped back per record.
        Map<String, List<Map<String, Object>>> itemsByWaste = new HashMap<>();
        if (!posted.isEmpty()) {
            for (Map<String, Object> it : SqlRows.list(jdbc.sql("SELECT wi.\"wasteId\", wi.\"productId\","
                    + " wi.\"productName\", wi.unit, wi.qty, wi.\"totalCost\", wi.\"isExpiry\" FROM \"WasteItem\" wi"
                    + " JOIN \"WasteRecord\" w ON w.id = wi.\"wasteId\" WHERE w.\"organizationId\" = :org"
                    + " AND wi.\"wasteId\" IN (:ids)").param("org", org())
                    .param("ids", posted.stream().map(r -> str(r, "id")).toList()))) {
                itemsByWaste.computeIfAbsent(str(it, "wasteId"), k -> new ArrayList<>()).add(it);
            }
        }
        for (Map<String, Object> r : posted) {
            BigDecimal value = dec(r, "totalValue");
            totalValue = totalValue.add(value);
            Map<String, Object> c = byCategory.computeIfAbsent(str(r, "category"),
                    k -> Obj.of("category", k, "records", 0, "value", BigDecimal.ZERO));
            c.put("records", (int) c.get("records") + 1);
            c.put("value", ((BigDecimal) c.get("value")).add(value));
            Map<String, Object> l = byLocation.computeIfAbsent(str(r, "locationId"),
                    k -> Obj.of("locationId", k, "code", r.get("code"), "name", r.get("name"), "records", 0, "value",
                            BigDecimal.ZERO));
            l.put("records", (int) l.get("records") + 1);
            l.put("value", ((BigDecimal) l.get("value")).add(value));
            for (Map<String, Object> it : itemsByWaste.getOrDefault(str(r, "id"), List.of())) {
                Map<String, Object> p = byProduct.computeIfAbsent(str(it, "productId"),
                        k -> Obj.of("productId", k, "productName", it.get("productName"), "unit", it.get("unit"), "qty",
                                BigDecimal.ZERO, "value", BigDecimal.ZERO, "lines", 0));
                p.put("qty", ((BigDecimal) p.get("qty")).add(dec(it, "qty")));
                p.put("value", ((BigDecimal) p.get("value")).add(dec(it, "totalCost")));
                p.put("lines", (int) p.get("lines") + 1);
                if (Boolean.TRUE.equals(it.get("isExpiry"))) {
                    expiryValue = expiryValue.add(dec(it, "totalCost"));
                }
            }
        }
        Comparator<Map<String, Object>> desc =
                (a, b) -> ((BigDecimal) b.get("value")).compareTo((BigDecimal) a.get("value"));
        return Obj.of("period", Obj.of("from", q.from(), "to", q.to()),
                "postedRecords", posted.size(),
                "totalValue", JsNumbers.js(totalValue),
                "expiryValue", JsNumbers.js(expiryValue),
                "pendingRecords", ((Number) pending.get("n")).longValue(),
                "pendingValue", JsNumbers.js(pending.get("v") == null ? BigDecimal.ZERO : dec(pending, "v")),
                "byCategory", jsValues(sorted(byCategory.values(), desc)),
                "byLocation", jsValues(sorted(byLocation.values(), desc)),
                "topProducts", jsValues(sorted(byProduct.values(), desc).stream().limit(10).toList()));
    }

    private static List<Map<String, Object>> sorted(java.util.Collection<Map<String, Object>> rows,
            Comparator<Map<String, Object>> cmp) {
        List<Map<String, Object>> list = new ArrayList<>(rows);
        list.sort(cmp);
        return list;
    }

    /** Decimal accumulators → JS numbers. */
    private static List<Map<String, Object>> jsValues(List<Map<String, Object>> rows) {
        for (Map<String, Object> r : rows) {
            r.replaceAll((k, v) -> v instanceof BigDecimal d ? JsNumbers.js(d) : v);
        }
        return rows;
    }

    private String wasteWhere(WasteQuery q, String dateField, List<Object> params) {
        return wasteWhere(q, dateField, params, "");
    }

    private String wasteWhere(WasteQuery q, String dateField, List<Object> params, String a) {
        StringBuilder sb = new StringBuilder(" " + a + "\"organizationId\" = ?");
        params.add(org());
        if (q.status() != null) {
            sb.append(" AND ").append(a).append("\"status\"::text = ?");
            params.add(q.status());
        }
        if (q.category() != null) {
            sb.append(" AND ").append(a).append("\"category\"::text = ?");
            params.add(q.category());
        }
        DataScope access = scope.current();
        if (access.restricted()) {
            sb.append(" AND ").append(a).append("\"locationId\" = ANY(?::text[])");
            params.add(access.locationArray());
        }
        if (q.locationId() != null) {
            sb.append(" AND ").append(a).append("\"locationId\" = ?");
            params.add(q.locationId());
        }
        if (q.from() != null) {
            sb.append(" AND ").append(a).append("\"").append(dateField).append("\" >= ?");
            params.add(SqlRows.ts(EntityPatch.toInstant(q.from())));
        }
        if (q.to() != null) {
            Instant to = q.to().length() <= 10 ? EntityPatch.toInstant(q.to()).plusMillis(86_400_000L)
                    : EntityPatch.toInstant(q.to());
            sb.append(" AND ").append(a).append("\"").append(dateField).append("\" < ?");
            params.add(SqlRows.ts(to));
        }
        return sb.toString();
    }

    // ================================================================== Adjustment

    /** Create a pending adjustment snapshotting system on-hand per line. */
    @Transactional
    public Map<String, Object> createAdjustment(DocInput dto, boolean embedded) {
        lookups.requireLocation(dto.locationId());
        if (!embedded) {
            scope.assertAllowed(dto.locationId());
            staff.assertActiveStaff(org(), StaffAttribution.people(dto.responsibleById(), dto.approvedById()));
        }
        Set<String> seen = new HashSet<>();
        for (DocLine i : dto.items()) {
            if (!seen.add(i.productId() + ":" + (i.variantId() == null ? "" : i.variantId()))) {
                throw ApiException.badRequest("Each product can appear only once in an adjustment");
            }
        }
        Map<String, String> names = names(dto.items());
        for (DocLine i : dto.items()) {
            if (names.get(i.productId()) == null) {
                throw ApiException.notFound("Product " + i.productId() + " not found");
            }
        }
        String adjCode = seq.next("stock_adj", "ADJ-", 5);
        String id = UUID.randomUUID().toString();
        String user = TenantContext.userId();
        jdbc.sql("INSERT INTO \"StockAdjustment\" (id, \"organizationId\", \"adjCode\", \"locationId\", reason, status,"
                        + " notes, \"performedById\", \"responsibleById\", \"approvedById\", \"createdBy\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?::\"StockAdjustmentReason\", 'pending', ?, ?, ?, ?, ?, " + NOW
                        + ", " + NOW + ")")
                .params(id, org(), adjCode, dto.locationId(), dto.reason() != null ? dto.reason() : "cycle_count",
                        dto.notes(), user, dto.responsibleById(), dto.approvedById(), user)
                .update();
        for (DocLine i : dto.items()) {
            Map<String, Object> si = lookups.stockItem(i.productId(), i.variantId(), dto.locationId());
            BigDecimal qtySystem = si == null ? Money.ZERO : dec(si, "quantity");
            jdbc.sql("INSERT INTO \"StockAdjustmentItem\" (id, \"organizationId\", \"adjId\", \"productId\", \"variantId\","
                            + " \"productName\", unit, \"qtySystem\", \"qtyActual\", \"qtyDiff\", \"batchNumber\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, i.productId(), i.variantId(),
                            names.getOrDefault(i.productId(), "Unknown"), i.unit(), qtySystem, i.qtyActual(),
                            i.qtyActual().subtract(qtySystem), i.batchNumber())
                    .update();
        }
        return withItems(adjustment(id));
    }

    /** Approve + post an adjustment (each line counts to {@code qtyActual}); drift-guarded when standalone. */
    @Transactional
    public Map<String, Object> approveAdjustment(String id, boolean embedded, Boolean force, String forceReason) {
        if (!embedded) {
            Map<String, Object> pre = adjustment(id);
            if (pre == null) {
                throw ApiException.notFound("Adjustment not found");
            }
            BigDecimal magnitude = BigDecimal.ZERO;
            for (Map<String, Object> it : items("StockAdjustmentItem", StockAdjustmentItem.class, "adjId", id)) {
                magnitude = magnitude.add(dec(it, "qtyDiff").abs());
            }
            List<String> makers = new ArrayList<>();
            makers.add(str(pre, "performedById"));
            makers.add(str(pre, "createdBy"));
            gateApproval("stock_adjustment", id, Obj.of("amount", JsNumbers.js(magnitude), "adjCode", pre.get("adjCode"),
                    "reason", pre.get("reason"), "makerIds", String.join(",", makers.stream()
                            .filter(java.util.Objects::nonNull).distinct().toList())),
                    "adjustment " + pre.get("adjCode"));
            sod.assertSeparated(SegregationOfDutiesService.STOCK_ADJUSTMENT, "StockAdjustment", id, makers);
        }
        Map<String, Object> doc = adjustment(id);
        if (doc == null) {
            throw ApiException.notFound("Adjustment not found");
        }
        if (!embedded) {
            scope.assertAllowed(str(doc, "locationId"));
        }
        if (doc.get("postedAt") != null) {
            throw ApiException.badRequest("Document already posted");
        }
        String status = str(doc, "status");
        if (!"pending".equals(status) && !"draft".equals(status)) {
            throw ApiException.badRequest("Cannot approve a " + status + " document");
        }
        List<Map<String, Object>> items = items("StockAdjustmentItem", StockAdjustmentItem.class, "adjId", id);
        if (claim("StockAdjustment", id, "approved") == 0) {
            throw ApiException.badRequest("Adjustment was posted or cancelled concurrently");
        }
        if (!embedded) {
            List<String> drifted = new ArrayList<>();
            for (Map<String, Object> item : items) {
                Map<String, Object> si = lookups.stockItem(str(item, "productId"), str(item, "variantId"),
                        str(doc, "locationId"));
                BigDecimal nowQty = si == null ? Money.ZERO : dec(si, "quantity");
                if (nowQty.compareTo(dec(item, "qtySystem")) != 0) {
                    drifted.add(item.get("productName") + " (counted against " + JsNumbers.dec(dec(item, "qtySystem"))
                            + ", now " + JsNumbers.dec(nowQty) + ")");
                }
            }
            if (!drifted.isEmpty() && !Boolean.TRUE.equals(force)) {
                throw ApiException.withBody(HttpStatus.CONFLICT, Obj.of("code", "ADJUSTMENT_STOCK_DRIFT", "message",
                        "Stock moved after this adjustment was created: "
                                + String.join("; ", drifted.subList(0, Math.min(5, drifted.size())))
                                + (drifted.size() > 5 ? " …and " + (drifted.size() - 5) + " more" : "") + ". "
                                + "Re-create the adjustment, or approve with force and a reason to set the counted"
                                + " quantities anyway.",
                        "drifted", drifted));
            }
            if (!drifted.isEmpty() && (forceReason == null || forceReason.trim().isEmpty())) {
                throw ApiException.badRequest(
                        "A reason is required to force-approve an adjustment over stock that moved.");
            }
            if (!drifted.isEmpty()) {
                String note = "Force-approved over drift: " + forceReason.trim();
                String notes = doc.get("notes") != null && !str(doc, "notes").isEmpty() ? doc.get("notes") + " · " + note
                        : note;
                jdbc.sql("UPDATE \"StockAdjustment\" SET notes = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                        .params(notes, id).update();
            }
        }
        String user = TenantContext.userId();
        for (Map<String, Object> item : lockOrder(items)) {
            StockMove m = StockMove.of(str(item, "productId"), str(doc, "locationId"), null)
                    .variant(str(item, "variantId")).notes(doc.get("adjCode") + " · " + doc.get("reason"))
                    .batch(str(item, "batchNumber")).source("stock_adjustment", str(doc, "adjCode"))
                    .people(str(doc, "responsibleById"), user != null ? user : str(doc, "approvedById"));
            m.countedQuantity = dec(item, "qtyActual");
            stock.adjust(m);
        }
        jdbc.sql("UPDATE \"StockAdjustment\" SET status = 'completed', \"approvedById\" = ?, \"approvedAt\" = " + NOW
                        + ", \"postedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(user, id).update();
        return withItems(adjustment(id));
    }

    /**
     * One count variance to post: the change to apply ({@code delta}, against the on-hand read under the quant lock),
     * the lot it belongs to (by number; null = the quant without a named lot) and the serial units that went missing
     * (loss) or were found (gain). {@code qtySystem} / {@code qtyActual} are what the adjustment document shows.
     */
    public record CountVariance(String productId, String variantId, String unit, String batchNumber,
            BigDecimal qtySystem, BigDecimal qtyActual, BigDecimal delta, List<String> serialNumbers) {}

    /**
     * Post a count's variances as one completed stock adjustment ({@code cycle_count}) inside the caller's
     * transaction. Unlike {@link #approveAdjustment} each line applies a delta, so several lots of one quant can be
     * counted and movements made after the count stay in the on-hand; the count submit owns approval, separation of
     * duties and branch scope.
     */
    @Transactional
    public Map<String, Object> postCountVariances(String locationId, String responsibleById, String notes,
            List<CountVariance> lines) {
        lookups.requireLocation(locationId);
        Map<String, String> names = lookups.productNames(new LinkedHashSet<>(lines.stream()
                .map(CountVariance::productId).toList()));
        String adjCode = seq.next("stock_adj", "ADJ-", 5);
        String id = UUID.randomUUID().toString();
        String user = TenantContext.userId();
        jdbc.sql("INSERT INTO \"StockAdjustment\" (id, \"organizationId\", \"adjCode\", \"locationId\", reason, status,"
                        + " notes, \"performedById\", \"responsibleById\", \"approvedById\", \"createdBy\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, 'cycle_count'::\"StockAdjustmentReason\", 'approved', ?, ?, ?, ?, ?,"
                        + " " + NOW + ", " + NOW + ")")
                .params(id, org(), adjCode, locationId, notes, user, responsibleById, user != null ? user : "", user)
                .update();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CountVariance l : lines) {
            jdbc.sql("INSERT INTO \"StockAdjustmentItem\" (id, \"organizationId\", \"adjId\", \"productId\", \"variantId\","
                            + " \"productName\", unit, \"qtySystem\", \"qtyActual\", \"qtyDiff\", \"batchNumber\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, l.productId(), l.variantId(),
                            names.getOrDefault(l.productId(), "Unknown"), l.unit(), l.qtySystem(), l.qtyActual(),
                            l.delta(), l.batchNumber())
                    .update();
            Map<String, Object> row = new HashMap<>();
            row.put("productId", l.productId());
            row.put("variantId", l.variantId());
            row.put("line", l);
            rows.add(row);
        }
        for (Map<String, Object> row : lockOrder(rows)) {
            CountVariance l = (CountVariance) row.get("line");
            StockMove m = StockMove.of(l.productId(), locationId, null).variant(l.variantId())
                    .notes(adjCode + " · cycle_count").batch(l.batchNumber()).source("stock_adjustment", adjCode)
                    .serials(l.serialNumbers() == null || l.serialNumbers().isEmpty() ? null : l.serialNumbers())
                    .people(responsibleById, user);
            m.deltaQuantity = l.delta();
            stock.adjust(m);
        }
        jdbc.sql("UPDATE \"StockAdjustment\" SET status = 'completed', \"approvedById\" = ?, \"approvedAt\" = " + NOW
                        + ", \"postedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(user, id).update();
        return withItems(adjustment(id));
    }

    /** Discard a not-yet-posted adjustment. */
    @Transactional
    public Map<String, Object> cancelAdjustment(String id) {
        Map<String, Object> doc = adjustment(id);
        if (doc == null) {
            throw ApiException.notFound("Adjustment not found");
        }
        scope.assertAllowed(str(doc, "locationId"));
        assertPostable(str(doc, "status"), doc.get("postedAt"));
        if (cancel("StockAdjustment", id) == 0) {
            throw ApiException.badRequest("Adjustment was posted or cancelled concurrently");
        }
        return withLocation(withItems(adjustment(id)), true);
    }

    // ================================================================== Transfer

    /** Create a pending transfer (immediate or transit). */
    @Transactional
    public Map<String, Object> createTransfer(DocInput dto) {
        if (dto.fromLocationId().equals(dto.toLocationId())) {
            throw ApiException.badRequest("Source and destination must differ");
        }
        scope.assertEither(dto.fromLocationId(), dto.toLocationId());
        Map<String, Object> from = lookups.requireLocation(dto.fromLocationId());
        Map<String, Object> to = lookups.requireLocation(dto.toLocationId());
        if ("transit".equals(str(from, "type")) || "transit".equals(str(to, "type"))) {
            throw ApiException.badRequest("The transit location is system-managed; use a transit transfer instead of"
                    + " moving stock into or out of it directly");
        }
        for (DocLine i : dto.items()) {
            if (i.qtyRequested() == null || i.qtyRequested().signum() <= 0) {
                throw ApiException.badRequest("Every transfer line needs a positive quantity");
            }
        }
        staff.assertActiveStaff(org(), StaffAttribution.people(dto.responsibleById(), dto.approvedById()));
        Map<String, String> names = names(dto.items());
        String code = seq.next("stock_transfer", "TRF-", 5);
        String id = UUID.randomUUID().toString();
        String user = TenantContext.userId();
        jdbc.sql("INSERT INTO \"StockTransfer\" (id, \"organizationId\", \"transferCode\", \"fromLocId\", \"toLocId\","
                        + " status, mode, notes, \"performedById\", \"responsibleById\", \"approvedById\", \"createdBy\","
                        + " \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?, " + NOW + ", "
                        + NOW + ")")
                .params(id, org(), code, dto.fromLocationId(), dto.toLocationId(),
                        dto.mode() != null ? dto.mode() : "immediate", dto.notes(), user, dto.responsibleById(),
                        dto.approvedById(), user)
                .update();
        for (DocLine i : dto.items()) {
            jdbc.sql("INSERT INTO \"StockTransferItem\" (id, \"organizationId\", \"transferId\", \"productId\","
                            + " \"variantId\", \"productName\", unit, \"qtyRequested\", \"batchNumber\", \"distStrategy\")"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, i.productId(), i.variantId(),
                            names.getOrDefault(i.productId(), "Unknown"), i.unit(), i.qtyRequested(), i.batchNumber(),
                            i.distStrategy() != null ? i.distStrategy() : "FEFO")
                    .update();
        }
        return withItems(transfer(id));
    }

    /** Approve a transfer: immediate posts it; transit only authorises dispatch. */
    @Transactional
    public Map<String, Object> approveTransfer(String id) {
        Map<String, Object> doc = transfer(id);
        if (doc == null) {
            throw ApiException.notFound("Transfer not found");
        }
        scope.assertEither(str(doc, "fromLocationId"), str(doc, "toLocationId"));
        List<Map<String, Object>> items = items("StockTransferItem", StockTransferItem.class, "transferId", id);
        assertPostable(str(doc, "status"), doc.get("postedAt"));
        BigDecimal magnitude = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            magnitude = magnitude.add(dec(it, "qtyRequested"));
        }
        gateApproval("stock_transfer", id, Obj.of("amount", JsNumbers.js(magnitude), "transferCode",
                doc.get("transferCode")), "transfer " + doc.get("transferCode"));
        String user = TenantContext.userId();
        if ("transit".equals(str(doc, "mode"))) {
            int claimed = jdbc.sql("UPDATE \"StockTransfer\" SET status = 'approved', \"approvedById\" = ?, \"approvedAt\" = "
                            + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"postedAt\""
                            + " IS NULL AND status IN ('pending', 'draft')")
                    .params(user, id, org()).update();
            if (claimed == 0) {
                throw ApiException.badRequest("Transfer was approved or cancelled concurrently");
            }
            return getTransfer(id);
        }
        int claimed = jdbc.sql("UPDATE \"StockTransfer\" SET status = 'approved', \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"postedAt\" IS NULL AND status IN ('pending',"
                        + " 'draft') AND mode = 'immediate'")
                .params(id, org()).update();
        if (claimed == 0) {
            throw ApiException.badRequest("Transfer was posted or cancelled concurrently");
        }
        for (Map<String, Object> item : lockOrder(items)) {
            StockMove m = StockMove.transfer(str(item, "productId"), str(doc, "fromLocId"), str(doc, "toLocId"),
                            dec(item, "qtyRequested")).variant(str(item, "variantId"))
                    .source("stock_transfer", str(doc, "transferCode")).notes(str(doc, "notes"))
                    .people(str(doc, "responsibleById"), user != null ? user : str(doc, "approvedById"));
            stock.transfer(m);
            jdbc.sql("UPDATE \"StockTransferItem\" SET \"qtyTransferred\" = ? WHERE id = ?")
                    .params(dec(item, "qtyRequested"), item.get("id")).update();
        }
        jdbc.sql("UPDATE \"StockTransfer\" SET status = 'completed', \"approvedById\" = ?, \"approvedAt\" = " + NOW
                        + ", \"postedAt\" = " + NOW + ", \"completedAt\" = " + NOW + ", \"updatedAt\" = " + NOW
                        + " WHERE id = ?")
                .params(user, id).update();
        return withItems(transfer(id));
    }

    /** Transfer with items and receipts (oldest first). */
    @Transactional(readOnly = true)
    public Map<String, Object> getTransfer(String id) {
        Map<String, Object> doc = transfer(id);
        if (doc == null) {
            throw ApiException.notFound("Transfer not found");
        }
        scope.assertEither(str(doc, "fromLocationId"), str(doc, "toLocationId"));
        return withReceipts(withItems(doc), true);
    }

    /** The org's transit location, created on first use (outside the movement transaction). */
    private String ensureTransitLocation() {
        String found = tx.execute(s -> findTransit());
        if (found != null) {
            return found;
        }
        for (String code : List.of("TRANSIT", "TRANSIT-SYS")) {
            try {
                String id = UUID.randomUUID().toString();
                newTx.executeWithoutResult(s -> jdbc.sql("INSERT INTO \"InventoryLocation\" (id, \"organizationId\", code,"
                                + " name, type, \"createdBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, 'Goods in"
                                + " transit', 'transit', ?, " + NOW + ", " + NOW + ")")
                        .params(id, org(), code, TenantContext.userId()).update());
                return id;
            } catch (org.springframework.dao.DataIntegrityViolationException err) {
                String raced = tx.execute(s -> findTransit());
                if (raced != null) {
                    return raced;
                }
            }
        }
        throw ApiException.conflict("Could not create the transit location (codes TRANSIT and TRANSIT-SYS are used by"
                + " other locations)");
    }

    private String findTransit() {
        return jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND type = 'transit'"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" ASC LIMIT 1")
                .param(org()).query(String.class).optional().orElse(null);
    }

    /** Row-lock the transfer header and return it with fresh items; must be a transit transfer. */
    private Map<String, Object> lockTransfer(String id) {
        jdbc.sql("SELECT id FROM \"StockTransfer\" WHERE id = ? AND \"organizationId\" = ? FOR UPDATE")
                .params(id, org()).query().listOfRows();
        Map<String, Object> doc = transfer(id);
        if (doc == null) {
            throw ApiException.notFound("Transfer not found");
        }
        scope.assertEither(str(doc, "fromLocationId"), str(doc, "toLocationId"));
        if (!"transit".equals(str(doc, "mode"))) {
            throw ApiException.badRequest(doc.get("transferCode")
                    + " is an immediate transfer; it has no dispatch or receipt stages");
        }
        doc.put("items", items("StockTransferItem", StockTransferItem.class, "transferId", id));
        return doc;
    }

    private static BigDecimal outstanding(Map<String, Object> item) {
        return dec(item, "qtyDispatched").subtract(dec(item, "qtyReceived")).subtract(dec(item, "qtyDamaged"))
                .subtract(dec(item, "qtyShort")).subtract(dec(item, "qtyRecalled"));
    }

    /** Dispatch an approved transit transfer: source → transit for every line. */
    public Map<String, Object> dispatchTransfer(String id) {
        String transit = ensureTransitLocation();
        return tx.execute(s -> {
            Map<String, Object> doc = lockTransfer(id);
            if (!"approved".equals(str(doc, "status")) || doc.get("dispatchedAt") != null) {
                throw ApiException.badRequest("Only an approved, undispatched transfer can be dispatched ("
                        + doc.get("transferCode") + " is " + doc.get("status") + ")");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = lockOrder((List<Map<String, Object>>) doc.get("items"));
            for (Map<String, Object> item : items) {
                StockMove m = StockMove.transfer(str(item, "productId"), str(doc, "fromLocId"), transit,
                                dec(item, "qtyRequested")).variant(str(item, "variantId"))
                        .source("stock_transfer_dispatch", str(doc, "transferCode")).notes(str(doc, "notes"))
                        .people(str(doc, "responsibleById"), str(doc, "approvedById"));
                recordTransitLayers(id, str(item, "id"), dec(item, "qtyRequested"), stock.transfer(m));
                jdbc.sql("UPDATE \"StockTransferItem\" SET \"qtyDispatched\" = ? WHERE id = ?")
                        .params(dec(item, "qtyRequested"), item.get("id")).update();
            }
            String user = TenantContext.userId();
            jdbc.sql("UPDATE \"StockTransfer\" SET status = 'in_transit', \"transitLocId\" = ?, \"dispatchedAt\" = " + NOW
                            + ", \"dispatchedById\" = ?, \"postedAt\" = " + NOW + ", \"updatedBy\" = ?, \"updatedAt\" = "
                            + NOW + " WHERE id = ?")
                    .params(transit, user, user, id).update();
            audit.recordInTx(AuditInput.of("StockTransfer", id, "update", null, Obj.of("kind", "transfer_dispatched",
                    "transferCode", doc.get("transferCode"), "transitLocId", transit)));
            return withReceipts(withItems(transfer(id)), false);
        });
    }

    /** Record one destination receipt splitting outstanding transit qty into received / damaged / short. */
    @Transactional
    public Map<String, Object> receiveTransfer(String id, List<ReceiveLine> lines, String notes) {
        Map<String, Object> doc = lockTransfer(id);
        String status = str(doc, "status");
        String code = str(doc, "transferCode");
        if (!"in_transit".equals(status) && !"partially_received".equals(status)) {
            throw ApiException.badRequest(code + " is " + status + "; only in-transit transfers can be received");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docItems = (List<Map<String, Object>>) doc.get("items");
        Map<String, Map<String, Object>> byId = new HashMap<>();
        docItems.forEach(i -> byId.put(str(i, "id"), i));
        Set<String> seen = new HashSet<>();
        List<Object[]> plan = new ArrayList<>();
        for (ReceiveLine line : lines) {
            Map<String, Object> item = byId.get(line.itemId());
            if (item == null) {
                throw ApiException.badRequest("Line " + line.itemId() + " does not belong to " + code);
            }
            if (!seen.add(line.itemId())) {
                throw ApiException.badRequest(item.get("productName") + " appears more than once in the receipt");
            }
            BigDecimal received = Money.dec(line.received());
            BigDecimal damaged = Money.dec(line.damaged());
            BigDecimal shortQty = Money.dec(line.shortQty());
            BigDecimal total = received.add(damaged).add(shortQty);
            if (total.signum() == 0) {
                continue;
            }
            BigDecimal open = outstanding(item);
            if (total.compareTo(open.add(QTY_EPSILON)) > 0) {
                throw ApiException.badRequest(item.get("productName") + ": received " + JsNumbers.dec(received)
                        + " + damaged " + JsNumbers.dec(damaged) + " + short " + JsNumbers.dec(shortQty)
                        + " exceeds the " + JsNumbers.dec(open) + " still in transit");
            }
            plan.add(new Object[] {item, received, damaged, shortQty});
        }
        if (plan.isEmpty()) {
            throw ApiException.badRequest("Enter a received, damaged or short quantity on at least one line");
        }
        String receiptCode = seq.next("stock_transfer_receipt", "TRR-", 5);
        String transitLocId = str(doc, "transitLocId");
        String reference = code + " " + receiptCode;
        String user = TenantContext.userId();
        List<Map<String, Object>> receiptLines = new ArrayList<>();
        Map<Object, Map<String, Object>> receiptLineByItem = new HashMap<>();
        List<Object[]> ordered = new ArrayList<>(plan);
        ordered.sort(Comparator.comparing((Object[] p) -> String.valueOf(((Map<?, ?>) p[0]).get("productId")))
                .thenComparing(p -> ((Map<?, ?>) p[0]).get("variantId") == null ? ""
                        : String.valueOf(((Map<?, ?>) p[0]).get("variantId"))));
        for (Object[] p : ordered) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) p[0];
            BigDecimal received = (BigDecimal) p[1];
            BigDecimal damaged = (BigDecimal) p[2];
            BigDecimal shortQty = (BigDecimal) p[3];
            if (received.signum() > 0) {
                List<String> n = new ArrayList<>(List.of(receiptCode));
                if (notes != null && !notes.isEmpty()) {
                    n.add(notes);
                }
                stock.transfer(pinTransit(StockMove.transfer(str(item, "productId"), transitLocId, str(doc, "toLocId"),
                                received).variant(str(item, "variantId")).source("stock_transfer_receipt", code)
                        .notes(String.join(" · ", n)).people(str(doc, "responsibleById"), user), str(item, "id")));
            }
            if (damaged.signum() > 0) {
                stock.issue(pinTransit(StockMove.of(str(item, "productId"), transitLocId, damaged)
                        .variant(str(item, "variantId")).moveType("waste").source("stock_transfer_loss", code)
                        .reference(reference + " damaged in transit").requireAvailable(true).people(null, user),
                        str(item, "id")));
            }
            if (shortQty.signum() > 0) {
                Issued res = stock.issue(pinTransit(StockMove.of(str(item, "productId"), transitLocId, shortQty)
                        .variant(str(item, "variantId")).moveType("adjustment_out").source("stock_transfer_loss", code)
                        .reference(reference + " short on arrival").requireAvailable(true).skipGl().people(null, user),
                        str(item, "id")));
                stockPosting.postAdjustment(str(item, "productId"), BigDecimal.ONE.negate(), res.totalValue(),
                        Instant.now(), "stock_transfer_loss", code, "Transit shortage · " + item.get("productName")
                                + " · " + JsNumbers.dec(shortQty) + " · " + reference);
            }
            jdbc.sql("UPDATE \"StockTransferItem\" SET \"qtyReceived\" = \"qtyReceived\" + ?, \"qtyDamaged\" ="
                            + " \"qtyDamaged\" + ?, \"qtyShort\" = \"qtyShort\" + ?, \"qtyTransferred\" ="
                            + " \"qtyTransferred\" + ? WHERE id = ?")
                    .params(received, damaged, shortQty, received, item.get("id")).update();
            receiptLineByItem.put(item.get("id"), Obj.of("itemId", item.get("id"), "productId", item.get("productId"),
                    "received", JsNumbers.dec(received), "damaged", JsNumbers.dec(damaged), "short",
                    JsNumbers.dec(shortQty)));
        }
        for (Object[] p : plan) {
            receiptLines.add(receiptLineByItem.get(((Map<?, ?>) p[0]).get("id")));
        }
        jdbc.sql("INSERT INTO \"StockTransferReceipt\" (id, \"organizationId\", \"transferId\", \"receiptCode\", kind,"
                        + " \"receivedById\", notes, lines, \"createdAt\") VALUES (?, ?, ?, ?, 'receipt', ?, ?, ?::jsonb, "
                        + NOW + ")")
                .params(UUID.randomUUID().toString(), org(), id, receiptCode, user, notes,
                        EntityJson.toJson(receiptLines))
                .update();
        Map<String, Object> done = finaliseTransit(id, false);
        audit.recordInTx(AuditInput.of("StockTransfer", id, "update", null, Obj.of("kind", "transfer_received",
                "transferCode", code, "receiptCode", receiptCode, "status", done.get("status"))));
        return done;
    }

    /** Recall everything still in transit back to the source. */
    @Transactional
    public Map<String, Object> recallTransfer(String id, String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw ApiException.badRequest("A reason is required to recall a transfer");
        }
        Map<String, Object> doc = lockTransfer(id);
        String status = str(doc, "status");
        String code = str(doc, "transferCode");
        if (!"in_transit".equals(status) && !"partially_received".equals(status)) {
            throw ApiException.badRequest(code + " is " + status + "; only goods still in transit can be recalled");
        }
        String receiptCode = seq.next("stock_transfer_receipt", "TRR-", 5);
        List<Map<String, Object>> lines = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> docItems = (List<Map<String, Object>>) doc.get("items");
        for (Map<String, Object> item : lockOrder(docItems)) {
            BigDecimal open = outstanding(item);
            if (open.compareTo(QTY_EPSILON) <= 0) {
                continue;
            }
            stock.transfer(pinTransit(StockMove.transfer(str(item, "productId"), str(doc, "transitLocId"),
                            str(doc, "fromLocId"), open).variant(str(item, "variantId")).source("stock_transfer_recall", code)
                    .notes(receiptCode + " recall: " + reason.trim()).people(null, TenantContext.userId()),
                    str(item, "id")));
            jdbc.sql("UPDATE \"StockTransferItem\" SET \"qtyRecalled\" = \"qtyRecalled\" + ? WHERE id = ?")
                    .params(open, item.get("id")).update();
        }
        for (Map<String, Object> item : docItems) {
            BigDecimal open = outstanding(item);
            if (open.compareTo(QTY_EPSILON) > 0) {
                lines.add(Obj.of("itemId", item.get("id"), "productId", item.get("productId"), "recalled",
                        JsNumbers.dec(open)));
            }
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest(code + " has nothing left in transit");
        }
        jdbc.sql("INSERT INTO \"StockTransferReceipt\" (id, \"organizationId\", \"transferId\", \"receiptCode\", kind,"
                        + " \"receivedById\", notes, lines, \"createdAt\") VALUES (?, ?, ?, ?, 'recall', ?, ?, ?::jsonb, "
                        + NOW + ")")
                .params(UUID.randomUUID().toString(), org(), id, receiptCode, TenantContext.userId(), reason.trim(),
                        EntityJson.toJson(lines))
                .update();
        Map<String, Object> done = finaliseTransit(id, true);
        audit.recordInTx(AuditInput.of("StockTransfer", id, "update", null, Obj.of("kind", "transfer_recalled",
                "transferCode", code, "receiptCode", receiptCode, "reason", reason.trim(), "status", done.get("status"))));
        return done;
    }

    /** Cancel a transfer that has not moved any stock yet. */
    @Transactional
    public Map<String, Object> cancelTransfer(String id) {
        Map<String, Object> doc = getTransfer(id);
        int upd = jdbc.sql("UPDATE \"StockTransfer\" SET status = 'cancelled', \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"postedAt\" IS NULL AND \"dispatchedAt\" IS NULL"
                        + " AND status IN ('draft', 'pending', 'approved')")
                .params(TenantContext.userId(), id, org()).update();
        if (upd == 0) {
            throw ApiException.badRequest(doc.get("transferCode") + " has already moved stock; recall or reverse it instead");
        }
        return getTransfer(id);
    }

    /**
     * Remember what one transit line put into the transit location: the lots created there, the serial units moved
     * and the unit cost of the dispatch, so receipt / loss / recall move exactly this transfer's units at their cost
     * (the transit location is shared by every transfer of the organisation).
     */
    private void recordTransitLayers(String transferId, String itemId, BigDecimal qty, StockMove.Transferred moved) {
        String sql = "INSERT INTO \"StockTransferLayer\" (id, \"organizationId\", \"transferId\", \"transferItemId\","
                + " \"batchId\", \"serialId\", quantity, \"unitCost\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.sql(sql).params(UUID.randomUUID().toString(), org(), transferId, itemId, null, null, qty,
                moved.carriedUnitCost()).update();
        for (StockMove.MovedLayer layer : moved.layers()) {
            jdbc.sql(sql).params(UUID.randomUUID().toString(), org(), transferId, itemId, layer.batchId(),
                    layer.serialId(), layer.quantity(), layer.unitCost()).update();
        }
    }

    /** Pin a move out of transit to the lots / serials / cost its line dispatched (unpinned for older dispatches). */
    private StockMove pinTransit(StockMove move, String itemId) {
        List<Map<String, Object>> rows = jdbc.sql("SELECT \"batchId\", \"serialId\", \"unitCost\" FROM"
                        + " \"StockTransferLayer\" WHERE \"organizationId\" = ? AND \"transferItemId\" = ?")
                .params(org(), itemId).query().listOfRows();
        if (rows.isEmpty()) {
            return move;
        }
        List<String> batchIds = new ArrayList<>();
        List<String> serialIds = new ArrayList<>();
        BigDecimal unitCost = null;
        for (Map<String, Object> r : rows) {
            if (r.get("batchId") != null) {
                batchIds.add((String) r.get("batchId"));
            } else if (r.get("serialId") != null) {
                serialIds.add((String) r.get("serialId"));
            } else if (r.get("unitCost") != null) {
                unitCost = (BigDecimal) r.get("unitCost");
            }
        }
        return move.pin(batchIds, serialIds, unitCost);
    }

    private Map<String, Object> finaliseTransit(String id, boolean recalled) {
        List<Map<String, Object>> items = items("StockTransferItem", StockTransferItem.class, "transferId", id);
        boolean allAccounted = items.stream().allMatch(i -> outstanding(i).compareTo(QTY_EPSILON) <= 0);
        boolean anyDelivered = items.stream().anyMatch(i -> dec(i, "qtyReceived").add(dec(i, "qtyDamaged"))
                .add(dec(i, "qtyShort")).signum() > 0);
        String status = !allAccounted ? "partially_received" : anyDelivered ? "completed" : "cancelled";
        jdbc.sql("UPDATE \"StockTransfer\" SET status = ?::\"StockDocStatus\""
                        + (recalled ? "" : ", \"lastReceivedAt\" = " + NOW)
                        + ("completed".equals(status) ? ", \"completedAt\" = " + NOW : "")
                        + ", \"updatedBy\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(status, TenantContext.userId(), id).update();
        return withReceipts(withItems(transfer(id)), true);
    }

    // ================================================================== shared

    private void assertPostable(String status, Object postedAt) {
        if (postedAt != null) {
            throw ApiException.badRequest("Document already posted");
        }
        if (!"pending".equals(status) && !"draft".equals(status)) {
            throw ApiException.badRequest("Cannot approve a " + status + " document");
        }
    }

    /** Document lists (newest first) with the Nest includes per kind. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String kind, String status) {
        String table = switch (kind) {
            case "out" -> "StockOut";
            case "waste" -> "WasteRecord";
            case "adjustment" -> "StockAdjustment";
            default -> "StockTransfer";
        };
        Class<?> type = switch (kind) {
            case "out" -> StockOut.class;
            case "waste" -> WasteRecord.class;
            case "adjustment" -> StockAdjustment.class;
            default -> StockTransfer.class;
        };
        var spec = status != null
                ? jdbc.sql("SELECT * FROM \"" + table + "\" WHERE \"organizationId\" = ? AND status::text = ?"
                        + " ORDER BY \"createdAt\" DESC").params(org(), status)
                : jdbc.sql("SELECT * FROM \"" + table + "\" WHERE \"organizationId\" = ? ORDER BY \"createdAt\" DESC")
                        .param(org());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : SqlRows.list(type, spec)) {
            if (!reachable(kind, row)) {
                continue;
            }
            Map<String, Object> r = withItems(row);
            if ("adjustment".equals(kind)) {
                r = withLocation(r, true);
            }
            if ("transfer".equals(kind)) {
                r = withReceipts(r, true);
            }
            out.add(r);
        }
        return out;
    }

    private boolean reachable(String kind, Map<String, Object> row) {
        return "transfer".equals(kind) ? scope.permits(str(row, "fromLocationId")) || scope.permits(str(row, "toLocationId"))
                : scope.permits(str(row, "locationId"));
    }

    private int claim(String table, String id, String status) {
        return jdbc.sql("UPDATE \"" + table + "\" SET status = ?::\"StockDocStatus\", \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"postedAt\" IS NULL AND status IN ('pending', 'draft')")
                .params(status, id, org()).update();
    }

    private int cancel(String table, String id) {
        return jdbc.sql("UPDATE \"" + table + "\" SET status = 'cancelled', \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"postedAt\" IS NULL AND status IN ('pending', 'draft')")
                .params(TenantContext.userId(), id, org()).update();
    }

    Map<String, Object> stockOut(String id) {
        return header("StockOut", StockOut.class, id);
    }

    Map<String, Object> waste(String id) {
        return header("WasteRecord", WasteRecord.class, id);
    }

    Map<String, Object> adjustment(String id) {
        return header("StockAdjustment", StockAdjustment.class, id);
    }

    Map<String, Object> transfer(String id) {
        return header("StockTransfer", StockTransfer.class, id);
    }

    private Map<String, Object> header(String table, Class<?> type, String id) {
        return SqlRows.one(type, jdbc.sql("SELECT * FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
    }

    List<Map<String, Object>> items(String table, Class<?> type, String fk, String docId) {
        return SqlRows.list(type, jdbc.sql("SELECT * FROM \"" + table + "\" WHERE \"" + fk + "\" = ?").param(docId));
    }

    /** {@code include: {items: true}} by header type. */
    Map<String, Object> withItems(Map<String, Object> doc) {
        if (doc == null) {
            return null;
        }
        String id = str(doc, "id");
        if (doc.containsKey("outCode")) {
            doc.put("items", items("StockOutItem", StockOutItem.class, "stockOutId", id));
        } else if (doc.containsKey("wasteCode")) {
            doc.put("items", items("WasteItem", WasteItem.class, "wasteId", id));
        } else if (doc.containsKey("adjCode")) {
            doc.put("items", items("StockAdjustmentItem", StockAdjustmentItem.class, "adjId", id));
        } else {
            doc.put("items", items("StockTransferItem", StockTransferItem.class, "transferId", id));
        }
        return doc;
    }

    /** {@code include: {location: true}} (full) or {@code location: {select: {id, code, name}}}. */
    private Map<String, Object> withLocation(Map<String, Object> doc, boolean full) {
        if (doc == null) {
            return null;
        }
        Map<String, Object> loc = SqlRows.one(InventoryLocation.class,
                jdbc.sql("SELECT * FROM \"InventoryLocation\" WHERE id = ?").param(doc.get("locationId")));
        doc.put("location", full ? loc : InventoryLookups.locSelect(loc, "id", "code", "name"));
        return doc;
    }

    /** {@code include: {receipts: true}} (ordered by createdAt when {@code ordered}). */
    private Map<String, Object> withReceipts(Map<String, Object> doc, boolean ordered) {
        doc.put("receipts", SqlRows.list(StockTransferReceipt.class, jdbc.sql("SELECT * FROM \"StockTransferReceipt\""
                + " WHERE \"transferId\" = ?" + (ordered ? " ORDER BY \"createdAt\" ASC" : "")).param(doc.get("id"))));
        return doc;
    }
}
