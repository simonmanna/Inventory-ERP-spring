package com.inventoryerp.manufacturing.application;

import com.inventoryerp.inventory.application.ReplenishmentService;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.manufacturing.application.ProductionRequestService.RaisedLine;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Answers replenishment alerts whose rule route is {@code manufacture} (PR-5, ADR-S027): the {@code manufacture} lines
 * of {@code inventory.replenishment.requested} become one {@code submitted} production request (source
 * {@code replenishment}), each line delivering to its rule's location, and the alerts are told the request. Alerts no
 * longer waiting are skipped, so a redelivered event raises nothing. Buy lines are procurement's.
 */
@Service
public class ManufacturingReplenishmentService {

    private static final Logger log = LoggerFactory.getLogger(ManufacturingReplenishmentService.class);

    /** One manufacture line of the event. */
    public record Line(String alertId, String productId, BigDecimal quantity, String uomId, String locationId,
            String neededBy, String description) {}

    private final EventBus events;
    private final ProductionRequestService requests;
    private final ReplenishmentService replenishment;
    private final BomService boms;
    private final MfgDb db;

    public ManufacturingReplenishmentService(EventBus events, ProductionRequestService requests,
            ReplenishmentService replenishment, BomService boms, MfgDb db) {
        this.events = events;
        this.requests = requests;
        this.replenishment = replenishment;
        this.boms = boms;
        this.db = db;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.INVENTORY_REPLENISHMENT_REQUESTED, this::onRequested);
    }

    void onRequested(JsonNode p) {
        String orgId = p.path("organizationId").asString("");
        if (orgId.isEmpty()) {
            return;
        }
        List<Line> lines = new ArrayList<>();
        for (JsonNode l : p.path("lines")) {
            if (!"manufacture".equals(l.path("route").asString(""))) {
                continue;
            }
            String uom = l.path("uomId").asString("");
            lines.add(new Line(l.path("alertId").asString(""), l.path("productId").asString(""),
                    new BigDecimal(l.path("quantity").asString("0")), uom.isEmpty() ? null : uom,
                    l.path("locationId").asString(""), l.path("neededBy").asString(""),
                    l.path("description").asString("")));
        }
        if (lines.isEmpty()) {
            return;
        }
        String userId = p.path("requestedBy").asString("");
        TenantStore store = userId.isEmpty() ? TenantStore.organization(orgId) : TenantStore.user(orgId, userId);
        TenantContext.run(store, () -> raise(lines));
    }

    /** One submitted production request for the lines still waiting; all or nothing. */
    @Transactional
    public Map<String, Object> raise(List<Line> lines) {
        List<String> waiting = replenishment.stillRequested(lines.stream().map(Line::alertId).toList());
        List<Line> own = lines.stream().filter(l -> waiting.contains(l.alertId())).toList();
        if (own.isEmpty()) {
            return null;
        }
        List<RaisedLine> raised = new ArrayList<>();
        Instant neededBy = null;
        for (Line l : own) {
            Map<String, Object> bom = boms.findActive(l.productId(), "");
            Instant due = date(l.neededBy());
            neededBy = neededBy == null || (due != null && due.isBefore(neededBy)) ? due : neededBy;
            raised.add(new RaisedLine(l.productId(), null, l.quantity(), l.uomId(),
                    bom == null ? null : (String) bom.get("id"), due, l.description()));
        }
        Map<String, Object> request = requests.raiseRequest("replenishment", null, neededBy,
                "Replenishment: " + own.size() + " product(s) below minimum", raised);
        String requestId = (String) request.get("id");
        for (int i = 0; i < own.size(); i++) {
            db.jdbc().sql("UPDATE \"ProductionRequestLine\" SET \"deliveryLocationId\" = ? WHERE \"requestId\" = ?"
                    + " AND \"lineNumber\" = ? AND \"organizationId\" = ?")
                    .params(own.get(i).locationId().isEmpty() ? null : own.get(i).locationId(), requestId, i,
                            MfgDb.org())
                    .update();
        }
        replenishment.markDocumented(own.stream().map(Line::alertId).toList(), "production_request", requestId,
                (String) request.get("requestNumber"));
        log.info("Production request {} raised for {} replenishment alert(s)", request.get("requestNumber"),
                own.size());
        return request;
    }

    private static Instant date(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        return LocalDate.parse(v.substring(0, 10)).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
