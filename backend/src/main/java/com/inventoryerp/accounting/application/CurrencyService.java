package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Currency;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Currency catalog + FX rate history (port of {@code currency.service.ts}). Rates are TO per 1 FROM;
 * {@link #getRate} returns the latest rate with {@code asOf <= date}, falling back to the reciprocal of the
 * inverse pair.
 */
@Service
public class CurrencyService {

    static final MathContext DECIMAL_JS = new MathContext(20, RoundingMode.HALF_UP);

    /**
     * The rate a posting uses (PC-0, ADR-S054): TO per 1 FROM, where it came from ({@code document}, {@code table} or
     * {@code base}) and the date it applies to.
     */
    public record Resolution(String fromCode, String toCode, BigDecimal rate, String source, Instant rateDate,
            String rateType, String context, BigDecimal policyRate, String overrideReason) {

        public Resolution(String fromCode, String toCode, BigDecimal rate, String source, Instant rateDate) {
            this(fromCode, toCode, rate, source, rateDate, null, null, null, null);
        }
    }

    /** FX-2 B: rate types a rate row carries and a policy can require. */
    public static final List<String> RATE_TYPES = List.of("official", "bank", "cash", "market");

    /** FX-2 B: transaction contexts a company can give its own rate policy. */
    public static final List<String> CONTEXTS = List.of("sales", "purchase", "goods_receipt", "expense", "payment_in",
            "payment_out", "treasury", "deposit", "revaluation", "opening_balance");

    /**
     * FX-2 B: a company's rate policy for one transaction context. Without a stored row a context uses the official
     * rate, needs no approval and accepts stated rates with no tolerance check — the pre-FX-2 behaviour.
     */
    public record Policy(String context, String rateType, boolean requireApprovedRate, BigDecimal statedRateTolerancePct,
            boolean allowStatedRate, boolean stored) {

        static Policy defaults(String context) {
            return new Policy(context, "official", false, null, true, false);
        }
    }

    private final EntityManager em;
    private final JdbcClient jdbc;

    public CurrencyService(EntityManager em, JdbcClient jdbc) {
        this.em = em;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listCurrencies() {
        return EntityJson.list(em.createQuery("select c from Currency c order by c.code asc", Currency.class)
                .getResultList());
    }

    /** Idempotent currency upsert. */
    @Transactional
    public void upsertCurrency(String code, String symbol, String name, int decimalPlaces) {
        jdbc.sql("INSERT INTO \"Currency\" (id, code, symbol, name, \"decimalPlaces\", \"isActive\", \"updatedAt\")"
                        + " VALUES (gen_random_uuid()::text, ?, ?, ?, ?, true, now()) ON CONFLICT (code) DO UPDATE SET"
                        + " symbol = EXCLUDED.symbol, name = EXCLUDED.name, \"decimalPlaces\" = EXCLUDED.\"decimalPlaces\","
                        + " \"isActive\" = true, \"updatedAt\" = now()")
                .params(code, symbol, name, decimalPlaces).update();
    }

    /**
     * Idempotent rate upsert for (organization, from, to, asOf). A tenant's rate belongs to that tenant (ACC-2, B4);
     * without a tenant (seeds, jobs) the row is shared and serves as every organization's fallback.
     */
    @Transactional
    public void upsertRate(String fromCode, String toCode, Instant asOf, BigDecimal rate, String source) {
        upsertRate(fromCode, toCode, asOf, rate, source, "official");
    }

    /**
     * FX-2 B: upsert of a typed rate. A rate is recorded {@code approved} unless one of the company's policies requires
     * approved rates of that type, in which case it waits as {@code pending} for a second person ({@link #approveRate}).
     */
    @Transactional
    public void upsertRate(String fromCode, String toCode, Instant asOf, BigDecimal rate, String source,
            String rateType) {
        String type = rateType == null || rateType.isBlank() ? "official" : rateType.trim().toLowerCase();
        if (!RATE_TYPES.contains(type)) {
            throw ApiException.badRequest("rateType must be one of the following values: official, bank, cash, market");
        }
        if (fromCode.equals(toCode)) {
            throw ApiException.badRequest("fromCode and toCode must differ");
        }
        if (rate == null || rate.signum() <= 0) {
            // FX-1: a zero rate would value every foreign document at nothing (extension; Nest accepted 0).
            throw ApiException.badRequest("rate must be greater than zero");
        }
        String org = tenant();
        boolean needsApproval = org != null && jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"FxRatePolicy\" WHERE"
                + " \"organizationId\" = ? AND \"rateType\" = ? AND \"requireApprovedRate\")").params(org, type)
                .query(Boolean.class).single();
        String status = needsApproval ? "pending" : "approved";
        jdbc.sql("INSERT INTO \"CurrencyRate\" (id, \"organizationId\", \"fromCode\", \"toCode\", \"asOf\", rate, source,"
                        + " \"createdBy\", \"rateType\", status, \"approvedBy\", \"approvedAt\")"
                        + " VALUES (gen_random_uuid()::text, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)"
                        + " ON CONFLICT ((coalesce(\"organizationId\", '')), \"fromCode\", \"toCode\", \"asOf\", \"rateType\")"
                        + " DO UPDATE SET rate = EXCLUDED.rate, source = EXCLUDED.source, \"createdBy\" = EXCLUDED.\"createdBy\","
                        + " status = EXCLUDED.status, \"approvedBy\" = NULL, \"approvedAt\" = NULL")
                .params(org, fromCode, toCode, FiscalPeriodControl.ts(asOf), rate, source, TenantContext.userId(), type,
                        status)
                .update();
    }

    /**
     * FX-2 B: approve ({@code approve = true}) or reject a pending rate of the caller's company. Maker-checker: the
     * person who entered a rate cannot decide on it.
     */
    @Transactional
    public Map<String, Object> decideRate(String id, boolean approve) {
        String org = tenant();
        Map<String, Object> row = jdbc.sql("SELECT id, \"createdBy\", status FROM \"CurrencyRate\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Exchange rate not found"));
        if (!"pending".equals(row.get("status"))) {
            throw ApiException.badRequest("Only a pending exchange rate can be approved or rejected");
        }
        String me = TenantContext.userId();
        if (me != null && me.equals(row.get("createdBy"))) {
            throw ApiException.forbidden("You cannot approve or reject an exchange rate you entered");
        }
        jdbc.sql("UPDATE \"CurrencyRate\" SET status = ?, \"approvedBy\" = ?, \"approvedAt\" = now() WHERE id = ?")
                .params(approve ? "approved" : "rejected", me, id).update();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", approve ? "approved" : "rejected");
        return out;
    }

    /** FX-2 B: the company's policy for {@code context} (defaults when none is stored or the context is general). */
    public Policy policy(String context) {
        if (context == null || !CONTEXTS.contains(context)) {
            return Policy.defaults(context);
        }
        String org = tenant();
        if (org == null) {
            return Policy.defaults(context);
        }
        return jdbc.sql("SELECT \"rateType\", \"requireApprovedRate\", \"statedRateTolerancePct\", \"allowStatedRate\""
                        + " FROM \"FxRatePolicy\" WHERE \"organizationId\" = ? AND context = ?")
                .params(org, context)
                .query((rs, n) -> new Policy(context, rs.getString(1), rs.getBoolean(2), rs.getBigDecimal(3),
                        rs.getBoolean(4), true))
                .optional().orElse(Policy.defaults(context));
    }

    /** FX-2 B: every context's effective policy for the caller's company. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPolicies() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String c : CONTEXTS) {
            Policy p = policy(c);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("context", c);
            m.put("rateType", p.rateType());
            m.put("requireApprovedRate", p.requireApprovedRate());
            m.put("statedRateTolerancePct", p.statedRateTolerancePct());
            m.put("allowStatedRate", p.allowStatedRate());
            m.put("configured", p.stored());
            out.add(m);
        }
        return out;
    }

    /** FX-2 B: store the caller's company policy for one context. */
    @Transactional
    public Map<String, Object> savePolicy(String context, String rateType, Boolean requireApprovedRate,
            BigDecimal tolerancePct, Boolean allowStatedRate) {
        if (!CONTEXTS.contains(context)) {
            throw ApiException.badRequest("context must be one of the following values: " + String.join(", ", CONTEXTS));
        }
        String type = rateType == null ? "official" : rateType.trim().toLowerCase();
        if (!RATE_TYPES.contains(type)) {
            throw ApiException.badRequest("rateType must be one of the following values: official, bank, cash, market");
        }
        if (tolerancePct != null && tolerancePct.signum() < 0) {
            throw ApiException.badRequest("statedRateTolerancePct must not be less than 0");
        }
        jdbc.sql("INSERT INTO \"FxRatePolicy\" (id, \"organizationId\", context, \"rateType\", \"requireApprovedRate\","
                        + " \"statedRateTolerancePct\", \"allowStatedRate\", \"updatedBy\") VALUES (gen_random_uuid()::text,"
                        + " ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (\"organizationId\", context) DO UPDATE SET \"rateType\" ="
                        + " EXCLUDED.\"rateType\", \"requireApprovedRate\" = EXCLUDED.\"requireApprovedRate\","
                        + " \"statedRateTolerancePct\" = EXCLUDED.\"statedRateTolerancePct\", \"allowStatedRate\" ="
                        + " EXCLUDED.\"allowStatedRate\", \"updatedBy\" = EXCLUDED.\"updatedBy\", \"updatedAt\" = now()")
                .params(tenant(), context, type, Boolean.TRUE.equals(requireApprovedRate), tolerancePct,
                        allowStatedRate == null || allowStatedRate, TenantContext.userId())
                .update();
        Map<String, Object> out = new LinkedHashMap<>();
        Policy p = policy(context);
        out.put("context", context);
        out.put("rateType", p.rateType());
        out.put("requireApprovedRate", p.requireApprovedRate());
        out.put("statedRateTolerancePct", p.statedRateTolerancePct());
        out.put("allowStatedRate", p.allowStatedRate());
        out.put("configured", true);
        return out;
    }

    private static String tenant() {
        String org = TenantContext.organizationId();
        return org == null || org.isEmpty() ? null : org;
    }

    /** FX rate (TO per 1 FROM) valid at {@code asOf}. */
    @Transactional(readOnly = true)
    public BigDecimal getRate(String fromCode, String toCode, Instant asOf) {
        BigDecimal rate = findRate(fromCode, toCode, asOf);
        if (rate == null) {
            throw ApiException.notFound("No FX rate for " + fromCode + "→" + toCode + " at or before "
                    + FiscalPeriodControl.day(asOf));
        }
        return rate;
    }

    /**
     * The table rate (TO per 1 FROM) at or before {@code asOf}, direct or the reciprocal of the inverse pair, or
     * {@code null}. Never throws and opens no transaction of its own, so a missing rate cannot mark the caller's
     * transaction rollback-only (LC-0 finding G2).
     */
    public BigDecimal findRate(String fromCode, String toCode, Instant asOf) {
        return findRate(fromCode, toCode, asOf, "official", false);
    }

    /**
     * FX-2 B: as {@link #findRate(String, String, Instant)} for one rate type; {@code approvedOnly} ignores pending
     * rates (rejected rates are never used).
     */
    public BigDecimal findRate(String fromCode, String toCode, Instant asOf, String rateType, boolean approvedOnly) {
        if (fromCode.equals(toCode)) {
            return BigDecimal.ONE;
        }
        BigDecimal direct = lookupRate(fromCode, toCode, asOf, rateType, approvedOnly);
        if (direct != null && direct.signum() > 0) {
            return direct;
        }
        BigDecimal inverse = lookupRate(toCode, fromCode, asOf, rateType, approvedOnly);
        return inverse != null && inverse.signum() > 0 ? BigDecimal.ONE.divide(inverse, DECIMAL_JS) : null;
    }

    /**
     * Rate of a foreign-currency amount carried on a stored document (owner decision D-PC1): an explicit document rate
     * wins, else the rate table, else it is refused. A table rate never overrides a stated document rate. Documents
     * store 1 when no rate was stated (column default), so here a rate of exactly 1 on a foreign currency is treated as
     * absent. A rate the caller's own request states (where {@code null} means "not stated") goes through
     * {@link #resolveStated}, which honours 1.
     */
    public Resolution resolve(String fromCode, String toCode, Object explicitRate, Instant date) {
        return resolve(fromCode, toCode, explicitRate, date, false, null);
    }

    /** FX-2 B: {@link #resolve(String, String, Object, Instant)} under the company's policy for {@code context}. */
    public Resolution resolve(String fromCode, String toCode, Object explicitRate, Instant date, String context) {
        return resolve(fromCode, toCode, explicitRate, date, false, context);
    }

    /** FX-2 B: {@link #resolveStated(String, String, Object, Instant)} under the policy for {@code context}. */
    public Resolution resolveStated(String fromCode, String toCode, Object statedRate, Instant date, String context) {
        return resolve(fromCode, toCode, statedRate, date, true, context);
    }

    /** FX-2 B: the policy rate (no stated rate) for {@code context} at {@code date}, refused when there is none. */
    public BigDecimal policyRate(String fromCode, String toCode, Instant date, String context) {
        return resolve(fromCode, toCode, null, date, true, context).rate();
    }

    /**
     * As {@link #resolve} for a rate stated on the request itself: {@code null} = not stated (table, else refused); any
     * positive stated rate wins over the table, an explicit 1 (a pegged or 1:1 rate) included.
     */
    public Resolution resolveStated(String fromCode, String toCode, Object statedRate, Instant date) {
        return resolve(fromCode, toCode, statedRate, date, true, null);
    }

    private Resolution resolve(String fromCode, String toCode, Object explicitRate, Instant date, boolean oneIsStated,
            String context) {
        Policy p = policy(context);
        BigDecimal table = "official".equals(p.rateType()) && !p.requireApprovedRate()
                ? findRate(fromCode, toCode, date)
                : findRate(fromCode, toCode, date, p.rateType(), p.requireApprovedRate());
        if (explicitRate != null) {
            BigDecimal stated = Money.dec(explicitRate);
            if (stated.signum() <= 0) {
                throw ApiException.badRequest("exchangeRate must be greater than zero");
            }
            if (oneIsStated || stated.compareTo(BigDecimal.ONE) != 0) {
                String reason = statedRateReason(p, stated, table);
                return new Resolution(fromCode, toCode, stated, "document", date, p.rateType(), context, table, reason);
            }
        }
        if (table == null) {
            String kind = "official".equals(p.rateType()) && !p.requireApprovedRate() ? ""
                    : (p.requireApprovedRate() ? "approved " : "") + p.rateType() + " ";
            throw ApiException.badRequest("No " + kind + "exchange rate for " + fromCode + "→" + toCode + " on "
                    + FiscalPeriodControl.day(date) + ": state the document's rate or add one to the rate table");
        }
        return new Resolution(fromCode, toCode, table, "table", date, p.rateType(), context, table, null);
    }

    /**
     * FX-2 B: whether a stated rate is acceptable under the policy; returns the override reason when one was needed.
     * A policy may forbid stated rates, or bound them to a tolerance around its own rate; beyond it the caller needs
     * {@code currency:rate_override} and a reason (header {@code X-Fx-Rate-Override-Reason}).
     */
    private String statedRateReason(Policy p, BigDecimal stated, BigDecimal policyRate) {
        if (!p.allowStatedRate()) {
            throw ApiException.badRequest("Stated exchange rates are not allowed for " + p.context()
                    + " transactions: the " + p.rateType() + " rate applies");
        }
        if (p.statedRateTolerancePct() == null || policyRate == null || policyRate.signum() == 0) {
            return FxRateOverride.reason();
        }
        BigDecimal deviation = stated.subtract(policyRate).abs().multiply(BigDecimal.valueOf(100))
                .divide(policyRate, 6, RoundingMode.HALF_UP);
        if (deviation.compareTo(p.statedRateTolerancePct()) <= 0) {
            return FxRateOverride.reason();
        }
        String reason = FxRateOverride.reason();
        if (reason == null || !TenantContext.has(com.inventoryerp.kernel.security.Permissions.CURRENCY__RATE_OVERRIDE)) {
            throw ApiException.badRequest("The stated rate " + stated.stripTrailingZeros().toPlainString()
                    + " differs from the " + p.rateType() + " rate " + policyRate.stripTrailingZeros().toPlainString()
                    + " by more than " + p.statedRateTolerancePct().stripTrailingZeros().toPlainString()
                    + "%: give a reason (permission currency:rate_override)");
        }
        return reason;
    }

    public BigDecimal convert(Object amount, String fromCode, String toCode, Instant asOf) {
        return Money.dec(amount).multiply(getRate(fromCode, toCode, asOf), DECIMAL_JS);
    }

    /** Rates for a pair the caller's organization can use (its own and the shared ones), most recent first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRates(String fromCode, String toCode, int limit) {
        return listRates(fromCode, toCode, limit, null, null);
    }

    /** FX-2 B: rates filtered by type and status; rows also carry id, type, status and who entered / approved them. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRates(String fromCode, String toCode, int limit, String rateType,
            String status) {
        StringBuilder sql = new StringBuilder("SELECT \"asOf\", rate, source, id, \"rateType\", status, \"createdBy\","
                + " \"approvedBy\", (\"organizationId\" IS NULL) AS shared FROM \"CurrencyRate\" WHERE"
                + " (\"organizationId\" IS NULL OR \"organizationId\" = ?)");
        List<Object> params = new java.util.ArrayList<>();
        params.add(tenant());
        if (fromCode != null) {
            sql.append(" AND \"fromCode\" = ?");
            params.add(fromCode);
        }
        if (toCode != null) {
            sql.append(" AND \"toCode\" = ?");
            params.add(toCode);
        }
        if (rateType != null && !rateType.isBlank()) {
            sql.append(" AND \"rateType\" = ?");
            params.add(rateType);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        if (fromCode == null && toCode == null && (status == null || status.isBlank())) {
            // Pre-FX-2 contract: no pair and no filter lists nothing.
            return List.of();
        }
        sql.append(" ORDER BY \"asOf\" DESC, (\"organizationId\" IS NULL) ASC LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("asOf", rs.getTimestamp(1).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC));
                    m.put("rate", rs.getBigDecimal(2));
                    m.put("source", rs.getString(3));
                    m.put("id", rs.getString(4));
                    m.put("rateType", rs.getString(5));
                    m.put("status", rs.getString(6));
                    m.put("createdBy", rs.getString(7));
                    m.put("approvedBy", rs.getString(8));
                    m.put("shared", rs.getBoolean(9));
                    return m;
                }).list();
    }

    /**
     * Latest rate at or before {@code asOf}: the organization's own rate when it has any for the pair, else the shared
     * rate (ACC-2, B4). An organization that keeps its own rates is never mixed with someone else's.
     */
    private BigDecimal lookupRate(String fromCode, String toCode, Instant asOf, String rateType,
            boolean approvedOnly) {
        return jdbc.sql("SELECT rate FROM \"CurrencyRate\" WHERE \"fromCode\" = ? AND \"toCode\" = ? AND \"asOf\" <= ?"
                        + " AND (\"organizationId\" IS NULL OR \"organizationId\" = ?) AND \"rateType\" = ?"
                        + (approvedOnly ? " AND status = 'approved'" : " AND status <> 'rejected'")
                        + " ORDER BY (\"organizationId\" IS NULL) ASC, \"asOf\" DESC LIMIT 1")
                .params(fromCode, toCode, FiscalPeriodControl.ts(asOf), tenant(),
                        rateType == null ? "official" : rateType)
                .query(BigDecimal.class).optional().orElse(null);
    }
}
