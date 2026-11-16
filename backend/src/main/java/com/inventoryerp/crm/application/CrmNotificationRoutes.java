package com.inventoryerp.crm.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.stereotype.Component;

/** Opportunities won, lost and handed to someone (ADR-S065). */
@Component
public class CrmNotificationRoutes implements RoutedEventSource {

    private static RoutedEvent.Builder deal(String event, String label, String filter) {
        return RoutedEvent.on(event, "dealId")
                .area("sales")
                .label(label)
                .record("SELECT name FROM \"Deal\" WHERE id = :id AND \"organizationId\" = :org" + filter)
                .party(Party.column("owner", "Owner", "Deal", "ownerId"))
                .party(Party.creator("Deal"))
                .party(Party.query("teamLeader", "Sales team leader", """
                        SELECT t."leaderId" FROM "Deal" d JOIN "CrmTeam" t ON t.id = d."teamId"
                        WHERE d.id = :id AND d."organizationId" = :org"""))
                .href("/crm/opportunities/{id}");
    }

    @Override
    public List<RoutedEvent> routedEvents() {
        return List.of(
                deal("crm.deal.assigned", "Opportunity assigned", " AND \"ownerId\" = :ownerId")
                        .defaults("owner")
                        .title("You now own {name}")
                        .build(),
                deal("crm.deal.won", "Opportunity won", "")
                        .defaults("owner", "teamLeader")
                        .title("Won: {name}")
                        .build(),
                deal("crm.deal.lost", "Opportunity lost", "")
                        .defaults("owner", "teamLeader")
                        .title("Lost: {name}")
                        .build());
    }
}
