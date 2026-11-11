package com.inventoryerp.procurement.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Purchase requests, orders, receipts and bills (ADR-S065). A purchase order has no buyer column: its creator is the
 * buyer. A request or order decided through the approval engine is skipped, because {@code approval.decided} already told
 * the requester.
 */
@Component
public class ProcurementNotificationRoutes implements RoutedEventSource {

    private static final String AREA = "purchasing";

    /** Requesters of the purchase requests behind purchase order {@code :po} (header link or line links). */
    private static String requestersOf(String poExpr) {
        return """
                SELECT pr."requestedById" FROM "PurchaseOrder" po JOIN "PurchaseRequest" pr ON pr.id = po."requestId"
                WHERE po.id = %1$s AND po."organizationId" = :org
                UNION
                SELECT pr."requestedById" FROM "PurchaseOrderLine" l
                JOIN "PurchaseRequestLine" rl ON rl.id = l."requestLineId"
                JOIN "PurchaseRequest" pr ON pr.id = rl."purchaseRequestId"
                WHERE l."purchaseOrderId" = %1$s AND pr."organizationId" = :org""".formatted(poExpr);
    }

    private static final String NO_ENGINE = " AND NOT EXISTS (SELECT 1 FROM \"ApprovalRequest\" a WHERE"
            + " a.\"organizationId\" = :org AND a.\"entityId\" = :id AND a.status <> 'pending')";

    private static RoutedEvent.Builder request(String event, String label, String status, boolean skipEngine) {
        return RoutedEvent.on(event, "requestId")
                .area(AREA)
                .label(label)
                .record("SELECT \"requestNumber\" AS number FROM \"PurchaseRequest\" WHERE id = :id AND"
                        + " \"organizationId\" = :org AND status = '" + status + "'" + (skipEngine ? NO_ENGINE : ""))
                .party(Party.column("requester", "Requester", "PurchaseRequest", "requestedById"))
                .href("/procurement/purchase-requests");
    }

    private static RoutedEvent.Builder order(String event, String label, String recordFilter) {
        return RoutedEvent.on(event, "orderId")
                .area(AREA)
                .label(label)
                .record("SELECT \"orderNumber\" AS number FROM \"PurchaseOrder\" WHERE id = :id AND \"organizationId\" ="
                        + " :org" + recordFilter)
                .party(Party.column("buyer", "Buyer (created the order)", "PurchaseOrder", "createdBy"))
                .party(Party.query("requester", "Requester of the purchase request", requestersOf(":id")))
                .href("/procurement/purchase-orders/{id}");
    }

    private static final String BILL_BUYERS = """
            SELECT po."createdBy" FROM "VendorBillLink" k JOIN "PurchaseOrder" po ON po.id = k."purchaseOrderId"
            WHERE k."vendorBillId" = :id AND po."organizationId" = :org""";

