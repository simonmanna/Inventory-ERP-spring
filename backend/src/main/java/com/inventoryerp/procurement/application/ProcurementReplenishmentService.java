package com.inventoryerp.procurement.application;

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

    public ProcurementReplenishmentService(PurchaseRequestsService requests, ReplenishmentService replenishment) {
        this.requests = requests;
        this.replenishment = replenishment;
    }

    /** One replenishment line of the event. */
    public record Line(String alertId, String productId, String description, BigDecimal quantity, String uomId,
            String supplierId, String neededBy, String locationId) {}

    /** One draft purchase request per supplier for the lines still waiting; all or nothing. */
    @Transactional
    public void raise(List<Line> lines) {
        List<String> waiting = replenishment.stillRequested(lines.stream().map(Line::alertId).toList());
        Map<String, List<Line>> bySupplier = new LinkedHashMap<>();
        for (Line l : lines) {
            if (waiting.contains(l.alertId())) {
                bySupplier.computeIfAbsent(l.supplierId() == null ? NO_SUPPLIER : l.supplierId(),
                        k -> new ArrayList<>())
                        .add(l);
            }
        }
        for (Map.Entry<String, List<Line>> group : bySupplier.entrySet()) {
            List<Line> own = group.getValue();
            List<LineInput> inputs = own.stream().map(l -> new LineInput(l.productId(), l.description(), l.quantity(),
                    l.uomId(), null, "Replenishment alert", l.neededBy(), l.locationId(), null, null, null,
                    "reorder", l.alertId())).toList();
            String neededBy = own.stream().map(Line::neededBy).min(String::compareTo).orElse(null);
            Map<String, Object> created = requests.create(new CreateInput(
                    NO_SUPPLIER.equals(group.getKey()) ? null : group.getKey(), null,
                    "Replenishment: " + own.size() + " product(s) below minimum", neededBy, inputs, null));
            replenishment.markRequisitioned(own.stream().map(Line::alertId).toList(), (String) created.get("id"),
                    (String) created.get("requestNumber"));
            log.info("Purchase request {} raised for {} replenishment alert(s)", created.get("requestNumber"),
                    own.size());
        }
    }
}
