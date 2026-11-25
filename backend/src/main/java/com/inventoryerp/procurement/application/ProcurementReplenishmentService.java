package com.inventoryerp.procurement.application;

import org.springframework.beans.factory.ObjectProvider;
import com.inventoryerp.core.application.ProductLifecycleGuard;
import com.inventoryerp.inventory.application.ReplenishmentService;
import com.inventoryerp.procurement.application.PurchaseRequestsService.CreateInput;
import com.inventoryerp.procurement.application.PurchaseRequestsService.LineInput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises draft purchase requests for replenishment alerts ({@link ProcurementReplenishmentSubscriber}): one request
 * per supplier, then the alerts are told their request. Alerts no longer waiting are skipped, so a repeated event
 * raises nothing. The request is an ordinary draft: submit and approve as usual.
 */
@Service
public class ProcurementReplenishmentService {

    private static final Logger log = LoggerFactory.getLogger(ProcurementReplenishmentService.class);
    private static final String NO_SUPPLIER = "";

    private final PurchaseRequestsService requests;
    private final ReplenishmentService replenishment;

    private final ObjectProvider<ProductLifecycleGuard> lifecycleGuard;

    public ProcurementReplenishmentService(PurchaseRequestsService requests, ReplenishmentService replenishment,
            ObjectProvider<ProductLifecycleGuard> lifecycleGuard) {
        this.requests = requests;
        this.replenishment = replenishment;
        this.lifecycleGuard = lifecycleGuard;
    }

    /** One replenishment line of the event. */
    public record Line(String alertId, String productId, String description, BigDecimal quantity, String uomId,
            String supplierId, String neededBy, String locationId, String variantId) {

        public Line(String alertId, String productId, String description, BigDecimal quantity, String uomId,
                String supplierId, String neededBy, String locationId) {
            this(alertId, productId, description, quantity, uomId, supplierId, neededBy, locationId, null);
        }
    }

    /**
     * One draft purchase request per supplier for the lines still waiting; all or nothing. h-01 F12: the waiting alerts
     * stay locked until this transaction commits, so a second delivery of the same event raises nothing, and a request
     * is kept only when every one of its alerts was still waiting.
     */
    @Transactional
    public void raise(List<Line> lines) {
        List<String> waiting = replenishment.stillRequested(lines.stream().map(Line::alertId).toList());
        // ADR-S086: an alert for a product the PLM lifecycle policy keeps from being replenished or bought stays open
        // for a person to decide; it raises no request.
        java.util.Set<String> blocked = new java.util.HashSet<>();
        ProductLifecycleGuard guard = lifecycleGuard.getIfAvailable();
        if (guard != null) {
            List<String> ids = lines.stream().map(Line::productId).distinct().toList();
            blocked.addAll(guard.blocked(ProductLifecycleGuard.REPLENISH, ids));
            blocked.addAll(guard.blocked(ProductLifecycleGuard.PURCHASE, ids));
        }
        Map<String, List<Line>> bySupplier = new LinkedHashMap<>();
        for (Line l : lines) {
            if (waiting.contains(l.alertId()) && !blocked.contains(l.productId())) {
                bySupplier.computeIfAbsent(l.supplierId() == null ? NO_SUPPLIER : l.supplierId(),
                        k -> new ArrayList<>())
                        .add(l);
            }
        }
        for (Map.Entry<String, List<Line>> group : bySupplier.entrySet()) {
            List<Line> own = group.getValue();
            List<LineInput> inputs = own.stream().map(l -> new LineInput(l.productId(), l.description(), l.quantity(),
                    l.uomId(), null, "Replenishment alert", l.neededBy(), l.locationId(), null, null, null,
                    "reorder", l.alertId(), l.variantId())).toList();
            String neededBy = own.stream().map(Line::neededBy).min(String::compareTo).orElse(null);
            Map<String, Object> created = requests.create(new CreateInput(
                    NO_SUPPLIER.equals(group.getKey()) ? null : group.getKey(), null,
                    "Replenishment: " + own.size() + " product(s) below minimum", neededBy, inputs, null));
            List<String> alertIds = own.stream().map(Line::alertId).toList();
            ReplenishmentService.assertAllMarked(replenishment.markRequisitioned(alertIds, (String) created.get("id"),
                    (String) created.get("requestNumber")), alertIds);
            log.info("Purchase request {} raised for {} replenishment alert(s)", created.get("requestNumber"),
                    own.size());
        }
    }
}
