package com.inventoryerp.accounting.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FX-8 (ADR-S082 Outstanding): automatic exchange-rate fetch. Off unless {@code FX_RATE_FETCH_ENABLED=true} and a
 * provider URL is configured, so no outbound call is ever made by default.
 *
 * <ul>
 *   <li>Provider: {@code FX_RATE_PROVIDER_URL}, a JSON endpoint with {@code {base}} replaced by the company's functional
 *       currency, answering {@code {"rates": {"USD": 0.00027, ...}}} (or {@code conversion_rates}): units of each
 *       currency per one functional unit. The stored rate is the inverse (functional per foreign unit).</li>
 *   <li>Currencies fetched: those the company uses (rates on file, accounts held in a currency).</li>
 *   <li>Manual override wins: a rate already on file for the day and rate type (any source) is never replaced.</li>
 *   <li>Rates go through {@link CurrencyService#upsertRate}, so the company's approval policy applies (a fetched rate
 *       of a type that requires approval waits as {@code pending}).</li>
 *   <li>Missing-rate warning: {@link #missing} lists used currencies with no approved rate within N days.</li>
 * </ul>
 */
@Service
public class FxRateFetchService {

    private static final Logger log = LoggerFactory.getLogger(FxRateFetchService.class);

    private final JdbcClient jdbc;
    private final CurrencyService currencies;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String providerUrl;
    private final String providerName;
    private final String rateType;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public FxRateFetchService(JdbcClient jdbc, CurrencyService currencies, AuditService audit,
            org.springframework.transaction.PlatformTransactionManager txm, ObjectMapper json,
            @Value("${FX_RATE_FETCH_ENABLED:false}") boolean enabled,
            @Value("${FX_RATE_PROVIDER_URL:}") String providerUrl,
            @Value("${FX_RATE_PROVIDER_NAME:provider}") String providerName,
            @Value("${FX_RATE_FETCH_RATE_TYPE:market}") String rateType) {
        this.jdbc = jdbc;
        this.currencies = currencies;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
        this.json = json;
        this.enabled = enabled;
        this.providerUrl = providerUrl == null ? "" : providerUrl.trim();
        this.providerName = providerName == null || providerName.isBlank() ? "provider" : providerName.trim();
        this.rateType = rateType == null || rateType.isBlank() ? "market" : rateType.trim().toLowerCase();
    }

    /** Whether fetching is switched on and configured. */
    public boolean configured() {
        return enabled && !providerUrl.isEmpty();
    }

    /** Daily run for every company (cron {@code FX_RATE_FETCH_CRON}, default 06:30 server time). */
    @Scheduled(cron = "${FX_RATE_FETCH_CRON:0 30 6 * * *}")
    @SchedulerLock(name = "fx-rate-fetch", lockAtMostFor = "PT30M")
    public void daily() {
        if (!configured()) {
            return;
        }
        for (String org : jdbc.sql("SELECT id FROM \"Organization\" WHERE \"currencyCode\" IS NOT NULL")
                .query(String.class).list()) {
            try {
                TenantContext.run(TenantContext.TenantStore.organization(org), () -> fetchForCurrentOrg());
            } catch (RuntimeException e) {
                log.warn("FX rate fetch failed for {}: {}", org, e.toString());
            }
        }
    }

    /**
     * Fetch today's rates for the caller's company (also the manual {@code POST /currencies/rates/fetch}). Returns what
     * was stored, skipped (manual rate already on file) and not offered by the provider.
     */
    public Map<String, Object> fetchForCurrentOrg() {
        if (!configured()) {
            throw ApiException.badRequest("Automatic rate fetch is not configured (FX_RATE_FETCH_ENABLED and"
                    + " FX_RATE_PROVIDER_URL)");
        }
        String org = TenantContext.organizationId();
        String functional = ForeignCash.functional(jdbc, org);
        List<String> used = used(org, functional);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("functionalCurrency", functional);
        out.put("rateType", rateType);
        List<Map<String, Object>> stored = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        if (used.isEmpty()) {
            out.put("stored", stored);
            out.put("skipped", skipped);
            out.put("unavailable", unavailable);
            return out;
        }
        JsonNode rates = provider(functional);
        LocalDate day = LocalDate.now(AccountingDay.zone(jdbc, org));
        Instant asOf = EntityPatch.toInstant(day.toString());
        for (String ccy : used) {
            JsonNode node = rates.get(ccy);
            if (node == null || !node.isNumber() || node.decimalValue().signum() <= 0) {
                unavailable.add(ccy);
                continue;
            }
            boolean onFile = jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"CurrencyRate\" WHERE \"organizationId\" = ? AND"
                    + " upper(\"fromCode\") = ? AND upper(\"toCode\") = ? AND \"asOf\" = ? AND \"rateType\" = ?)")
                    .params(org, ccy, functional, FiscalPeriodControl.ts(asOf), rateType).query(Boolean.class).single();
            if (onFile) {
                skipped.add(ccy);
                continue;
            }
            BigDecimal rate = BigDecimal.ONE.divide(node.decimalValue(), MathContext.DECIMAL64)
                    .setScale(8, RoundingMode.HALF_UP);
            tx.executeWithoutResult(s -> {
                currencies.upsertRate(ccy, functional, asOf, rate, providerName, rateType);
                audit.recordInTx(AuditInput.of("CurrencyRate", ccy + "/" + functional + "/" + day, "create", null,
                        Obj.of("rate", rate.toPlainString(), "source", providerName, "rateType", rateType)));
            });
            stored.add(Obj.of("currency", ccy, "rate", rate.toPlainString()));
        }
        out.put("stored", stored);
        out.put("skipped", skipped);
        out.put("unavailable", unavailable);
        return out;
    }

    /**
     * Used currencies of the caller's company with no approved rate to the functional currency dated within
     * {@code maxAgeDays} (default 3): the missing-rate warning.
     */
    public List<Map<String, Object>> missing(Integer maxAgeDays) {
        String org = TenantContext.organizationId();
        String functional = ForeignCash.functional(jdbc, org);
        int days = maxAgeDays == null || maxAgeDays < 0 ? 3 : maxAgeDays;
        LocalDate today = LocalDate.now(AccountingDay.zone(jdbc, org));
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ccy : used(org, functional)) {
            Instant latest = jdbc.sql("SELECT max(\"asOf\") FROM \"CurrencyRate\" WHERE (\"organizationId\" = ? OR"
                    + " \"organizationId\" IS NULL) AND upper(\"fromCode\") = ? AND upper(\"toCode\") = ? AND"
                    + " coalesce(status, 'approved') = 'approved'").params(org, ccy, functional)
                    .query(java.sql.Timestamp.class).optional().map(java.sql.Timestamp::toInstant).orElse(null);
            LocalDate latestDay = latest == null ? null : latest.atZone(java.time.ZoneOffset.UTC).toLocalDate();
            if (latestDay == null || latestDay.isBefore(today.minusDays(days))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("currency", ccy);
                row.put("latestRateDate", latestDay == null ? null : latestDay.toString());
                out.add(row);
            }
        }
        return out;
    }

    private List<String> used(String org, String functional) {
        return jdbc.sql("SELECT DISTINCT c FROM (SELECT upper(\"fromCode\") AS c FROM \"CurrencyRate\" WHERE"
                + " \"organizationId\" = ? AND upper(\"toCode\") = ? UNION SELECT upper(\"currencyId\") FROM \"Account\""
                + " WHERE \"organizationId\" = ? AND \"currencyId\" IS NOT NULL AND \"deletedAt\" IS NULL) u WHERE c <> ?"
                + " ORDER BY c").params(org, functional, org, functional).query(String.class).list();
    }

    private JsonNode provider(String base) {
        String url = providerUrl.replace("{base}", base);
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw ApiException.badRequest("The rate provider answered " + res.statusCode());
            }
            JsonNode body = json.readTree(res.body());
            JsonNode rates = body.has("rates") ? body.get("rates") : body.get("conversion_rates");
            if (rates == null || !rates.isObject()) {
                throw ApiException.badRequest("The rate provider's answer has no rates");
            }
            return rates;
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("The rate provider could not be reached: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.badRequest("The rate fetch was interrupted");
        }
    }
}
