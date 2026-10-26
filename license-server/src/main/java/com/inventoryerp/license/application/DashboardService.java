package com.inventoryerp.license.application;

import com.inventoryerp.license.crypto.LicenseKeyCodec;
import com.inventoryerp.license.domain.LicenseActivation;
import com.inventoryerp.license.domain.LicenseActivationRepository;
import com.inventoryerp.license.domain.LicenseHeartbeat;
import com.inventoryerp.license.domain.LicenseHeartbeatRepository;
import com.inventoryerp.license.domain.LicensePeriod;
import com.inventoryerp.license.domain.LicenseRepository;
import com.inventoryerp.license.domain.LicenseStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one screen the business is run from: what is live, what lapses soon, who has gone quiet, and
 * who is over the limits they pay for.
 *
 * <p>Recurring revenue is an <em>estimate</em> and labelled as one. Each active, dated licence is
 * normalised to a monthly figure from its plan's price and term; trials and perpetual licences
 * contribute nothing. Money is {@link BigDecimal} over integer minor units throughout — never a
 * double (AGENTS.md §8) — and mixed-currency portfolios are reported per currency rather than
 * summed at an invented exchange rate.
 */
@Service
public class DashboardService {

    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.4375"); // mean Gregorian month

    private final LicenseRepository licenses;
    private final LicenseActivationRepository activations;
    private final LicenseHeartbeatRepository heartbeats;
    private final LicenseService licenseService;
    private final LicenseProperties properties;
    private final JdbcClient jdbc;

    public DashboardService(LicenseRepository licenses, LicenseActivationRepository activations,
            LicenseHeartbeatRepository heartbeats, LicenseService licenseService, LicenseProperties properties,
            JdbcClient jdbc) {
        this.licenses = licenses;
        this.activations = activations;
        this.heartbeats = heartbeats;
        this.licenseService = licenseService;
        this.properties = properties;
        this.jdbc = jdbc;
    }

