package com.inventoryerp.alerts.spi;

import com.inventoryerp.kernel.security.Permissions;
import java.math.BigDecimal;
import java.util.List;

/** The alert catalog: every type with its defaults. A type is added here and fed by one or more sources. */
public final class AlertTypes {

    public static final String STOCK_BELOW_REORDER = "stock_below_reorder";
    public static final String WEIGHBRIDGE_EXCEPTION = "weighbridge_exception";
    public static final String YIELD_BELOW_THRESHOLD = "yield_below_threshold";
    public static final String CURING_RELEASE_DUE = "curing_release_due";
    public static final String EXPIRING_DOCUMENT = "expiring_document";
    public static final String OVERDUE_MAINTENANCE = "overdue_maintenance";
    public static final String CREDIT_LIMIT_BREACH = "credit_limit_breach";
    public static final String BUDGET_OVERRUN = "budget_overrun";
    public static final String CAPA_OVERDUE = "capa_overdue";

    /** Scanned every hour: what the yard should act on today. */
    public static final String HOURLY = "hourly";
    /** Scanned once a day, before the working day. */
    public static final String DAILY = "daily";

    public static final String CHANNEL_IN_APP = "in_app";
    public static final String CHANNEL_EMAIL = "email";
    public static final String CHANNEL_WHATSAPP = "whatsapp";
    public static final List<String> CHANNELS = List.of(CHANNEL_IN_APP, CHANNEL_EMAIL, CHANNEL_WHATSAPP);

    public static final String REPEAT_DAILY = "daily";
    public static final String REPEAT_WEEKLY = "weekly";
    public static final String REPEAT_MONTHLY = "monthly";
    public static final List<String> REPEATS = List.of(REPEAT_DAILY, REPEAT_WEEKLY, REPEAT_MONTHLY);

    /**
     * One alert type.
     *
     * @param thresholdUnit {@code percent}, {@code days} or {@code hours}; null when the type has no threshold
     * @param thresholdHelp what the threshold means, shown on the rule form
     */
    public record Definition(String type, String label, String cadence, BigDecimal defaultThreshold,
            String thresholdUnit, String thresholdHelp, String defaultRepeat, List<String> defaultChannels) {}

    private static final List<String> BELL_AND_EMAIL = List.of(CHANNEL_IN_APP, CHANNEL_EMAIL);

    public static final List<Definition> ALL = List.of(
            // The replenishment scan already rings the bell when an alert opens, so this type starts on e-mail alone.
            new Definition(STOCK_BELOW_REORDER, "Stock below reorder level", HOURLY, null, null, null, REPEAT_DAILY,
                    List.of(CHANNEL_EMAIL)),
            new Definition(WEIGHBRIDGE_EXCEPTION, "Weighbridge exceptions", HOURLY, BigDecimal.ZERO, "hours",
                    "Alert when an exception has waited for a decision this many hours", REPEAT_DAILY, BELL_AND_EMAIL),
            new Definition(CURING_RELEASE_DUE, "Curing release due", HOURLY, new BigDecimal("24"), "hours",
                    "Alert this many hours before the minimum curing time ends", REPEAT_DAILY,
                    List.of(CHANNEL_IN_APP)),
            new Definition(YIELD_BELOW_THRESHOLD, "Yield below threshold", DAILY, new BigDecimal("90"), "percent",
                    "Minimum yield over the last 7 days: crushing output on feed, good blocks on blocks cured",
                    REPEAT_WEEKLY, BELL_AND_EMAIL),
            new Definition(EXPIRING_DOCUMENT, "Expiring documents and licences", DAILY, new BigDecimal("30"), "days",
                    "Lead days for quarry licences and employee documents; fleet documents use their own lead days",
                    REPEAT_WEEKLY, BELL_AND_EMAIL),
            new Definition(OVERDUE_MAINTENANCE, "Overdue maintenance", DAILY, BigDecimal.ZERO, "days",
                    "Alert when an asset's maintenance is this many days late; fleet services alert when overdue",
                    REPEAT_WEEKLY, BELL_AND_EMAIL),
            new Definition(CREDIT_LIMIT_BREACH, "Credit limit breaches", DAILY, new BigDecimal("100"), "percent",
                    "Alert when a customer's exposure reaches this share of their limit", REPEAT_WEEKLY,
                    BELL_AND_EMAIL),
            new Definition(BUDGET_OVERRUN, "Budget overruns", DAILY, new BigDecimal("100"), "percent",
                    "Alert when spending reaches this share of the budget so far", REPEAT_WEEKLY, BELL_AND_EMAIL),
            new Definition(CAPA_OVERDUE, "Overdue corrective actions", DAILY, null, null, null, REPEAT_WEEKLY,
                    BELL_AND_EMAIL));

    /** Who receives a type until its rule says otherwise. */
    public static final String DEFAULT_RECIPIENT_PERMISSION = Permissions.ALERTS__READ;

    private AlertTypes() {}

    /** The definition of {@code type}, or null when the catalog has no such type. */
    public static Definition definition(String type) {
        return ALL.stream().filter(d -> d.type().equals(type)).findFirst().orElse(null);
    }
}
