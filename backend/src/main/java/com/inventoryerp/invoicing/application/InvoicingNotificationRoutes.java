package com.inventoryerp.invoicing.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Customer invoices, credit notes and receipts (ADR-S065). {@code "Document"."salespersonId"} holds a user id when the
 * invoice came from an order or a cash sale and an HR employee id when it was typed in, so both are resolved. Invoices
 * of cash sales are left out: {@code CashSaleService} already tells the submitter.
 */
@Component
public class InvoicingNotificationRoutes implements RoutedEventSource {

    private static final String AREA = "sales";

    private static final String SALESPERSON = """
            SELECT COALESCE(u.id, e."userId") FROM "Document" d
            LEFT JOIN "User" u ON u.id = d."salespersonId"
            LEFT JOIN "HrEmployee" e ON e.id = d."salespersonId"
            WHERE d.id = :id AND d."organizationId" = :org""";

    private static final String ORDER_OWNER = """
            SELECT so."ownerId" FROM "Document" d JOIN "SalesOrder" so ON so.id = d."sourceId"
            WHERE d.id = :id AND d."organizationId" = :org AND d."sourceType" = 'sales_order'""";

    private static RoutedEvent.Builder invoice(String event, String label, String status) {
        return RoutedEvent.on(event, "documentId")
                .area(AREA)
                .label(label)
                .record("SELECT \"documentNumber\" AS number FROM \"Document\" WHERE id = :id AND \"organizationId\" ="
                        + " :org AND \"documentType\" = 'sales_invoice' AND status IN (" + status + ") AND"
                        + " (\"sourceType\" IS NULL OR \"sourceType\" <> 'cash_sale')")
                .party(Party.query("salesperson", "Salesperson on the invoice", SALESPERSON))
                .party(Party.query("orderOwner", "Owner of the sales order", ORDER_OWNER))
                .href("/invoices/{id}");
    }

    @Override
    public List<RoutedEvent> routedEvents() {
        return List.of(
                invoice("invoice.posted", "Invoice posted", "'posted', 'paid'")
                        .defaults("salesperson", "orderOwner")
                        .title("Invoice {number} posted")
                        .build(),
                invoice("invoice.paid", "Invoice paid in full", "'paid', 'posted'")
                        .defaults("salesperson", "orderOwner")
                        .title("Invoice {number} is paid")
                        .build(),
                invoice("invoice.cancelled", "Invoice cancelled", "'cancelled'")
                        .defaults("salesperson", "orderOwner")
                        .title("Invoice {number} cancelled")
                        .build(),
                RoutedEvent.on("creditnote.issued", "documentId")
                        .area(AREA)
                        .label("Credit note issued")
                        .record("SELECT \"documentNumber\" AS number FROM \"Document\" WHERE id = :id AND"
                                + " \"organizationId\" = :org AND \"documentType\" = 'credit_note'")
                        .party(Party.query("salesperson", "Salesperson on the original invoice", """
                                SELECT COALESCE(u.id, e."userId") FROM "Document" cn
                                JOIN "Document" inv ON inv.id = cn."reversedDocumentId"
                                LEFT JOIN "User" u ON u.id = inv."salespersonId"
                                LEFT JOIN "HrEmployee" e ON e.id = inv."salespersonId"
                                WHERE cn.id = :id AND cn."organizationId" = :org"""))
                        .party(Party.query("returnRequester", "Requested the customer return", """
                                SELECT "requestedById" FROM "SalesReturn"
                                WHERE "creditNoteId" = :id AND "organizationId" = :org"""))
                        .defaults("salesperson")
                        .title("Credit note {number} issued")
                        .href("/credit-notes/{id}")
                        .build(),
                RoutedEvent.on("payment.received", "paymentId")
                        .area(AREA)
                        .label("Customer payment received")
                        .record("SELECT \"paymentNumber\" AS number FROM \"Payment\" WHERE id = :id AND"
                                + " \"organizationId\" = :org AND direction = 'inbound' AND \"refundOfId\" IS NULL")
                        .party(Party.query("salesperson", "Salesperson on a paid invoice", """
                                SELECT DISTINCT COALESCE(u.id, e."userId") FROM "PaymentAllocation" a
                                JOIN "Document" d ON d.id = a."documentId"
                                LEFT JOIN "User" u ON u.id = d."salespersonId"
                                LEFT JOIN "HrEmployee" e ON e.id = d."salespersonId"
                                WHERE a."paymentId" = :id AND d."organizationId" = :org"""))
                        .party(Party.query("creditController", "Customer's credit controller", """
                                SELECT p."creditControllerId" FROM "Payment" pm JOIN "Partner" p ON p.id = pm."partnerId"
                                WHERE pm.id = :id AND pm."organizationId" = :org"""))
                        .defaults("salesperson")
                        .title("Payment {number} received ({amount})")
                        .href("/payments/{id}")
                        .build());
    }
}
