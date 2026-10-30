package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.fleet.application.DeliverySourcePort;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Sales orders as a fleet delivery source (ADR-S011 S5): only confirmed, released orders are deliverable; each line
 * offers its open quantity (ordered − closed short − dispatched) in the order's unit.
 */
@Component
public class SalesDeliverySource implements DeliverySourcePort {

    private final SalesDb db;

    public SalesDeliverySource(SalesDb db) {
        this.db = db;
    }

    @Override
    public boolean supports(String sourceType, String sourceId) {
        return "sales_order".equals(sourceType) && sourceId != null && db.exists("SalesOrder", sourceId);
    }

    @Override
    public Source load(String sourceId) {
        Map<String, Object> o = db.row("SELECT * FROM \"SalesOrder\" WHERE id = ? AND \"organizationId\" = ?", sourceId,
                org());
        List<SourceLine> lines = new ArrayList<>();
        for (Map<String, Object> l : db.rows("SELECT * FROM \"SalesOrderLine\" WHERE \"orderId\" = ? AND \"productId\" IS"
                + " NOT NULL ORDER BY \"sortOrder\"", sourceId)) {
            BigDecimal open = dec(l.get("quantity")).subtract(dec(l.get("qtyCancelled"))).subtract(dec(l.get(
                    "qtyDispatched"))).max(BigDecimal.ZERO);
            lines.add(new SourceLine(str(l.get("id")), str(l.get("productId")), str(l.get("description")),
                    str(l.get("uomId")), dec(l.get("uomFactor")), open, dec(l.get("unitPrice")), dec(l.get(
                    "discountPercent")), str(l.get("taxId"))));
        }
        return new Source(str(o.get("number")), str(o.get("partnerId")), str(o.get("branchId")), str(o.get("locationId")),
                str(o.get("siteId")), o.get("requestedDate") instanceof LocalDate d ? d : null, lines);
    }

    @Override
    public void assertDeliverable(String sourceId) {
        Map<String, Object> o = db.row("SELECT number, status, \"onHold\", \"holdReason\" FROM \"SalesOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ?", sourceId, org());
        if (o == null) {
            throw ApiException.notFound("Sales order not found");
        }
        if (!SalesOrderService.OPEN.contains(o.get("status"))) {
            throw ApiException.badRequest("Sales order " + o.get("number") + " is " + o.get("status")
                    + " and cannot be delivered");
        }
        if (Boolean.TRUE.equals(o.get("onHold"))) {
            throw ApiException.badRequest("Sales order " + o.get("number") + " is on hold: " + o.get("holdReason"));
        }
    }
}
