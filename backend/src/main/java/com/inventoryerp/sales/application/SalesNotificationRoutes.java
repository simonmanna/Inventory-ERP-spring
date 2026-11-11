package com.inventoryerp.sales.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Sales orders, returns, cash sales and quotations (ADR-S065). Rejected and returned cash sales are left out: {@code
 * CashSaleService.notify} already tells the submitter. Holds go to the team leader by default, because the owner is
 * told by {@code SalesAlerts} when the organization turned {@code notifyOnHold} on.
 */
@Component
public class SalesNotificationRoutes implements RoutedEventSource {

    private static final String AREA = "sales";

    private static final String ORDER = "SalesOrder";

    private static RoutedEvent.Builder order(String event, String label, String status) {
        return RoutedEvent.on(event, "orderId")
                .area(AREA)
                .label(label)
                .record("SELECT number FROM \"SalesOrder\" WHERE id = :id AND \"organizationId\" = :org"
                        + (status == null ? "" : " AND status IN (" + status + ")"))
                .party(Party.column("owner", "Salesperson (owner)", ORDER, "ownerId"))
                .party(Party.creator(ORDER))
                .party(Party.query("teamLeader", "Sales team leader", """
                        SELECT t."leaderId" FROM "SalesOrder" o JOIN "CrmTeam" t ON t.id = o."teamId"
                        WHERE o.id = :id AND o."organizationId" = :org"""))
                .href("/sales/orders/{id}");
    }

    private static RoutedEvent.Builder quotation(String event, String label) {
        return RoutedEvent.on(event, "quotationId")
                .area(AREA)
                .label(label)
                .record("SELECT number FROM \"SalesQuotation\" WHERE id = :id AND \"organizationId\" = :org")
                .party(Party.column("owner", "Salesperson (owner)", "SalesQuotation", "ownerId"))
                .party(Party.creator("SalesQuotation"))
                .href("/sales/quotations/{id}");
    }

    @Override
    public List<RoutedEvent> routedEvents() {
        return List.of(
                order("sales.order.confirmed", "Sales order confirmed", "'confirmed', 'in_fulfilment', 'done'")
                        .defaults("owner", Party.CREATOR)
                        .title("Sales order {number} confirmed")
                        .build(),
                order("sales.order.held", "Sales order put on hold", null)
                        .defaults("teamLeader")
                        .title("Sales order {number} is on hold")
                        .body("{reason}")
                        .build(),
                order("sales.order.released", "Sales order released from hold", null)
                        .defaults("owner")
                        .title("Sales order {number} released from hold")
                        .build(),
                order("sales.order.cancelled", "Sales order cancelled", "'cancelled'")
                        .defaults("owner", Party.CREATOR)
                        .title("Sales order {number} cancelled")
                        .body("{reason}")
                        .build(),
                order("sales.order.amended", "Sales order amended", null)
                        .defaults("owner")
                        .title("Sales order {number} amended (revision {revision})")
                        .build(),
                RoutedEvent.on("sales.return.received", "returnId")
                        .area(AREA)
                        .label("Customer return received")
                        .record("SELECT number FROM \"SalesReturn\" WHERE id = :id AND \"organizationId\" = :org")
                        .party(Party.query("orderOwner", "Owner of the sales order", """
                                SELECT o."ownerId" FROM "SalesReturn" r JOIN "SalesOrder" o ON o.id = r."orderId"
                                WHERE r.id = :id AND r."organizationId" = :org"""))
                        .party(Party.column("requester", "Requested the return", "SalesReturn", "requestedById"))
                        .defaults("orderOwner", "requester")
                        .title("Return {number} received")
                        .href("/sales/returns/{id}")
                        .build(),
                RoutedEvent.on("sales.cash_sale.approved", "submissionId")
                        .area(AREA)
                        .label("Cash sale approved by head office")
                        .record("SELECT number FROM \"SalesSubmission\" WHERE id = :id AND \"organizationId\" = :org")
                        .party(Party.column("submitter", "Submitted it", "SalesSubmission", "submittedById"))
                        .party(Party.column("salesperson", "Salesperson", "SalesSubmission", "salespersonId"))
                        .defaults("submitter", "salesperson")
                        .title("Cash sale {number} approved")
                        .href("/sales/cash-sales/{id}")
                        .build(),
                quotation("crm.quotation.approved", "Quotation approved")
                        .defaults("owner", Party.CREATOR)
                        .title("Quotation {number} approved")
                        .body("It can now be sent to the customer.")
                        .build(),
                quotation("crm.quotation.converted", "Quotation converted")
                        .defaults("owner")
                        .title("Quotation {number} converted")
                        .build());
    }
}
