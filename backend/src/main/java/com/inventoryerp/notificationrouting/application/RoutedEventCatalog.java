package com.inventoryerp.notificationrouting.application;

import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Every routable event contributed by the enabled modules, in area order. Built once at start-up. */
@Component
public class RoutedEventCatalog {

    /** Areas in display order; also the notification categories a user can opt out of. */
    public static final List<String> AREAS = List.of("sales", "purchasing", "inventory", "projects", "helpdesk", "hr",
            "fleet", "maintenance", "production", "quality");

    public static final List<String> CHANNELS = List.of("in_app", "email", "push", "sms");

    private final Map<String, RoutedEvent> events;

    public RoutedEventCatalog(ObjectProvider<RoutedEventSource> sources) {
        Map<String, RoutedEvent> byName = new LinkedHashMap<>();
        sources.orderedStream().flatMap(s -> s.routedEvents().stream())
                .sorted(Comparator.comparingInt(e -> areaIndex(e.area())))
                .forEach(e -> {
                    if (!AREAS.contains(e.area())) {
                        throw new IllegalStateException(e.event() + ": unknown area '" + e.area() + "'");
                    }
                    if (byName.putIfAbsent(e.event(), e) != null) {
                        throw new IllegalStateException("Routed event declared twice: " + e.event());
                    }
                });
        this.events = Collections.unmodifiableMap(byName);
    }

    private static int areaIndex(String area) {
        int i = AREAS.indexOf(area);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    public RoutedEvent get(String event) {
        return events.get(event);
    }

    public Collection<RoutedEvent> all() {
        return events.values();
    }
}
