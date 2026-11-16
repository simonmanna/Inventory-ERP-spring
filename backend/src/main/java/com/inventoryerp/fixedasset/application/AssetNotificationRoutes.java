package com.inventoryerp.fixedasset.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Asset custody (ADR-S065). {@code "Asset"."assignedToId"} is typed by hand: for an employee it is tried as an HR
 * employee, then as a user; other custodian types (department, room, ...) reach nobody.
 */
@Component
@ConditionalOnProperty(name = "app.modules.assets", havingValue = "true")
public class AssetNotificationRoutes implements RoutedEventSource {

    @Override
    public List<RoutedEvent> routedEvents() {
        return List.of(RoutedEvent.on("fixed_asset.assigned", "assetId")
                .area("maintenance")
                .label("Asset handed to a custodian")
                .record("SELECT \"assetCode\", name FROM \"Asset\" WHERE id = :id AND \"organizationId\" = :org")
                .party(Party.query("custodian", "Custodian", """
                        SELECT COALESCE(e."userId", u.id) FROM "Asset" a
                        LEFT JOIN "HrEmployee" e ON e.id = a."assignedToId" AND e."organizationId" = a."organizationId"
                        LEFT JOIN "User" u ON u.id = a."assignedToId" AND u."organizationId" = a."organizationId"
                        WHERE a.id = :id AND a."organizationId" = :org AND a."assignedToType"::text = 'employee'"""))
                .party(Party.column(Party.CREATOR, "Registered the asset", "Asset", "createdBy"))
                .defaults("custodian")
                .title("Asset {assetCode} ({name}) is now in your custody")
                .href("/fixed-assets/{id}")
                .build());
    }
}