    @Override
    public List<RoutedEvent> routedEvents() {
        return List.of(
                request("purchase_request.approved", "Purchase request approved", "approved", true)
                        .defaults("requester")
                        .title("Purchase request {number} approved")
                        .build(),
                request("purchase_request.rejected", "Purchase request rejected", "rejected", true)
                        .defaults("requester")
                        .title("Purchase request {number} rejected")
                        .body("{reason}")
                        .build(),
                request("purchase_request.converted", "Purchase request ordered", "converted", false)
                        .defaults("requester")
                        .title("Purchase request {number} is on order")
                        .build(),
                order("purchase_order.approved", "Purchase order approved", " AND status IN ('approved', 'active')"
                        + NO_ENGINE)
                        .defaults("buyer")
                        .title("Purchase order {number} approved")
                        .body("It can be sent to the supplier.")
                        .build(),
                order("purchase_order.cancelled", "Purchase order cancelled", " AND status = 'cancelled'")
                        .defaults("buyer", "requester")
                        .title("Purchase order {number} cancelled")
                        .body("{reason}")
                        .build(),
                order("purchase_order.closed", "Purchase order closed short", " AND status IN ('closed', 'cancelled')")
                        .defaults("buyer", "requester")
                        .title("Purchase order {number} closed")
                        .body("{reason}")
                        .build(),
                order("purchase_order.changed", "Purchase order changed", "")
                        .party(Party.query("changeRequester", "Asked for the change", """
                                SELECT "requestedBy" FROM "PoChangeRequest"
                                WHERE id = :changeRequestId AND "organizationId" = :org"""))
                        .defaults("buyer", "changeRequester")
                        .title("Purchase order {number} changed (revision {revision})")
                        .build(),
                RoutedEvent.on("goods_receipt.posted", "receiptId")
                        .area(AREA)
                        .label("Goods received")
                        .record("SELECT \"receiptNumber\" AS number FROM \"GoodsReceiptNote\" WHERE id = :id AND"
                                + " \"organizationId\" = :org")
                        .party(Party.query("buyer", "Buyer of the purchase order", """
                                SELECT po."createdBy" FROM "GoodsReceiptNote" g
                                JOIN "PurchaseOrder" po ON po.id = g."purchaseOrderId"
                                WHERE g.id = :id AND g."organizationId" = :org"""))
                        .party(Party.query("requester", "Requester of the purchase request",
                                requestersOf("(SELECT g.\"purchaseOrderId\" FROM \"GoodsReceiptNote\" g WHERE g.id"
                                        + " = :id)")))
                        .defaults("buyer", "requester")
                        .title("Goods received: {number}")
                        .href("/procurement/goods-receipts/{id}")
                        .build(),
                RoutedEvent.on("bill.posted", "documentId")
                        .area(AREA)
                        .label("Vendor bill posted")
                        .record("SELECT \"documentNumber\" AS number FROM \"Document\" WHERE id = :id AND"
                                + " \"organizationId\" = :org AND \"documentType\" = 'vendor_bill'")
                        .party(Party.query("buyer", "Buyer of a billed purchase order", BILL_BUYERS))
                        .party(Party.column("enteredBy", "Entered the bill", "Document", "createdBy"))
                        .defaults("buyer")
                        .title("Vendor bill {number} posted")
                        .href("/vendor-bills/{id}")
                        .build(),
                RoutedEvent.on("bill.duplicate_suspected", "billId")
                        .area(AREA)
                        .label("Possible duplicate vendor bill")
                        .record("SELECT \"documentNumber\" AS number FROM \"Document\" WHERE id = :id AND"
                                + " \"organizationId\" = :org AND \"documentType\" = 'vendor_bill'")
                        .party(Party.column("enteredBy", "Entered the bill", "Document", "createdBy"))
                        .party(Party.query("buyer", "Buyer of a billed purchase order", BILL_BUYERS))
                        .defaults("enteredBy", "buyer")
                        .title("Vendor bill {number} may be a duplicate")
                        .body("Check it before it is paid.")
                        .href("/vendor-bills/{id}")
                        .build(),
                RoutedEvent.on("rfq.awarded", "rfqId")
                        .area(AREA)
                        .label("Request for quotation awarded")
                        .record("SELECT \"rfqNumber\" AS number FROM \"Rfq\" WHERE id = :id AND \"organizationId\" = :org")
                        .party(Party.column(Party.CREATOR, "Created the RFQ", "Rfq", "createdBy"))
                        .party(Party.query("requester", "Requester of the purchase request", """
                                SELECT DISTINCT pr."requestedById" FROM "RfqLine" l
                                JOIN "PurchaseRequestLine" rl ON rl.id = l."requestLineId"
                                JOIN "PurchaseRequest" pr ON pr.id = rl."purchaseRequestId"
                                WHERE l."rfqId" = :id AND pr."organizationId" = :org"""))
                        .defaults(Party.CREATOR, "requester")
                        .title("RFQ {number} awarded")
                        .href("/procurement/rfqs/{id}")
                        .build(),
                // Inventory: a reorder point raised a draft purchase request that a buyer must source.
                RoutedEvent.on("purchase_request.created", "requestId")
                        .area("inventory")
                        .label("Reorder purchase request raised")
                        .record("SELECT \"requestNumber\" AS number FROM \"PurchaseRequest\" pr WHERE id = :id AND"
                                + " \"organizationId\" = :org AND EXISTS (SELECT 1 FROM \"PurchaseRequestLine\" l WHERE"
                                + " l.\"purchaseRequestId\" = pr.id AND l.\"demandSource\" = 'reorder')")
                        .party(Party.permission("buyers", "Buyers (can create purchase orders)",
                                "purchase_order:create"))
                        .party(Party.column("requester", "Requester", "PurchaseRequest", "requestedById"))
                        .defaults("buyers")
                        .title("Reorder request {number} needs sourcing")
                        .href("/procurement/purchase-requests")
                        .build());
    }
}
