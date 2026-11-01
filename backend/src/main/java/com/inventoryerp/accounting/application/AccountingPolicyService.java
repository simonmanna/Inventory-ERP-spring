package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governed accounting policy elections and cost eligibility (Cost Management CM-2, ADR-S032, AD-4 / AD-10).
 *
 * <p>An election is a choice an IFRS standard permits — never a free switch. Each one has a fixed catalogue of allowed
 * values; a proposal needs an IAS 8 justification and an effective date not in the past (prospective), and is approved
 * by someone other than its proposer (maker-checker). Choices the standards forbid cannot be expressed: LIFO (IAS 2.25),
 * a stripping-activity asset without its IFRIC 20 assessment, over-time revenue without the IFRS 15 criteria.
 *
 * <p>Cost eligibility (election {@code cost_eligibility} scoped to an account, cost component or cost pool) decides
 * what may ever be capitalised into inventory: nothing unclassified is assumed capitalisable.
 */
@Service
public class AccountingPolicyService {

    /** Eligibility classes that may be capitalised into inventory (IAS 2.10–2.15). */
    public static final Set<String> CAPITALISABLE = Set.of("production_capitalisable", "acquisition_capitalisable",
            "transfer_capitalisable");

    /** Every eligibility class (AD-10). */
    public static final List<String> ELIGIBILITY = List.of("production_capitalisable", "production_abnormal",
            "acquisition_capitalisable", "transfer_capitalisable", "distribution", "selling", "admin", "storage",
            "financing");

    /** One election of the catalogue. */
    public record Election(String key, String label, String standard, List<String> scopeTypes, List<String> values,
            BigDecimal min, BigDecimal max, String defaultValue, String note) {

        boolean numeric() {
            return values == null;
        }
    }

    /** A proposal. */
    public record ProposalInput(String election, String scopeType, String scopeId, String value, String effectiveFrom,
            String justification) {}

    private static final Map<String, Election> CATALOGUE = new LinkedHashMap<>();

    static {
        put(new Election("variance_posting", "Production variance posting", "IAS 1 (presentation)",
                List.of("organization"), List.of("split", "summary"), null, null, "split",
                "split = price, usage, labour, overhead and yield accounts; summary = one variance account."));
        put(new Election("price_difference_tolerance_pct", "Standard-cost price-difference tolerance (%)", "IAS 2.21",
                List.of("organization"), null, BigDecimal.ZERO, new BigDecimal("50"), "5",
                "A layer whose price difference exceeds this share of the standard raises a standard-review flag."));
        put(new Election("materiality_threshold", "Costing materiality threshold", "IAS 8.8",
                List.of("organization"), null, BigDecimal.ZERO, new BigDecimal("1000000000"), "0",
                "Settlement differences below this amount go to cost of sales."));
        put(new Election("ppv_lineage_split", "Split purchase price variance over lineage", "IAS 2.21",
                List.of("organization"), List.of("off", "on"), null, null, "off",
                "on = PPV of STANDARD receipts follows the stock (on hand / WIP / sold) like landed cost."));
        put(new Election("joint_cost_method", "Joint-cost allocation basis", "IAS 2.14",
                List.of("organization", "process"), List.of("quantity", "weight", "market_value", "fixed_pct"), null,
                null, "market_value", "Rational and consistent basis between joint products."));
        put(new Election("byproduct_valuation", "By-product valuation", "IAS 2.14",
                List.of("organization", "process"), List.of("nrv", "zero"), null, null, "nrv",
                "By-products are measured at net realisable value and deducted from the joint cost."));
        put(new Election("stripping_cost", "Production-phase stripping cost", "IFRIC 20",
                List.of("organization", "quarry"), List.of("inventory", "stripping_asset"), null, null, "inventory",
                "stripping_asset needs an approved IFRIC 20 assessment of the ore-body component."));
        put(new Election("project_revenue_recognition", "Contract revenue recognition", "IFRS 15.35",
                List.of("organization", "contract"), List.of("point_in_time", "over_time_cost_to_cost"), null, null,
                "point_in_time", "Over time only when the contract meets IFRS 15.35 (a), (b) or (c)."));
        put(new Election("empty_leg_policy", "Empty return legs of owned-fleet deliveries", "IAS 2.10 / AD-10",
                List.of("organization"), List.of("loaded_leg", "pooled"), null, null, "loaded_leg",
                "loaded_leg = each trip carries its own empty return; pooled = empty km shared by loaded tonne-km."));
        put(new Election("cost_eligibility", "Cost eligibility for inventory", "IAS 2.10–2.16",
                List.of("account", "cost_component", "cost_pool"), ELIGIBILITY, null, null, null,
                "Only *_capitalisable classes can ever be absorbed or capitalised into inventory."));
    }

