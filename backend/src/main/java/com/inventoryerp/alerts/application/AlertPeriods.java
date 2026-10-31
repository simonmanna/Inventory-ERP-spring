package com.inventoryerp.alerts.application;

import com.inventoryerp.alerts.spi.AlertTypes;
import java.time.LocalDate;
import java.time.temporal.IsoFields;

/** The period an alert belongs to: the key that makes a subject alert once per day, ISO week or month. */
public final class AlertPeriods {

    private AlertPeriods() {}

    /** {@code 2026-10-31}, {@code 2026-W44} (ISO week: 1 January may fall in the previous year) or {@code 2026-10}. */
    public static String key(String repeatEvery, LocalDate day) {
        return switch (repeatEvery) {
            case AlertTypes.REPEAT_WEEKLY -> "%d-W%02d".formatted(day.get(IsoFields.WEEK_BASED_YEAR),
                    day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case AlertTypes.REPEAT_MONTHLY -> "%d-%02d".formatted(day.getYear(), day.getMonthValue());
            default -> day.toString();
        };
    }
}
