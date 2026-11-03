package com.inventoryerp.costing.application;

import com.inventoryerp.shared.costing.AllocationCalculator;

import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingRequest.Line;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.shared.costing.AllocationCalculator.Receiver;
import com.inventoryerp.shared.costing.AllocationCalculator.Rounding;
import com.inventoryerp.shared.costing.AllocationCalculator.Share;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cost-centre allocation engine (Cost Management CM-3, ADR-S033, AD-5 / AD-8): assessment / distribution cycles that
 * move the period's cost of sender cost centres to receiver cost centres by a <em>measured</em> driver.
 *
 * <ul>
 *   <li><b>Sequential</b> (step-down): segments run in order; a later sender passes on what it received earlier in
 *       the same run. <b>Reciprocal</b>: service centres that serve each other are solved simultaneously
 *       ({@link AllocationCalculator#reciprocal}), per cost element.</li>
 *   <li><b>Financial</b> runs post one journal entry (cost element preserved: Cr the account on the sender, Dr the
 *       same account on each receiver), dated the period end, subject to the period lock. <b>Statistical</b> runs
 *       compute and keep the same lines with no journal entry — management reporting only.</li>
 *   <li>Every line keeps sender, source account, receiver, driver value, share and amount; one posted run per cycle
 *       and period (reverse it to run again).</li>
 * </ul>
 */
@Service
public class CostCycleService {

    /** Drivers; the hour / headcount / area drivers are measured through a statistical key. */
    public static final Set<String> BASES = Set.of("equal", "manual", "fixed_pct", "statistical_key", "posted_cost",
            "revenue", "tonnage", "distance_km", "tonne_km", "labour_hours", "machine_hours", "operating_hours",
            "headcount", "floor_area");

    private static final Set<String> KEYED = Set.of("statistical_key", "labour_hours", "machine_hours",
            "operating_hours", "headcount", "floor_area");

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{1,30}$");
    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();
    private static final String EXPENSE_CATEGORIES = "('operating_expense', 'cost_of_goods_sold', 'other_expense')";

    /** One segment of a cycle. */
    public record SegmentInput(String name, String senderCostCenterId, List<String> senderAccountIds,
            List<String> receiverIds, String basis, String statisticalKeyId, Map<String, BigDecimal> manualValues,
            BigDecimal sharePercent, String rounding) {}

    /** A cycle with its segments (in order). */
    public record CycleInput(String code, String name, String postingMode, String method, Boolean isActive,
            List<SegmentInput> segments) {}

    /** One statistical key value. */
    public record KeyValueInput(String receiverId, String periodFrom, String periodTo, BigDecimal value) {}

    /** One computed line. */
    record Computed(int seq, String sender, String account, String receiver, String basis, BigDecimal basisValue,
            BigDecimal share, BigDecimal amount) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;
    private final PostingService posting;
    private final AuditService audit;

    public CostCycleService(JdbcClient jdbc, SequenceService seq, PostingService posting, AuditService audit) {
        this.jdbc = jdbc;
        this.seq = seq;
        this.posting = posting;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ---- statistical keys -------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> keys() {
        List<Map<String, Object>> keys = SqlRows.list(jdbc.sql("SELECT * FROM \"StatisticalKey\" WHERE \"organizationId\""
                + " = ? ORDER BY code").param(org()));
        for (Map<String, Object> k : keys) {
            k.put("values", SqlRows.list(jdbc.sql("SELECT \"receiverId\", \"periodFrom\", \"periodTo\", value FROM"
                    + " \"StatisticalKeyValue\" WHERE \"keyId\" = ? ORDER BY \"periodFrom\" DESC, \"receiverId\"")
                    .param(k.get("id"))));
        }
        return keys;
    }

    @Transactional
    public Map<String, Object> createKey(String code, String name, String unit) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw ApiException.badRequest("code must be 1 to 30 letters, digits, '-' or '_'");
        }
        if (jdbc.sql("SELECT count(*) FROM \"StatisticalKey\" WHERE \"organizationId\" = ? AND code = ?").params(org(),
                code).query(Long.class).single() > 0) {
            throw ApiException.conflict("A statistical key with code '" + code + "' already exists");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"StatisticalKey\" (id, \"organizationId\", code, name, unit) VALUES (?, ?, ?, ?, ?)")
                .params(id, org(), code, name == null || name.isBlank() ? code : name.trim(), unit).update();
        audit.recordInTx(AuditInput.of("StatisticalKey", id, "create", null, Obj.of("code", code)));
        return key(id);
    }

    /** Replace the key's values for the periods given (one value per receiver and period). */
    @Transactional
    public Map<String, Object> setKeyValues(String keyId, List<KeyValueInput> values) {
        key(keyId);
        for (KeyValueInput v : values) {
            costCenter(v.receiverId());
            LocalDate from = day(v.periodFrom(), "periodFrom");
            LocalDate to = day(v.periodTo(), "periodTo");
            if (to.isBefore(from)) {
                throw ApiException.badRequest("periodTo must not be before periodFrom");
            }
            if (v.value() == null || v.value().signum() < 0) {
                throw ApiException.badRequest("value must not be less than 0");
            }
            jdbc.sql("DELETE FROM \"StatisticalKeyValue\" WHERE \"keyId\" = ? AND \"receiverId\" = ? AND \"periodFrom\" = ?"
                    + " AND \"periodTo\" = ?").params(keyId, v.receiverId(), Date.valueOf(from), Date.valueOf(to)).update();
            jdbc.sql("INSERT INTO \"StatisticalKeyValue\" (id, \"organizationId\", \"keyId\", \"receiverId\","
                            + " \"periodFrom\", \"periodTo\", value) VALUES (?, ?, ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), keyId, v.receiverId(), Date.valueOf(from),
                            Date.valueOf(to), v.value())
                    .update();
        }
        audit.recordInTx(AuditInput.of("StatisticalKey", keyId, "update", null, Obj.of("values", values.size())));
        return key(keyId);
    }

    private Map<String, Object> key(String id) {
        Map<String, Object> k = SqlRows.one(jdbc.sql("SELECT * FROM \"StatisticalKey\" WHERE id = ? AND \"organizationId\""
                + " = ?").params(id, org()));
        if (k == null) {
            throw ApiException.notFound("Statistical key not found");
        }
        return k;
    }

    // ---- cycles -----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Map<String, Object>> cycles() {
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT * FROM \"AllocationCycle\" WHERE"
                + " \"organizationId\" = ? ORDER BY code").param(org()));
        for (Map<String, Object> c : rows) {
            c.put("segments", segments((String) c.get("id")));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> cycle(String id) {
        Map<String, Object> c = cycleRow(id);
        c.put("segments", segments(id));
        return c;
    }

    private Map<String, Object> cycleRow(String id) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"AllocationCycle\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (c == null) {
            throw ApiException.notFound("Allocation cycle not found");
        }
        return c;
    }

    private List<Map<String, Object>> segments(String cycleId) {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"AllocationSegment\" WHERE \"cycleId\" = ? ORDER BY seq")
                .param(cycleId));
    }

    @Transactional
    public Map<String, Object> createCycle(CycleInput in) {
        if (in.code() == null || !CODE.matcher(in.code()).matches()) {
            throw ApiException.badRequest("code must be 1 to 30 letters, digits, '-' or '_'");
        }
        if (jdbc.sql("SELECT count(*) FROM \"AllocationCycle\" WHERE \"organizationId\" = ? AND code = ?").params(org(),
                in.code()).query(Long.class).single() > 0) {
            throw ApiException.conflict("An allocation cycle with code '" + in.code() + "' already exists");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"AllocationCycle\" (id, \"organizationId\", code, name, \"postingMode\", method,"
                        + " \"isActive\", \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), in.code(), name(in.name()), mode(in.postingMode()), method(in.method()),
                        in.isActive() == null || in.isActive(), TenantContext.userId())
                .update();
        writeSegments(id, in.segments());
        audit.recordInTx(AuditInput.of("AllocationCycle", id, "create", null, Obj.of("code", in.code())));
        return cycle(id);
    }

    /** Update a cycle; segments, when given, replace the existing ones. */
    @Transactional
    public Map<String, Object> updateCycle(String id, CycleInput in) {
        Map<String, Object> before = cycleRow(id);
        jdbc.sql("UPDATE \"AllocationCycle\" SET name = ?, \"postingMode\" = ?, method = ?, \"isActive\" = ?,"
                        + " \"updatedAt\" = now() WHERE id = ?")
                .params(in.name() == null ? before.get("name") : name(in.name()),
                        in.postingMode() == null ? before.get("postingMode") : mode(in.postingMode()),
                        in.method() == null ? before.get("method") : method(in.method()),
                        in.isActive() == null ? before.get("isActive") : in.isActive(), id)
                .update();
        if (in.segments() != null) {
            jdbc.sql("DELETE FROM \"AllocationSegment\" WHERE \"cycleId\" = ?").param(id).update();
            writeSegments(id, in.segments());
        }
        audit.recordInTx(AuditInput.of("AllocationCycle", id, "update", before, null));
        return cycle(id);
    }

    private void writeSegments(String cycleId, List<SegmentInput> segments) {
        if (segments == null || segments.isEmpty()) {
            throw ApiException.badRequest("Add at least one segment");
        }
        int n = 1;
        for (SegmentInput s : segments) {
            costCenter(s.senderCostCenterId());
            if (s.receiverIds() == null || s.receiverIds().isEmpty()) {
                throw ApiException.badRequest("Segment " + n + " needs at least one receiver");
            }
            for (String r : s.receiverIds()) {
                costCenter(r);
                if (r.equals(s.senderCostCenterId())) {
                    throw ApiException.badRequest("Segment " + n + ": a cost centre cannot allocate to itself");
                }
            }
            if (s.basis() == null || !BASES.contains(s.basis())) {
                throw ApiException.badRequest("basis must be one of the following values: "
                        + String.join(", ", BASES.stream().sorted().toList()));
            }
            if (KEYED.contains(s.basis())) {
                if (s.statisticalKeyId() == null) {
                    throw ApiException.badRequest("Segment " + n + ": basis " + s.basis() + " is measured through a"
                            + " statistical key; choose one");
                }
                key(s.statisticalKeyId());
            }
            if (("manual".equals(s.basis()) || "fixed_pct".equals(s.basis()))
                    && (s.manualValues() == null || !s.manualValues().keySet().containsAll(s.receiverIds()))) {
                throw ApiException.badRequest("Segment " + n + ": give a " + s.basis().replace('_', ' ')
                        + " value for every receiver");
            }
            List<String> accounts = s.senderAccountIds() == null ? List.of() : s.senderAccountIds();
            for (String a : accounts) {
                if (jdbc.sql("SELECT count(*) FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                        + " WHERE a.id = ? AND a.\"organizationId\" = ? AND c.key IN " + EXPENSE_CATEGORIES)
                        .params(a, org()).query(Long.class).single() == 0) {
                    throw ApiException.badRequest("Segment " + n + ": sender accounts must be expense or cost-of-sales"
                            + " accounts");
                }
            }
            BigDecimal pct = s.sharePercent() == null ? new BigDecimal("100") : s.sharePercent();
            if (pct.signum() <= 0 || pct.compareTo(new BigDecimal("100")) > 0) {
                throw ApiException.badRequest("sharePercent must be greater than 0 and at most 100");
            }
            Rounding.of(s.rounding());
            jdbc.sql("INSERT INTO \"AllocationSegment\" (id, \"organizationId\", \"cycleId\", seq, name,"
                            + " \"senderCostCenterId\", \"senderAccountIds\", \"receiverIds\", basis, \"statisticalKeyId\","
                            + " \"manualValues\", \"sharePercent\", rounding) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                            + " ?::jsonb, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), cycleId, n, s.name() == null || s.name().isBlank()
                                    ? "Segment " + n : s.name().trim(), s.senderCostCenterId(),
                            accounts.toArray(String[]::new), s.receiverIds().toArray(String[]::new), s.basis(),
                            KEYED.contains(s.basis()) ? s.statisticalKeyId() : null,
                            s.manualValues() == null ? null : EntityJson.toJson(s.manualValues()), pct,
                            s.rounding() == null ? "largest_remainder" : s.rounding())
                    .update();
            n++;
        }
    }

    // ---- runs -------------------------------------------------------------------------------------------------------

    /** Compute without writing anything. */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String cycleId, String from, String to) {
        Map<String, Object> c = cycleRow(cycleId);
        LocalDate f = day(from, "from");
        LocalDate t = day(to, "to");
        period(f, t);
        List<Computed> lines = compute(c, f, t);
        return Obj.of("cycleId", cycleId, "periodFrom", f.toString(), "periodTo", t.toString(), "postingMode",
                c.get("postingMode"), "method", c.get("method"), "total", total(lines), "lines", render(lines));
    }

    /** Run the cycle for a period: lines kept; a financial run posts one journal entry. */
    @Transactional
    public Map<String, Object> run(String cycleId, String from, String to) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"AllocationCycle\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(cycleId, org()));
        if (c == null) {
            throw ApiException.notFound("Allocation cycle not found");
        }
        if (!Boolean.TRUE.equals(c.get("isActive"))) {
            throw ApiException.badRequest("The allocation cycle is inactive");
        }
        LocalDate f = day(from, "from");
        LocalDate t = day(to, "to");
        period(f, t);
        if (jdbc.sql("SELECT count(*) FROM \"AllocationRun\" WHERE \"cycleId\" = ? AND \"periodFrom\" = ? AND"
                + " \"periodTo\" = ? AND status = 'posted'").params(cycleId, Date.valueOf(f), Date.valueOf(t))
                .query(Long.class).single() > 0) {
            throw ApiException.conflict("This cycle already has a posted run for " + f + " – " + t
                    + "; reverse it first");
        }
        List<Computed> lines = compute(c, f, t);
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Nothing to allocate: the senders have no cost in the period");
        }
        String id = UUID.randomUUID().toString();
        String number = seq.next("cost_allocation", "CAR/", 5);
        boolean financial = "financial".equals(c.get("postingMode"));
        BigDecimal total = total(lines);
        jdbc.sql("INSERT INTO \"AllocationRun\" (id, \"organizationId\", number, \"cycleId\", \"periodFrom\","
                        + " \"periodTo\", \"postingMode\", method, total, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), number, cycleId, Date.valueOf(f), Date.valueOf(t), c.get("postingMode"),
                        c.get("method"), total, TenantContext.userId())
                .update();
        String entryId = null;
        if (financial) {
            entryId = postRun(id, number, c, t, lines);
            jdbc.sql("UPDATE \"AllocationRun\" SET \"journalEntryId\" = ? WHERE id = ?").params(entryId, id).update();
        }
        for (Computed l : lines) {
            jdbc.sql("INSERT INTO \"AllocationLine\" (id, \"organizationId\", \"runId\", \"segmentSeq\","
                            + " \"senderCostCenterId\", \"sourceAccountId\", \"receiverType\", \"receiverId\", basis,"
                            + " \"basisValue\", share, amount, \"journalEntryId\") VALUES (?, ?, ?, ?, ?, ?, 'cost_center', ?,"
                            + " ?, ?, ?, ?, ?)")
                    .params(UUID.randomUUID().toString(), org(), id, l.seq(), l.sender(), l.account(), l.receiver(),
                            l.basis(), l.basisValue(), l.share(), l.amount(), entryId)
                    .update();
        }
        audit.recordInTx(AuditInput.of("AllocationRun", id, "post", null, Obj.of("number", number, "cycle",
                c.get("code"), "total", total, "journalEntryId", entryId)));
        return getRun(id);
    }

    private String postRun(String runId, String number, Map<String, Object> c, LocalDate to, List<Computed> lines) {
        String text = "Allocation " + number + " · " + c.get("code");
        Map<String, BigDecimal> credits = new LinkedHashMap<>();
        List<Line> out = new ArrayList<>();
        for (Computed l : lines) {
            if (l.amount().signum() == 0) {
                continue;
            }
            out.add(amountLine(l.account(), l.amount(), l.receiver(), text));
            credits.merge(l.account() + "|" + l.sender(), l.amount(), BigDecimal::add);
        }
        credits.forEach((k, v) -> {
            String[] p = k.split("\\|");
            out.add(amountLine(p[0], v.negate(), p[1], text));
        });
        Map<String, Object> je = posting.post(new PostingRequest("GEN", to.atStartOfDay(ZoneOffset.UTC).toInstant(),
                text, null, null, "cost_allocation", runId, null, "costing:allocation:" + org() + ":" + runId, null,
                null, null, out));
        return je == null ? null : (String) je.get("id");
    }

    /** Debit for a positive amount, credit for a negative one, on the given cost centre. */
    private static Line amountLine(String account, BigDecimal amount, String costCenter, String text) {
        String v = JsNumbers.dec(amount.abs());
        return amount.signum() >= 0 ? new Line(account, v, null, null, text, null, costCenter, null)
                : new Line(account, null, v, null, text, null, costCenter, null);
    }

    /** Reverse a posted run (mirrored entry for a financial run); the cycle can then run again for the period. */
    @Transactional
    public Map<String, Object> reverse(String runId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"AllocationRun\" WHERE id = ? AND \"organizationId\""
                + " = ? FOR UPDATE").params(runId, org()));
        if (r == null) {
            throw ApiException.notFound("Allocation run not found");
        }
        if (!"posted".equals(r.get("status"))) {
            throw ApiException.badRequest("Only a posted run can be reversed");
        }
        String reversal = null;
        if (r.get("journalEntryId") != null) {
            Map<String, Object> rev = posting.reverse((String) r.get("journalEntryId"), java.time.Instant.now(),
                    "Reversal of allocation " + r.get("number"));
            reversal = (String) rev.get("id");
        }
        jdbc.sql("UPDATE \"AllocationRun\" SET status = 'reversed', \"reversalEntryId\" = ?, \"reversedAt\" = now() WHERE"
                + " id = ?").params(reversal, runId).update();
        audit.recordInTx(AuditInput.of("AllocationRun", runId, "cancel", null, Obj.of("reversalEntryId", reversal)));
        return getRun(runId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs(String cycleId) {
        return cycleId == null || cycleId.isBlank()
                ? SqlRows.list(jdbc.sql("SELECT r.*, c.code AS \"cycleCode\" FROM \"AllocationRun\" r JOIN"
                        + " \"AllocationCycle\" c ON c.id = r.\"cycleId\" WHERE r.\"organizationId\" = ? ORDER BY"
                        + " r.\"createdAt\" DESC").param(org()))
                : SqlRows.list(jdbc.sql("SELECT r.*, c.code AS \"cycleCode\" FROM \"AllocationRun\" r JOIN"
                        + " \"AllocationCycle\" c ON c.id = r.\"cycleId\" WHERE r.\"organizationId\" = ? AND r.\"cycleId\""
                        + " = ? ORDER BY r.\"createdAt\" DESC").params(org(), cycleId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getRun(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"AllocationRun\" WHERE id = ? AND \"organizationId\""
                + " = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Allocation run not found");
        }
        r.put("lines", SqlRows.list(jdbc.sql("SELECT l.*, s.code AS \"senderCode\", v.code AS \"receiverCode\","
                + " a.code AS \"accountCode\" FROM \"AllocationLine\" l LEFT JOIN \"CostCenter\" s ON s.id ="
                + " l.\"senderCostCenterId\" LEFT JOIN \"CostCenter\" v ON v.id = l.\"receiverId\" LEFT JOIN \"Account\" a"
                + " ON a.id = l.\"sourceAccountId\" WHERE l.\"runId\" = ? ORDER BY l.\"segmentSeq\", a.code, v.code")
                .param(id)));
        return r;
    }

    // ---- computation ------------------------------------------------------------------------------------------------

    List<Computed> compute(Map<String, Object> cycle, LocalDate from, LocalDate to) {
        List<Map<String, Object>> segs = segments((String) cycle.get("id"));
        if (segs.isEmpty()) {
            throw ApiException.badRequest("The allocation cycle has no segments");
        }
        Timestamp start = Timestamp.valueOf(from.atStartOfDay());
        Timestamp end = Timestamp.valueOf(to.plusDays(1).atStartOfDay());
        Map<Integer, Map<String, BigDecimal>> bases = new LinkedHashMap<>();
        for (Map<String, Object> s : segs) {
            bases.put(((Number) s.get("seq")).intValue(), driver(s, from, to, start, end));
        }
        return "reciprocal".equals(cycle.get("method")) ? reciprocal(segs, bases, start, end)
                : sequential(segs, bases, start, end);
    }

    private List<Computed> sequential(List<Map<String, Object>> segs, Map<Integer, Map<String, BigDecimal>> bases,
            Timestamp start, Timestamp end) {
        Map<String, BigDecimal> moved = new LinkedHashMap<>(); // (cc|account) → received − sent in this run
        List<Computed> out = new ArrayList<>();
        for (Map<String, Object> s : segs) {
            int n = ((Number) s.get("seq")).intValue();
            String sender = (String) s.get("senderCostCenterId");
            BigDecimal pct = SqlRows.dec(s, "sharePercent");
            Map<String, BigDecimal> pools = senderPools(s, start, end);
            moved.forEach((k, v) -> {
                String[] p = k.split("\\|");
                if (p[0].equals(sender)) {
                    pools.merge(p[1], v, BigDecimal::add);
                }
            });
            List<Receiver> receivers = receivers(s, bases.get(n));
            Rounding rounding = Rounding.of((String) s.get("rounding"));
            for (Map.Entry<String, BigDecimal> pool : pools.entrySet()) {
                BigDecimal amount = pool.getValue().multiply(pct, MC).divide(new BigDecimal("100"), 2,
                        RoundingMode.HALF_UP);
                if (amount.signum() == 0) {
                    continue;
                }
                for (Share sh : AllocationCalculator.allocate(amount, receivers, rounding, 2)) {
                    out.add(new Computed(n, sender, pool.getKey(), sh.key(), (String) s.get("basis"), sh.basis(),
                            sh.fraction(), sh.amount()));
                    moved.merge(sh.key() + "|" + pool.getKey(), sh.amount(), BigDecimal::add);
                }
                moved.merge(sender + "|" + pool.getKey(), amount.negate(), BigDecimal::add);
            }
        }
        return out;
    }

    /**
     * Reciprocal: per cost element, the service centres' totals {@code T = D + F·T} are solved simultaneously; then each
     * sender allocates its total to all its receivers by the driver. Service centres end the run at zero.
     */
    private List<Computed> reciprocal(List<Map<String, Object>> segs, Map<Integer, Map<String, BigDecimal>> bases,
            Timestamp start, Timestamp end) {
        Map<String, Map<String, Object>> bySender = new LinkedHashMap<>();
        for (Map<String, Object> s : segs) {
            if (bySender.put((String) s.get("senderCostCenterId"), s) != null) {
                throw ApiException.badRequest("A reciprocal cycle needs exactly one segment per sender cost centre");
            }
            if (SqlRows.dec(s, "sharePercent").compareTo(new BigDecimal("100")) != 0) {
                throw ApiException.badRequest("A reciprocal cycle allocates 100 % of each sender");
            }
        }
        Map<String, Map<String, BigDecimal>> direct = new LinkedHashMap<>(); // account → sender → D
        Map<String, Map<String, BigDecimal>> fractions = new LinkedHashMap<>(); // sender → receiver → f
        Map<String, List<Receiver>> receivers = new LinkedHashMap<>();
        for (Map<String, Object> s : segs) {
            String sender = (String) s.get("senderCostCenterId");
            senderPools(s, start, end).forEach((account, v) -> direct.computeIfAbsent(account,
                    k -> new LinkedHashMap<>()).put(sender, v));
            List<Receiver> rs = receivers(s, bases.get(((Number) s.get("seq")).intValue()));
            receivers.put(sender, rs);
            BigDecimal sum = rs.stream().map(Receiver::basis).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, BigDecimal> f = new LinkedHashMap<>();
            for (Receiver r : rs) {
                f.put(r.key(), sum.signum() == 0 ? BigDecimal.ZERO : r.basis().divide(sum, MC));
            }
            fractions.put(sender, f);
        }
        List<Computed> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal>> acc : direct.entrySet()) {
            Map<String, BigDecimal> d = new LinkedHashMap<>();
            for (String sender : bySender.keySet()) {
                d.put(sender, acc.getValue().getOrDefault(sender, BigDecimal.ZERO));
            }
            Map<String, BigDecimal> totals = AllocationCalculator.reciprocal(d, fractions);
            for (Map.Entry<String, BigDecimal> t : totals.entrySet()) {
                BigDecimal amount = t.getValue().setScale(2, RoundingMode.HALF_UP);
                if (amount.signum() == 0) {
                    continue;
                }
                Map<String, Object> s = bySender.get(t.getKey());
                for (Share sh : AllocationCalculator.allocate(amount, receivers.get(t.getKey()), Rounding.of(
                        (String) s.get("rounding")), 2)) {
                    out.add(new Computed(((Number) s.get("seq")).intValue(), t.getKey(), acc.getKey(), sh.key(),
                            (String) s.get("basis"), sh.basis(), sh.fraction(), sh.amount()));
                }
            }
        }
        return out;
    }

    /** The sender's posted cost per account in the period (its own cost-centre lines). */
    private Map<String, BigDecimal> senderPools(Map<String, Object> s, Timestamp start, Timestamp end) {
        List<String> accounts = strings(s.get("senderAccountIds"));
        String accountFilter = accounts.isEmpty()
                ? " AND l.\"accountId\" IN (SELECT a.id FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id ="
                        + " a.\"categoryId\" WHERE a.\"organizationId\" = l.\"organizationId\" AND c.key IN "
                        + EXPENSE_CATEGORIES + ")"
                : " AND l.\"accountId\" = ANY (?)";
        List<Object> params = new ArrayList<>(List.of(org(), s.get("senderCostCenterId"), start, end));
        if (!accounts.isEmpty()) {
            params.add(accounts.toArray(String[]::new));
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT l.\"accountId\" AS a, sum(l.\"baseDebit\" -"
                + " l.\"baseCredit\") AS v FROM \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\""
                + " WHERE l.\"organizationId\" = ? AND l.\"costCenterId\" = ? AND e.status::text IN ('posted', 'reversed')"
                + " AND e.\"postingDate\" >= ? AND e.\"postingDate\" < ?" + accountFilter
                + " GROUP BY 1 ORDER BY 1").params(params))) {
            BigDecimal v = SqlRows.dec(r, "v");
            if (v.signum() != 0) {
                out.put((String) r.get("a"), v);
            }
        }
        return out;
    }

    private static List<Receiver> receivers(Map<String, Object> s, Map<String, BigDecimal> basis) {
        List<Receiver> out = new ArrayList<>();
        for (String r : strings(s.get("receiverIds"))) {
            out.add(new Receiver(r, basis.getOrDefault(r, BigDecimal.ZERO)));
        }
        return out;
    }

    /** Driver value per receiver for one segment (measured, never assumed). */
    Map<String, BigDecimal> driver(Map<String, Object> s, LocalDate from, LocalDate to, Timestamp start,
            Timestamp end) {
        List<String> receivers = strings(s.get("receiverIds"));
        String basis = (String) s.get("basis");
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        String[] ids = receivers.toArray(String[]::new);
        switch (basis) {
            case "equal" -> receivers.forEach(r -> out.put(r, BigDecimal.ONE));
            case "manual", "fixed_pct" -> {
                Map<?, ?> m = s.get("manualValues") instanceof Map<?, ?> mm ? mm
                        : JSON.readValue(String.valueOf(s.get("manualValues")), Map.class);
                for (String r : receivers) {
                    Object v = m.get(r);
                    out.put(r, v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()));
                }
            }
            case "posted_cost", "revenue" -> {
                String sign = "posted_cost".equals(basis) ? "l.\"baseDebit\" - l.\"baseCredit\""
                        : "l.\"baseCredit\" - l.\"baseDebit\"";
                String cats = "posted_cost".equals(basis) ? EXPENSE_CATEGORIES : "('revenue')";
                fill(out, receivers, SqlRows.list(jdbc.sql("SELECT l.\"costCenterId\" AS k, sum(" + sign + ") AS v FROM"
                        + " \"JournalLine\" l JOIN \"JournalEntry\" e ON e.id = l.\"journalEntryId\" JOIN \"Account\" a ON"
                        + " a.id = l.\"accountId\" JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE"
                        + " l.\"organizationId\" = ? AND l.\"costCenterId\" = ANY (?) AND e.status::text IN ('posted',"
                        + " 'reversed') AND e.\"sourceType\" IS DISTINCT FROM 'cost_allocation' AND e.\"postingDate\" >= ?"
                        + " AND e.\"postingDate\" < ? AND c.key IN " + cats + " GROUP BY 1")
                        .params(org(), ids, start, end)));
            }
            case "tonnage", "distance_km", "tonne_km" -> {
                String expr = switch (basis) {
                    case "tonnage" -> "coalesce(\"loadedTonnes\", 0)";
                    case "distance_km" -> "coalesce(\"distanceKm\", 0)";
                    default -> "coalesce(\"loadedTonnes\", 0) * coalesce(\"distanceKm\", 0)";
                };
                fill(out, receivers, SqlRows.list(jdbc.sql("SELECT \"costCenterId\" AS k, sum(" + expr + ") AS v FROM"
                        + " \"FleetLoad\" WHERE \"organizationId\" = ? AND \"costCenterId\" = ANY (?) AND"
                        + " \"dispatchedAt\" >= ? AND \"dispatchedAt\" < ? GROUP BY 1").params(org(), ids, start, end)));
            }
            default -> fill(out, receivers, SqlRows.list(jdbc.sql("SELECT \"receiverId\" AS k, sum(value) AS v FROM"
                    + " \"StatisticalKeyValue\" WHERE \"keyId\" = ? AND \"receiverId\" = ANY (?) AND \"periodFrom\" <= ?"
                    + " AND \"periodTo\" >= ? GROUP BY 1").params(s.get("statisticalKeyId"), ids, Date.valueOf(to),
                            Date.valueOf(from))));
        }
        for (Map.Entry<String, BigDecimal> e : out.entrySet()) {
            if (e.getValue().signum() < 0) {
                e.setValue(BigDecimal.ZERO);
            }
        }
        return out;
    }

    private static void fill(Map<String, BigDecimal> out, List<String> receivers, List<Map<String, Object>> rows) {
        receivers.forEach(r -> out.put(r, BigDecimal.ZERO));
        for (Map<String, Object> r : rows) {
            if (r.get("k") != null) {
                out.put((String) r.get("k"), SqlRows.dec(r, "v"));
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private static BigDecimal total(List<Computed> lines) {
        return lines.stream().map(Computed::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private List<Map<String, Object>> render(List<Computed> lines) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Computed l : lines) {
            out.add(Obj.of("segmentSeq", l.seq(), "senderCostCenterId", l.sender(), "sourceAccountId", l.account(),
                    "receiverId", l.receiver(), "basis", l.basis(), "basisValue", l.basisValue(), "share", l.share(),
                    "amount", l.amount()));
        }
        return out;
    }

    private void costCenter(String id) {
        if (id == null || jdbc.sql("SELECT count(*) FROM \"CostCenter\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(id, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Cost centre not found");
        }
    }

    private static void period(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("to must not be before from");
        }
        if (to.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw ApiException.badRequest("A run cannot end in the future");
        }
    }

    private static String name(String v) {
        if (v == null || v.isBlank()) {
            throw ApiException.badRequest("name should not be empty");
        }
        return v.trim();
    }

    private static String mode(String v) {
        if (v == null) {
            return "financial";
        }
        if (!"financial".equals(v) && !"statistical".equals(v)) {
            throw ApiException.badRequest("postingMode must be one of the following values: financial, statistical");
        }
        return v;
    }

    private static String method(String v) {
        if (v == null) {
            return "sequential";
        }
        if (!"sequential".equals(v) && !"reciprocal".equals(v)) {
            throw ApiException.badRequest("method must be one of the following values: sequential, reciprocal");
        }
        return v;
    }

    static LocalDate day(String raw, String name) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest(name + " should not be empty");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(name + " must be a valid ISO 8601 date string");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            return (List<String>) l;
        }
        if (v instanceof String[] a) {
            return List.of(a);
        }
        if (v instanceof java.sql.Array arr) {
            try {
                return List.of((String[]) arr.getArray());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(List.of(v.toString())));
    }
}