    private static void put(Election e) {
        CATALOGUE.put(e.key(), e);
    }

    private final JdbcClient jdbc;
    private final AuditService audit;

    public AccountingPolicyService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** The election catalogue with the value in force at organisation level. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> catalogue() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Election e : CATALOGUE.values()) {
            out.add(Obj.of("key", e.key(), "label", e.label(), "standard", e.standard(), "scopeTypes", e.scopeTypes(),
                    "values", e.values(), "min", e.min(), "max", e.max(), "default", e.defaultValue(), "note", e.note(),
                    "current", "cost_eligibility".equals(e.key()) ? null : current(e.key(), "organization", "")));
        }
        return out;
    }

    /** Proposals and approved elections, newest first (optionally one election). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String election) {
        List<Map<String, Object>> rows = election == null || election.isBlank()
                ? SqlRows.list(jdbc.sql("SELECT * FROM \"AccountingPolicy\" WHERE \"organizationId\" = ? ORDER BY"
                        + " \"createdAt\" DESC").param(org()))
                : SqlRows.list(jdbc.sql("SELECT * FROM \"AccountingPolicy\" WHERE \"organizationId\" = ? AND election = ?"
                        + " ORDER BY \"createdAt\" DESC").params(org(), election));
        for (Map<String, Object> r : rows) {
            r.put("inForce", "approved".equals(r.get("status")) && r.get("id").equals(inForceId((String) r.get(
                    "election"), (String) r.get("scopeType"), (String) r.get("scopeId"))));
        }
        return rows;
    }

    private String inForceId(String election, String scopeType, String scopeId) {
        return jdbc.sql("SELECT id FROM \"AccountingPolicy\" WHERE \"organizationId\" = ? AND election = ? AND"
                        + " \"scopeType\" = ? AND \"scopeId\" = ? AND status = 'approved' AND \"effectiveFrom\" <= ? ORDER BY"
                        + " \"effectiveFrom\" DESC, \"approvedAt\" DESC LIMIT 1")
                .params(org(), election, scopeType, scopeId == null ? "" : scopeId, java.sql.Date.valueOf(today()))
                .query(String.class).optional().orElse(null);
    }

    /**
     * The value in force for a scope: the scope's own approved election, else the organisation's, else the catalogue
     * default.
     */
    @Transactional(readOnly = true)
    public String current(String election, String scopeType, String scopeId) {
        Election e = election(election);
        String sql = "SELECT value FROM \"AccountingPolicy\" WHERE \"organizationId\" = ? AND election = ? AND"
                + " \"scopeType\" = ? AND \"scopeId\" = ? AND status = 'approved' AND \"effectiveFrom\" <= ? ORDER BY"
                + " \"effectiveFrom\" DESC, \"approvedAt\" DESC LIMIT 1";
        java.sql.Date d = java.sql.Date.valueOf(today());
        if (scopeType != null && !"organization".equals(scopeType)) {
            String v = jdbc.sql(sql).params(org(), election, scopeType, scopeId == null ? "" : scopeId, d)
                    .query(String.class).optional().orElse(null);
            if (v != null) {
                return v;
            }
        }
        return jdbc.sql(sql).params(org(), election, "organization", "", d).query(String.class).optional()
                .orElse(e.defaultValue());
    }

    /** A numeric election in force at organisation level. */
    public BigDecimal currentNumber(String election) {
        return new BigDecimal(current(election, "organization", ""));
    }

    private static Election election(String key) {
        if ("cost_formula".equals(key)) {
            throw ApiException.badRequest("The cost formula is set by the inventory costing policy (FIFO or weighted"
                    + " average, IAS 2.25); LIFO is not permitted");
        }
        Election e = CATALOGUE.get(key);
        if (e == null) {
            throw ApiException.badRequest("election must be one of the following values: "
                    + String.join(", ", CATALOGUE.keySet()));
        }
        return e;
    }

    /** Propose an election (status proposed; nothing changes until approved). */
    @Transactional
    public Map<String, Object> propose(ProposalInput in) {
        Election e = election(in.election());
        String scopeType = in.scopeType() == null || in.scopeType().isBlank() ? e.scopeTypes().getFirst()
                : in.scopeType();
        if (!e.scopeTypes().contains(scopeType)) {
            throw ApiException.badRequest("scopeType must be one of the following values: "
                    + String.join(", ", e.scopeTypes()));
        }
        String scopeId = "organization".equals(scopeType) ? "" : in.scopeId();
        if (!"organization".equals(scopeType) && (scopeId == null || scopeId.isBlank())) {
            throw ApiException.badRequest("scopeId should not be empty");
        }
        String value = validValue(e, in.value(), scopeType, scopeId);
        if (in.justification() == null || in.justification().trim().length() < 10) {
            throw ApiException.badRequest("Give a justification of at least 10 characters (IAS 8: why the change gives"
                    + " reliable and more relevant information)");
        }
        LocalDate from;
        try {
            from = in.effectiveFrom() == null || in.effectiveFrom().isBlank() ? today()
                    : LocalDate.parse(in.effectiveFrom().trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest("effectiveFrom must be a valid ISO 8601 date string");
        }
        if (from.isBefore(today())) {
            throw ApiException.badRequest("Accounting policy changes apply prospectively; effectiveFrom cannot be before"
                    + " today");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"AccountingPolicy\" (id, \"organizationId\", election, \"scopeType\", \"scopeId\", value,"
                        + " \"effectiveFrom\", justification, \"proposedBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), e.key(), scopeType, scopeId, value, java.sql.Date.valueOf(from),
                        in.justification().trim(), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("AccountingPolicy", id, "create", null, Obj.of("election", e.key(), "scopeType",
                scopeType, "scopeId", scopeId, "value", value, "effectiveFrom", from.toString())));
        return get(id);
    }

    private String validValue(Election e, String raw, String scopeType, String scopeId) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("value should not be empty");
        }
        String v = raw.trim();
        if (e.numeric()) {
            BigDecimal n;
            try {
                n = new BigDecimal(v);
            } catch (NumberFormatException ex) {
                throw ApiException.badRequest("value must be a number");
            }
            if (n.compareTo(e.min()) < 0 || n.compareTo(e.max()) > 0) {
                throw ApiException.badRequest("value must be between " + e.min().toPlainString() + " and "
                        + e.max().toPlainString());
            }
            return n.stripTrailingZeros().toPlainString();
        }
        if (!e.values().contains(v)) {
            throw ApiException.badRequest("value must be one of the following values: " + String.join(", ", e.values()));
        }
        if ("stripping_cost".equals(e.key()) && "stripping_asset".equals(v)
                && !strippingAssessed(scopeType, scopeId)) {
            throw ApiException.badRequest("A stripping-activity asset needs an approved IFRIC 20 assessment of the"
                    + " ore-body component (quarry costing, CM-5)");
        }
        if ("project_revenue_recognition".equals(e.key()) && v.startsWith("over_time")) {
            throw ApiException.badRequest("Over-time revenue needs the contract's IFRS 15.35 criteria assessment"
                    + " (contract commercials, CM-8b)");
        }
        if ("cost_eligibility".equals(e.key())) {
            assertSubject(scopeType, scopeId);
        }
        return v;
    }

    /** An approved IFRIC 20 assessment exists for the quarry (or, at organisation scope, for any quarry) — CM-5. */
    private boolean strippingAssessed(String scopeType, String scopeId) {
        boolean quarry = "quarry".equals(scopeType);
        return jdbc.sql("SELECT count(*) FROM \"QuarryStrippingAsset\" WHERE \"organizationId\" = ? AND status ="
                + " 'approved'" + (quarry ? " AND \"siteId\" = ?" : "")).params(quarry ? List.of(org(), scopeId)
                : List.of(org())).query(Long.class).single() > 0;
    }

    private void assertSubject(String scopeType, String scopeId) {
        String table = switch (scopeType) {
            case "account" -> "Account";
            case "cost_component" -> "CostComponent";
            default -> null;
        };
        if (table != null && jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?")
                .params(scopeId, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest(("account".equals(scopeType) ? "Account" : "Cost component") + " not found");
        }
    }

    /** Approve a proposal; never your own. */
    @Transactional
    public Map<String, Object> approve(String id) {
        Map<String, Object> p = row(id);
        if (!"proposed".equals(p.get("status"))) {
            throw ApiException.badRequest("Only a proposed policy change can be approved");
        }
        if (TenantContext.userId() != null && TenantContext.userId().equals(p.get("proposedBy"))) {
            throw ApiException.forbidden("A policy change must be approved by someone other than its proposer");
        }
        jdbc.sql("UPDATE \"AccountingPolicy\" SET status = 'approved', \"approvedBy\" = ?, \"approvedAt\" = now() WHERE"
                + " id = ?").params(TenantContext.userId(), id).update();
        audit.recordInTx(AuditInput.of("AccountingPolicy", id, "approve", p, null));
        return get(id);
    }

    /** Reject a proposal. */
    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        Map<String, Object> p = row(id);
        if (!"proposed".equals(p.get("status"))) {
            throw ApiException.badRequest("Only a proposed policy change can be rejected");
        }
        jdbc.sql("UPDATE \"AccountingPolicy\" SET status = 'rejected', \"rejectedReason\" = ? WHERE id = ?")
                .params(reason, id).update();
        audit.recordInTx(AuditInput.of("AccountingPolicy", id, "reject", p, Obj.of("reason", reason)));
        return get(id);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> p = row(id);
        p.put("inForce", "approved".equals(p.get("status")) && id.equals(inForceId((String) p.get("election"),
                (String) p.get("scopeType"), (String) p.get("scopeId"))));
        return p;
    }

    private Map<String, Object> row(String id) {
        Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT * FROM \"AccountingPolicy\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (p == null) {
            throw ApiException.notFound("Policy change not found");
        }
        return p;
    }

    // ---- cost eligibility (AD-10) -----------------------------------------------------------------------------------

    /** The approved eligibility class of a subject, or null when unclassified. */
    public String eligibility(String subjectType, String subjectId) {
        if (subjectId == null) {
            return null;
        }
        return jdbc.sql("SELECT value FROM \"AccountingPolicy\" WHERE \"organizationId\" = ? AND election ="
                        + " 'cost_eligibility' AND \"scopeType\" = ? AND \"scopeId\" = ? AND status = 'approved' AND"
                        + " \"effectiveFrom\" <= ? ORDER BY \"effectiveFrom\" DESC, \"approvedAt\" DESC LIMIT 1")
                .params(org(), subjectType, subjectId, java.sql.Date.valueOf(today())).query(String.class).optional()
                .orElse(null);
    }

    /**
     * Refuse to capitalise a subject explicitly classified as not capitalisable (selling, distribution, admin, abnormal…).
     * With {@code requireClassified}, an unclassified subject is refused too (absorption and allocation into inventory).
     */
    public void assertCapitalisable(String subjectType, String subjectId, String label, boolean requireClassified) {
        String cls = eligibility(subjectType, subjectId);
        if (cls == null) {
            if (requireClassified) {
                throw ApiException.badRequest(label + " is not classified as capitalisable (IAS 2.10–2.16); classify it"
                        + " under Costing & Valuation → cost eligibility");
            }
            return;
        }
        if (!CAPITALISABLE.contains(cls)) {
            throw ApiException.badRequest(label + " is classified as " + cls.replace('_', ' ') + "; it cannot be"
                    + " capitalised into inventory (IAS 2.16)");
        }
    }

    /** Approved classifications, newest effective per subject. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> classifications() {
        return SqlRows.list(jdbc.sql("SELECT DISTINCT ON (\"scopeType\", \"scopeId\") \"scopeType\" AS \"subjectType\","
                + " \"scopeId\" AS \"subjectId\", value AS eligibility, \"effectiveFrom\", \"approvedBy\", id FROM"
                + " \"AccountingPolicy\" WHERE \"organizationId\" = ? AND election = 'cost_eligibility' AND status ="
                + " 'approved' AND \"effectiveFrom\" <= ? ORDER BY \"scopeType\", \"scopeId\", \"effectiveFrom\" DESC,"
                + " \"approvedAt\" DESC").params(org(), java.sql.Date.valueOf(today())));
    }

    /** Expense / cost-of-sales accounts with no approved eligibility (review list; never capitalised). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> unclassifiedAccounts() {
        return SqlRows.list(jdbc.sql("SELECT a.id, a.code, a.name, c.key AS category FROM \"Account\" a JOIN"
                + " \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ? AND a.\"deletedAt\" IS"
                + " NULL AND a.\"isPostable\" AND c.key IN ('operating_expense', 'cost_of_goods_sold', 'other_expense')"
                + " AND NOT EXISTS (SELECT 1 FROM \"AccountingPolicy\" p WHERE p.\"organizationId\" = a.\"organizationId\""
                + " AND p.election = 'cost_eligibility' AND p.\"scopeType\" = 'account' AND p.\"scopeId\" = a.id AND"
                + " p.status = 'approved') ORDER BY a.code").param(org()));
    }

    /**
     * System defaults for the landed-cost components (approved by the system, IAS 2.11 / 2.16): duty, freight,
     * insurance and handling → acquisition capitalisable; demurrage → abnormal; selling → selling; administration →
     * admin. Idempotent; never overrides an existing classification.
     */
    @Transactional
    public int seedComponentDefaults() {
        int n = 0;
        for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT id, code, kind FROM \"CostComponent\" WHERE"
                + " \"organizationId\" = ?").param(org()))) {
            String cls = switch (String.valueOf(c.get("code"))) {
                case "DEMURRAGE" -> "production_abnormal";
                case "SELLING" -> "selling";
                case "ADMIN" -> "admin";
                case "IMPORT_VAT" -> null;
                default -> switch (String.valueOf(c.get("kind"))) {
                    case "duty", "freight", "insurance", "handling" -> "acquisition_capitalisable";
                    default -> null;
                };
            };
            if (cls == null || eligibilityAnyStatus("cost_component", (String) c.get("id"))) {
                continue;
            }
            jdbc.sql("INSERT INTO \"AccountingPolicy\" (id, \"organizationId\", election, \"scopeType\", \"scopeId\","
                            + " value, \"effectiveFrom\", status, justification, \"proposedBy\", \"approvedBy\","
                            + " \"approvedAt\") VALUES (?, ?, 'cost_eligibility', 'cost_component', ?, ?, ?, 'approved', ?,"
                            + " 'system', 'system', now())")
                    .params(UUID.randomUUID().toString(), org(), c.get("id"), cls, java.sql.Date.valueOf(today()),
                            "System default (ADR-S032): IAS 2.11 / 2.16 classification of the standard component")
                    .update();
            n++;
        }
        return n;
    }

    private boolean eligibilityAnyStatus(String subjectType, String subjectId) {
        return jdbc.sql("SELECT count(*) FROM \"AccountingPolicy\" WHERE \"organizationId\" = ? AND election ="
                        + " 'cost_eligibility' AND \"scopeType\" = ? AND \"scopeId\" = ? AND status <> 'rejected'")
                .params(org(), subjectType, subjectId).query(Long.class).single() > 0;
    }
}
