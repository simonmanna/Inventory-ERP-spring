package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.InventoryLookups.dec;
import static com.inventoryerp.inventory.application.InventoryLookups.str;
import static com.inventoryerp.inventory.application.StockService.NOW;

import com.inventoryerp.inventory.application.StockDocService.DocInput;
import com.inventoryerp.inventory.application.StockDocService.DocLine;
import com.inventoryerp.inventory.domain.InventoryCountLine;
import com.inventoryerp.inventory.domain.InventoryCountSession;
import com.inventoryerp.inventory.domain.InventoryLocation;
import com.inventoryerp.kernel.approvals.ApprovalsService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * sessions, blind counting, staleness guard on submit, and variances posted through a stock adjustment in one
 * transaction with the session claim.
 */
@Service
public class InventoryCountService {

    private static final Map<String, String> LABELS = Map.of("opening", "Opening", "closing", "Closing", "cycle",
            "Cycle", "spot", "Spot");
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
            "Nov", "Dec"};

    /** One draft line save. */
    public record DraftLine(String lineId, BigDecimal countedQty, String reason) {}

    private final JdbcClient jdbc;
    private final InventoryLookups lookups;
    private final SequenceService seq;
    private final StockDocService stockDoc;
    private final ApprovalsService approvals;
    private final AuditService audit;
    private final LocationScope locationScope;
    private final TransactionTemplate newTx;

    public InventoryCountService(JdbcClient jdbc, InventoryLookups lookups, SequenceService seq,
            StockDocService stockDoc, ApprovalsService approvals, AuditService audit, LocationScope locationScope,
            PlatformTransactionManager txm) {
        this.locationScope = locationScope;
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
                + " \"sessionId\" = ? ORDER BY \"parentProductId\" ASC, \"productName\" ASC").param(sessionId));
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
        return s;
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
        return start(locationId, countTypeIn, notes, blind, scopeCategoryIds, scopeProductIds, restart);
    }

    /** Start (or resume) a count session. */
    @Transactional
    public Map<String, Object> start(String locationId, String countTypeIn, String notes, Boolean blind,
            List<String> scopeCategoryIds, List<String> scopeProductIds, Boolean restart) {
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
        List<Map<String, Object>> lines = buildLines(locationId, scope);
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
                            + " variance) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0)")
                    .params(UUID.randomUUID().toString(), org(), id, l.get("productId"), l.get("variantId"),
                            l.get("productName"), l.get("parentProductId"), l.get("parentProductName"), l.get("unit"),
                            l.get("systemQty"))
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
        String countType = countTypeIn != null ? countTypeIn : "opening";
        Map<String, Object> draft = openDraft(locationId, countType);
        if (draft != null) {
            return get(str(draft, "id"), false);
        }
        boolean hasScope = (categoryIds == null ? 0 : categoryIds.size()) + (productIds == null ? 0 : productIds.size()) > 0;
        Scope scope = ("cycle".equals(countType) || "spot".equals(countType)) && !hasScope ? null
                : resolveScope(countType, categoryIds, productIds);
        List<Map<String, Object>> built = scope != null ? buildLines(locationId, scope) : List.of();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Map<String, Object> ln : built) {
            Map<String, Object> m = new LinkedHashMap<>(ln);
            m.put("id", "preview:" + ln.get("productId") + ":" + (ln.get("variantId") == null ? "" : ln.get("variantId")));
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

    /** One row per countable product (variant parents expand to their active variants) with system on-hand. */
    private List<Map<String, Object>> buildLines(String locationId, Scope scope) {
        boolean scoped = !scope.categoryIds().isEmpty() || !scope.productIds().isEmpty();
        StringBuilder sql = new StringBuilder("SELECT p.id, p.name, p.\"hasVariants\", u.code AS uom FROM \"Product\" p"
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
        return lines;
    }

    private Map<String, Object> line(String productId, String variantId, String name, String parentId,
            String parentName, String unit) {
        return Obj.of("organizationId", org(), "productId", productId, "variantId", variantId, "productName", name,
                "parentProductId", parentId, "parentProductName", parentName, "unit", unit, "systemQty", Money.ZERO,
                "countedQty", null, "variance", Money.ZERO);
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

    /** Persist in-progress counts (per line) and the optional name / notes. */
    @Transactional
    public Map<String, Object> saveDraft(String id, List<DraftLine> lines, String name, String notes) {
        assertDraft(id);
        Map<String, BigDecimal> sys = new HashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, \"systemQty\" FROM \"InventoryCountLine\" WHERE"
                + " \"sessionId\" = ? AND \"organizationId\" = ?").params(id, org()))) {
            sys.put(str(l, "id"), dec(l, "systemQty"));
        }
        String user = TenantContext.userId();
        for (DraftLine l : lines) {
            if (!sys.containsKey(l.lineId())) {
                continue;
            }
            BigDecimal counted = l.countedQty();
            BigDecimal variance = counted == null ? Money.ZERO : counted.subtract(sys.get(l.lineId()));
            jdbc.sql("UPDATE \"InventoryCountLine\" SET \"countedQty\" = ?, variance = ?, reason = ?, \"countedById\" = ?,"
                            + " \"countedAt\" = " + (counted == null ? "NULL" : NOW) + " WHERE id = ? AND \"sessionId\" = ?"
                            + " AND \"organizationId\" = ?")
                    .params(counted, variance, l.reason(), counted == null ? null : user, l.lineId(), id, org())
                    .update();
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

    private List<Map<String, Object>> movementsAfterCount(Map<String, Object> session, List<Map<String, Object>> lines) {
        List<Map<String, Object>> counted = lines.stream().filter(l -> l.get("countedQty") != null).toList();
        if (counted.isEmpty()) {
            return List.of();
        }
        Instant startedAt = (Instant) session.get("startedAt");
        Instant earliest = counted.getFirst().get("countedAt") != null ? (Instant) counted.getFirst().get("countedAt")
                : startedAt;
        for (Map<String, Object> l : counted) {
            Instant at = l.get("countedAt") != null ? (Instant) l.get("countedAt") : startedAt;
            if (at.isBefore(earliest)) {
                earliest = at;
            }
        }
        LinkedHashSet<String> productIds = new LinkedHashSet<>();
        counted.forEach(l -> productIds.add(str(l, "productId")));
        Map<String, Map<String, Object>> moves = new HashMap<>();
        for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT \"productId\", \"variantId\", SUM(\"quantityChange\") AS"
                        + " net, MAX(\"createdAt\") AS last, count(*) AS n FROM \"InventoryLedger\" WHERE \"organizationId\" = :org"
                        + " AND \"locationId\" = :loc AND \"createdAt\" > :since AND \"productId\" IN (:ids)"
                        + " GROUP BY \"productId\", \"variantId\"")
                .param("org", org()).param("loc", session.get("locationId")).param("since", SqlRows.ts(earliest))
                .param("ids", List.copyOf(productIds)))) {
            moves.put(m.get("productId") + "::" + (m.get("variantId") == null ? "" : m.get("variantId")), m);
        }
        List<Map<String, Object>> affected = new ArrayList<>();
        for (Map<String, Object> l : counted) {
            Map<String, Object> m = moves.get(l.get("productId") + "::" + (l.get("variantId") == null ? ""
                    : l.get("variantId")));
            if (m == null) {
                continue;
            }
            Instant countedAt = l.get("countedAt") != null ? (Instant) l.get("countedAt") : startedAt;
            Instant last = (Instant) m.get("last");
            if (last == null || !last.isAfter(countedAt)) {
                continue;
            }
            affected.add(Obj.of("lineId", l.get("id"), "productId", l.get("productId"), "productName",
                    l.get("productName"), "countedAt", countedAt, "movements", ((Number) m.get("n")).longValue(),
                    "netQuantityChange", m.get("net") == null ? "0" : JsNumbers.dec(dec(m, "net"))));
        }
        return affected;
    }

    /** Finalise: reasons on variance lines, staleness guard, then adjustment + session claim in one tx. */
    @Transactional
    public Map<String, Object> submit(String id, Boolean force, String forceReason) {
        assertDraft(id);
        Map<String, Object> approval = newTx.execute(s -> approvals.checkOrRequestApproval("inventory_count_submit", id,
                Obj.of("countId", id)));
        if (approval != null && Boolean.TRUE.equals(approval.get("needsApproval"))) {
            throw ApiException.forbidden("Inventory count submission requires approval. Request ID: "
                    + approval.get("requestId") + ".");
        }
        Map<String, Object> snapshot = get(id, false);
        List<Map<String, Object>> lines = SqlRows.list(InventoryCountLine.class, jdbc.sql("SELECT * FROM"
                + " \"InventoryCountLine\" WHERE \"sessionId\" = ? AND \"organizationId\" = ?").params(id, org()));
        List<Map<String, Object>> counted = lines.stream().filter(l -> l.get("countedQty") != null).toList();
        if (counted.isEmpty()) {
            throw ApiException.badRequest("Nothing counted yet — enter at least one physical count.");
        }
        List<Map<String, Object>> stale = movementsAfterCount(snapshot, counted);
        if (!stale.isEmpty() && !Boolean.TRUE.equals(force)) {
            List<String> sample = new ArrayList<>();
            for (Map<String, Object> s : stale.subList(0, Math.min(5, stale.size()))) {
                sample.add(s.get("productName") + " (" + s.get("movements") + " movement(s), net "
                        + s.get("netQuantityChange") + ")");
            }
            throw ApiException.badRequest(stale.size() + " counted line(s) moved after they were counted — submitting now"
                    + " would overwrite those movements: " + String.join("; ", sample)
                    + (stale.size() > 5 ? " …and " + (stale.size() - 5) + " more" : "") + ". "
                    + "Re-count the affected lines, or resubmit with force=true and a reason to accept the variance as"
                    + " counted.");
        }
        if (!stale.isEmpty() && Boolean.TRUE.equals(force) && (forceReason == null || forceReason.trim().isEmpty())) {
            throw ApiException.badRequest(
                    "A reason is required to force-submit a count over lines that moved after being counted.");
        }
        List<Map<String, Object>> variance = counted.stream().filter(l -> dec(l, "variance").signum() != 0).toList();
        long missingReason = variance.stream().filter(l -> l.get("reason") == null || str(l, "reason").trim().isEmpty())
                .count();
        if (missingReason > 0) {
            throw ApiException.badRequest("A reason is required for " + missingReason + " line(s) with a variance.");
        }
        String user = TenantContext.userId();
        int claimed = jdbc.sql("UPDATE \"InventoryCountSession\" SET status = 'submitted', \"submittedById\" = ?,"
                        + " \"submittedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL AND status = 'draft'")
                .params(user, id, org()).update();
        if (claimed == 0) {
            throw ApiException.conflict("Count was already submitted or cancelled by another request");
        }
        if (variance.isEmpty()) {
            if (!stale.isEmpty()) {
                setNotes(id, appendForceNote(str(snapshot, "notes"), stale.size(), forceReason));
            }
            auditSubmit(id, snapshot, stale, force, forceReason, null);
            return get(id, false);
        }
        Map<String, Object> session = session(id);
        List<DocLine> items = new ArrayList<>();
        for (Map<String, Object> l : variance) {
            items.add(new DocLine(str(l, "productId"), str(l, "variantId"), str(l, "unit"), null, null, null, null,
                    dec(l, "countedQty"), null));
        }
        String responsible = session.get("startedById") != null && !str(session, "startedById").isEmpty()
                ? str(session, "startedById") : user != null ? user : "";
        Map<String, Object> adj = stockDoc.createAdjustment(new DocInput(str(session, "locationId"), null, null, null,
                responsible, user != null ? user : "", null, "cycle_count",
                session.get("countType") + " count " + session.get("countCode"), items), true);
        stockDoc.approveAdjustment(str(adj, "id"), true, null, null);
        jdbc.sql("UPDATE \"InventoryCountSession\" SET \"adjustmentId\" = ?" + (stale.isEmpty() ? "" : ", notes = ?")
                        + ", \"updatedAt\" = " + NOW + " WHERE id = ?")
                .params(stale.isEmpty() ? List.of(adj.get("id"), id)
                        : List.of(adj.get("id"), appendForceNote(str(session, "notes"), stale.size(), forceReason), id))
                .update();
        auditSubmit(id, session, stale, force, forceReason, str(adj, "id"));
        Map<String, Object> out = session(id);
        return full(out);
    }

    private void setNotes(String id, String notes) {
        jdbc.sql("UPDATE \"InventoryCountSession\" SET notes = ? WHERE id = ?").params(notes, id).update();
    }

    private static String appendForceNote(String existing, int staleCount, String forceReason) {
        String note = "[FORCED] " + staleCount + " line(s) moved after being counted and were overwritten by this count. "
                + "Reason: " + (forceReason == null ? "n/a" : forceReason.trim());
        return existing != null && !existing.trim().isEmpty() ? existing.trim() + "\n" + note : note;
    }

    private void auditSubmit(String id, Map<String, Object> session, List<Map<String, Object>> stale, Boolean force,
            String forceReason, String adjustmentId) {
        Map<String, Object> values = Obj.of("kind", "count_submitted", "countCode", session.get("countCode"),
                "locationId", session.get("locationId"), "adjustmentId", adjustmentId,
                "forced", !stale.isEmpty() && Boolean.TRUE.equals(force), "forceReason", forceReason);
        if (!stale.isEmpty()) {
            values.put("absorbedMovements", stale);
        }
        audit.recordInTx(AuditInput.of("InventoryCountSession", id, "update", null, values));
    }

    /** Abandon a draft count without touching stock. */
    @Transactional
    public Map<String, Object> cancel(String id) {
        assertDraft(id);
        jdbc.sql("UPDATE \"InventoryCountSession\" SET status = 'cancelled', \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(TenantContext.userId(), id, org()).update();
        return Obj.of("ok", true);
    }
}
