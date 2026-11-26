package com.inventoryerp.gatepass.application;

import static com.inventoryerp.gatepass.application.GatePassSources.dec;
import static com.inventoryerp.gatepass.application.GatePassSources.s;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.fleet.application.FleetDispatchService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.sales.application.CashSaleService;
import com.inventoryerp.sales.application.SalesHandoverService;
import com.inventoryerp.sales.application.SalesLetterhead;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gate passes (ADR-S085): issue a pass for a source document, print it, verify it at the gate, let the bearer out
 * (or record a refusal) and void unused passes.
 *
 * <p>Rules: a pass may be issued at any time, also before the goods are issued from stock or paid, but such a pass
 * carries the warnings {@code goods_not_issued} / {@code unpaid} / {@code partially_paid} and prints them. Never for a
 * cancelled source. Per source line, the quantities on all passes that are not void never exceed the source quantity,
 * and per product never exceed what the root document holds across the passes of related documents (an invoice or
 * delivery of the same cash sale / sales order), so the same goods cannot be passed out twice. Gate-out needs an unused pass that is not expired and whose source is
 * still not cancelled. After the gate-out is committed the source is marked "departed" in its own transaction where
 * that step posts nothing (trip depart, handover / cash sale delivery dispatched). If that step is refused by the
 * source's own rules, the gate-out stays and the reason is kept on the pass.
 */
@Service
public class GatePassService {

    public static final List<String> BEARER_TYPES = List.of("driver", "person", "courier");
    static final int DEFAULT_VALID_HOURS = 24;
    static final int MAX_VALID_HOURS = 168;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Issue request. {@code lines} null = every line with the quantity still open. */
    public record IssueInput(String sourceType, String sourceId, String bearerType, String bearerName,
            String bearerIdNumber, String bearerPhone, String vehiclePlate, String vehicleType, String transportCompany,
            String destination, String purpose, String remarks, Integer validHours, List<LineInput> lines) {}

    public record LineInput(String sourceLineId, BigDecimal quantity) {}

    public record GateOutInput(String verifyCode, String plate, String note) {}

