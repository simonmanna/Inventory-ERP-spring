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
        if (fromCode.equals(toCode)) {
            return BigDecimal.ONE;
        }
        BigDecimal direct = lookupRate(fromCode, toCode, asOf);
        if (direct != null) {
            return direct;
        }
        BigDecimal inverse = lookupRate(toCode, fromCode, asOf);
        if (inverse != null && inverse.signum() != 0) {
            return BigDecimal.ONE.divide(inverse, DECIMAL_JS);
        }
        throw ApiException.notFound("No FX rate for " + fromCode + "→" + toCode + " at or before "
                + FiscalPeriodControl.day(asOf));
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
