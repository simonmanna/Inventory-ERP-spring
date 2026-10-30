package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Opens incoming inspection lots after a goods receipt is posted (outbox, at-least-once, idempotent per receipt
 * line): {@code goods_receipt.posted} (ad-hoc / draft GRN) and {@code purchase_order.received} (PO receive). No change
 * to procurement: the events are subscribed by name.
 */
@Component
public class QualityProcurementSubscriber {

    private final EventBus events;
    private final QualityIncomingService incoming;

    public QualityProcurementSubscriber(EventBus events, QualityIncomingService incoming) {
        this.events = events;
        this.incoming = incoming;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe("goods_receipt.posted", p -> run(p, p.path("receiptId").asString(""), false));
        events.subscribe("purchase_order.received", p -> run(p, p.path("receiptNumber").asString(""), true));
    }

    void run(JsonNode p, String ref, boolean byNumber) {
        String orgId = p.path("organizationId").asString("");
        if (orgId.isEmpty() || ref.isEmpty()) {
            return;
        }
        TenantContext.run(TenantStore.organization(orgId), () -> {
            if (byNumber) {
                incoming.openForReceiptNumber(ref);
            } else {
                incoming.openForGoodsReceipt(ref);
            }
        });
    }
}
