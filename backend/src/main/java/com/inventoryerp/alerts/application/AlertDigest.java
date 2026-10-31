package com.inventoryerp.alerts.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertTypes.Definition;
import java.util.List;

/**
 * What one run says about one alert type: one message, however many subjects were raised, so ten licences expiring is
 * one e-mail and not ten. A single item keeps its own wording.
 */
public record AlertDigest(String title, String body, String severity, String link) {

    static final int MAX_LINES = 15;

    /** The route of the alert list filtered to {@code type}. */
    static String listLink(String type) {
        return "/alerts?type=" + type;
    }

    /** {@code items} must not be empty. */
    static AlertDigest of(Definition def, List<AlertItem> items) {
        if (items.size() == 1) {
            AlertItem only = items.getFirst();
            return new AlertDigest(only.title(), only.body() == null ? only.title() : only.body(), only.severity(),
                    only.link() == null ? listLink(def.type()) : only.link());
        }
        StringBuilder body = new StringBuilder();
        items.stream().limit(MAX_LINES).forEach(i -> body.append("- ").append(i.title()).append('\n'));
        if (items.size() > MAX_LINES) {
            body.append("... and ").append(items.size() - MAX_LINES).append(" more\n");
        }
        return new AlertDigest(def.label() + ": " + items.size() + " new", body.toString().stripTrailing(),
                worst(items), listLink(def.type()));
    }

    private static String worst(List<AlertItem> items) {
        if (items.stream().anyMatch(i -> AlertItem.CRITICAL.equals(i.severity()))) {
            return AlertItem.CRITICAL;
        }
        return items.stream().anyMatch(i -> AlertItem.WARNING.equals(i.severity())) ? AlertItem.WARNING
                : AlertItem.INFO;
    }
}
