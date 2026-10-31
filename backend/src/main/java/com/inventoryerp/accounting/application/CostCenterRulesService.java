package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.PostingMath.NormalizedLine;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cost centre as a first-class GL dimension (ADR-S007 §4). Applied by {@link PostingService} to every entry before
 * its lines are written:
 * <ol>
 *   <li><b>Split</b> — a line whose dimensions carry {@code costAllocationRuleId} (an active {@code split} rule) is
 *       replaced by one line per target centre, shares = weight / Σ weights, the last target absorbing rounding so
 *       the parts sum exactly to the original line.</li>
 *   <li><b>Defaults</b> — a revenue / expense line without a centre inherits one from the partner, then the branch,
 *       then the posting user ({@code CostCenterDefault}).</li>
 *   <li><b>Account rules</b> — {@code required}: a line on the account must carry a centre; {@code forbidden}: an
 *       explicit centre is rejected and one inherited from the entry header is dropped.</li>
 *   <li><b>Validity</b> — a centre must exist, be active and be valid on the posting date.</li>
 * </ol>
 * Organisations that configure none of this post exactly as before.
 */
@Service
public class CostCenterRulesService {

    public static final String SPLIT_DIMENSION = "costAllocationRuleId";
    private static final Set<String> PNL = Set.of("revenue", "contra_revenue", "cogs", "operating_expense",
            "other_income", "other_expense");
    private static final List<String> SCOPES = List.of("branch", "partner", "user", "location", "employee");

    /** A line after the rules: account, amounts and the centre to write. */
    public record Applied(List<NormalizedLine> lines) {}

    private final JdbcClient jdbc;

    public CostCenterRulesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ------------------------------------------------------------------ posting hook

    /**
     * Apply splits, defaults, account rules and validity to normalized lines. {@code headerCostCenterId} is the
     * entry-level centre (inherited by lines without their own). Returns the lines to persist; their
     * {@code costCenterId} is final (never re-inherited from the header).
     */
    public List<NormalizedLine> apply(List<NormalizedLine> lines, String headerCostCenterId, String headerBranchId,
            Instant postingDate) {
        String orgId = org();
        boolean configured = jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"CostCenterDefault\" WHERE \"organizationId\" = ?)"
                + " OR EXISTS (SELECT 1 FROM \"AccountDimensionRule\" WHERE \"organizationId\" = ?"
                + " AND dimension = 'costCenter')")
                .params(orgId, orgId).query(Boolean.class).single();
        boolean splits = lines.stream().anyMatch(l -> l.dimensions() != null && l.dimensions().get(SPLIT_DIMENSION) != null);
        boolean anyCenter = headerCostCenterId != null || lines.stream().anyMatch(l -> l.costCenterId() != null);
        if (!configured && !splits && !anyCenter) {
            return lines;
        }
        List<NormalizedLine> expanded = splits ? expandSplits(lines) : lines;

