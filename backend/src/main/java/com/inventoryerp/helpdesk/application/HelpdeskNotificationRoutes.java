package com.inventoryerp.helpdesk.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Helpdesk tickets (ADR-S065). Assignment is left out: {@code HelpdeskTicketService.notifyAssignee} already tells the
 * assignee. A new ticket reaches its team only while nobody is assigned.
 */
@Component
public class HelpdeskNotificationRoutes implements RoutedEventSource {

    private static final String AREA = "helpdesk";

    private static final String RECORD = "SELECT number, subject FROM \"HdTicket\" WHERE id = :id AND \"organizationId\""
            + " = :org";

    @Override
    public List<RoutedEvent> routedEvents() {
        Party assignee = Party.column("assignee", "Assignee", "HdTicket", "assigneeId");
        return List.of(
                RoutedEvent.on("helpdesk.ticket.created", "ticketId")
                        .area(AREA)
                        .label("Unassigned ticket opened")
                        .record(RECORD + " AND \"assigneeId\" IS NULL")
                        .party(Party.query("teamLeader", "Team leader", """
                                SELECT tm."leaderId" FROM "HdTicket" t JOIN "HdTeam" tm ON tm.id = t."teamId"
                                WHERE t.id = :id AND t."organizationId" = :org"""))
                        .party(Party.query("teamMembers", "Team members", """
                                SELECT m."userId" FROM "HdTicket" t JOIN "HdTeamMember" m ON m."teamId" = t."teamId"
                                WHERE t.id = :id AND t."organizationId" = :org"""))
                        .defaults("teamLeader", "teamMembers")
                        .title("New ticket {number}: {subject}")
                        .href("/helpdesk/tickets/{id}")
                        .build(),
                RoutedEvent.on("helpdesk.ticket.closed", "ticketId")
                        .area(AREA)
                        .label("Ticket closed")
                        .record(RECORD)
                        .party(Party.creator("HdTicket"))
                        .party(assignee)
                        .defaults(Party.CREATOR, "assignee")
                        .title("Ticket {number} closed")
                        .body("{subject}")
                        .href("/helpdesk/tickets/{id}")
                        .build());
    }
}
