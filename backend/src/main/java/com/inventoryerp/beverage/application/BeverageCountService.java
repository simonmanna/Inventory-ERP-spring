package com.inventoryerp.beverage.application;

import static com.inventoryerp.kernel.crud.SqlRows.dec;
import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.beverage.domain.BottleCountLine;
import com.inventoryerp.beverage.domain.BottleCountReading;
import com.inventoryerp.beverage.domain.BottleCountSession;
import com.inventoryerp.inventory.application.StockDocService;
import com.inventoryerp.inventory.application.StockDocService.DocInput;
import com.inventoryerp.inventory.application.StockDocService.DocLine;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.auth.PasswordService;
import com.inventoryerp.kernel.common.BeverageMath;
import com.inventoryerp.kernel.common.BeverageMath.ReadingCheck;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Beverage Control — digital-weight bottle count (port of {@code beverage-count.service.ts}).
 *
 * <p>Bartenders weigh open bottles; grams are converted to remaining ml and reconciled against live system stock
 * (which POS sales already deducted in ml). On submit only the variance is posted as a stock adjustment through
 * {@link StockDocService}, and the immutable {@code BottleMeasurement} log is written in the same transaction.
 */
@Service
public class BeverageCountService {

    /** Fallback variance tolerance (grams) when neither product nor org sets one. */
    static final double DEFAULT_TOLERANCE_G = 5;
    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
            "Nov", "Dec"};

    /** Beverage config of one digital-weight product. */
    record BevProduct(String id, String name, String unit, double containerVolumeMl, double effectiveEmptyWeightG,
            double fullBottleWeightG, double conversionFactorMlPerG, double standardPourMl, double toleranceG) {}

    /** One physical open-bottle weighing. */
    public record Reading(double measuredWeightG, String bottleNumber, String measurementSource) {}

    /** Variance cause codes for {@code BottleCountLine.reasonCode} (ADR-S004; not in Nest). */
    public static final List<String> REASON_CODES = List.of("spillage", "breakage", "complimentary", "staff_drink",
            "unrecorded_sale", "bottle_transfer", "measurement_error", "unexplained");

    /** One draft line save; {@code reasonCode} is the ADR-S004 extension. */
    public record DraftLine(String lineId, Double sealedFullCount, List<Reading> readings, String reason,
            String reasonCode) {}

    /**
     * Optional manager-approval credentials on submit; {@code unweighedReason} is the ADR-S004 unweighed-bottle
     * exception.
     */
    public record Approval(String approverId, String approverEmail, String managerPin, String unweighedReason) {}

    record ComputedReading(double measuredWeightG, String bottleNumber, String measurementSource, double remainingMl,
            String confidence) {}

    record ComputedLine(boolean counted, double countedMl, double varianceMl, double varianceG, String confidence,
            Double expectedShots, List<ComputedReading> readings) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final AuditService audit;
    private final PasswordService password;
    private final StockDocService stockDoc;

    public BeverageCountService(JdbcClient jdbc, SequenceService seq, AuditService audit, PasswordService password,
            StockDocService stockDoc) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.audit = audit;
        this.password = password;
        this.stockDoc = stockDoc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Org-default variance tolerance (grams), read from Setting; else the fallback. */
    private double orgToleranceG() {
        String raw = jdbc.sql("SELECT value::text FROM \"Setting\" WHERE \"organizationId\" = ? AND scope = 'organization'"
                        + " AND key = 'beverage.varianceToleranceG' LIMIT 1")
                .param(org()).query(String.class).optional().orElse(null);
        double n = Double.NaN;
        if (raw != null) {
            JsonNode v = EntityJson.tree(raw);
            if (v != null && !v.isNull()) {
                n = v.isNumber() ? v.asDouble() : JsNumbers.number(v.isString() ? v.asString() : v.toString());
            }
        }
        return Double.isFinite(n) && n > 0 ? n : DEFAULT_TOLERANCE_G;
    }

    /** ADR-S004 opt-in: every bottle holding stock must be weighed (or excepted) before submit. */
    private boolean requireAllBottlesWeighed() {
        String raw = jdbc.sql("SELECT value::text FROM \"Setting\" WHERE \"organizationId\" = ? AND scope = 'organization'"
                        + " AND key = 'beverage.requireAllBottlesWeighed' LIMIT 1")
                .param(org()).query(String.class).optional().orElse(null);
        JsonNode v = raw == null ? null : EntityJson.tree(raw);
        return v != null && (v.isBoolean() ? v.asBoolean() : v.isString() && "true".equals(v.asString()));
    }

    /** Explicit location, else the active warehouse (where POS sales deduct stock). */
    private Map<String, Object> resolveLocation(String locationId) {
        if (locationId != null && !locationId.isEmpty()) {
            Map<String, Object> loc = SqlRows.one(jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE id = ?"
                    + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(locationId, org()));
            if (loc == null) {
                throw ApiException.notFound("Location not found");
            }
            return loc;
        }
        Map<String, Object> wh = SqlRows.one(jdbc.sql("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND type::text = 'warehouse' AND \"isActive\" = true LIMIT 1").param(org()));
        if (wh == null) {
            throw ApiException.badRequest("No location specified and no active warehouse configured.");
        }
        return wh;
    }

    /** Beverage config for the given products (digital-weight only). */
    private Map<String, BevProduct> loadProducts(List<String> productIds, double orgTolerance) {
        Map<String, BevProduct> map = new HashMap<>();
        if (productIds.isEmpty()) {
            return map;
        }
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT p.id, p.name, p.\"containerVolumeMl\","
                        + " p.\"emptyBottleWeightG\", p.\"actualEmptyWeightG\", p.\"fullBottleWeightG\","
                        + " p.\"conversionFactorMlPerG\", p.\"standardPourMl\", p.\"varianceToleranceG\", u.code AS uom"
                        + " FROM \"Product\" p LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\""
                        + " WHERE p.id IN (:ids) AND p.\"organizationId\" = :org AND p.\"deletedAt\" IS NULL"
                        + " AND p.\"measurementMethod\"::text = 'digital_weight'")
                .param("ids", productIds).param("org", org()))) {
            double empty = (r.get("actualEmptyWeightG") != null ? dec(r, "actualEmptyWeightG")
                    : dec(r, "emptyBottleWeightG")).doubleValue();
            double tol = dec(r, "varianceToleranceG").doubleValue();
            map.put(str(r, "id"), new BevProduct(str(r, "id"), str(r, "name"), r.get("uom") != null ? str(r, "uom") : "ml",
                    dec(r, "containerVolumeMl").doubleValue(), empty, dec(r, "fullBottleWeightG").doubleValue(),
                    dec(r, "conversionFactorMlPerG").doubleValue(), dec(r, "standardPourMl").doubleValue(),
                    tol != 0 ? tol : orgTolerance));
        }
        return map;
    }

    // ── reads ────────────────────────────────────────────────────────────────

    private Map<String, Object> session(String id) {
        return SqlRows.one(BottleCountSession.class, jdbc.sql("SELECT * FROM \"BottleCountSession\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(id, org()));
    }

    private Map<String, Object> withLines(Map<String, Object> session) {
        List<Map<String, Object>> lines = SqlRows.list(BottleCountLine.class, jdbc.sql("SELECT * FROM \"BottleCountLine\""
                + " WHERE \"sessionId\" = ? AND \"organizationId\" = ? ORDER BY \"productName\" ASC")
                .params(session.get("id"), org()));
        Map<String, List<Map<String, Object>>> byLine = readingsByLine(str(session, "id"));
        for (Map<String, Object> l : lines) {
            l.put("readings", byLine.getOrDefault(str(l, "id"), new ArrayList<>()));
        }
        session.put("lines", lines);
        return session;
    }

    private Map<String, List<Map<String, Object>>> readingsByLine(String sessionId) {
        Map<String, List<Map<String, Object>>> byLine = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(BottleCountReading.class, jdbc.sql("SELECT r.* FROM"
                        + " \"BottleCountReading\" r JOIN \"BottleCountLine\" l ON l.id = r.\"lineId\""
                        + " WHERE l.\"sessionId\" = ? AND r.\"organizationId\" = ?")
                .params(sessionId, org()))) {
            byLine.computeIfAbsent(str(r, "lineId"), k -> new ArrayList<>()).add(r);
        }
        return byLine;
    }

    /** Session with its lines (by product name) and their readings. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> s = session(id);
        if (s == null) {
            throw ApiException.notFound("Bottle count not found");
        }
        return withLines(s);
    }

    /** Most recent 100 sessions with their line count. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.sql("SELECT s.*, (SELECT count(*) FROM \"BottleCountLine\" l"
                        + " WHERE l.\"sessionId\" = s.id) AS \"_lineCount\" FROM \"BottleCountSession\" s"
                        + " WHERE s.\"organizationId\" = ? AND s.\"deletedAt\" IS NULL ORDER BY s.\"startedAt\" DESC LIMIT 100")
                .param(org()).query().listOfRows()) {
            Map<String, Object> s = SqlRows.as(BottleCountSession.class, row);
            s.put("_count", Obj.of("lines", ((Number) row.get("_lineCount")).longValue()));
            out.add(s);
        }
        return out;
    }

    // ── start ────────────────────────────────────────────────────────────────

    /**
     * Start a fresh bottle count: cancels any draft for this location + type, then snapshots every active
     * digital-weight product and its live system ml.
     */
    @Transactional
    public Map<String, Object> start(String locationId, String countTypeIn, String notes) {
        String locId = str(resolveLocation(locationId), "id");
        String countType = countTypeIn != null ? countTypeIn : "closing";
        String user = TenantContext.userId();

        jdbc.sql("UPDATE \"BottleCountSession\" SET status = 'cancelled', \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"locationId\" = ?"
                        + " AND \"countType\" = ? AND status = 'draft'")
                .params(user, org(), locId, countType).update();

        List<Map<String, Object>> products = SqlRows.list(jdbc.sql("SELECT p.id, p.name, u.code AS uom FROM \"Product\" p"
                + " LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE p.\"organizationId\" = ?"
                + " AND p.\"deletedAt\" IS NULL AND p.\"measurementMethod\"::text = 'digital_weight' AND p.\"isActive\" = true"
                + " ORDER BY p.name ASC").param(org()));
        if (products.isEmpty()) {
            throw ApiException.badRequest("No digital-weight (bar alcohol) products configured. Set a product's"
                    + " measurement method to \"Digital weight scale\" first.");
        }

        Map<String, BigDecimal> systemMl = new HashMap<>();
        for (Map<String, Object> si : SqlRows.list(jdbc.sql("SELECT \"productId\", quantity FROM \"StockItem\""
                        + " WHERE \"organizationId\" = :org AND \"locationId\" = :loc AND \"productId\" IN (:ids)"
                        + " AND \"variantKey\" = ''")
                .param("org", org()).param("loc", locId).param("ids", products.stream().map(p -> str(p, "id")).toList()))) {
            systemMl.put(str(si, "productId"), dec(si, "quantity"));
        }

        String countCode = seq.next("bottle_count", "BCNT-", 5);
        String id = UUID.randomUUID().toString();
        try {
            jdbc.sql("SAVEPOINT bottle_count_start").update();
            jdbc.sql("INSERT INTO \"BottleCountSession\" (id, \"organizationId\", \"countCode\", name, \"locationId\","
                            + " \"countType\", status, notes, \"startedById\", \"createdBy\", \"startedAt\", \"createdAt\","
                            + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, " + NOW + ", " + NOW + ", " + NOW + ")")
                    .params(id, org(), countCode, autoName(countType), locId, countType, notes, user, user)
                    .update();
            jdbc.sql("RELEASE SAVEPOINT bottle_count_start").update();
        } catch (DataIntegrityViolationException err) {
            jdbc.sql("ROLLBACK TO SAVEPOINT bottle_count_start").update();
            Map<String, Object> existing = SqlRows.one(jdbc.sql("SELECT id FROM \"BottleCountSession\" WHERE"
                    + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"locationId\" = ? AND \"countType\" = ?"
                    + " AND status = 'draft' LIMIT 1").params(org(), locId, countType));
            if (existing != null) {
                return get(str(existing, "id"));
            }
            throw err;
        }
        for (Map<String, Object> p : products) {
            jdbc.sql("INSERT INTO \"BottleCountLine\" (id, \"organizationId\", \"sessionId\", \"productId\", \"productName\","
                            + " unit, \"systemMl\", \"sealedFullCount\", \"varianceMl\", \"varianceG\", confidence)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 'GOOD')")
                    .params(UUID.randomUUID().toString(), org(), id, p.get("id"), p.get("name"),
                            p.get("uom") != null ? p.get("uom") : "ml",
                            systemMl.getOrDefault(str(p, "id"), BigDecimal.ZERO))
                    .update();
        }
        return get(id);
    }

    private static String autoName(String countType) {
        LocalDate now = LocalDate.now(BusinessZone.get());
        return ("opening".equals(countType) ? "Opening" : "Closing") + " Bottle Count – " + MONTHS[now.getMonthValue() - 1]
                + " " + String.format("%02d", now.getDayOfMonth()) + ", " + now.getYear();
    }

    private Map<String, Object> assertDraft(String id) {
        Map<String, Object> s = session(id);
        if (s == null) {
            throw ApiException.notFound("Bottle count not found");
        }
        if (!"draft".equals(str(s, "status"))) {
            throw ApiException.badRequest("Cannot modify a " + s.get("status") + " count");
        }
        return s;
    }

    // ── save draft ───────────────────────────────────────────────────────────

    /**
     * Persist per-line weighings: replace each line's readings, recompute remaining ml, variance (ml + grams) and
     * confidence. Lines not in this session are ignored.
     */
    @Transactional
    public Map<String, Object> saveDraft(String id, List<DraftLine> lines, String name, String notes) {
        assertDraft(id);
        double tol = orgToleranceG();
        Map<String, Map<String, Object>> lineById = new LinkedHashMap<>();
        for (Map<String, Object> l : SqlRows.list(jdbc.sql("SELECT id, \"productId\", \"systemMl\" FROM \"BottleCountLine\""
                + " WHERE \"sessionId\" = ? AND \"organizationId\" = ?").params(id, org()))) {
            lineById.put(str(l, "id"), l);
        }
        Set<String> productIds = new LinkedHashSet<>();
        lineById.values().forEach(l -> productIds.add(str(l, "productId")));
        Map<String, BevProduct> products = loadProducts(List.copyOf(productIds), tol);
        String user = TenantContext.userId();

        for (DraftLine l : lines) {
            Map<String, Object> db = lineById.get(l.lineId());
            if (db == null) {
                continue;
            }
            double sealed = l.sealedFullCount() == null ? 0 : l.sealedFullCount();
            ComputedLine c = computeLine(products.get(str(db, "productId")), sealed,
                    l.readings() == null ? List.of() : l.readings(), dec(db, "systemMl").doubleValue());

            jdbc.sql("DELETE FROM \"BottleCountReading\" WHERE \"lineId\" = ? AND \"organizationId\" = ?")
                    .params(l.lineId(), org()).update();
            for (ComputedReading r : c.readings()) {
                jdbc.sql("INSERT INTO \"BottleCountReading\" (id, \"organizationId\", \"lineId\", \"bottleNumber\","
                                + " \"measuredWeightG\", \"expectedWeightG\", \"remainingMl\", confidence)"
                                + " VALUES (?, ?, ?, ?, ?, NULL, ?, ?)")
                        .params(UUID.randomUUID().toString(), org(), l.lineId(), r.bottleNumber(),
                                BigDecimal.valueOf(r.measuredWeightG()), BigDecimal.valueOf(r.remainingMl()),
                                r.confidence())
                        .update();
            }
            jdbc.sql("UPDATE \"BottleCountLine\" SET \"sealedFullCount\" = ?, \"countedMl\" = ?, \"varianceMl\" = ?,"
                            + " \"varianceG\" = ?, \"expectedShots\" = ?, reason = ?, \"reasonCode\" = ?, confidence = ?,"
                            + " \"countedById\" = ?, \"countedAt\" = " + (c.counted() ? NOW : "NULL")
                            + " WHERE id = ? AND \"sessionId\" = ? AND \"organizationId\" = ?")
                    .params(BigDecimal.valueOf(sealed), c.counted() ? BigDecimal.valueOf(c.countedMl()) : null,
                            BigDecimal.valueOf(c.varianceMl()), BigDecimal.valueOf(c.varianceG()),
                            c.expectedShots() == null ? null : BigDecimal.valueOf(c.expectedShots()), l.reason(),
                            l.reasonCode(), c.confidence(), c.counted() ? user : null, l.lineId(), id, org())
                    .update();
        }

        List<Object> params = new ArrayList<>();
        StringBuilder set = new StringBuilder("\"updatedBy\" = ?");
        params.add(user);
        if (EntityPatch.present("name", name)) {
            set.append(", name = ?");
            params.add(name);
        }
        if (EntityPatch.present("notes", notes)) {
            set.append(", notes = ?");
            params.add(notes);
        }
        params.add(id);
        params.add(org());
        jdbc.sql("UPDATE \"BottleCountSession\" SET " + set + ", \"updatedAt\" = " + NOW + " WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(params).update();
        return get(id);
    }

    /** Pure per-line math (grams → ml, variance, confidence). */
    static ComputedLine computeLine(BevProduct cfg, double sealedFullCount, List<Reading> readings, double systemMl) {
        double factor = cfg != null ? cfg.conversionFactorMlPerG() : 0;
        double vol = cfg != null ? cfg.containerVolumeMl() : 0;
        double empty = cfg != null ? cfg.effectiveEmptyWeightG() : 0;
        double full = cfg != null ? cfg.fullBottleWeightG() : 0;
        double tol = cfg != null ? cfg.toleranceG() : DEFAULT_TOLERANCE_G;
        double pour = cfg != null ? cfg.standardPourMl() : 0;

        boolean counted = sealedFullCount > 0 || !readings.isEmpty();
        boolean anyOutOfRange = false;
        List<ComputedReading> computed = new ArrayList<>();
        for (Reading r : readings) {
            ReadingCheck chk = BeverageMath.checkReading(r.measuredWeightG(), empty, full);
            anyOutOfRange |= chk.outOfRange();
            double remainingMl = factor > 0 ? BeverageMath.remainingMlFromWeight(r.measuredWeightG(), empty, factor) : 0;
            computed.add(new ComputedReading(r.measuredWeightG(), r.bottleNumber(),
                    r.measurementSource() != null ? r.measurementSource() : "MANUAL", remainingMl,
                    chk.outOfRange() ? "OUT_OF_RANGE" : "GOOD"));
        }
        double openMl = 0;
        for (ComputedReading r : computed) {
            openMl += r.remainingMl();
        }
        double countedMl = JsNumbers.round(sealedFullCount * vol + openMl, 6);
        double varianceMl = JsNumbers.round(countedMl - systemMl, 6);
        double varianceG = factor > 0 ? JsNumbers.round(varianceMl / factor, 6) : 0;
        String confidence = BeverageMath.classifyConfidence(varianceG, tol, anyOutOfRange);
        Double expectedShots = pour > 0 ? JsNumbers.round(countedMl / pour, 6) : null;
        return new ComputedLine(counted, countedMl, varianceMl, varianceG, confidence, expectedShots, computed);
    }

    // ── submit ───────────────────────────────────────────────────────────────

    /**
     * Finalise the count: a reason on every flagged line, manager approval (not the counter) when any line is over
     * tolerance or physically impossible, then the variance adjustment, the immutable measurement log, the session
     * claim and the audit row — one transaction.
     */
    @Transactional
    public Map<String, Object> submit(String id, Approval dto) {
        assertDraft(id);
        List<Map<String, Object>> lines = SqlRows.list(BottleCountLine.class, jdbc.sql("SELECT * FROM \"BottleCountLine\""
                + " WHERE \"sessionId\" = ? AND \"organizationId\" = ?").params(id, org()));
        Map<String, List<Map<String, Object>>> readings = readingsByLine(id);
        List<Map<String, Object>> counted = lines.stream().filter(l -> l.get("countedMl") != null).toList();
        if (counted.isEmpty()) {
            throw ApiException.badRequest("Nothing weighed yet — weigh at least one bottle.");
        }
        List<Map<String, Object>> flagged = counted.stream().filter(l -> !"GOOD".equals(str(l, "confidence"))).toList();
        long missingReason = flagged.stream()
                .filter(l -> l.get("reason") == null || str(l, "reason").trim().isEmpty()).count();
        if (missingReason > 0) {
            throw ApiException.badRequest("A reason is required for " + missingReason
                    + " flagged line(s) (variance over tolerance).");
        }
        List<Map<String, Object>> varianceLines = counted.stream().filter(l -> dec(l, "varianceMl").signum() != 0)
                .toList();

        // ADR-S004: bottles that hold stock but were not weighed need a recorded, approved exception (opt-in).
        long unweighed = 0;
        String unweighedReason = null;
        if (requireAllBottlesWeighed()) {
            unweighed = lines.stream().filter(l -> l.get("countedMl") == null && dec(l, "systemMl").signum() > 0).count();
            String given = dto == null ? null : dto.unweighedReason();
            if (unweighed > 0 && (given == null || given.trim().isEmpty())) {
                throw ApiException.badRequest(unweighed + " bottle line(s) with stock were not weighed. Weigh them, or"
                        + " submit an unweighed exception with a reason and manager approval.");
            }
            unweighedReason = unweighed > 0 ? given.trim() : null;
        }
        boolean needsApproval = !flagged.isEmpty() || unweighed > 0;

        Map<String, Object> session = SqlRows.one(BottleCountSession.class, jdbc.sql("SELECT * FROM"
                + " \"BottleCountSession\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL FOR UPDATE")
                .params(id, org()));
        if (session == null || !"draft".equals(str(session, "status"))) {
            throw ApiException.badRequest("Count was modified; please retry.");
        }
        String user = TenantContext.userId();

        String approverId = null;
        if (needsApproval) {
            Approval a = dto == null ? new Approval(null, null, null, null) : dto;
            approverId = assertManagerApproval(a, str(session, "startedById"), !flagged.isEmpty()
                    ? "This count has a variance over tolerance and requires manager approval."
                    : "Submitting with unweighed bottles requires manager approval.");
        }

        String adjustmentId = null;
        if (!varianceLines.isEmpty()) {
            List<DocLine> items = new ArrayList<>();
            for (Map<String, Object> l : varianceLines) {
                items.add(new DocLine(str(l, "productId"), null, str(l, "unit"), null, null, null, null,
                        dec(l, "countedMl"), null));
            }
            String startedBy = str(session, "startedById");
            String responsible = startedBy != null && !startedBy.isEmpty() ? startedBy : user != null ? user : "";
            String approvedBy = approverId != null ? approverId : user != null ? user : "";
            Map<String, Object> adj = stockDoc.createAdjustment(new DocInput(str(session, "locationId"), null, null, null,
                    responsible, approvedBy, null, "cycle_count",
                    session.get("countType") + " bottle count " + session.get("countCode"), items), true);
            stockDoc.approveAdjustment(str(adj, "id"), true, null, null);
            adjustmentId = str(adj, "id");
        }

        for (Map<String, Object> l : counted) {
            for (Map<String, Object> r : readings.getOrDefault(str(l, "id"), List.of())) {
                jdbc.sql("INSERT INTO \"BottleMeasurement\" (id, \"organizationId\", \"productId\", \"bottleNumber\","
                                + " \"locationId\", \"sessionId\", \"expectedWeightG\", \"measuredWeightG\", \"remainingMl\","
                                + " \"varianceMl\", \"varianceG\", \"measurementSource\", confidence, \"measuredById\","
                                + " \"measuredAt\", \"createdAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 'MANUAL', ?, ?, "
                                + NOW + ", " + NOW + ")")
                        .params(UUID.randomUUID().toString(), org(), l.get("productId"), r.get("bottleNumber"),
                                session.get("locationId"), id, r.get("expectedWeightG"), r.get("measuredWeightG"),
                                r.get("remainingMl"), r.get("confidence"), user)
                        .update();
            }
        }

        jdbc.sql("UPDATE \"BottleCountSession\" SET status = 'submitted', \"submittedById\" = ?, \"submittedAt\" = " + NOW
                        + ", \"approvedById\" = ?, \"approvedAt\" = " + (approverId != null ? NOW : "NULL")
                        + ", \"adjustmentId\" = ?, \"unweighedReason\" = ?, \"unweighedCount\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ?")
                .params(user, approverId, adjustmentId, unweighedReason, (int) unweighed, id, org()).update();

        Map<String, Object> auditValues = Obj.of(
                "countCode", session.get("countCode"),
                "locationId", session.get("locationId"),
                "counterId", session.get("startedById"),
                "approverId", approverId,
                "adjustmentId", adjustmentId,
                "flaggedLines", flagged.size(),
                "varianceLines", varianceLines.size());
        if (unweighed > 0) {
            auditValues.put("unweighedLines", unweighed);
            auditValues.put("unweighedReason", unweighedReason);
        }
        audit.recordInTx(AuditInput.of("BottleCountSession", id, "measure", null, auditValues));

        return withLines(session(id));
    }

    /** Abandon a draft count without touching stock. */
    @Transactional
    public Map<String, Object> cancel(String id) {
        assertDraft(id);
        jdbc.sql("UPDATE \"BottleCountSession\" SET status = 'cancelled', \"updatedBy\" = ?, \"updatedAt\" = " + NOW
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(TenantContext.userId(), id, org()).update();
        return Obj.of("ok", true);
    }

    /**
     * Manager-approval gate (segregation of duties): approver by id or email, active, not the counter, PIN when
     * given, and holding {@code beverage:approve}. Returns the approver id.
     */
    private String assertManagerApproval(Approval a, String counterUserId, String missingMessage) {
        if (a.approverId() == null && a.approverEmail() == null) {
            throw ApiException.badRequest(missingMessage);
        }
        Map<String, Object> manager = a.approverId() != null
                ? SqlRows.one(jdbc.sql("SELECT id, \"pinHash\" FROM \"User\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL").params(a.approverId(), org()))
                : SqlRows.one(jdbc.sql("SELECT id, \"pinHash\" FROM \"User\" WHERE email = ? AND \"organizationId\" = ?"
                        + " AND \"isActive\" = true AND \"deletedAt\" IS NULL").params(a.approverEmail().toLowerCase(), org()));
        if (manager == null) {
            throw ApiException.notFound("Approving manager not found");
        }
        String managerId = str(manager, "id");
        if (counterUserId != null && managerId.equals(counterUserId)) {
            throw ApiException.forbidden("The person who counted cannot approve their own count.");
        }
        if (a.managerPin() != null && !a.managerPin().isEmpty()) {
            String pinHash = str(manager, "pinHash");
            if (pinHash == null) {
                throw ApiException.badRequest("Manager has not set a PIN");
            }
            if (!password.compare(a.managerPin(), pinHash)) {
                throw ApiException.unauthorized("Invalid manager PIN");
            }
        }
        Set<String> perms = new LinkedHashSet<>();
        jdbc.sql("SELECT r.permissions FROM \"_UserRoles\" ur JOIN \"Role\" r ON r.id = ur.\"A\" WHERE ur.\"B\" = ?")
                .param(managerId)
                .query((rs, n) -> {
                    java.sql.Array arr = rs.getArray(1);
                    if (arr != null) {
                        perms.addAll(List.of((String[]) arr.getArray()));
                    }
                    return null;
                }).list();
        if (!perms.contains(Permissions.BEVERAGE__APPROVE)) {
            throw ApiException.unauthorized("Approver does not hold " + Permissions.BEVERAGE__APPROVE);
        }
        return managerId;
    }
}