    private final JdbcClient jdbc;
    private final GatePassSources sources;
    private final BranchScopeService scopes;
    private final SequenceService sequence;
    private final AuditService audit;
    private final SalesLetterhead letterhead;
    private final FleetDispatchService fleet;
    private final SalesHandoverService handovers;
    private final CashSaleService cashSales;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    public GatePassService(JdbcClient jdbc, GatePassSources sources, BranchScopeService scopes, SequenceService sequence,
            AuditService audit, SalesLetterhead letterhead, FleetDispatchService fleet, SalesHandoverService handovers,
            CashSaleService cashSales, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.scopes = scopes;
        this.sequence = sequence;
        this.audit = audit;
        this.letterhead = letterhead;
        this.fleet = fleet;
        this.handovers = handovers;
        this.cashSales = cashSales;
        this.tx = new TransactionTemplate(txm);
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String user() {
        return TenantContext.userId();
    }

    // ------------------------------------------------------------------ source view

    /**
     * What the issue dialog needs for one source: the source summary, warnings, transport prefill, per line the source
     * quantity, what is already on passes and what is left, and the passes issued so far.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> sourceView(String type, String id) {
        GatePassSources.Source src = sources.load(type, id);
        scopes.assertBranch(src.branchId());
        Map<String, BigDecimal> used = usedBySourceLine(type, id);
        Map<String, BigDecimal> left = remaining(src, used);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (GatePassSources.Line l : src.lines()) {
            lines.add(Obj.of("sourceLineId", l.id(), "productId", l.productId(), "variantId", l.variantId(),
                    "description", l.description(), "unit", l.unit(), "sourceQuantity", l.quantity(),
                    "onPasses", used.getOrDefault(l.id(), BigDecimal.ZERO), "remaining", left.get(l.id())));
        }
        return Obj.of("source", summary(src), "warnings", warnings(src), "prefill", src.prefill(), "lines", lines,
                "passes", rows("SELECT * FROM \"GatePass\" WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND"
                        + " \"sourceId\" = ? ORDER BY \"issuedAt\" DESC", org(), type, id).stream()
                        .map(GatePassService::withState).toList());
    }

    private static Map<String, Object> summary(GatePassSources.Source src) {
        return Obj.of("type", src.type(), "id", src.id(), "label", src.label(), "number", src.number(),
                "branchId", src.branchId(), "partnerId", src.partnerId(), "partnerName", src.partnerName(),
                "status", src.status(), "cancelled", src.cancelled(), "goodsIssued", src.goodsIssued(),
                "paymentState", src.paymentState());
    }

    static List<String> warnings(GatePassSources.Source src) {
        List<String> w = new ArrayList<>();
        if (!src.goodsIssued()) {
            w.add("goods_not_issued");
        }
        if ("unpaid".equals(src.paymentState())) {
            w.add("unpaid");
        } else if ("partial".equals(src.paymentState())) {
            w.add("partially_paid");
        }
        return w;
    }

    /** True when the live passes of the source now carry more of one of this pass's lines than the source holds. */
    private boolean exceedsSource(Map<String, Object> p, GatePassSources.Source src) {
        Map<String, BigDecimal> used = usedBySourceLine(s(p.get("sourceType")), s(p.get("sourceId")));
        Map<String, BigDecimal> current = new HashMap<>();
        src.lines().forEach(l -> current.put(l.id(), l.quantity()));
        for (Map<String, Object> l : rows("SELECT \"sourceLineId\" FROM \"GatePassLine\" WHERE \"gatePassId\" = ? AND"
                + " \"organizationId\" = ?", p.get("id"), org())) {
            String line = s(l.get("sourceLineId"));
            BigDecimal now = current.getOrDefault(line, BigDecimal.ZERO);
            if (used.getOrDefault(line, BigDecimal.ZERO).compareTo(now) > 0) {
                return true;
            }
        }
        return false;
    }

    private Map<String, BigDecimal> usedBySourceLine(String type, String id) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : rows("SELECT l.\"sourceLineId\", sum(l.quantity) AS q FROM \"GatePassLine\" l JOIN"
                + " \"GatePass\" p ON p.id = l.\"gatePassId\" WHERE p.\"organizationId\" = ? AND p.\"sourceType\" = ? AND"
                + " p.\"sourceId\" = ? AND p.status <> 'void' GROUP BY l.\"sourceLineId\"", org(), type, id)) {
            out.put(s(r.get("sourceLineId")), dec(r.get("q")));
        }
        return out;
    }

    /**
     * Per source line, what may still go on a pass: the line quantity less this source's passes, and never more than
     * what the root document (cash sale / order behind an invoice or delivery) has left for that product across the
     * passes of every related document. Shared per product, first lines first.
     */
    private Map<String, BigDecimal> remaining(GatePassSources.Source src, Map<String, BigDecimal> used) {
        Map<String, BigDecimal> familyLeft = new HashMap<>();
        List<GatePassSources.Line> rootLines = src.lines();
        if (!src.isRoot()) {
            try {
                rootLines = sources.load(src.rootType(), src.rootId()).lines();
            } catch (ApiException e) {
                rootLines = List.of();
            }
        }
        for (GatePassSources.Line l : rootLines) {
            if (l.productId() != null) {
                familyLeft.merge(l.productId(), l.quantity(), BigDecimal::add);
            }
        }
        for (Map<String, Object> r : rows("SELECT l.\"productId\", sum(l.quantity) AS q FROM \"GatePassLine\" l JOIN"
                + " \"GatePass\" p ON p.id = l.\"gatePassId\" WHERE p.\"organizationId\" = ? AND p.\"rootType\" = ? AND"
                + " p.\"rootId\" = ? AND p.status <> 'void' AND l.\"productId\" IS NOT NULL GROUP BY l.\"productId\"", org(),
                src.rootType(), src.rootId())) {
            familyLeft.computeIfPresent(s(r.get("productId")), (k, v) -> v.subtract(dec(r.get("q"))));
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (GatePassSources.Line l : src.lines()) {
            BigDecimal left = l.quantity().subtract(used.getOrDefault(l.id(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
            if (l.productId() != null && familyLeft.containsKey(l.productId())) {
                left = left.min(familyLeft.get(l.productId()).max(BigDecimal.ZERO));
                familyLeft.merge(l.productId(), left.negate(), BigDecimal::add);
            }
            out.put(l.id(), left);
        }
        return out;
    }

    // ------------------------------------------------------------------ issue

    /** Issue a gate pass (see the class rules). */
    @Transactional
    public Map<String, Object> issue(IssueInput in) {
        String type = trim(in.sourceType());
        String sourceId = trim(in.sourceId());
        if (sourceId == null) {
            throw ApiException.badRequest("sourceId should not be empty");
        }
        GatePassSources.Source src = sources.load(type, sourceId);
        // One issuer per root document at a time, so two passes (on the same or related documents) cannot both take
        // the last quantity. Quantities on passes are only read after the lock.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("gate_pass:" + org() + ":" + src.rootType() + ":"
                + src.rootId()).query().singleRow();
        scopes.assertBranch(src.branchId());
        if (src.cancelled()) {
            throw ApiException.badRequest("Cannot issue a gate pass for a cancelled " + src.label().toLowerCase(Locale.ROOT));
        }
        String bearerType = trim(in.bearerType());
        if (!BEARER_TYPES.contains(bearerType)) {
            throw ApiException.badRequest("bearerType must be one of: " + String.join(", ", BEARER_TYPES));
        }
        String bearerName = trim(in.bearerName());
        if (bearerName == null) {
            throw ApiException.badRequest("bearerName should not be empty");
        }
        String plate = plate(in.vehiclePlate());
        if ("driver".equals(bearerType) && plate == null) {
            throw ApiException.badRequest("vehiclePlate should not be empty for a driver");
        }
        int hours = in.validHours() == null ? DEFAULT_VALID_HOURS : in.validHours();
        if (hours < 1 || hours > MAX_VALID_HOURS) {
            throw ApiException.badRequest("validHours must be between 1 and " + MAX_VALID_HOURS);
        }
        Map<String, BigDecimal> left = remaining(src, usedBySourceLine(type, sourceId));
        Map<String, GatePassSources.Line> byId = new LinkedHashMap<>();
        src.lines().forEach(l -> byId.put(l.id(), l));
        List<Object[]> picked = new ArrayList<>();
        if (in.lines() == null) {
            for (GatePassSources.Line l : src.lines()) {
                if (left.get(l.id()).signum() > 0) {
                    picked.add(new Object[] {l, left.get(l.id())});
                }
            }
            if (picked.isEmpty()) {
                throw ApiException.badRequest(src.lines().isEmpty()
                        ? "This " + src.label().toLowerCase(Locale.ROOT) + " has no items to take out"
                        : "Every item of this " + src.label().toLowerCase(Locale.ROOT) + " is already on a gate pass");
            }
        } else {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (LineInput li : in.lines()) {
                GatePassSources.Line l = li == null ? null : byId.get(li.sourceLineId());
                if (l == null) {
                    throw ApiException.badRequest("Line " + (li == null ? null : li.sourceLineId()) + " is not on this "
                            + src.label().toLowerCase(Locale.ROOT));
                }
                if (!seen.add(l.id())) {
                    throw ApiException.badRequest("Line " + l.id() + " is listed twice");
                }
                if (li.quantity() == null || li.quantity().signum() <= 0) {
                    throw ApiException.badRequest("quantity must be greater than 0");
                }
                if (li.quantity().compareTo(left.get(l.id())) > 0) {
                    throw ApiException.badRequest("Quantity for " + l.description() + " exceeds what is left to pass out ("
                            + left.get(l.id()).stripTrailingZeros().toPlainString() + ")");
                }
                picked.add(new Object[] {l, li.quantity()});
            }
            if (picked.isEmpty()) {
                throw ApiException.badRequest("lines should not be empty");
            }
        }
        int year = LocalDate.now(BusinessZone.get()).getYear();
        String number = sequence.next("gate_pass:" + year, "GP-" + year + "-", 5);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<String> warnings = warnings(src);
        jdbc.sql("""
                INSERT INTO "GatePass" (id, "organizationId", "branchId", number, "sourceType", "sourceId", "sourceNumber",
                    "rootType", "rootId",
                    "partnerId", "partnerName", "bearerType", "bearerName", "bearerIdNumber", "bearerPhone", "vehiclePlate",
                    "vehicleType", "transportCompany", destination, purpose, remarks, "goodsIssued", "paymentState", warnings,
                    "validUntil", "verifyCode", "issuedAt", "issuedById", "createdAt", "updatedAt")
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, org(), src.branchId(), number, type, sourceId, src.number(), src.rootType(), src.rootId(),
                src.partnerId(),
                src.partnerName(), bearerType, bearerName, trim(in.bearerIdNumber()), trim(in.bearerPhone()), plate,
                trim(in.vehicleType()), trim(in.transportCompany()), firstNonNull(trim(in.destination()),
                        (String) src.prefill().get("destination")), trim(in.purpose()), trim(in.remarks()),
                src.goodsIssued(), src.paymentState(), warnings.toArray(String[]::new),
                SqlRows.ts(now.plus(Duration.ofHours(hours))), verifyCode(), SqlRows.ts(now), user(), SqlRows.ts(now),
                SqlRows.ts(now)).update();
        int n = 0;
        for (Object[] p : picked) {
            GatePassSources.Line l = (GatePassSources.Line) p[0];
            jdbc.sql("INSERT INTO \"GatePassLine\" (id, \"organizationId\", \"gatePassId\", \"lineNo\", \"sourceLineId\","
                    + " \"productId\", \"variantId\", description, quantity, unit, \"sourceQuantity\") VALUES (?, ?, ?, ?,"
                    + " ?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), id, ++n, l.id(), l.productId(),
                    l.variantId(), l.description(), p[1], l.unit(), l.quantity()).update();
        }
        event(id, "issued", null, Obj.of("warnings", warnings));
        audit.record(AuditInput.of("GatePass", id, "issue", null, Obj.of("number", number, "sourceType", type,
                "sourceId", sourceId, "bearerName", bearerName, "vehiclePlate", plate)));
        return get(id);
    }

    // ------------------------------------------------------------------ read

    /** Register: newest first; filters status (issued, used, void, expired), sourceType, sourceId, q, from, to. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(Map<String, String> q, PageQuery page) {
        StringBuilder where = new StringBuilder("p.\"organizationId\" = ?");
        List<Object> args = new ArrayList<>(List.of(org()));
        BranchScopeService.DataScope scope = scopes.scope();
        if (scope.restricted()) {
            where.append(" AND p.\"branchId\" = ANY(?::text[])");
            args.add(scope.branchArray());
        }
        String status = trim(q.get("status"));
        if (status != null) {
            switch (status) {
                case "expired" -> where.append(" AND p.status = 'issued' AND p.\"validUntil\" < now() AT TIME ZONE 'UTC'");
                case "issued" -> where.append(" AND p.status = 'issued' AND p.\"validUntil\" >= now() AT TIME ZONE 'UTC'");
                case "used", "void" -> {
                    where.append(" AND p.status = ?");
                    args.add(status);
                }
                default -> throw ApiException.badRequest("status must be one of: issued, used, void, expired");
            }
        }
        if (trim(q.get("sourceType")) != null) {
            where.append(" AND p.\"sourceType\" = ?");
            args.add(q.get("sourceType").trim());
        }
        if (trim(q.get("sourceId")) != null) {
            where.append(" AND p.\"sourceId\" = ?");
            args.add(q.get("sourceId").trim());
        }
        if (trim(q.get("branchId")) != null) {
            where.append(" AND p.\"branchId\" = ?");
            args.add(q.get("branchId").trim());
        }
        String search = firstNonNull(trim(q.get("q")), trim(page.search()));
        if (search != null) {
            where.append(" AND (p.number ILIKE ? OR p.\"bearerName\" ILIKE ? OR p.\"vehiclePlate\" ILIKE ? OR"
                    + " p.\"sourceNumber\" ILIKE ? OR p.\"partnerName\" ILIKE ?)");
            for (int i = 0; i < 5; i++) {
                args.add("%" + search + "%");
            }
        }
        for (String k : List.of("from", "to")) {
            String v = trim(q.get(k));
            if (v != null) {
                LocalDate d;
                try {
                    d = LocalDate.parse(v.substring(0, Math.min(10, v.length())));
                } catch (RuntimeException e) {
                    throw ApiException.badRequest(k + " must be a valid ISO 8601 date string");
                }
                where.append("from".equals(k) ? " AND p.\"issuedAt\" >= ?" : " AND p.\"issuedAt\" < ?");
                args.add(SqlRows.ts(("from".equals(k) ? d : d.plusDays(1)).atStartOfDay(BusinessZone.get()).toInstant()));
            }
        }
        long total = jdbc.sql("SELECT count(*) FROM \"GatePass\" p WHERE " + where).params(args).query(Long.class).single();
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(page.pageSizeOrDefault());
        pageArgs.add(page.offset());
        List<Map<String, Object>> data = rows("SELECT p.*, (SELECT count(*) FROM \"GatePassLine\" l WHERE l.\"gatePassId\""
                + " = p.id) AS \"lineCount\" FROM \"GatePass\" p WHERE " + where + " ORDER BY p.\"issuedAt\" DESC, p.number"
                + " DESC LIMIT ? OFFSET ?", pageArgs.toArray()).stream().map(GatePassService::withState).toList();
        return PageResult.of(data, page, total);
    }

    /** One pass with its lines, its events and the live state of the source. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> p = require(id, false);
        return detail(p);
    }

    /** Sales sources whose goods must be issued from stock before they pass the gate (R4d, DEL-5). */
    private static final java.util.Set<String> ISSUE_FIRST = java.util.Set.of("delivery", "sales_order", "invoice",
            "cash_sale");

    private Map<String, Object> detail(Map<String, Object> p) {
        Map<String, Object> out = withState(p);
        out.remove("verifyCode");
        String id = s(p.get("id"));
        out.put("lines", rows("SELECT * FROM \"GatePassLine\" WHERE \"gatePassId\" = ? AND \"organizationId\" = ? ORDER BY"
                + " \"lineNo\"", id, org()));
        out.put("events", rows("SELECT e.*, coalesce(nullif(trim(coalesce(u.\"firstName\", '') || ' ' ||"
                + " coalesce(u.\"lastName\", '')), ''), u.email) AS \"byName\" FROM \"GatePassEvent\" e LEFT JOIN \"User\" u"
                + " ON u.id = e.\"byId\" WHERE e.\"gatePassId\" = ? AND e.\"organizationId\" = ? ORDER BY e.at, e.id", id,
                org()));
        GatePassSources.Source src;
        try {
            src = sources.load(s(p.get("sourceType")), s(p.get("sourceId")));
        } catch (ApiException e) {
            src = null;
        }
        out.put("source", src == null ? null : summary(src));
        List<String> problems = new ArrayList<>();
        if ("void".equals(p.get("status"))) {
            problems.add("void");
        } else if ("used".equals(p.get("status"))) {
            problems.add("already_used");
        } else if (Boolean.TRUE.equals(out.get("expired"))) {
            problems.add("expired");
        }
        if (src == null) {
            problems.add("source_missing");
        } else if (src.cancelled()) {
            problems.add("source_cancelled");
        } else if (!src.goodsIssued() && ISSUE_FIRST.contains(s(p.get("sourceType")))) {
            // Sales readiness R4d (DEL-5): sold goods leave only after the stock issue (and its cost) is booked.
            problems.add("goods_not_issued");
        }
        if (src != null && !src.cancelled() && "issued".equals(p.get("status")) && exceedsSource(p, src)) {
            // Sales readiness DEL-11: the source was re-planned, re-weighed or partly returned since the pass was issued.
            problems.add("source_quantity_changed");
        }
        out.put("problems", problems);
        out.put("canLetOut", problems.isEmpty());
        out.put("liveWarnings", src == null ? List.of() : warnings(src));
        return out;
    }

    /** Adds {@code state} (issued, used, void, expired) and {@code expired}. */
    static Map<String, Object> withState(Map<String, Object> p) {
        Map<String, Object> out = new LinkedHashMap<>(p);
        out.remove("verifyCode");
        boolean expired = "issued".equals(p.get("status")) && p.get("validUntil") instanceof Instant until
                && until.isBefore(Instant.now());
        out.put("expired", expired);
        out.put("state", expired ? "expired" : p.get("status"));
        return out;
    }

    private Map<String, Object> require(String id, boolean lock) {
        List<Map<String, Object>> r = rows("SELECT * FROM \"GatePass\" WHERE id = ? AND \"organizationId\" = ?"
                + (lock ? " FOR UPDATE" : ""), id, org());
        if (r.isEmpty()) {
            throw ApiException.notFound("Gate pass not found");
        }
        scopes.assertBranch(s(r.get(0).get("branchId")));
        return r.get(0);
    }

    // ------------------------------------------------------------------ gate

    /**
     * Gate check: find a pass by its number or by the scanned QR text ({@code GP-2026-00001|ABC123}). With a code, the
     * code must match ({@code codeVerified}); typed numbers come back with {@code codeVerified} false.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> verify(String code) {
        String c = trim(code);
        if (c == null) {
            throw ApiException.badRequest("code should not be empty");
        }
        String number = c;
        String verify = null;
        int bar = c.indexOf('|');
        if (bar > 0) {
            number = c.substring(0, bar).trim();
            verify = c.substring(bar + 1).trim();
        }
        List<Map<String, Object>> r = rows("SELECT * FROM \"GatePass\" WHERE \"organizationId\" = ? AND upper(number) ="
                + " upper(?)", org(), number);
        if (r.isEmpty()) {
            throw ApiException.notFound("Gate pass not found");
        }
        Map<String, Object> p = r.get(0);
        scopes.assertBranch(s(p.get("branchId")));
        if (verify != null && !verify.equalsIgnoreCase(s(p.get("verifyCode")))) {
            throw ApiException.badRequest("Verification code does not match this gate pass");
        }
        Map<String, Object> out = detail(p);
        out.put("codeVerified", verify != null);
        return out;
    }

    /** Let the bearer out (see the class rules). Returns the pass and {@code sourceUpdate}. */
    public Map<String, Object> gateOut(String id, GateOutInput in) {
        Map<String, Object> pass = tx.execute(st -> {
            Map<String, Object> p = require(id, true);
            Map<String, Object> d = detail(p);
            List<?> problems = (List<?>) d.get("problems");
            if (problems.contains("void")) {
                throw ApiException.badRequest("Gate pass is void");
            }
            if (problems.contains("already_used")) {
                throw ApiException.conflict("Gate pass was already used at " + p.get("gateOutAt"));
            }
            if (problems.contains("expired")) {
                throw ApiException.badRequest("Gate pass expired at " + p.get("validUntil"));
            }
            if (problems.contains("source_cancelled") || problems.contains("source_missing")) {
                throw ApiException.badRequest("The source document was cancelled; the goods may not leave");
            }
            if (problems.contains("source_quantity_changed")) {
                throw ApiException.badRequest("The quantities of the source changed after this pass was issued; void it"
                        + " and issue a new pass");
            }
            if (problems.contains("goods_not_issued")) {
                throw ApiException.badRequest("The goods on this pass have not been issued from stock yet (dispatch the"
                        + " delivery, confirm the sale or post the invoice first); they may not leave");
            }
            String code = in == null ? null : trim(in.verifyCode());
            if (code != null && !code.equalsIgnoreCase(s(p.get("verifyCode")))) {
                throw ApiException.badRequest("Verification code does not match this gate pass");
            }
            String seen = in == null ? null : plate(in.plate());
            String onPass = s(p.get("vehiclePlate"));
            if (seen != null && onPass != null && !normalizePlate(seen).equals(normalizePlate(onPass))) {
                throw ApiException.badRequest("Vehicle plate " + seen + " does not match the gate pass (" + onPass + ")");
            }
            Instant now = Instant.now();
            jdbc.sql("UPDATE \"GatePass\" SET status = 'used', \"gateOutAt\" = ?, \"gateOutById\" = ?, \"gateOutPlate\" = ?,"
                    + " \"gateOutNote\" = ?, version = version + 1, \"updatedAt\" = ? WHERE id = ? AND \"organizationId\" = ?")
                    .params(SqlRows.ts(now), user(), seen, in == null ? null : trim(in.note()), SqlRows.ts(now), id, org())
                    .update();
            event(id, "gate_out", in == null ? null : trim(in.note()), Obj.of("plate", seen, "codeVerified",
                    code != null));
            audit.record(AuditInput.of("GatePass", id, "update", null, Obj.of("event", "gate_out", "plate", seen)));
            return p;
        });
        String result;
        try {
            result = newTx.execute(st -> markDeparted(pass));
        } catch (ApiException e) {
            result = "Not updated: " + e.getMessage();
        } catch (RuntimeException e) {
            result = "Not updated: " + e.getClass().getSimpleName();
        }
        String outcome = result;
        newTx.executeWithoutResult(st -> jdbc.sql("UPDATE \"GatePassEvent\" SET data = coalesce(data, '{}'::jsonb) ||"
                + " jsonb_build_object('sourceUpdate', ?::text) WHERE \"gatePassId\" = ? AND \"organizationId\" = ? AND"
                + " type = 'gate_out'").params(outcome, id, org()).update());
        Map<String, Object> out = tx.execute(st -> get(id));
        out.put("sourceUpdate", outcome);
        return out;
    }

    /** Mark the source as left through the gate where that step posts nothing; returns what was done. */
    private String markDeparted(Map<String, Object> p) {
        String type = s(p.get("sourceType"));
        String id = s(p.get("sourceId"));
        String plate = s(p.get("vehiclePlate"));
        String bearer = s(p.get("bearerName"));
        switch (type) {
            case "delivery" -> {
                String status = jdbc.sql("SELECT status FROM \"FleetLoad\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(id, org()).query(String.class).optional().orElse(null);
                if (!"dispatched".equals(status)) {
                    return "Not updated: the delivery is " + status + " (it departs once its stock is issued)";
                }
                fleet.depart(id);
                return "Delivery marked departed";
            }
            case "sales_order", "invoice" -> {
                Map<String, Object> cs = "invoice".equals(type) ? SqlRows.list(jdbc.sql("SELECT \"sourceId\" FROM"
                        + " \"Document\" WHERE id = ? AND \"organizationId\" = ? AND \"sourceType\" = 'cash_sale'")
                        .params(id, org())).stream().findFirst().orElse(null) : null;
                if (cs != null) {
                    return cashSaleDispatched(s(cs.get("sourceId")), p, plate, bearer);
                }
                List<Map<String, Object>> h = SqlRows.list(jdbc.sql("SELECT status, driver, vehicle FROM \"SalesHandover\""
                        + " WHERE \"organizationId\" = ? AND \"sourceType\" = ? AND \"sourceId\" = ?").params(org(), type, id));
                String status = h.isEmpty() ? "pending" : s(h.get(0).get("status"));
                if (!List.of("pending", "scheduled").contains(status)) {
                    return "Not updated: the handover is already " + status;
                }
                Map<String, Object> patch = new LinkedHashMap<>();
                patch.put("status", "dispatched");
                if (h.isEmpty() || h.get(0).get("driver") == null) {
                    patch.put("driver", bearer);
                }
                if (plate != null && (h.isEmpty() || h.get(0).get("vehicle") == null)) {
                    patch.put("vehicle", plate);
                }
                handovers.update(type, id, patch);
                return "Handover marked dispatched";
            }
            case "cash_sale" -> {
                return cashSaleDispatched(id, p, plate, bearer);
            }
            default -> {
                return "Recorded at the gate only (" + GatePassSources.LABELS.get(type).toLowerCase(Locale.ROOT)
                        + " has no separate departure step)";
            }
        }
    }

    private String cashSaleDispatched(String saleId, Map<String, Object> p, String plate, String bearer) {
        Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT \"deliveryStatus\", \"deliveryMethod\", \"deliveryDriver\","
                + " \"deliveryVehicle\" FROM \"SalesSubmission\" WHERE id = ? AND \"organizationId\" = ?").params(saleId, org()));
        String status = s.get("deliveryStatus") == null ? "pending" : s(s.get("deliveryStatus"));
        if (!List.of("pending", "scheduled").contains(status)) {
            return "Not updated: the cash sale delivery is already " + status;
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("deliveryStatus", "dispatched");
        if (s.get("deliveryMethod") == null) {
            // Nothing was planned: goods leaving with the bearer are a collection, a driver's load a delivery.
            patch.put("deliveryMethod", "driver".equals(p.get("bearerType")) ? "delivery" : "pickup");
            if ("driver".equals(p.get("bearerType")) && p.get("destination") != null) {
                patch.put("deliveryAddress", p.get("destination"));
            }
        }
        if (s.get("deliveryDriver") == null) {
            patch.put("deliveryDriver", bearer);
        }
        if (plate != null && s.get("deliveryVehicle") == null) {
            patch.put("deliveryVehicle", plate);
        }
        cashSales.updateDelivery(saleId, patch);
        return "Cash sale delivery marked dispatched";
    }

    /** Record that the guard refused to let the bearer out; the pass stays usable. */
    @Transactional
    public Map<String, Object> refuse(String id, String reason) {
        String r = trim(reason);
        if (r == null) {
            throw ApiException.badRequest("reason should not be empty");
        }
        Map<String, Object> p = require(id, true);
        if (!"issued".equals(p.get("status"))) {
            throw ApiException.badRequest("Only an unused gate pass can be refused at the gate");
        }
        event(id, "refused", r, null);
        audit.record(AuditInput.of("GatePass", id, "reject", null, Obj.of("event", "refused", "reason", r)));
        return get(id);
    }

    /** Void an unused pass; its quantities become available for a new pass. */
    @Transactional
    public Map<String, Object> voidPass(String id, String reason) {
        String r = trim(reason);
        if (r == null) {
            throw ApiException.badRequest("reason should not be empty");
        }
        Map<String, Object> p = require(id, true);
        if ("used".equals(p.get("status"))) {
            throw ApiException.badRequest("A gate pass that was used at the gate cannot be voided");
        }
        if ("void".equals(p.get("status"))) {
            throw ApiException.badRequest("Gate pass is already void");
        }
        Instant now = Instant.now();
        jdbc.sql("UPDATE \"GatePass\" SET status = 'void', \"voidedAt\" = ?, \"voidedById\" = ?, \"voidReason\" = ?,"
                + " version = version + 1, \"updatedAt\" = ? WHERE id = ? AND \"organizationId\" = ?").params(SqlRows.ts(now),
                user(), r, SqlRows.ts(now), id, org()).update();
        event(id, "voided", r, null);
        audit.record(AuditInput.of("GatePass", id, "cancel", null, Obj.of("event", "voided", "reason", r)));
        return get(id);
    }

    // ------------------------------------------------------------------ print

    /** The printable pass; every print is counted, reprints say COPY n. */
    @Transactional
    public GatePassPdf.Pdf pdf(String id) {
        Map<String, Object> p = require(id, true);
        int prints = ((Number) p.get("printCount")).intValue() + 1;
        jdbc.sql("UPDATE \"GatePass\" SET \"printCount\" = ? WHERE id = ? AND \"organizationId\" = ?").params(prints, id,
                org()).update();
        event(id, "printed", prints > 1 ? "COPY " + (prints - 1) : null, null);
        Map<String, Object> d = detail(p);
        d.put("printCount", prints);
        return GatePassPdf.render(letterhead.current(), d, s(p.get("verifyCode")),
                userName(s(p.get("issuedById"))), userName(s(p.get("gateOutById"))));
    }

    private String userName(String userId) {
        if (userId == null) {
            return null;
        }
        return jdbc.sql("SELECT coalesce(nullif(trim(coalesce(\"firstName\", '') || ' ' || coalesce(\"lastName\", '')), ''),"
                + " email) FROM \"User\" WHERE id = ?").param(userId).query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------------ helpers

    private void event(String passId, String type, String note, Map<String, Object> data) {
        jdbc.sql("INSERT INTO \"GatePassEvent\" (id, \"organizationId\", \"gatePassId\", type, at, \"byId\", note, data)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)").params(UUID.randomUUID().toString(), org(), passId, type,
                SqlRows.ts(Instant.now()), user(), note, data == null ? null
                        : com.inventoryerp.shared.web.EntityJson.toJson(data)).update();
    }

    private List<Map<String, Object>> rows(String sql, Object... params) {
        return SqlRows.list(jdbc.sql(sql).params(java.util.Arrays.asList(params)));
    }

    static String verifyCode() {
        StringBuilder b = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            b.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return b.toString();
    }

    static String plate(String v) {
        String t = trim(v);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }

    static String normalizePlate(String v) {
        return v.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
