package com.inventoryerp.alerts.spi;

import java.util.Map;

/**
 * One thing to alert about.
 *
 * @param subjectKey stable key of the subject within the alert type, at most one alert per period; add the state to the
 *     key when a change of state must alert again in the same period (expiring, then expired)
 * @param severity {@link #INFO}, {@link #WARNING} or {@link #CRITICAL}
 * @param title one line, shown in the digest and the exception report
 * @param body what to do about it; may be null
 * @param link the web route of the record; may be null
 * @param data figures kept with the event for the exception report; may be null
 */
public record AlertItem(String subjectKey, String severity, String title, String body, String link,
        Map<String, Object> data) {

    public static final String INFO = "info";
    public static final String WARNING = "warning";
    public static final String CRITICAL = "critical";
}