    /** One install that needs attention, in the shape the admin UI lists. */
    public record Attention(String licenseId, String licenseKey, String customer, String plan, String reason,
            String detail, Instant since) {}

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Instant now = Instant.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", now.toString());
        out.put("licenses", statusCounts());
        out.put("expiring", Map.of(
                "in7Days", expiringWithin(now, Duration.ofDays(7)),
                "in30Days", expiringWithin(now, Duration.ofDays(30))));
        out.put("activations", Map.of(
                "active", countActivations("active"),
                "deactivated", countActivations("deactivated")));
        out.put("recurringRevenueEstimate", recurringRevenueByCurrency());
        out.put("silentInstalls", silentInstalls(now));
        out.put("overLimitInstalls", overLimitInstalls());
        return out;
    }

    private Map<String, Long> statusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (LicenseStatus status : LicenseStatus.values()) {
            counts.put(status.value(), licenses.countByStatus(status.value()));
        }
        return counts;
    }

    private int expiringWithin(Instant now, Duration window) {
        return licenses.expiringBetween(now, now.plus(window)).size();
    }

    private long countActivations(String status) {
        Long value = jdbc.sql("SELECT count(*) FROM license_activation WHERE status = ?")
                .param(status)
                .query(Long.class)
                .single();
        return value == null ? 0L : value;
    }

    /**
     * Monthly-equivalent value of the active book, per currency. Minor units in, minor units out —
     * the caller formats.
     */
    private Map<String, Long> recurringRevenueByCurrency() {
        Map<String, Long> byCurrency = new TreeMap<>();
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT p.currency AS currency, p.price_minor AS price_minor, p.period AS period,
                               p.period_count AS period_count, count(*) AS licences
                        FROM license l
                        JOIN plan p ON p.id = l.plan_id
                        WHERE l.status = 'active'
                          AND p.is_trial = false
                          AND p.period <> 'PERPETUAL'
                        GROUP BY p.currency, p.price_minor, p.period, p.period_count""")
                .query()
                .listOfRows();
        for (Map<String, Object> row : rows) {
            BigDecimal months = termInMonths(
                    LicensePeriod.of((String) row.get("period")),
                    ((Number) row.get("period_count")).intValue());
            if (months.signum() <= 0) {
                continue;
            }
            BigDecimal price = BigDecimal.valueOf(((Number) row.get("price_minor")).longValue());
            BigDecimal count = BigDecimal.valueOf(((Number) row.get("licences")).longValue());
            long monthly = price.multiply(count)
                    .divide(months, 0, RoundingMode.HALF_UP)
                    .longValueExact();
            byCurrency.merge((String) row.get("currency"), monthly, Long::sum);
        }
        return byCurrency;
    }

    private static BigDecimal termInMonths(LicensePeriod period, int periodCount) {
        BigDecimal count = BigDecimal.valueOf(periodCount);
        return switch (period) {
            case DAY -> count.divide(DAYS_PER_MONTH, 10, RoundingMode.HALF_UP);
            case MONTH -> count;
            case YEAR -> count.multiply(BigDecimal.valueOf(12));
            case TRIAL, PERPETUAL -> BigDecimal.ZERO;
        };
    }

    /**
     * Installs that have missed several check-ins in a row. The threshold is per plan, because a
     * daily plan reports hourly and a yearly one once a day — one fixed cut-off would either cry
     * wolf about the second or miss a week of silence from the first.
     */
    @Transactional(readOnly = true)
    public List<Attention> silentInstalls(Instant now) {
        int multiplier = Math.max(2, properties.getReminders().getSilentMultiplier());
        // Widest plausible window first, then filter precisely per plan.
        Instant widest = now.minus(LicensePeriod.DAY.heartbeatInterval().multipliedBy(multiplier));
        List<Attention> out = new ArrayList<>();
        for (LicenseActivation activation : activations.liveButLastSeenBefore(widest)) {
            LicenseService.Loaded loaded;
            try {
                loaded = licenseService.load(activation.getLicenseId());
            } catch (RuntimeException e) {
                continue;
            }
            if (loaded.license().statusType() != LicenseStatus.ACTIVE) {
                continue;
            }
            Duration threshold = loaded.plan().periodType().heartbeatInterval().multipliedBy(multiplier);
            if (activation.getLastSeenAt().isAfter(now.minus(threshold))) {
                continue;
            }
            long hours = Duration.between(activation.getLastSeenAt(), now).toHours();
            out.add(new Attention(loaded.license().getId(), LicenseKeyCodec.mask(loaded.license().getKey()),
                    loaded.customer().getName(), loaded.plan().getCode(), "silent",
                    "No check-in for " + hours + "h (expected every "
                            + loaded.plan().periodType().heartbeatInterval().toHours() + "h)",
                    activation.getLastSeenAt()));
        }
        return out;
    }

    /**
     * Installs whose last check-in reported more devices, users or organizations than the licence
     * allows. Not an enforcement signal — the install already refused the call that would have gone
     * over — but the clearest upsell list there is.
     */
    @Transactional(readOnly = true)
    public List<Attention> overLimitInstalls() {
        List<Attention> out = new ArrayList<>();
        for (LicenseActivation activation : activations.findAll()) {
            if (!activation.isActive()) {
                continue;
            }
            LicenseHeartbeat latest = heartbeats.findFirstByActivationIdOrderByAtDesc(activation.getId())
                    .orElse(null);
            if (latest == null) {
                continue;
            }
            LicenseService.Loaded loaded;
            try {
                loaded = licenseService.load(activation.getLicenseId());
            } catch (RuntimeException e) {
                continue;
            }
            Entitlements entitlements = loaded.entitlements();
            List<String> over = new ArrayList<>();
            describeOver(over, "devices", latest.getDeviceCount(), entitlements.maxDevices());
            describeOver(over, "users", latest.getUserCount(), entitlements.maxUsers());
            describeOver(over, "organizations", latest.getOrgCount(), entitlements.maxOrganizations());
            if (!over.isEmpty()) {
                out.add(new Attention(loaded.license().getId(), LicenseKeyCodec.mask(loaded.license().getKey()),
                        loaded.customer().getName(), loaded.plan().getCode(), "over_limit",
                        String.join(", ", over), latest.getAt()));
            }
        }
        return out;
    }

    private static void describeOver(List<String> into, String name, Integer reported, Integer limit) {
        if (reported != null && limit != null && reported > limit) {
            into.add(reported + " " + name + " on a limit of " + limit);
        }
    }
}