        Set<String> accountIds = new LinkedHashSet<>();
        expanded.forEach(l -> accountIds.add(l.accountId()));
        Map<String, Map<String, Object>> accounts = new HashMap<>();
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT a.id, a.code, a.name, c.\"reportSection\"::text AS"
                + " section, r.rule FROM \"Account\" a LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\""
                + " LEFT JOIN \"AccountDimensionRule\" r ON r.\"accountId\" = a.id AND r.\"organizationId\" = a.\"organizationId\""
                + " AND r.dimension = 'costCenter' WHERE a.\"organizationId\" = ? AND a.id = ANY(?::text[])")
                .params(orgId, accountIds.toArray(String[]::new)))) {
            accounts.put((String) a.get("id"), a);
        }

        String userId = TenantContext.userId();
        List<NormalizedLine> out = new ArrayList<>(expanded.size());
        for (NormalizedLine l : expanded) {
            Map<String, Object> acc = accounts.getOrDefault(l.accountId(), Map.of());
            String rule = (String) acc.get("rule");
            boolean pnl = PNL.contains(String.valueOf(acc.get("section")));
            String cc = l.costCenterId();
            if ("forbidden".equals(rule)) {
                if (cc != null) {
                    throw ApiException.badRequest("Account " + acc.get("code") + " " + acc.get("name")
                            + " does not take a cost center");
                }
                out.add(with(l, null));
                continue;
            }
            if (cc == null) {
                cc = headerCostCenterId;
            }
            if (cc == null && configured && pnl) {
                cc = defaultFor(l.partnerId(), l.branchId() != null ? l.branchId() : headerBranchId, userId);
            }
            if (cc == null && "required".equals(rule)) {
                throw ApiException.badRequest("Account " + acc.get("code") + " " + acc.get("name")
                        + " requires a cost center");
            }
            out.add(with(l, cc));
        }
        assertValid(out, postingDate);
        return out;
    }

    private static NormalizedLine with(NormalizedLine l, String cc) {
        return new NormalizedLine(l.accountId(), l.partnerId(), l.description(), l.branchId(), cc, l.dimensions(),
                l.debit(), l.credit(), l.baseDebit(), l.baseCredit());
    }

    private String defaultFor(String partnerId, String branchId, String userId) {
        for (String[] scope : new String[][] {{"partner", partnerId}, {"branch", branchId}, {"user", userId}}) {
            if (scope[1] == null) {
                continue;
            }
            String cc = jdbc.sql("SELECT \"costCenterId\" FROM \"CostCenterDefault\" WHERE \"organizationId\" = ?"
                    + " AND \"scopeType\" = ? AND \"scopeId\" = ?").params(org(), scope[0], scope[1])
                    .query(String.class).optional().orElse(null);
            if (cc != null) {
                return cc;
            }
        }
        return null;
    }

    private void assertValid(List<NormalizedLine> lines, Instant postingDate) {
        Set<String> ids = new LinkedHashSet<>();
        lines.forEach(l -> {
            if (l.costCenterId() != null) {
                ids.add(l.costCenterId());
            }
        });
        if (ids.isEmpty()) {
            return;
        }
        LocalDate day = postingDate == null ? LocalDate.now(ZoneOffset.UTC) : postingDate.atOffset(ZoneOffset.UTC)
                .toLocalDate();
        Map<String, Map<String, Object>> found = new HashMap<>();
        for (Map<String, Object> c : SqlRows.list(jdbc.sql("SELECT id, code, name, \"isActive\", \"validFrom\","
                + " \"validTo\" FROM \"CostCenter\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND id = ANY(?::text[])").params(org(), ids.toArray(String[]::new)))) {
            found.put((String) c.get("id"), c);
        }
        for (String id : ids) {
            Map<String, Object> c = found.get(id);
            if (c == null) {
                throw ApiException.badRequest("Cost center not found");
            }
            LocalDate from = (LocalDate) c.get("validFrom");
            LocalDate to = (LocalDate) c.get("validTo");
            if (!Boolean.TRUE.equals(c.get("isActive")) || (from != null && day.isBefore(from))
                    || (to != null && day.isAfter(to))) {
                throw ApiException.badRequest("Cost center " + c.get("code") + " " + c.get("name")
                        + " is inactive or not valid on " + day);
            }
        }
    }

    /** Replace every line tagged with a split rule by its per-target parts (exact sum, remainder on the last). */
    private List<NormalizedLine> expandSplits(List<NormalizedLine> lines) {
        List<NormalizedLine> out = new ArrayList<>();
        for (NormalizedLine l : lines) {
            Object ruleId = l.dimensions() == null ? null : l.dimensions().get(SPLIT_DIMENSION);
            if (ruleId == null) {
                out.add(l);
                continue;
            }
            List<Map<String, Object>> targets = splitTargets(String.valueOf(ruleId));
            BigDecimal total = Money6.sum(targets.stream().map(t -> SqlRows.dec(t, "weight")).toList());
            Map<String, Object> dims = new LinkedHashMap<>(l.dimensions());
            dims.remove(SPLIT_DIMENSION);
            Map<String, Object> rest = dims.isEmpty() ? null : dims;
            BigDecimal[] left = {l.debit(), l.credit(), l.baseDebit(), l.baseCredit()};
            for (int i = 0; i < targets.size(); i++) {
                Map<String, Object> t = targets.get(i);
                boolean last = i == targets.size() - 1;
                BigDecimal share = SqlRows.dec(t, "weight");
                BigDecimal[] part = new BigDecimal[4];
                BigDecimal[] orig = {l.debit(), l.credit(), l.baseDebit(), l.baseCredit()};
                for (int k = 0; k < 4; k++) {
                    part[k] = last ? left[k] : Money6.share(orig[k], share, total);
                    left[k] = left[k].subtract(part[k]);
                }
                out.add(new NormalizedLine(l.accountId(), l.partnerId(), l.description(), l.branchId(),
                        (String) t.get("costCenterId"), rest, part[0], part[1], part[2], part[3]));
            }
        }
        return out;
    }

    private List<Map<String, Object>> splitTargets(String ruleId) {
        Map<String, Object> rule = SqlRows.one(jdbc.sql("SELECT id, code, kind, \"isActive\" FROM \"CostAllocationRule\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(ruleId, org()));
        if (rule == null || !"split".equals(rule.get("kind")) || !Boolean.TRUE.equals(rule.get("isActive"))) {
            throw ApiException.badRequest("Cost allocation rule not found or not an active split rule");
        }
        List<Map<String, Object>> targets = SqlRows.list(jdbc.sql("SELECT \"costCenterId\", weight FROM"
                + " \"CostAllocationTarget\" WHERE \"organizationId\" = ? AND \"ruleId\" = ? ORDER BY \"lineNumber\", id")
                .params(org(), ruleId));
        if (targets.isEmpty()) {
            throw ApiException.badRequest("Cost allocation rule " + rule.get("code") + " has no targets");
        }
        return targets;
    }

    // ------------------------------------------------------------------ defaults

    /** Every default mapping with the centre's code / name. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDefaults() {
        return SqlRows.list(jdbc.sql("SELECT d.*, c.code AS \"costCenterCode\", c.name AS \"costCenterName\" FROM"
                + " \"CostCenterDefault\" d JOIN \"CostCenter\" c ON c.id = d.\"costCenterId\" WHERE d.\"organizationId\" = ?"
                + " ORDER BY d.\"scopeType\", d.\"scopeId\"").param(org()));
    }

    /** Create or replace the default for one scope. */
    @Transactional
    public Map<String, Object> upsertDefault(String scopeType, String scopeId, String costCenterId) {
        if (!SCOPES.contains(scopeType)) {
            throw ApiException.badRequest("scopeType must be one of the following values: " + String.join(", ", SCOPES));
        }
        assertCenter(costCenterId);
        jdbc.sql("INSERT INTO \"CostCenterDefault\" (id, \"organizationId\", \"scopeType\", \"scopeId\", \"costCenterId\")"
                        + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"scopeType\", \"scopeId\") DO UPDATE"
                        + " SET \"costCenterId\" = EXCLUDED.\"costCenterId\", \"updatedAt\" = CURRENT_TIMESTAMP")
                .params(UUID.randomUUID().toString(), org(), scopeType, scopeId, costCenterId).update();
        return SqlRows.one(jdbc.sql("SELECT * FROM \"CostCenterDefault\" WHERE \"organizationId\" = ? AND \"scopeType\" = ?"
                + " AND \"scopeId\" = ?").params(org(), scopeType, scopeId));
    }

    @Transactional
    public void deleteDefault(String id) {
        if (jdbc.sql("DELETE FROM \"CostCenterDefault\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .update() == 0) {
            throw ApiException.notFound("Cost center default not found");
        }
    }

    // ------------------------------------------------------------------ account rules

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAccountRules() {
        return SqlRows.list(jdbc.sql("SELECT r.*, a.code AS \"accountCode\", a.name AS \"accountName\" FROM"
                + " \"AccountDimensionRule\" r JOIN \"Account\" a ON a.id = r.\"accountId\" WHERE r.\"organizationId\" = ?"
                + " AND r.dimension = 'costCenter' ORDER BY a.code").param(org()));
    }

    /** Set an account's cost-centre rule ({@code required} / {@code forbidden}); {@code optional} removes it. */
    @Transactional
    public Map<String, Object> setAccountRule(String accountId, String rule) {
        long n = jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(accountId, org()).query(Long.class).single();
        if (n == 0) {
            throw ApiException.badRequest("Account not found");
        }
        if ("optional".equals(rule)) {
            jdbc.sql("DELETE FROM \"AccountDimensionRule\" WHERE \"organizationId\" = ? AND \"accountId\" = ?"
                    + " AND dimension = 'costCenter'").params(org(), accountId).update();
            return Map.of("accountId", accountId, "rule", "optional");
        }
        jdbc.sql("INSERT INTO \"AccountDimensionRule\" (id, \"organizationId\", \"accountId\", dimension, rule) VALUES"
                        + " (?, ?, ?, 'costCenter', ?) ON CONFLICT (\"organizationId\", \"accountId\", dimension) DO UPDATE"
                        + " SET rule = EXCLUDED.rule, \"updatedAt\" = CURRENT_TIMESTAMP")
                .params(UUID.randomUUID().toString(), org(), accountId, rule).update();
        return SqlRows.one(jdbc.sql("SELECT * FROM \"AccountDimensionRule\" WHERE \"organizationId\" = ? AND"
                + " \"accountId\" = ? AND dimension = 'costCenter'").params(org(), accountId));
    }

    void assertCenter(String costCenterId) {
        Boolean active = jdbc.sql("SELECT \"isActive\" FROM \"CostCenter\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(costCenterId, org()).query(Boolean.class).optional().orElse(null);
        if (active == null) {
            throw ApiException.badRequest("Cost center not found");
        }
        if (!active) {
            throw ApiException.badRequest("Cost center is inactive");
        }
    }

    /** Exact 6-dp share arithmetic shared by splits and periodic allocation. */
    static final class Money6 {
        private Money6() {}

        static BigDecimal share(BigDecimal amount, BigDecimal weight, BigDecimal total) {
            if (amount == null || amount.signum() == 0) {
                return amount == null ? BigDecimal.ZERO : amount;
            }
            return amount.multiply(weight).divide(total, 6, RoundingMode.HALF_UP);
        }

        static BigDecimal sum(List<BigDecimal> values) {
            BigDecimal s = BigDecimal.ZERO;
            for (BigDecimal v : values) {
                s = s.add(v);
            }
            return s;
        }
    }
}
