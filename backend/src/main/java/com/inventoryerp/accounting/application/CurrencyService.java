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
    public record Resolution(String fromCode, String toCode, BigDecimal rate, String source, Instant rateDate) {}

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
        if (fromCode.equals(toCode)) {
            throw ApiException.badRequest("fromCode and toCode must differ");
        }
        jdbc.sql("INSERT INTO \"CurrencyRate\" (id, \"organizationId\", \"fromCode\", \"toCode\", \"asOf\", rate, source,"
                        + " \"createdBy\") VALUES (gen_random_uuid()::text, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT ((coalesce(\"organizationId\", '')), \"fromCode\", \"toCode\", \"asOf\") DO UPDATE"
                        + " SET rate = EXCLUDED.rate, source = EXCLUDED.source, \"createdBy\" = EXCLUDED.\"createdBy\"")
                .params(tenant(), fromCode, toCode, FiscalPeriodControl.ts(asOf), rate, source, TenantContext.userId())
                .update();
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
        if (fromCode.equals(toCode)) {
            return BigDecimal.ONE;
        }
        BigDecimal direct = lookupRate(fromCode, toCode, asOf);
        if (direct != null) {
            return direct;
        }
        BigDecimal inverse = lookupRate(toCode, fromCode, asOf);
        return inverse != null && inverse.signum() != 0 ? BigDecimal.ONE.divide(inverse, DECIMAL_JS) : null;
    }

    /**
     * Rate of a foreign-currency amount (owner decision D-PC1): an explicit document rate wins, else the rate table,
     * else it is refused. A table rate never overrides a stated document rate. A rate of exactly 1 on a foreign currency
     * is the legacy "not stated" default of documents and is treated as absent; a genuine 1:1 rate goes in the table.
     */
    public Resolution resolve(String fromCode, String toCode, Object explicitRate, Instant date) {
        if (explicitRate != null) {
            BigDecimal stated = Money.dec(explicitRate);
            if (stated.signum() <= 0) {
                throw ApiException.badRequest("exchangeRate must be greater than zero");
            }
            if (stated.compareTo(BigDecimal.ONE) != 0) {
                return new Resolution(fromCode, toCode, stated, "document", date);
            }
        }
        BigDecimal table = findRate(fromCode, toCode, date);
        if (table == null) {
            throw ApiException.badRequest("No exchange rate for " + fromCode + "→" + toCode + " on "
                    + FiscalPeriodControl.day(date) + ": state the document's rate or add one to the rate table");
        }
        return new Resolution(fromCode, toCode, table, "table", date);
    }

    public BigDecimal convert(Object amount, String fromCode, String toCode, Instant asOf) {
        return Money.dec(amount).multiply(getRate(fromCode, toCode, asOf), DECIMAL_JS);
    }

    /** Rates for a pair the caller's organization can use (its own and the shared ones), most recent first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRates(String fromCode, String toCode, int limit) {
        return jdbc.sql("SELECT \"asOf\", rate, source FROM \"CurrencyRate\" WHERE \"fromCode\" = ? AND \"toCode\" = ?"
                        + " AND (\"organizationId\" IS NULL OR \"organizationId\" = ?)"
                        + " ORDER BY \"asOf\" DESC, (\"organizationId\" IS NULL) ASC LIMIT ?")
                .params(fromCode, toCode, tenant(), limit)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("asOf", rs.getTimestamp(1).toLocalDateTime().toInstant(java.time.ZoneOffset.UTC));
                    m.put("rate", rs.getBigDecimal(2));
                    m.put("source", rs.getString(3));
                    return m;
                }).list();
    }

    /**
     * Latest rate at or before {@code asOf}: the organization's own rate when it has any for the pair, else the shared
     * rate (ACC-2, B4). An organization that keeps its own rates is never mixed with someone else's.
     */
    private BigDecimal lookupRate(String fromCode, String toCode, Instant asOf) {
        return jdbc.sql("SELECT rate FROM \"CurrencyRate\" WHERE \"fromCode\" = ? AND \"toCode\" = ? AND \"asOf\" <= ?"
                        + " AND (\"organizationId\" IS NULL OR \"organizationId\" = ?)"
                        + " ORDER BY (\"organizationId\" IS NULL) ASC, \"asOf\" DESC LIMIT 1")
                .params(fromCode, toCode, FiscalPeriodControl.ts(asOf), tenant()).query(BigDecimal.class).optional()
                .orElse(null);
    }
}
