package com.inventoryerp.notificationrouting.application;

import com.inventoryerp.kernel.events.OutboxHandlers;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Feeds every catalogued event to the {@link NotificationRouter} (outbox, after commit, at-least-once). Off with
 * {@code NOTIFICATION_ROUTING_ENABLED=false}: the settings stay editable, nothing is sent.
 */
@Component
@ConditionalOnProperty(name = "app.notification-routing.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRoutingSubscriber {

    private final OutboxHandlers handlers;
    private final RoutedEventCatalog catalog;
    private final NotificationRouter router;

    public NotificationRoutingSubscriber(OutboxHandlers handlers, RoutedEventCatalog catalog,
            NotificationRouter router) {
        this.handlers = handlers;
        this.catalog = catalog;
        this.router = router;
    }

    @PostConstruct
    void subscribe() {
        for (RoutedEvent e : catalog.all()) {
            handlers.subscribeDelivery(e.event(), router::route);
        }
    }
}
