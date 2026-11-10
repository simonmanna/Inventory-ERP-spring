package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryLookups.dec;
import static com.inventoryerp.inventory.application.InventoryLookups.str;
import static com.inventoryerp.inventory.application.StockService.NOW;

import com.inventoryerp.inventory.application.StockDocService.CountVariance;
import com.inventoryerp.inventory.domain.InventoryCountLine;
import com.inventoryerp.inventory.domain.InventoryCountSession;
import com.inventoryerp.inventory.domain.InventoryLocation;
import com.inventoryerp.kernel.approvals.ApprovalsService;
import com.inventoryerp.kernel.approvals.SegregationOfDutiesService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Guided physical stock counts (port of {@code inventory-count.service.ts}): opening / closing / cycle / spot
 * sessions, blind counting, and variances posted through a stock adjustment in one transaction with the session claim.
 *
 * <p>Owner-approved hardening (docs/spec/inventory.md Deviations 10-16): a line posts {@code counted + movements on its
 * cell after it was counted − on-hand at submit} (force = overwrite with the count as of now); the
 * {@code inventory_count_submit} approval sees the variance value and quantity and the {@code stock_adjustment}
 * separation-of-duties pair keeps counters from submitting; batch-tracked products are counted per lot (screen
 * starts) and serial-tracked losses name the missing units.
 */
@Service
public class InventoryCountService {

