package com.inventoryerp.manufacturing.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production orders and engineering changes (ADR-S065). A production order names no planner: whoever created it is
 * responsible. An engineering change has an owner, may come from a change request, and each stage has approvers.
 */
@Component
@ConditionalOnProperty(name = "app.modules.manufacturing", havingValue = "true")
public class ManufacturingNotificationRoutes implements RoutedEventSource {

    private static final String AREA = "production";

    private static final String ECO = "EngineeringChange";

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

    private static RoutedEvent.Builder change(String event, String label) {
        return RoutedEvent.on(event, "changeId")
                .area(AREA)
                .label(label)
                .record("SELECT number, title FROM \"EngineeringChange\" WHERE id = :id AND \"organizationId\" = :org")
                .party(Party.column("owner", "Change owner", ECO, "ownerId"))
                .party(Party.column(Party.CREATOR, "Created the change", ECO, "createdBy"))
                .party(Party.query("requester", "Asked for it (change request)", """
                        SELECT r."requestedById" FROM "EngineeringChange" e JOIN "PlmChangeRequest" r ON r.id = e."requestId"
                        WHERE e.id = :id AND e."organizationId" = :org"""))
                .href("/manufacturing/plm/changes/{id}");
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
                        .build(),
                change("plm.change.created", "Engineering change opened")
                        .defaults("owner", "requester")
                        .title("Engineering change {number}: {title}")
                        .build(),
                change("plm.change.stage_changed", "Engineering change needs your approval")
                        .party(Party.query("stageApprovers", "Approvers of the new stage", """
                                SELECT a."userId" FROM "PlmChangeStageApprover" a
                                JOIN "PlmChangeStage" s ON s.id = a."stageId" AND s."requiresApproval"
                                WHERE a."stageId" = :toStageId AND s."organizationId" = :org"""))
                        .defaults("stageApprovers", "owner")
                        .title("Engineering change {number} moved to a new stage")
                        .body("{title}")
                        .build(),
                change("plm.change.approved", "Engineering change approved")
                        .defaults("owner", "requester")
                        .title("Engineering change {number} approved")
                        .body("{title}")
                        .build(),
                change("plm.change.applied", "Engineering change applied")
                        .party(Party.query("itemOwner", "Owner of the product", """
                                SELECT i."ownerId" FROM "EngineeringChange" e
                                JOIN "PlmItem" i ON i."productId" = e."productId" AND i."organizationId" = e."organizationId"
                                WHERE e.id = :id AND e."organizationId" = :org"""))
                        .defaults("owner", "requester", "itemOwner")
                        .title("Engineering change {number} is now in effect")
                        .body("{title}")
                        .build(),
                RoutedEvent.on("plm.request.created", "requestId")
                        .area(AREA)
                        .label("Engineering change request raised")
                        .record("SELECT number, title FROM \"PlmChangeRequest\" WHERE id = :id AND \"organizationId\" ="
                                + " :org")
                        .party(Party.query("itemOwner", "Owner of the product", """
                                SELECT i."ownerId" FROM "PlmChangeRequest" r
                                JOIN "PlmItem" i ON i."productId" = r."productId" AND i."organizationId" = r."organizationId"
                                WHERE r.id = :id AND r."organizationId" = :org"""))
                        .party(Party.permission("plmApprovers", "Engineering approvers", "plm:approve"))
                        .defaults("itemOwner")
                        .title("Change request {number}: {title}")
                        .href("/manufacturing/plm/requests?q={number}")
                        .build(),
                RoutedEvent.on("plm.lifecycle.changed", "itemId")
                        .area(AREA)
                        .label("Product lifecycle state changed")
                        .record("""
                                SELECT p.code, p.name FROM "PlmItem" i JOIN "Product" p ON p.id = i."productId"
                                WHERE i.id = :id AND i."organizationId" = :org""")
                        .party(Party.column("itemOwner", "Owner of the product", "PlmItem", "ownerId"))
                        .defaults("itemOwner")
                        .title("{code} {name} is now {toState}")
                        .href("/manufacturing/plm/products/{id}")
                        .build());
    }
}
