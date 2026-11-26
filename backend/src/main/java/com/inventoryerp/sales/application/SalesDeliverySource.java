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

    private com.inventoryerp.inventory.application.VirtualLocationService locations;
    private com.inventoryerp.accounting.application.AccountResolverService accounts;

    /** System locations and accounts (DEL-9); optional for plain constructions. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDel9(com.inventoryerp.inventory.application.VirtualLocationService locations,
            com.inventoryerp.accounting.application.AccountResolverService accounts) {
        this.locations = locations;
        this.accounts = accounts;
    }

    private Map<String, Object> settingsRow() {
        return db.row("SELECT \"deliveryLossPosting\", \"deliveryLossAccountId\", \"rejectedToQuarantine\","
                + " \"rejectedQuarantineLocationId\" FROM \"SalesSettings\" WHERE \"organizationId\" = ?", org());
    }

    private com.inventoryerp.inventory.application.StockReservationService reservations;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setReservations(com.inventoryerp.inventory.application.StockReservationService reservations) {
        this.reservations = reservations;
    }

    /**
     * Sales readiness DEL-8: free stock for this line = on hand at the cell − active reservations of every other
     * source. The cell is locked first (the order the stock engine uses), so two issues cannot both take the same free
     * stock, and a reservation made after this check waits for the issue.
     */
    @Override
    public void assertIssuable(String sourceLineId, String productId, String variantId, String locationId,
            BigDecimal baseQty) {
        if (reservations == null || productId == null || locationId == null || baseQty == null || baseQty.signum() <= 0) {
            return;
        }
        reservations.lockCell(productId, variantId, locationId);
        String key = variantId == null ? "" : variantId;
        BigDecimal onHand = db.jdbc().sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ?").params(org(), productId, key,
                        locationId).query(BigDecimal.class).single();
        BigDecimal others = db.jdbc().sql("SELECT COALESCE(sum(quantity), 0) FROM \"StockReservation\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND \"locationId\" = ? AND status ="
                + " 'active' AND NOT (\"sourceType\" = ? AND \"sourceId\" = ?)").params(org(), productId, key, locationId,
                        SalesAtpService.SOURCE, sourceLineId == null ? "" : sourceLineId).query(BigDecimal.class).single();
        BigDecimal free = onHand.subtract(others).max(BigDecimal.ZERO);
        if (baseQty.compareTo(free) > 0 && others.signum() > 0) {
            String name = db.jdbc().sql("SELECT name FROM \"Product\" WHERE id = ?").param(productId).query(String.class)
                    .optional().orElse(productId);
            throw ApiException.badRequest("Only " + com.inventoryerp.kernel.common.JsNumbers.dec(free) + " of " + name
                    + " is free at this location; " + com.inventoryerp.kernel.common.JsNumbers.dec(others)
                    + " is reserved for other orders");
        }
    }

    /** DEL-9b (owner decision 2026-11-26): rejected goods go to quarantine unless the organisation turned it off. */
    @Override
    public String rejectedGoodsLocation(String dispatchLocationId) {
        Map<String, Object> s = settingsRow();
        if (s != null && Boolean.FALSE.equals(s.get("rejectedToQuarantine"))) {
            return dispatchLocationId;
        }
        if (s != null && s.get("rejectedQuarantineLocationId") != null) {
            return str(s.get("rejectedQuarantineLocationId"));
        }
        return locations == null ? dispatchLocationId : locations.ensure("SALES-QUARANTINE", "Sales rejections quarantine");
    }

    /** DEL-9a (owner decision 2026-11-26): delivery losses leave COGS unless the organisation turned it off. */
    @Override
    public String deliveryLossAccount() {
        Map<String, Object> s = settingsRow();
        if (s != null && Boolean.FALSE.equals(s.get("deliveryLossPosting"))) {
            return null;
        }
        if (s != null && s.get("deliveryLossAccountId") != null) {
            return str(s.get("deliveryLossAccountId"));
        }
        return accounts == null ? null : accounts.ensureByCode("5190", "Delivery Losses", "operating_expense",
                "delivery_loss");
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
                    "discountPercent")), str(l.get("taxId")), str(l.get("variantId"))));
        }
        return new Source(str(o.get("number")), str(o.get("partnerId")), str(o.get("branchId")), str(o.get("locationId")),
                str(o.get("siteId")), o.get("requestedDate") instanceof LocalDate d ? d : null, lines);
    }

    @Override
    public BigDecimal overDeliveryTolerancePct(String sourceLineId) {
        Map<String, Object> l = db.row("SELECT \"tolerancePct\" FROM \"SalesOrderLine\" WHERE id = ?"
                + " AND \"organizationId\" = ?",
                sourceLineId, org());
        return l == null || l.get("tolerancePct") == null ? BigDecimal.ZERO : dec(l.get("tolerancePct"));
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