    private static final Map<String, String> LABELS = Map.of("opening", "Opening", "closing", "Closing", "cycle",
            "Cycle", "spot", "Spot");
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
            "Nov", "Dec"};

    /**
     * One draft line save. {@code missingSerials} / {@code foundSerials}: null keeps what the line has, an empty list
     * clears it.
     */
    public record DraftLine(String lineId, BigDecimal countedQty, String reason, List<String> missingSerials,
            List<String> foundSerials) {

        public DraftLine(String lineId, BigDecimal countedQty, String reason) {
            this(lineId, countedQty, reason, null, null);
        }
    }

    /** Approval entity type of a count submission (also listed by the web approval-rule editor). */
    static final String APPROVAL_TYPE = "inventory_count_submit";

    private final JdbcClient jdbc;
    private final InventoryLookups lookups;
    private final SequenceService seq;
    private final StockDocService stockDoc;
    private final ApprovalsService approvals;
    private final AuditService audit;
    private final LocationScope locationScope;
    private final SegregationOfDutiesService sod;
    private final TransactionTemplate newTx;

    public InventoryCountService(JdbcClient jdbc, InventoryLookups lookups, SequenceService seq,
            StockDocService stockDoc, ApprovalsService approvals, AuditService audit, LocationScope locationScope,
            SegregationOfDutiesService sod, PlatformTransactionManager txm) {
        this.locationScope = locationScope;
        this.sod = sod;
        this.jdbc = jdbc;
        this.lookups = lookups;
        this.seq = seq;
        this.stockDoc = stockDoc;
        this.approvals = approvals;
        this.audit = audit;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private Map<String, Object> session(String id) {
        return SqlRows.one(InventoryCountSession.class, jdbc.sql("SELECT * FROM \"InventoryCountSession\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, org()));
    }

    private List<Map<String, Object>> lines(String sessionId) {
        return SqlRows.list(InventoryCountLine.class, jdbc.sql("SELECT * FROM \"InventoryCountLine\" WHERE"
                + " \"sessionId\" = ? ORDER BY \"parentProductId\" ASC, \"productName\" ASC,"
                + " \"batchNumber\" ASC NULLS LAST, id ASC").param(sessionId));
    }

    private Map<String, Object> full(Map<String, Object> session) {
        session.put("lines", lines(str(session, "id")));
        session.put("location", SqlRows.one(InventoryLocation.class,
                jdbc.sql("SELECT * FROM \"InventoryLocation\" WHERE id = ?").param(session.get("locationId"))));
        return session;
    }

    /** Session with lines + location; blind drafts are masked unless {@code reveal}. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id, boolean reveal) {
        Map<String, Object> s = session(id);
        if (s == null || !locationScope.permits((String) s.get("locationId"))) {
            throw ApiException.notFound("Count session not found");
        }
        full(s);
        if (Boolean.TRUE.equals(s.get("blind")) && "draft".equals(str(s, "status")) && !reveal) {
            s.put("systemHidden", true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ls = (List<Map<String, Object>>) s.get("lines");
            for (Map<String, Object> l : ls) {
                l.put("systemQty", null);
                l.put("variance", null);
            }
            return s;
        }
        s.put("systemHidden", false);
        if ("draft".equals(str(s, "status"))) {
            annotatePostings(s);
        }
        return s;
    }

    /**
     * Review fields of a draft (counted lines only; others get nulls): {@code onHandNow} (the cell or lot now),
     * {@code movementsSinceCount} (net ledger change after the line was counted) and {@code postingDelta}, what submit
     * would post. {@code variance} stays the snapshot variance (counted − systemQty at start).
     */
    @SuppressWarnings("unchecked")
    private void annotatePostings(Map<String, Object> s) {
        List<Map<String, Object>> ls = (List<Map<String, Object>>) s.get("lines");
        List<Map<String, Object>> counted = ls.stream().filter(l -> l.get("countedQty") != null).toList();
        Map<String, Posting> byLine = new HashMap<>();
        if (!counted.isEmpty()) {
            for (Posting p : postings(s, counted, false)) {
                byLine.put(str(p.line(), "id"), p);
            }
        }
        for (Map<String, Object> l : ls) {
            Posting p = byLine.get(str(l, "id"));
            l.put("onHandNow", p == null ? null : p.nowQty());
            l.put("movementsSinceCount", p == null ? null : p.netAfter());
            l.put("postingDelta", p == null ? null : p.delta());
        }
    }

    private record Scope(List<String> categoryIds, List<String> productIds) {}

    private Scope resolveScope(String countType, List<String> categoryIds, List<String> productIds) {
        Scope scope = new Scope(List.copyOf(new LinkedHashSet<>(categoryIds == null ? List.of() : categoryIds)),
                List.copyOf(new LinkedHashSet<>(productIds == null ? List.of() : productIds)));
        if ("spot".equals(countType) && scope.productIds().isEmpty()) {
            throw ApiException.badRequest("A spot count needs at least one product.");
        }
        if ("cycle".equals(countType) && scope.categoryIds().isEmpty() && scope.productIds().isEmpty()) {
            throw ApiException.badRequest("A cycle count needs a scope: pick categories or products.");
        }
        if (!scope.categoryIds().isEmpty()) {
            long found = jdbc.sql("SELECT count(*) FROM \"ProductCategory\" WHERE id IN (:ids) AND \"organizationId\" = :org"
                            + " AND \"deletedAt\" IS NULL").param("ids", scope.categoryIds()).param("org", org())
                    .query(Long.class).single();
            if (found != scope.categoryIds().size()) {
                throw ApiException.badRequest("One or more scope categories were not found");
            }
        }
        if (!scope.productIds().isEmpty()) {
            long found = jdbc.sql("SELECT count(*) FROM \"Product\" WHERE id IN (:ids) AND \"organizationId\" = :org"
                            + " AND \"deletedAt\" IS NULL").param("ids", scope.productIds()).param("org", org())
                    .query(Long.class).single();
            if (found != scope.productIds().size()) {
                throw ApiException.badRequest("One or more scope products were not found");
            }
        }
        return scope;
    }

    /** Session headers (most recent first, max 100) with location and line count. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : SqlRows.list(InventoryCountSession.class, jdbc.sql("SELECT * FROM"
                + " \"InventoryCountSession\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " ORDER BY \"startedAt\" DESC LIMIT 100").param(org()))) {
            if (!locationScope.permits((String) s.get("locationId"))) {
                continue;
            }
            s.put("location", SqlRows.one(InventoryLocation.class,
                    jdbc.sql("SELECT * FROM \"InventoryLocation\" WHERE id = ?").param(s.get("locationId"))));
            long n = jdbc.sql("SELECT count(*) FROM \"InventoryCountLine\" WHERE \"sessionId\" = ?").param(s.get("id"))
                    .query(Long.class).single();
            s.put("_count", Obj.of("lines", n));
            out.add(s);
        }
        return out;
    }

    /** {@link #start} from a user's screen (BR-1); offline sync calls {@link #start} and is not scoped. */
    @Transactional
    public Map<String, Object> startChecked(String locationId, String countTypeIn, String notes, Boolean blind,
            List<String> scopeCategoryIds, List<String> scopeProductIds, Boolean restart) {
        locationScope.assertAllowed(locationId);
        return start(locationId, countTypeIn, notes, blind, scopeCategoryIds, scopeProductIds, restart, true);
    }

    /**
     * Start (or resume) a count session with one line per product / variant (offline device counts: the device
     * counts a product's total, so batch-tracked products are not split per lot).
     */
    @Transactional
    public Map<String, Object> start(String locationId, String countTypeIn, String notes, Boolean blind,
            List<String> scopeCategoryIds, List<String> scopeProductIds, Boolean restart) {
        return start(locationId, countTypeIn, notes, blind, scopeCategoryIds, scopeProductIds, restart, false);
    }

    /** Start (or resume) a count session; {@code perLot} splits batch-tracked products into one line per open lot. */
    private Map<String, Object> start(String locationId, String countTypeIn, String notes, Boolean blind,
            List<String> scopeCategoryIds, List<String> scopeProductIds, Boolean restart, boolean perLot) {
        lookups.requireLocation(locationId);
        String countType = countTypeIn != null ? countTypeIn : "opening";
        Scope scope = resolveScope(countType, scopeCategoryIds, scopeProductIds);
        Map<String, Object> open = openDraft(locationId, countType);
        if (open != null) {
            long counted = jdbc.sql("SELECT count(*) FROM \"InventoryCountLine\" WHERE \"sessionId\" = ?"
                    + " AND \"countedQty\" IS NOT NULL").param(open.get("id")).query(Long.class).single();
            if (counted > 0 && !Boolean.TRUE.equals(restart)) {
                return get(str(open, "id"), false);
            }
            jdbc.sql("UPDATE \"InventoryCountSession\" SET status = 'cancelled', \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                            + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL AND status = 'draft'")
                    .params(TenantContext.userId(), open.get("id"), org()).update();
        }
        List<Map<String, Object>> lines = buildLines(locationId, scope, perLot);
        String countCode = seq.next("inv_count", "CNT-", 5);
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("SAVEPOINT count_start").update();
            jdbc.sql("INSERT INTO \"InventoryCountSession\" (id, \"organizationId\", \"countCode\", name, \"locationId\","
                            + " \"countType\", status, blind, \"scopeCategoryIds\", \"scopeProductIds\", notes, \"startedById\","
                            + " \"createdBy\", \"startedAt\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?,"
                            + " ?::\"InventoryCountType\", 'draft', ?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ", " + NOW + ")")
                    .params(id, org(), countCode, autoName(countType), locationId, countType, Boolean.TRUE.equals(blind),
                            scope.categoryIds().toArray(String[]::new), scope.productIds().toArray(String[]::new), notes,
                            TenantContext.userId(), TenantContext.userId())
                    .update();
            jdbc.sql("RELEASE SAVEPOINT count_start").update();
        } catch (DataIntegrityViolationException err) {
            jdbc.sql("ROLLBACK TO SAVEPOINT count_start").update();
            Map<String, Object> existing = openDraft(locationId, countType);
            if (existing != null) {
                return get(str(existing, "id"), false);
            }
            throw err;
        }
        for (Map<String, Object> l : lines) {
            jdbc.sql("INSERT INTO \"InventoryCountLine\" (id, \"organizationId\", \"sessionId\", \"productId\", \"variantId\","
                            + " \"productName\", \"parentProductId\", \"parentProductName\", unit, \"systemQty\", \"countedQty\","
                            + " variance, \"batchId\", \"batchNumber\", \"lotLine\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0,"
                            + " ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, l.get("productId"), l.get("variantId"),
                            l.get("productName"), l.get("parentProductId"), l.get("parentProductName"), l.get("unit"),
                            l.get("systemQty"), l.get("batchId"), l.get("batchNumber"),
                            Boolean.TRUE.equals(l.get("lotLine")))
                    .update();
        }
        return get(id, false);
    }

    private Map<String, Object> openDraft(String locationId, String countType) {
        return SqlRows.one(InventoryCountSession.class, jdbc.sql("SELECT * FROM \"InventoryCountSession\" WHERE"
                        + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"locationId\" = ?"
                        + " AND \"countType\"::text = ? AND status = 'draft' LIMIT 1")
                .params(org(), locationId, countType));
    }

    /** The open draft for a location + type, or a non-persisted preview sheet. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String locationId, String countTypeIn, List<String> categoryIds,
            List<String> productIds) {
        Map<String, Object> location = SqlRows.one(InventoryLocation.class, jdbc.sql("SELECT * FROM \"InventoryLocation\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(locationId, org()));
        if (location == null) {
            throw ApiException.notFound("Location not found");
        }
        locationScope.assertAllowed(locationId);
        String countType = countTypeIn != null ? countTypeIn : "opening";
        Map<String, Object> draft = openDraft(locationId, countType);
        if (draft != null) {
            return get(str(draft, "id"), false);
        }
        boolean hasScope = (categoryIds == null ? 0 : categoryIds.size()) + (productIds == null ? 0 : productIds.size()) > 0;
        Scope scope = ("cycle".equals(countType) || "spot".equals(countType)) && !hasScope ? null
                : resolveScope(countType, categoryIds, productIds);
        List<Map<String, Object>> built = scope != null ? buildLines(locationId, scope, true) : List.of();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> ln : built) {
            Map<String, Object> m = new LinkedHashMap<>(ln);
            m.put("id", "preview:" + ln.get("productId") + ":" + (ln.get("variantId") == null ? "" : ln.get("variantId"))
                    + (Boolean.TRUE.equals(ln.get("lotLine")) ? ":" + (ln.get("batchId") == null ? "" : ln.get("batchId"))
                            : ""));
            m.put("sessionId", "");
            m.put("countedById", null);
            m.put("countedAt", null);
            m.put("reason", null);
            lines.add(m);
        }
        return Obj.of("id", "", "countCode", "PREVIEW", "name", autoName(countType), "locationId", locationId,
                "countType", countType, "status", "preview", "blind", false, "systemHidden", false,
                "scopeCategoryIds", scope == null ? List.of() : scope.categoryIds(),
                "scopeProductIds", scope == null ? List.of() : scope.productIds(),
                "notes", null, "startedAt", Instant.now(), "submittedAt", null, "adjustmentId", null,
                "location", location, "lines", lines, "_count", Obj.of("lines", lines.size()));
    }

    private static String autoName(String countType) {
        LocalDate now = LocalDate.now(BusinessZone.get());
        return LABELS.get(countType) + " Count – " + MONTHS[now.getMonthValue() - 1] + " "
                + String.format("%02d", now.getDayOfMonth()) + ", " + now.getYear();
    }

    /**
     * One row per countable product (variant parents expand to their active variants) with system on-hand. With
     * {@code perLot}, a batch-tracked product / variant becomes one line per open lot (system = the lot's quantity)
     * plus a "no lot" line (system = on-hand − open lots; counts units without a known lot, posted to a new lot).
     */
    private List<Map<String, Object>> buildLines(String locationId, Scope scope, boolean perLot) {
        boolean scoped = !scope.categoryIds().isEmpty() || !scope.productIds().isEmpty();
        StringBuilder sql = new StringBuilder("SELECT p.id, p.name, p.\"hasVariants\", p.\"batchTracking\","
                + " u.code AS uom FROM \"Product\" p"
                + " LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE p.\"organizationId\" = :org"
                + " AND p.\"deletedAt\" IS NULL AND p.\"isActive\" = true AND p.\"trackInventory\" = true"
                + " AND p.\"productType\"::text IN ('stockable', 'consumable')");
        if (scoped) {
            List<String> or = new ArrayList<>();
            if (!scope.categoryIds().isEmpty()) {
                or.add("p.\"categoryId\" IN (:cats)");
            }
            if (!scope.productIds().isEmpty()) {
                or.add("p.id IN (:prods)");
            }
            sql.append(" AND (").append(String.join(" OR ", or)).append(")");
        }
        sql.append(" ORDER BY p.name ASC");
        var spec = jdbc.sql(sql.toString()).param("org", org());
        if (!scope.categoryIds().isEmpty()) {
            spec = spec.param("cats", scope.categoryIds());
        }
        if (!scope.productIds().isEmpty()) {
            spec = spec.param("prods", scope.productIds());
        }
        List<Map<String, Object>> products = SqlRows.list(spec);
        if (products.isEmpty()) {
            throw ApiException.badRequest(scoped
                    ? "No countable products in the selected scope. Products must be active, inventory-tracked, and"
                            + " stockable or consumable."
                    : "No countable products found. A product must be active, inventory-tracked, and of type stockable"
                            + " or consumable to appear on a count sheet.");
        }
        Map<String, Map<String, Object>> parentById = new HashMap<>();
        products.forEach(p -> parentById.put(str(p, "id"), p));
        List<String> parents = products.stream().filter(p -> Boolean.TRUE.equals(p.get("hasVariants")))
                .map(p -> str(p, "id")).toList();
        List<Map<String, Object>> variants = parents.isEmpty() ? List.of()
                : SqlRows.list(jdbc.sql("SELECT id, \"productId\", name FROM \"ProductVariant\" WHERE \"productId\" IN (:ids)"
                                + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL AND \"isActive\" = true"
                                + " ORDER BY name ASC")
                        .param("ids", parents).param("org", org()));
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> p : products) {
            if (Boolean.TRUE.equals(p.get("hasVariants"))) {
                continue;
            }
            lines.add(line(str(p, "id"), null, str(p, "name"), null, null, str(p, "uom")));
        }
        for (Map<String, Object> v : variants) {
            Map<String, Object> parent = parentById.get(str(v, "productId"));
            lines.add(line(str(v, "productId"), str(v, "id"), str(v, "name"), str(v, "productId"),
                    parent == null ? null : str(parent, "name"), parent == null ? null : str(parent, "uom")));
        }
        List<String> keys = new ArrayList<>(List.of(""));
        variants.forEach(v -> keys.add(str(v, "id")));
        Map<String, BigDecimal> onHand = new HashMap<>();
        for (Map<String, Object> si : SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantId\", quantity FROM \"StockItem\""
                        + " WHERE \"organizationId\" = :org AND \"locationId\" = :loc AND \"productId\" IN (:ids)"
                        + " AND \"variantKey\" IN (:keys)")
                .param("org", org()).param("loc", locationId)
                .param("ids", products.stream().map(p -> str(p, "id")).toList()).param("keys", keys))) {
            onHand.put(si.get("productId") + "::" + (si.get("variantId") == null ? "" : si.get("variantId")),
                    dec(si, "quantity"));
        }
        for (Map<String, Object> ln : lines) {
            ln.put("systemQty", onHand.getOrDefault(ln.get("productId") + "::"
                    + (ln.get("variantId") == null ? "" : ln.get("variantId")), Money.ZERO));
        }
        List<String> lotted = products.stream().filter(p -> Boolean.TRUE.equals(p.get("batchTracking")))
                .map(p -> str(p, "id")).toList();
        if (!perLot || lotted.isEmpty()) {
            return lines;
        }
        Map<String, List<Map<String, Object>>> lotsByCell = new HashMap<>();
        for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT id, \"productId\", \"variantId\", \"batchNumber\","
                        + " quantity FROM \"InventoryBatch\" WHERE \"organizationId\" = :org AND \"locationId\" = :loc"
                        + " AND \"productId\" IN (:ids) AND quantity > 0 AND \"isActive\" = true"
                        + " ORDER BY \"expiryDate\" ASC NULLS LAST, \"receivedAt\" ASC, id ASC")
                .param("org", org()).param("loc", locationId).param("ids", lotted))) {
            lotsByCell.computeIfAbsent(b.get("productId") + "::" + (b.get("variantId") == null ? "" : b.get("variantId")),
                    k -> new ArrayList<>()).add(b);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> ln : lines) {
            if (!lotted.contains(str(ln, "productId"))) {
                out.add(ln);
                continue;
            }
            BigDecimal inLots = Money.ZERO;
            for (Map<String, Object> b : lotsByCell.getOrDefault(ln.get("productId") + "::"
                    + (ln.get("variantId") == null ? "" : ln.get("variantId")), List.of())) {
                Map<String, Object> lot = new LinkedHashMap<>(ln);
                lot.put("batchId", b.get("id"));
                lot.put("batchNumber", b.get("batchNumber"));
                lot.put("lotLine", true);
                lot.put("systemQty", dec(b, "quantity"));
                inLots = inLots.add(dec(b, "quantity"));
                out.add(lot);
            }
            Map<String, Object> noLot = new LinkedHashMap<>(ln);
            noLot.put("lotLine", true);
            noLot.put("systemQty", dec(ln, "systemQty").subtract(inLots));
            out.add(noLot);
        }
        return out;
    }

    private Map<String, Object> line(String productId, String variantId, String name, String parentId,
            String parentName, String unit) {
        return Obj.of("organizationId", org(), "productId", productId, "variantId", variantId, "productName", name,
                "parentProductId", parentId, "parentProductName", parentName, "unit", unit, "systemQty", Money.ZERO,
                "countedQty", null, "variance", Money.ZERO, "batchId", null, "batchNumber", null, "lotLine", false,
                "missingSerials", null, "foundSerials", null);
    }

    private Map<String, Object> assertDraft(String id) {
        Map<String, Object> s = session(id);
        if (s == null) {
            throw ApiException.notFound("Count session not found");
        }
        if (!"draft".equals(str(s, "status"))) {
            throw ApiException.badRequest("Cannot modify a " + s.get("status") + " count");
        }
        return s;
    }

    /** {@link #saveDraft} from a user's screen: the session's location must be in the caller's data scope. */
    @Transactional
    public Map<String, Object> saveDraftChecked(String id, List<DraftLine> lines, String name, String notes) {
        assertInScope(id);
        return saveDraft(id, lines, name, notes);
    }

    /** 404 for a missing session, 403 when its location is outside the caller's data scope (BR-1). */
    private void assertInScope(String id) {
        Map<String, Object> s = session(id);
        if (s == null) {
            throw ApiException.notFound("Count session not found");
        }
        locationScope.assertAllowed(str(s, "locationId"));
    }

    /**
     * Persist in-progress counts (per line) and the optional name / notes. {@code countedAt} / {@code countedById}
     * move only when a line's counted quantity changes: they mark when the shelf was counted, which decides which
     * later movements submit keeps.
     */
    @Transactional
    public Map<String, Object> saveDraft(String id, List<DraftLine> lines, String name, String notes) {
        assertDraft(id);
        Map<String, Map<String, Object>> existing = new HashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, \"systemQty\", \"countedQty\" FROM"
                + " \"InventoryCountLine\" WHERE \"sessionId\" = ? AND \"organizationId\" = ?").params(id, org()))) {
            existing.put(str(l, "id"), l);
        }
        String user = TenantContext.userId();
        for (DraftLine l : lines) {
            Map<String, Object> ex = existing.get(l.lineId());
            if (ex == null) {
                continue;
            }
            BigDecimal counted = l.countedQty();
            BigDecimal prev = ex.get("countedQty") == null ? null : dec(ex, "countedQty");
            boolean changed = counted == null ? prev != null : prev == null || prev.compareTo(counted) != 0;
            BigDecimal variance = counted == null ? Money.ZERO : counted.subtract(dec(ex, "systemQty"));
            StringBuilder set = new StringBuilder("\"countedQty\" = ?, variance = ?, reason = ?");
            List<Object> params = new ArrayList<>();
            params.add(counted);
            params.add(variance);
            params.add(l.reason());
            if (changed) {
                set.append(", \"countedById\" = ?, \"countedAt\" = ").append(counted == null ? "NULL" : NOW);
                params.add(counted == null ? null : user);
            }
            if (l.missingSerials() != null) {
                set.append(", \"missingSerials\" = ?");
                params.add(cleanSerials(l.missingSerials()));
            }
            if (l.foundSerials() != null) {
                set.append(", \"foundSerials\" = ?");
                params.add(cleanSerials(l.foundSerials()));
            }
            params.add(l.lineId());
            params.add(id);
            params.add(org());
            jdbc.sql("UPDATE \"InventoryCountLine\" SET " + set + " WHERE id = ? AND \"sessionId\" = ?"
                    + " AND \"organizationId\" = ?").params(params).update();
        }
        boolean nameSent = EntityPatch.present("name", name);
        boolean notesSent = EntityPatch.present("notes", notes);
        if (nameSent || notesSent) {
            List<Object> params = new ArrayList<>();
            StringBuilder set = new StringBuilder("\"updatedBy\" = ?");
            params.add(user);
            if (nameSent) {
                set.append(", name = ?");
                params.add(name);
            }
            if (notesSent) {
                set.append(", notes = ?");
                params.add(notes);
            }
            params.add(id);
            params.add(org());
            jdbc.sql("UPDATE \"InventoryCountSession\" SET " + set + ", \"updatedAt\" = " + NOW + " WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(params).update();
        }
        return get(id, false);
    }

    private static String[] cleanSerials(List<String> serials) {
        return serials.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty()).distinct()
                .toArray(String[]::new);
    }

    @SuppressWarnings("unchecked")
    private static List<String> serialList(Object v) {
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }

    /**
     * What one counted line posts. {@code nowQty}: the line's cell (or lot / unlotted remainder) now;
     * {@code netAfter}: net ledger change on it after {@code countedAt}; {@code delta}: counted + netAfter − nowQty, or
     * counted − nowQty when forced (the count overwrites the later movements).
     */
    private record Posting(Map<String, Object> line, BigDecimal counted, BigDecimal nowQty, BigDecimal netAfter,
            long movements, Instant countedAt, BigDecimal delta, BigDecimal unitCost) {}

    private static String cell(Object productId, Object variantId) {
        return productId + "::" + (variantId == null ? "" : variantId);
    }

    private List<Posting> postings(Map<String, Object> session, List<Map<String, Object>> counted, boolean overwrite) {
        Instant startedAt = (Instant) session.get("startedAt");
        Object locationId = session.get("locationId");
        Instant earliest = null;
        LinkedHashSet<String> productIds = new LinkedHashSet<>();
        for (Map<String, Object> l : counted) {
            Instant at = l.get("countedAt") != null ? (Instant) l.get("countedAt") : startedAt;
            earliest = earliest == null || at.isBefore(earliest) ? at : earliest;
            productIds.add(str(l, "productId"));
        }
        List<String> ids = List.copyOf(productIds);
        List<Map<String, Object>> moves = SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantId\", \"batchId\","
                        + " \"quantityChange\", \"createdAt\" FROM \"InventoryLedger\" WHERE \"organizationId\" = :org"
                        + " AND \"locationId\" = :loc AND \"createdAt\" > :since AND \"productId\" IN (:ids)")
                .param("org", org()).param("loc", locationId).param("since", SqlRows.ts(earliest)).param("ids", ids));
        Map<String, Map<String, Object>> items = new HashMap<>();
        for (Map<String, Object> si : SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantId\", quantity,"
                        + " \"runningAverageCost\" FROM \"StockItem\" WHERE \"organizationId\" = :org AND \"locationId\" = :loc"
                        + " AND \"productId\" IN (:ids)")
                .param("org", org()).param("loc", locationId).param("ids", ids))) {
            items.put(cell(si.get("productId"), si.get("variantId")), si);
        }
        Map<String, Map<String, Object>> lots = new HashMap<>();
        Map<String, BigDecimal> openLotQty = new HashMap<>();
        for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT id, \"productId\", \"variantId\", quantity,"
                        + " \"unitCost\", \"isActive\" FROM \"InventoryBatch\" WHERE \"organizationId\" = :org"
                        + " AND \"locationId\" = :loc AND \"productId\" IN (:ids)")
                .param("org", org()).param("loc", locationId).param("ids", ids))) {
            lots.put(str(b, "id"), b);
            if (dec(b, "quantity").signum() > 0 && Boolean.TRUE.equals(b.get("isActive"))) {
                openLotQty.merge(cell(b.get("productId"), b.get("variantId")), dec(b, "quantity"), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> costPrice = new HashMap<>();
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, \"costPrice\" FROM \"Product\" WHERE id IN (:ids)"
                + " AND \"organizationId\" = :org").param("ids", ids).param("org", org()))) {
            costPrice.put(str(p, "id"), p.get("costPrice") == null ? Money.ZERO : dec(p, "costPrice"));
        }
        List<Posting> out = new ArrayList<>();
        for (Map<String, Object> l : counted) {
            Instant at = l.get("countedAt") != null ? (Instant) l.get("countedAt") : startedAt;
            String key = cell(l.get("productId"), l.get("variantId"));
            String batchId = str(l, "batchId");
            boolean lotLine = Boolean.TRUE.equals(l.get("lotLine"));
            Map<String, Object> si = items.get(key);
            BigDecimal quantQty = si == null ? Money.ZERO : dec(si, "quantity");
            BigDecimal nowQty;
            BigDecimal unitCost = si != null && dec(si, "runningAverageCost").signum() > 0
                    ? dec(si, "runningAverageCost") : costPrice.getOrDefault(str(l, "productId"), Money.ZERO);
            if (batchId != null) {
                Map<String, Object> lot = lots.get(batchId);
                nowQty = lot == null || !Boolean.TRUE.equals(lot.get("isActive")) ? Money.ZERO : dec(lot, "quantity");
                if (lot != null && lot.get("unitCost") != null && dec(lot, "unitCost").signum() > 0) {
                    unitCost = dec(lot, "unitCost");
                }
            } else if (lotLine) {
                nowQty = quantQty.subtract(openLotQty.getOrDefault(key, Money.ZERO));
            } else {
                nowQty = quantQty;
            }
            BigDecimal net = Money.ZERO;
            long n = 0;
            for (Map<String, Object> m : moves) {
                if (!key.equals(cell(m.get("productId"), m.get("variantId")))
                        || !((Instant) m.get("createdAt")).isAfter(at)) {
                    continue;
                }
                if (batchId != null ? !batchId.equals(m.get("batchId")) : lotLine && m.get("batchId") != null) {
                    continue;
                }
                net = net.add(dec(m, "quantityChange"));
                n++;
            }
            BigDecimal countedQty = dec(l, "countedQty");
            BigDecimal delta = overwrite ? countedQty.subtract(nowQty) : countedQty.add(net).subtract(nowQty);
            out.add(new Posting(l, countedQty, nowQty, net, n, at, delta, unitCost));
        }
        return out;
    }

    /** {@link #submit} from a user's screen: the session's location must be in the caller's data scope. */
    @Transactional
    public Map<String, Object> submitChecked(String id, Boolean force, String forceReason) {
        assertInScope(id);
        return submit(id, force, forceReason);
    }

    /**
     * Finalise: validation (something counted, reasons on posting lines, serial units), then the
     * {@code inventory_count_submit} approval gate on the variance value, separation of duties, and the adjustment +
     * session claim in one transaction. Each line posts counted + movements after it was counted − on-hand now;
     * {@code force} (with a reason when lines moved) posts the count as the on-hand of now instead.
     */
    @Transactional
    public Map<String, Object> submit(String id, Boolean force, String forceReason) {
        Map<String, Object> head = assertDraft(id);
        List<Map<String, Object>> lines = SqlRows.list(InventoryCountLine.class, jdbc.sql("SELECT * FROM"
                + " \"InventoryCountLine\" WHERE \"sessionId\" = ? AND \"organizationId\" = ?").params(id, org()));
        List<Map<String, Object>> counted = lines.stream().filter(l -> l.get("countedQty") != null).toList();
        if (counted.isEmpty()) {
            throw ApiException.badRequest("Nothing counted yet — enter at least one physical count.");
        }
        boolean overwrite = Boolean.TRUE.equals(force);
        List<Posting> all = postings(head, counted, overwrite);
        List<Posting> stale = all.stream().filter(p -> p.movements() > 0).toList();
        if (!stale.isEmpty() && overwrite && (forceReason == null || forceReason.trim().isEmpty())) {
            throw ApiException.badRequest(
                    "A reason is required to force-submit a count over lines that moved after being counted.");
        }
        List<Posting> variance = all.stream().filter(p -> p.delta().signum() != 0).toList();
        long missingReason = variance.stream().filter(p -> p.line().get("reason") == null
                || str(p.line(), "reason").trim().isEmpty()).count();
        if (missingReason > 0) {
            throw ApiException.badRequest("A reason is required for " + missingReason + " line(s) with a variance.");
        }
        assertSerials(head, variance);

        BigDecimal value = Money.ZERO;
        BigDecimal magnitude = Money.ZERO;
        for (Posting p : variance) {
            magnitude = magnitude.add(p.delta().abs());
            value = value.add(p.delta().abs().multiply(p.unitCost()));
        }
        value = value.setScale(2, RoundingMode.HALF_UP);
        LinkedHashSet<String> makers = new LinkedHashSet<>();
        if (head.get("startedById") != null) {
            makers.add(str(head, "startedById"));
        }
        counted.forEach(l -> {
            if (l.get("countedById") != null) {
                makers.add(str(l, "countedById"));
            }
        });
        Map<String, Object> snapshot = Obj.of("countId", id, "countCode", head.get("countCode"), "countType",
                head.get("countType"), "locationId", head.get("locationId"), "amount", JsNumbers.js(value),
                "varianceValue", JsNumbers.js(value), "varianceQty", JsNumbers.js(magnitude), "lines", variance.size(),
                "makerIds", String.join(",", makers));
        Map<String, Object> approval = newTx.execute(s -> approvals.checkOrRequestApproval(APPROVAL_TYPE, id, snapshot));
        if (approval != null && Boolean.TRUE.equals(approval.get("needsApproval"))) {
            throw ApiException.forbidden("Inventory count submission requires approval. Request ID: "
                    + approval.get("requestId") + ".");
        }
        if (!variance.isEmpty()) {
            // A count submit posts a stock adjustment: the adjustment maker-checker pair applies (counters ≠ submitter).
            sod.assertSeparated(SegregationOfDutiesService.STOCK_ADJUSTMENT, "InventoryCountSession", id,
                    List.copyOf(makers));
        }

        Map<String, Object> snapshotView = get(id, false);
        String user = TenantContext.userId();
        int claimed = jdbc.sql("UPDATE \"InventoryCountSession\" SET status = 'submitted', \"submittedById\" = ?,"
                        + " \"submittedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL AND status = 'draft'")
                .params(user, id, org()).update();
        if (claimed == 0) {
            throw ApiException.conflict("Count was already submitted or cancelled by another request");
        }
        boolean forcedOver = overwrite && !stale.isEmpty();
        List<Map<String, Object>> staleView = stale.stream().map(p -> Obj.of("lineId", p.line().get("id"),
                "productId", p.line().get("productId"), "productName", p.line().get("productName"), "batchNumber",
                p.line().get("batchNumber"), "countedAt", p.countedAt(), "movements", p.movements(),
                "netQuantityChange", JsNumbers.dec(p.netAfter()))).toList();
        if (variance.isEmpty()) {
            if (forcedOver) {
                setNotes(id, appendForceNote(str(snapshotView, "notes"), stale.size(), forceReason));
            }
            auditSubmit(id, snapshotView, staleView, forcedOver, forceReason, null, value);
            return get(id, false);
        }
        Map<String, Object> session = session(id);
        List<CountVariance> items = new ArrayList<>();
        for (Posting p : variance) {
            Map<String, Object> l = p.line();
            List<String> serials = p.delta().signum() < 0 ? serialList(l.get("missingSerials"))
                    : serialList(l.get("foundSerials"));
            items.add(new CountVariance(str(l, "productId"), str(l, "variantId"), str(l, "unit"), str(l, "batchNumber"),
                    p.nowQty(), p.nowQty().add(p.delta()), p.delta(), serials));
        }
        String responsible = session.get("startedById") != null && !str(session, "startedById").isEmpty()
                ? str(session, "startedById") : user != null ? user : "";
        Map<String, Object> adj = stockDoc.postCountVariances(str(session, "locationId"), responsible,
                session.get("countType") + " count " + session.get("countCode"), items);
        jdbc.sql("UPDATE \"InventoryCountSession\" SET \"adjustmentId\" = ?" + (forcedOver ? ", notes = ?" : "")
                        + ", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(forcedOver ? List.of(adj.get("id"), appendForceNote(str(session, "notes"), stale.size(),
                        forceReason), id) : List.of(adj.get("id"), id))
                .update();
        auditSubmit(id, session, staleView, forcedOver, forceReason, str(adj, "id"), value);
        Map<String, Object> out = session(id);
        return full(out);
    }

    /**
     * Serial-tracked lines that post must name their units: a loss lists exactly the missing serial numbers (in stock
     * here), a gain may list the found ones (then exactly as many as the gain). Checked before anything is claimed so
     * the engine never refuses deep inside the posting.
     */
    private void assertSerials(Map<String, Object> session, List<Posting> variance) {
        if (variance.isEmpty()) {
            return;
        }
        List<String> ids = variance.stream().map(p -> str(p.line(), "productId")).distinct().toList();
        Map<String, String> serialTracked = new HashMap<>();
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT id, name FROM \"Product\" WHERE id IN (:ids)"
                + " AND \"organizationId\" = :org AND \"serialTracking\" = true").param("ids", ids).param("org", org()))) {
            serialTracked.put(str(p, "id"), str(p, "name"));
        }
        for (Posting p : variance) {
            String name = serialTracked.get(str(p.line(), "productId"));
            if (name == null) {
                continue;
            }
            BigDecimal units = p.delta().abs();
            if (units.stripTrailingZeros().scale() > 0) {
                throw ApiException.badRequest(name + " is serial-tracked: the count must leave a whole number of units"
                        + " (variance " + JsNumbers.dec(p.delta()) + ")");
            }
            int n = units.intValueExact();
            if (p.delta().signum() < 0) {
                List<String> missing = serialList(p.line().get("missingSerials"));
                if (missing.size() != n) {
                    throw ApiException.badRequest(name + " is serial-tracked: a loss of " + n + " needs the " + n
                            + " missing serial number(s) on the count line, got " + missing.size());
                }
                List<String> inStock = jdbc.sql("SELECT \"serialNumber\" FROM \"InventorySerial\" WHERE"
                                + " \"organizationId\" = ? AND \"productId\" = ? AND \"variantId\" IS NOT DISTINCT FROM ?"
                                + " AND \"locationId\" = ? AND status = 'in_stock' AND \"serialNumber\" = ANY(?::text[])")
                        .params(org(), p.line().get("productId"), p.line().get("variantId"), session.get("locationId"),
                                missing.toArray(String[]::new))
                        .query(String.class).list();
                List<String> absent = missing.stream().filter(sn -> !inStock.contains(sn)).toList();
                if (!absent.isEmpty()) {
                    throw ApiException.badRequest("Serial number(s) of " + name + " not in stock at this location: "
                            + String.join(", ", absent));
                }
            } else {
                List<String> found = serialList(p.line().get("foundSerials"));
                if (!found.isEmpty() && found.size() != n) {
                    throw ApiException.badRequest(name + " is serial-tracked: " + n + " unit(s) found but "
                            + found.size() + " serial number(s) given");
                }
            }
        }
    }

    private void setNotes(String id, String notes) {
        jdbc.sql("UPDATE \"InventoryCountSession\" SET notes = ? WHERE id = ?").params(notes, id).update();
    }

    private static String appendForceNote(String existing, int staleCount, String forceReason) {
        String note = "[FORCED] " + staleCount + " line(s) moved after being counted and were overwritten by this count. "
                + "Reason: " + (forceReason == null ? "n/a" : forceReason.trim());
        return existing != null && !existing.trim().isEmpty() ? existing.trim() + "\n" + note : note;
    }

    private void auditSubmit(String id, Map<String, Object> session, List<Map<String, Object>> stale, boolean forced,
            String forceReason, String adjustmentId, BigDecimal varianceValue) {
        Map<String, Object> values = Obj.of("kind", "count_submitted", "countCode", session.get("countCode"),
                "locationId", session.get("locationId"), "adjustmentId", adjustmentId,
                "forced", forced, "forceReason", forceReason, "varianceValue", JsNumbers.js(varianceValue));
        if (!stale.isEmpty()) {
            // Forced: the count overwrote these movements. Otherwise they were carried on top of the count.
            values.put(forced ? "absorbedMovements" : "carriedMovements", stale);
        }
        audit.recordInTx(AuditInput.of("InventoryCountSession", id, "update", null, values));
    }

    /** Abandon a draft count without touching stock. */
    @Transactional
    public Map<String, Object> cancel(String id) {
        assertInScope(id);
        assertDraft(id);
        jdbc.sql("UPDATE \"InventoryCountSession\" SET status = 'cancelled', \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(TenantContext.userId(), id, org()).update();
        return Obj.of("ok", true);
    }
}
