package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Currency;
import com.inventoryerp.accounting.domain.CurrencyRate;
import com.inventoryerp.kernel.common.Money;
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

    /** Idempotent rate upsert for (from, to, asOf). */
    @Transactional
    public void upsertRate(String fromCode, String toCode, Instant asOf, BigDecimal rate, String source) {
        if (fromCode.equals(toCode)) {
            throw ApiException.badRequest("fromCode and toCode must differ");
        }
        jdbc.sql("INSERT INTO \"CurrencyRate\" (id, \"fromCode\", \"toCode\", \"asOf\", rate, source)"
                        + " VALUES (gen_random_uuid()::text, ?, ?, ?, ?, ?)"
                        + " ON CONFLICT (\"fromCode\", \"toCode\", \"asOf\") DO UPDATE SET rate = EXCLUDED.rate,"
                        + " source = EXCLUDED.source")
                .params(fromCode, toCode, FiscalPeriodControl.ts(asOf), rate, source).update();
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

    /** Rates for a pair, most recent first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRates(String fromCode, String toCode, int limit) {
        return em.createQuery("select r from CurrencyRate r where r.fromCode = :f and r.toCode = :t order by r.asOf desc",
                        CurrencyRate.class)
                .setParameter("f", fromCode).setParameter("t", toCode).setMaxResults(limit).getResultList().stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("asOf", r.getAsOf());
                    m.put("rate", r.getRate());
                    m.put("source", r.getSource());
                    return m;
                }).toList();
    }

    private BigDecimal lookupRate(String fromCode, String toCode, Instant asOf) {
        List<CurrencyRate> rows = em.createQuery("select r from CurrencyRate r where r.fromCode = :f and r.toCode = :t"
                        + " and r.asOf <= :d order by r.asOf desc", CurrencyRate.class)
                .setParameter("f", fromCode).setParameter("t", toCode).setParameter("d", asOf)
                .setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : rows.getFirst().getRate();
    }
}
