package com.inventoryerp.notificationrouting.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A routable event: its name, the area it belongs to (also the notification category a user can opt out of), the
 * payload field holding the record id, an optional query reading the record ({@code :id}, {@code :org}; its columns
 * feed the templates), the parties and which of them are told by default.
 *
 * <p>Templates replace {@code {name}} with the record column of that name, else the payload field, else nothing;
 * {@code {id}} is the record id. Example: {@code "Sales order {number} cancelled"}, {@code "/sales/orders/{id}"}.
 *
 * <p>The record query is also the guard: when it is declared and finds no row, nothing is sent. {@code EventBus} events
 * survive a rollback of the publishing transaction, so put the state the event announces in its {@code WHERE} (e.g.
 * {@code AND status = 'cancelled'}).
 *
 * <p>{@code skipWhen}: payload field → value; the event is not routed when any of them matches (e.g. a stage change into
 * a closed stage, already announced by its own "closed" event).
 */
public record RoutedEvent(String event, String area, String label, String idField, String recordSql,
        List<Party> parties, List<String> defaultParties, String title, String body, String href,
        Map<String, String> skipWhen) {

    public RoutedEvent {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(idField, "idField");
        Objects.requireNonNull(title, "title");
        parties = List.copyOf(parties);
        defaultParties = List.copyOf(defaultParties);
        skipWhen = skipWhen == null ? Map.of() : Map.copyOf(skipWhen);
    }

    public Party party(String key) {
        return parties.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }

    /** Start a definition: {@code on("sales.order.cancelled", "orderId")}. */
    public static Builder on(String event, String idField) {
        return new Builder(event, idField);
    }

    /** Fluent builder; {@link #build()} checks that every default is a declared party. */
    public static final class Builder {
        private final String event;
        private final String idField;
        private String area;
        private String label;
        private String recordSql;
        private final List<Party> parties = new ArrayList<>();
        private List<String> defaults = List.of();
        private String title;
        private String body;
        private String href;
        private final Map<String, String> skipWhen = new LinkedHashMap<>();

        private Builder(String event, String idField) {
            this.event = event;
            this.idField = idField;
        }

        public Builder area(String area) {
            this.area = area;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        /** The record query; its columns are template variables. */
        public Builder record(String sql) {
            this.recordSql = sql;
            return this;
        }

        public Builder party(Party party) {
            this.parties.add(party);
            return this;
        }

        public Builder defaults(String... keys) {
            this.defaults = List.of(keys);
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder href(String href) {
            this.href = href;
            return this;
        }

        /** Do not route when the payload's {@code field} equals {@code value} (text; {@code a|b} for either). */
        public Builder skipWhen(String field, String value) {
            this.skipWhen.put(field, value);
            return this;
        }

        public RoutedEvent build() {
            RoutedEvent e = new RoutedEvent(event, area, label, idField, recordSql, parties, defaults, title, body,
                    href, skipWhen);
            for (String key : e.defaultParties()) {
                if (e.party(key) == null) {
                    throw new IllegalStateException(event + ": default party '" + key + "' is not declared");
                }
            }
            if (e.parties().stream().map(Party::key).distinct().count() != e.parties().size()) {
                throw new IllegalStateException(event + ": duplicate party key");
            }
            return e;
        }
    }
}
