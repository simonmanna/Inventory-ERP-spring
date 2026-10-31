package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.PostingMath.NormalizedLine;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.sql.Date;
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
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generic analytic dimensions (roadmap 2.4, ADR-S025, extension). A dimension (project, department, region, ...) has
 * a code and a list of values. Ledger lines carry them in their {@code dimensions} json as
 * {@code {"<dimension code>": "<value code>"}}; the cost centre keeps its own column and is addressed by the reserved
 * code {@code costCenter}.
 *
 * <p>At posting ({@link #validate}) every key that names a defined dimension must hold an active value valid on the
 * posting date; account rules make a dimension {@code required} or {@code forbidden} on an account. Keys that are not
 * defined dimensions (system tags such as {@code vehicleId}) pass unchanged, so organizations that define nothing post
 * exactly as before.
 */
@Service
public class AnalyticDimensionService {

    /** The cost centre, addressed as a dimension in rules and reports. */
    public static final String COST_CENTER = "costCenter";
    private static final Set<String> RESERVED = Set.of(COST_CENTER, CostCenterRulesService.SPLIT_DIMENSION);
    private static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,39}$");
    /** The closing journal moves whole P&amp;L balances; dimension requirements do not apply to it. */
    private static final Set<String> EXEMPT_SOURCES = Set.of("period_close", "fiscal_year_close");

    private record Dim(String id, String code, String name, boolean active) {}

    private record Val(String id, String code, boolean active, LocalDate from, LocalDate to) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public AnalyticDimensionService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    // ------------------------------------------------------------------ dimensions

    private Dim dim(String id) {
        return jdbc.sql("SELECT id, code, name, \"isActive\" FROM \"AnalyticDimension\" WHERE id = ? AND"
                        + " \"organizationId\" = ?").params(id, org())
                .query((rs, n) -> new Dim(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
                .optional().orElseThrow(() -> ApiException.notFound("Analytic dimension not found"));
    }

    private Map<String, Object> dimView(String id) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT id, code, name, description, \"isActive\", \"sortOrder\","
                + " \"createdAt\", \"updatedAt\" FROM \"AnalyticDimension\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (d == null) {
            throw ApiException.notFound("Analytic dimension not found");
        }
        d.put("values", values(id));
        return d;
    }

    /** Dimensions with their value counts, by sort order then code. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(boolean includeInactive) {
        return SqlRows.list(jdbc.sql("SELECT d.id, d.code, d.name, d.description, d.\"isActive\", d.\"sortOrder\","
                + " (SELECT count(*) FROM \"AnalyticDimensionValue\" v WHERE v.\"dimensionId\" = d.id)"
                + " AS \"valueCount\","
                + " d.\"createdAt\", d.\"updatedAt\" FROM \"AnalyticDimension\" d WHERE d.\"organizationId\" = ?"
                + (includeInactive ? "" : " AND d.\"isActive\" = true") + " ORDER BY d.\"sortOrder\", d.code")
                .param(org()));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return dimView(id);
    }

    /** Create a dimension; its code is the key used on ledger lines and cannot change later. */
    @Transactional
    public Map<String, Object> create(String code, String name, String description, Integer sortOrder) {
        String c = code.trim();
        if (!CODE.matcher(c).matches()) {
            throw ApiException.badRequest("Dimension code must start with a letter and contain only letters, digits"
                    + " and underscores (at most 40)");
        }
        if (RESERVED.contains(c)) {
            throw ApiException.badRequest("Dimension code " + c + " is reserved");
        }
        if (jdbc.sql("SELECT count(*) FROM \"AnalyticDimension\" WHERE \"organizationId\" = ? AND code = ?")
                .params(org(), c).query(Long.class).single() > 0) {
            throw ApiException.conflict("Dimension code " + c + " already exists");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"AnalyticDimension\" (id, \"organizationId\", code, name, description, \"sortOrder\","
                        + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), c, name.trim(), description, sortOrder == null ? 0 : sortOrder,
                        TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("AnalyticDimension", id, "create", null, Obj.of("code", c, "name", name)));
        return dimView(id);
    }

    @Transactional
    public Map<String, Object> update(String id, String name, String description, Boolean isActive, Integer sortOrder) {
        Dim d = dim(id);
        jdbc.sql("UPDATE \"AnalyticDimension\" SET name = coalesce(?, name), description = coalesce(?, description),"
                        + " \"isActive\" = coalesce(?, \"isActive\"), \"sortOrder\" = coalesce(?, \"sortOrder\"),"
                        + " \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ? AND \"organizationId\" = ?")
                .params(name == null ? null : name.trim(), description, isActive, sortOrder, id, org()).update();
        audit.recordInTx(AuditInput.of("AnalyticDimension", id, "update", Obj.of("name", d.name(), "isActive",
                d.active()), Obj.of("name", name, "isActive", isActive)));
        return dimView(id);
    }

    private boolean usedOnLedger(String key, String value) {
        String json = value == null ? null : com.inventoryerp.shared.web.EntityJson.toJson(Map.of(key, value));
        return value == null
                ? jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"JournalLine\" WHERE \"organizationId\" = ?"
                        + " AND jsonb_exists(dimensions, ?))")
                        .params(org(), key).query(Boolean.class).single()
                : jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"JournalLine\" WHERE \"organizationId\" = ?"
                        + " AND dimensions @> ?::jsonb)").params(org(), json).query(Boolean.class).single();
    }

    /** Delete a dimension never used on the ledger (otherwise deactivate it). */
    @Transactional
    public Map<String, Object> delete(String id) {
        Dim d = dim(id);
        if (usedOnLedger(d.code(), null)) {
            throw ApiException.badRequest("Dimension " + d.code()
                    + " is used on journal entries; deactivate it instead");
        }
        jdbc.sql("DELETE FROM \"AccountDimensionRule\" WHERE \"organizationId\" = ? AND dimension = ?")
                .params(org(), d.code()).update();
        jdbc.sql("UPDATE \"AnalyticDimensionValue\" SET \"parentId\" = NULL WHERE \"dimensionId\" = ?").param(id)
                .update();
        jdbc.sql("DELETE FROM \"AnalyticDimensionValue\" WHERE \"dimensionId\" = ?").param(id).update();
        jdbc.sql("DELETE FROM \"AnalyticDimension\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .update();
        audit.recordInTx(AuditInput.of("AnalyticDimension", id, "delete", Obj.of("code", d.code()), null));
        return Map.of("id", id, "deleted", true);
    }

    // ------------------------------------------------------------------ values

    private List<Map<String, Object>> values(String dimensionId) {
        return SqlRows.list(jdbc.sql("SELECT id, code, name, \"parentId\", \"isActive\", \"validFrom\", \"validTo\""
                + " FROM \"AnalyticDimensionValue\" WHERE \"dimensionId\" = ? AND \"organizationId\" = ? ORDER BY code")
                .params(dimensionId, org()));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listValues(String dimensionId) {
        dim(dimensionId);
        return values(dimensionId);
    }

    private void assertParent(String dimensionId, String valueId, String parentId) {
        if (parentId == null) {
            return;
        }
        String cursor = parentId;
        Set<String> seen = new LinkedHashSet<>();
        while (cursor != null) {
            if (cursor.equals(valueId) || !seen.add(cursor)) {
                throw ApiException.badRequest("A value cannot be its own parent");
            }
            Map<String, Object> p = SqlRows.one(jdbc.sql("SELECT \"dimensionId\", \"parentId\" FROM"
                    + " \"AnalyticDimensionValue\" WHERE id = ? AND \"organizationId\" = ?").params(cursor, org()));
            if (p == null || !dimensionId.equals(p.get("dimensionId"))) {
                throw ApiException.badRequest("Parent value not found in this dimension");
            }
            cursor = (String) p.get("parentId");
        }
    }

    private static void assertDates(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("validTo must not be before validFrom");
        }
    }

    @Transactional
    public Map<String, Object> createValue(String dimensionId, String code, String name, String parentId,
            LocalDate validFrom, LocalDate validTo) {
        dim(dimensionId);
        String c = code.trim();
        if (c.isEmpty()) {
            throw ApiException.badRequest("code should not be empty");
        }
        assertDates(validFrom, validTo);
        assertParent(dimensionId, null, parentId);
        if (jdbc.sql("SELECT count(*) FROM \"AnalyticDimensionValue\" WHERE \"dimensionId\" = ? AND code = ?")
                .params(dimensionId, c).query(Long.class).single() > 0) {
            throw ApiException.conflict("Value code " + c + " already exists in this dimension");
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"AnalyticDimensionValue\" (id, \"organizationId\", \"dimensionId\", code, name,"
                        + " \"parentId\", \"validFrom\", \"validTo\") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, org(), dimensionId, c, name.trim(), parentId,
                        validFrom == null ? null : Date.valueOf(validFrom),
                        validTo == null ? null : Date.valueOf(validTo)).update();
        audit.recordInTx(AuditInput.of("AnalyticDimensionValue", id, "create", null, Obj.of("code", c, "name", name)));
        return value(id);
    }

    private Map<String, Object> value(String id) {
        Map<String,
                Object> v = SqlRows.one(jdbc.sql("SELECT id, \"dimensionId\", code, name, \"parentId\", \"isActive\","
                + " \"validFrom\", \"validTo\" FROM \"AnalyticDimensionValue\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()));
        if (v == null) {
            throw ApiException.notFound("Dimension value not found");
        }
        return v;
    }

    /**
     * Change a value. {@code clearParent} / {@code clearValidity} reset those fields, since a null in the body means
     * "keep". The code stays fixed once the value has been posted.
     */
    @Transactional
    public Map<String, Object> updateValue(String dimensionId, String valueId, String code, String name,
            String parentId, Boolean clearParent, Boolean isActive, LocalDate validFrom, LocalDate validTo,
            Boolean clearValidity) {
        Dim d = dim(dimensionId);
        Map<String, Object> old = value(valueId);
        if (!dimensionId.equals(old.get("dimensionId"))) {
            throw ApiException.notFound("Dimension value not found");
        }
        String newCode = code == null ? null : code.trim();
        if (newCode != null && !newCode.equals(old.get("code"))) {
            if (usedOnLedger(d.code(), (String) old.get("code"))) {
                throw ApiException.badRequest("Value " + old.get("code") + " is used on journal entries; its code"
                        + " cannot change");
            }
            if (jdbc.sql("SELECT count(*) FROM \"AnalyticDimensionValue\" WHERE \"dimensionId\" = ? AND code = ?")
                    .params(dimensionId, newCode).query(Long.class).single() > 0) {
                throw ApiException.conflict("Value code " + newCode + " already exists in this dimension");
            }
        }
        boolean resetParent = Boolean.TRUE.equals(clearParent);
        boolean resetDates = Boolean.TRUE.equals(clearValidity);
        if (!resetParent) {
            assertParent(dimensionId, valueId, parentId);
        }
        LocalDate from = resetDates ? validFrom : validFrom != null ? validFrom : date(old.get("validFrom"));
        LocalDate to = resetDates ? validTo : validTo != null ? validTo : date(old.get("validTo"));
        assertDates(from, to);
        jdbc.sql("UPDATE \"AnalyticDimensionValue\" SET code = coalesce(?, code), name = coalesce(?, name),"
                        + " \"parentId\" = CASE WHEN ? THEN NULL ELSE coalesce(?, \"parentId\") END,"
                        + " \"isActive\" = coalesce(?, \"isActive\"), \"validFrom\" = ?, \"validTo\" = ?,"
                        + " \"updatedAt\" = CURRENT_TIMESTAMP WHERE id = ? AND \"organizationId\" = ?")
                .params(newCode, name == null ? null : name.trim(), resetParent, parentId, isActive,
                        from == null ? null : Date.valueOf(from), to == null ? null : Date.valueOf(to), valueId, org())
                .update();
        audit.recordInTx(AuditInput.of("AnalyticDimensionValue", valueId, "update", old, Obj.of("code", newCode, "name",
                name, "isActive", isActive)));
        return value(valueId);
    }

    @Transactional
    public Map<String, Object> deleteValue(String dimensionId, String valueId) {
        Dim d = dim(dimensionId);
        Map<String, Object> old = value(valueId);
        if (!dimensionId.equals(old.get("dimensionId"))) {
            throw ApiException.notFound("Dimension value not found");
        }
        if (usedOnLedger(d.code(), (String) old.get("code"))) {
            throw ApiException.badRequest("Value " + old.get("code") + " is used on journal entries; deactivate it"
                    + " instead");
        }
        if (jdbc.sql("SELECT count(*) FROM \"AnalyticDimensionValue\" WHERE \"parentId\" = ?").param(valueId)
                .query(Long.class).single() > 0) {
            throw ApiException.badRequest("Value " + old.get("code") + " has child values");
        }
        jdbc.sql("DELETE FROM \"AnalyticDimensionValue\" WHERE id = ? AND \"organizationId\" = ?")
                .params(valueId, org())
                .update();
        audit.recordInTx(AuditInput.of("AnalyticDimensionValue", valueId, "delete", old, null));
        return Map.of("id", valueId, "deleted", true);
    }

    private static LocalDate date(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate l) {
            return l;
        }
        if (v instanceof Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }

    // ------------------------------------------------------------------ account rules

    /** Generic account rules ({@code costCenter} rules are listed under /cost-centers/account-rules). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listAccountRules(String dimension) {
        return SqlRows.list(jdbc.sql("SELECT r.\"accountId\", a.code AS \"accountCode\", a.name AS \"accountName\","
                + " r.dimension, r.rule, r.\"updatedAt\" FROM \"AccountDimensionRule\" r JOIN \"Account\" a ON"
                + " a.id = r.\"accountId\" WHERE r.\"organizationId\" = ? AND r.dimension <> 'costCenter'"
                + (dimension == null ? "" : " AND r.dimension = ?") + " ORDER BY a.code, r.dimension")
                .params(dimension == null ? new Object[] {org()} : new Object[] {org(), dimension}));
    }

    /** Set {@code rule} (required / optional / forbidden) for one account and dimension; optional removes the rule. */
    @Transactional
    public Map<String, Object> setAccountRule(String accountId, String dimension, String rule) {
        if (COST_CENTER.equals(dimension)) {
            throw ApiException.badRequest("Set cost center rules under /cost-centers/account-rules");
        }
        if (jdbc.sql("SELECT count(*) FROM \"AnalyticDimension\" WHERE \"organizationId\" = ? AND code = ?")
                .params(org(), dimension).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Unknown analytic dimension " + dimension);
        }
        if (jdbc.sql("SELECT count(*) FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?")
                .params(accountId, org())
                .query(Long.class).single() == 0) {
            throw ApiException.notFound("Account not found");
        }
        if ("optional".equals(rule)) {
            jdbc.sql("DELETE FROM \"AccountDimensionRule\" WHERE \"organizationId\" = ? AND \"accountId\" = ?"
                    + " AND dimension = ?").params(org(), accountId, dimension).update();
            return Obj.of("accountId", accountId, "dimension", dimension, "rule", "optional");
        }
        jdbc.sql("INSERT INTO \"AccountDimensionRule\" (id, \"organizationId\", \"accountId\", dimension, rule) VALUES"
                        + " (?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"accountId\", dimension) DO UPDATE"
                        + " SET rule = EXCLUDED.rule, \"updatedAt\" = CURRENT_TIMESTAMP")
                .params(UUID.randomUUID().toString(), org(), accountId, dimension, rule).update();
        audit.recordInTx(AuditInput.of("AccountDimensionRule", accountId, "update", null, Obj.of("dimension", dimension,
                "rule", rule)));
        return Obj.of("accountId", accountId, "dimension", dimension, "rule", rule);
    }

    // ------------------------------------------------------------------ validation

    /**
     * Check a dimension map supplied outside a posting (e.g. on a document line): every key must be a defined, active
     * dimension (or {@code costCenter}) and every value an active value of it. Validity dates are checked at posting.
     */
    @Transactional(readOnly = true)
    public void assertAssignable(Map<String, Object> dims) {
        if (dims == null) {
            return;
        }
        for (Map.Entry<String, Object> e : dims.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            if (!(v instanceof String s) || s.isBlank()) {
                throw ApiException.badRequest("Dimension " + key + " must have a value code");
            }
            if (COST_CENTER.equals(key)) {
                if (jdbc.sql("SELECT count(*) FROM \"CostCenter\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL AND \"isActive\" = true").params(s, org()).query(Long.class)
                        .single() == 0) {
                    throw ApiException.badRequest("Cost center not found or inactive");
                }
                continue;
            }
            Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT id, \"isActive\" FROM \"AnalyticDimension\" WHERE"
                    + " \"organizationId\" = ? AND code = ?").params(org(), key));
            if (d == null) {
                throw ApiException.badRequest("Unknown analytic dimension " + key);
            }
            if (!Boolean.TRUE.equals(d.get("isActive"))) {
                throw ApiException.badRequest("Analytic dimension " + key + " is inactive");
            }
            Boolean active = jdbc.sql("SELECT \"isActive\" FROM \"AnalyticDimensionValue\" WHERE \"dimensionId\" = ?"
                    + " AND code = ?").params(d.get("id"), s).query(Boolean.class).optional().orElse(null);
            if (active == null) {
                throw ApiException.badRequest("Unknown value " + s + " for dimension " + key);
            }
            if (!active) {
                throw ApiException.badRequest("Value " + s + " of dimension " + key + " is inactive");
            }
        }
    }

    /**
     * Posting hook ({@link PostingService}): validate the effective dimensions of each line (its own, else the
     * entry's) against defined dimensions and account rules. Lines are not changed.
     */
    public void validate(List<NormalizedLine> lines, Map<String, Object> entryDimensions, Instant postingDate,
            String sourceType) {
        String orgId = org();
        Map<String, Dim> dims = new HashMap<>();
        jdbc.sql("SELECT id, code, name, \"isActive\" FROM \"AnalyticDimension\" WHERE \"organizationId\" = ?")
                .param(orgId).query((rs, n) -> new Dim(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4))).list().forEach(d -> dims.put(d.code(), d));
        if (dims.isEmpty()) {
            return;
        }
        Set<String> accountIds = new LinkedHashSet<>();
        lines.forEach(l -> accountIds.add(l.accountId()));
        Map<String, Map<String, String>> rules = new HashMap<>();
        Map<String, String> accountLabel = new HashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT r.\"accountId\", r.dimension, r.rule, a.code, a.name"
                + " FROM \"AccountDimensionRule\" r JOIN \"Account\" a ON a.id = r.\"accountId\" WHERE"
                + " r.\"organizationId\" = ? AND r.dimension <> 'costCenter' AND r.\"accountId\" = ANY(?::text[])")
                .params(orgId, accountIds.toArray(String[]::new)))) {
            rules.computeIfAbsent((String) r.get("accountId"), k -> new LinkedHashMap<>())
                    .put((String) r.get("dimension"), (String) r.get("rule"));
            accountLabel.put((String) r.get("accountId"), r.get("code") + " " + r.get("name"));
        }
        boolean exempt = sourceType != null && EXEMPT_SOURCES.contains(sourceType);
        LocalDate day = postingDate.atOffset(ZoneOffset.UTC).toLocalDate();
        Map<String, Val> cache = new HashMap<>();
        Map<String, String> entry = PostingService.normalizeDimensions(entryDimensions);
        for (NormalizedLine l : lines) {
            Map<String, String> own = PostingService.normalizeDimensions(l.dimensions());
            Map<String, String> eff = own != null ? own : entry == null ? Map.of() : entry;
            for (Map.Entry<String, String> e : eff.entrySet()) {
                Dim d = dims.get(e.getKey());
                if (d == null) {
                    continue;
                }
                if (!d.active()) {
                    throw ApiException.badRequest("Analytic dimension " + d.code() + " is inactive");
                }
                Val v = cache.computeIfAbsent(d.id() + "\u0000" + e.getValue(), k -> jdbc.sql("SELECT id, code,"
                                + " \"isActive\", \"validFrom\", \"validTo\" FROM \"AnalyticDimensionValue\" WHERE"
                                + " \"dimensionId\" = ? AND code = ?").params(d.id(), e.getValue())
                        .query((rs, n) -> new Val(rs.getString(1), rs.getString(2), rs.getBoolean(3),
                                rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(),
                                rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate()))
                        .optional().orElse(null));
                if (v == null) {
                    throw ApiException.badRequest("Unknown value " + e.getValue() + " for dimension " + d.code());
                }
                if (!v.active()) {
                    throw ApiException.badRequest("Value " + v.code() + " of dimension " + d.code() + " is inactive");
                }
                if ((v.from() != null && day.isBefore(v.from())) || (v.to() != null && day.isAfter(v.to()))) {
                    throw ApiException.badRequest("Value " + v.code() + " of dimension " + d.code()
                            + " is not valid on " + day);
                }
            }
            Map<String, String> accountRules = rules.get(l.accountId());
            if (accountRules == null) {
                continue;
            }
            for (Map.Entry<String, String> r : accountRules.entrySet()) {
                boolean has = eff.get(r.getKey()) != null;
                if ("forbidden".equals(r.getValue()) && own != null && own.containsKey(r.getKey())) {
                    throw ApiException.badRequest("Account " + accountLabel.get(l.accountId())
                            + " does not take dimension " + r.getKey());
                }
                if ("required".equals(r.getValue()) && !has && !exempt) {
                    throw ApiException.badRequest("Account " + accountLabel.get(l.accountId())
                            + " requires dimension " + r.getKey());
                }
            }
        }
    }

    /** Codes of the organization's dimensions (for reports). */
    List<String> codes() {
        return new ArrayList<>(jdbc.sql("SELECT code FROM \"AnalyticDimension\" WHERE \"organizationId\" = ?"
                + " ORDER BY \"sortOrder\", code").param(org()).query(String.class).list());
    }
}
