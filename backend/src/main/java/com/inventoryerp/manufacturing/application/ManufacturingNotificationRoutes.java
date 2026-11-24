package com.inventoryerp.manufacturing.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production orders (ADR-S065). A production order names no planner: whoever created it is responsible. The
 * engineering change events moved to the PLM module with the rest of PLM (ADR-S086).
 */
@Component
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class ManufacturingNotificationRoutes implements RoutedEventSource {

    private static final String AREA = "production";

    private static RoutedEvent.Builder order(String event, String label, String statuses) {
        return RoutedEvent.on(event, "orderId")
                .area(AREA)
                .label(label)
                .record("SELECT \"orderCode\" FROM \"ProductionOrder\" WHERE id = :id AND \"organizationId\" = :org AND"
                        + " status IN (" + statuses + ")")
                .party(Party.column(Party.CREATOR, "Created the order", "ProductionOrder", "createdBy"))
                .defaults(Party.CREATOR)
                .href("/manufacturing/orders/{id}");
    }

    @Override
    public List<RoutedEvent> routedEvents() {
        return List.of(
                order("manufacturing.order.completed", "Production order completed", "'completed', 'qc_hold'")
                        .title("Production order {orderCode} {status}")
                        .body("Produced {producedQty}")
                        .build(),
                order("manufacturing.order.cancelled", "Production order cancelled", "'cancelled'")
                        .title("Production order {orderCode} cancelled")
                        .body("{reason}")
                        .build());
    }
}
