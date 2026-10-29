package com.inventoryerp.fleet.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.fleet.application.DispatchCommands.InvoiceRequest;
import com.inventoryerp.fleet.domain.FleetDeliveryCharge;
import com.inventoryerp.fleet.domain.FleetDeliveryOrder;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.InvoiceService;
import com.inventoryerp.invoicing.application.InvoiceService.InvoiceInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer invoicing of a delivery order (fleet 1.3): one <b>draft</b> sales invoice through {@link InvoiceService}
 * with the delivered goods at their billable quantity (unless the goods were invoiced already — source invoice or a
 * billed sales order) and the <b>transport charge</b> line from {@link DeliveryChargeCalculator} (account 4250
 * Transport &amp; Delivery Revenue, mapping {@code fleet_transport_revenue}). The goods lines are marked so posting the
 * invoice does not issue the stock again (fleet issued it per load). AR posts the invoice (Dr AR / Cr revenue + transport
 * revenue + tax); nothing is posted here.
 */
@Service
public class FleetDeliveryInvoiceService {

    private final FleetDb db;
    private final FleetDeliveryOrderService orders;
    private final FleetDispatchSettingsService settings;
    private final InvoiceService invoices;
    private final AccountResolverService accounts;
    private final FleetLoadFactors factors;
    private final AuditService audit;

    public FleetDeliveryInvoiceService(FleetDb db, FleetDeliveryOrderService orders,
            FleetDispatchSettingsService settings, InvoiceService invoices, AccountResolverService accounts,
            FleetLoadFactors factors, AuditService audit) {
        this.db = db;
        this.orders = orders;
        this.settings = settings;
        this.invoices = invoices;
        this.accounts = accounts;
        this.factors = factors;
        this.audit = audit;
    }

    /** Transport &amp; delivery revenue (4250, {@code fleet_transport_revenue}). */
    public String transportRevenueAccount() {
        return accounts.ensureByCode("4250", "Transport & Delivery Revenue", "revenue", "fleet_transport_revenue");
    }

    /** The charge the delivery order would carry now: {@code {facts, rule, charge}} ({@code charge} null = no rule). */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(String deliveryOrderId) {
        Map<String, Object> o = orders.require(deliveryOrderId, false);
        DeliveryChargeCalculator.Facts f = facts(o);
        Optional<DeliveryChargeCalculator.Rule> rule = DeliveryChargeCalculator.select(settings.activeRules(), f);
        DeliveryChargeCalculator.Charge c = rule.map(r -> DeliveryChargeCalculator.compute(r, f)).orElse(null);
        return Obj.of("facts", Obj.of("zone", f.zone(), "siteDistanceKm", f.siteDistanceKm(), "trips", f.trips(),
                "tonnes", f.tonnes(), "m3", f.m3(), "tripKm", f.tripKm()),
                "rule", rule.orElse(null), "charge", c == null ? null : Obj.of("description", c.description(),
                        "quantity", c.quantity(), "rate", c.rule().rate(), "amount", c.amount(), "taxId",
                        c.rule().taxId()),
                "alreadyInvoiced", o.get("chargesInvoiceId") != null);
    }

    /** Draft customer invoice for the delivered goods (if not invoiced yet) and the transport charge. */
    @Transactional
    public Map<String, Object> invoice(String deliveryOrderId, InvoiceRequest in) {
        if (!TenantContext.has(Permissions.INVOICE__CREATE)) {
            throw ApiException.forbidden("Invoicing a delivery order requires the invoice:create permission");
        }
        Map<String, Object> o = orders.require(deliveryOrderId, true);
        String code = (String) o.get("code");
        if (!"delivered".equals(o.get("status")) && !"closed".equals(o.get("status"))) {
            throw ApiException.badRequest("Delivery order " + code + " is " + o.get("status") + "; invoice it once its"
                    + " trips are delivered");
        }
        long open = db.jdbc().sql("SELECT count(*) FROM \"FleetLoad\" WHERE \"organizationId\" = ? AND"
                + " \"deliveryOrderId\" = ? AND status IN ('planned', 'loading', 'weighed', 'dispatched', 'in_transit')")
                .params(FleetDb.org(), deliveryOrderId).query(Long.class).single();
        if (open > 0) {
            throw ApiException.badRequest("Delivery order " + code + " still has trips in progress");
        }
        long pending = db.jdbc().sql("SELECT count(*) FROM \"FleetDeliveryDiscrepancy\" WHERE \"organizationId\" = ? AND"
                + " \"deliveryOrderId\" = ? AND status = 'pending'").params(FleetDb.org(), deliveryOrderId)
                .query(Long.class).single();
        if (pending > 0) {
            throw ApiException.badRequest("Delivery order " + code + " has " + pending + " pending discrepanc"
                    + (pending == 1 ? "y" : "ies") + "; decide them first");
        }
        List<LineInput> lines = new ArrayList<>();
        boolean goods = o.get("goodsInvoiceId") == null;
        if (goods) {
            for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT ol.\"productId\", ol.description,"
                    + " ol.\"uomId\", ol.\"unitPrice\", ol.\"discountPercent\", ol.\"taxId\", p.name, COALESCE(SUM(ll.\"billableQty\")"
                    + " FILTER (WHERE t.status IN ('delivered', 'closed', 'returned')), 0) AS qty FROM"
                    + " \"FleetDeliveryOrderLine\" ol JOIN \"Product\" p ON p.id = ol.\"productId\" LEFT JOIN"
                    + " \"FleetLoadLine\" ll ON ll.\"deliveryOrderLineId\" = ol.id LEFT JOIN \"FleetLoad\" t ON t.id ="
                    + " ll.\"loadId\" WHERE ol.\"organizationId\" = ? AND ol.\"deliveryOrderId\" = ? GROUP BY ol.id, p.name"
                    + " ORDER BY ol.\"lineNumber\"").params(FleetDb.org(), deliveryOrderId))) {
                BigDecimal qty = SqlRows.dec(l, "qty");
                if (qty.signum() <= 0) {
                    continue;
                }
                String desc = l.get("description") == null ? (String) l.get("name") : (String) l.get("description");
                lines.add(LineInput.of((String) l.get("productId"), null, desc + " (delivery " + code + ")", qty,
                        l.get("unitPrice") == null ? null : SqlRows.dec(l, "unitPrice"),
                        l.get("discountPercent") == null ? null : SqlRows.dec(l, "discountPercent"),
                        (String) l.get("taxId"), null, (String) l.get("uomId")));
            }
        }
        DeliveryChargeCalculator.Charge charge = null;
        if (o.get("chargesInvoiceId") == null && (in == null || !Boolean.FALSE.equals(in.includeCharges()))) {
            DeliveryChargeCalculator.Facts f = facts(o);
            charge = DeliveryChargeCalculator.select(settings.activeRules(), f)
                    .map(r -> DeliveryChargeCalculator.compute(r, f)).filter(c -> c.amount().signum() > 0).orElse(null);
            if (charge != null) {
                boolean flat = "flat".equals(charge.rule().basis());
                lines.add(LineInput.of(null, transportRevenueAccount(), charge.description(),
                        flat ? BigDecimal.ONE : charge.quantity(),
                        flat ? charge.amount() : charge.amount().divide(charge.quantity(), 6, RoundingMode.HALF_UP),
                        null, charge.rule().taxId(), null));
            }
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("Nothing to invoice on delivery order " + code);
        }
        LocalDate issue = in != null && in.issueDate() != null ? in.issueDate() : FleetDeliveryOrderService.today();
        Map<String, Object> site = o.get("siteId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT name, address,"
                + " city FROM \"FleetCustomerSite\" WHERE id = ?").param(o.get("siteId")));
        String address = site == null ? null : String.join(", ", java.util.stream.Stream.of(site.get("name"),
                site.get("address"), site.get("city")).filter(x -> x != null).map(Object::toString).toList());
        Instant delivered = db.jdbc().sql("SELECT MAX(\"deliveredAt\") FROM \"FleetLoad\" WHERE \"organizationId\" = ? AND"
                + " \"deliveryOrderId\" = ?").params(FleetDb.org(), deliveryOrderId).query(java.sql.Timestamp.class)
                .optional().map(java.sql.Timestamp::toInstant).orElse(null);
        Map<String, Object> inv = invoices.create(new InvoiceInput((String) o.get("partnerId"), issue.toString(), null,
                null, null, null, null, null, delivered == null ? null : JsonConfig.toJsIso(delivered), address, null,
                null, code, null, null, code, "Delivery order " + code + (o.get("sourceNumber") == null ? ""
                        : " (from " + o.get("sourceNumber") + ")"), lines));
        String invoiceId = (String) inv.get("id");
        String number = (String) inv.get("documentNumber");
        boolean goodsAdded = goods && lines.stream().anyMatch(l -> l.productId() != null);
        if (goodsAdded) {
            invoices.setStockIssuedByDelivery(invoiceId, code);
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        if (goodsAdded) {
            data.put("goodsInvoiceId", invoiceId);
            data.put("goodsInvoiceNumber", number);
            data.put("goodsInvoiceKind", "document");
        }
        if (charge != null) {
            data.put("chargesInvoiceId", invoiceId);
            db.insert(FleetDeliveryCharge.class, Obj.of("deliveryOrderId", deliveryOrderId, "ruleId",
                    charge.rule().id(), "description", charge.description(), "basis", charge.rule().basis(), "quantity",
                    charge.quantity(), "rate", charge.rule().rate(), "amount", charge.amount(), "taxId",
                    charge.rule().taxId(), "invoiceId", invoiceId, "invoiceNumber", number, "createdBy",
                    TenantContext.userId()));
        }
        if (!data.isEmpty()) {
            data.put("updatedBy", TenantContext.userId());
            db.update(FleetDeliveryOrder.class, deliveryOrderId, data);
        }
        audit.recordInTx(AuditInput.of(FleetDeliveryOrderService.AUDIT_ENTITY, deliveryOrderId, "update", null,
                Obj.of("invoiceId", invoiceId, "invoiceNumber", number, "goods", goodsAdded, "transportCharge",
                        charge == null ? null : charge.amount())));
        Map<String, Object> out = orders.get(deliveryOrderId);
        out.put("invoice", Obj.of("id", invoiceId, "documentNumber", number, "totalAmount", inv.get("totalAmount"),
                "status", inv.get("status")));
        return out;
    }

    /** Charge facts: delivered trips, tonnes / m³ delivered, trip distance, the site's zone and distance. */
    DeliveryChargeCalculator.Facts facts(Map<String, Object> o) {
        Map<String, Object> site = o.get("siteId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT zone,"
                + " \"distanceKm\" FROM \"FleetCustomerSite\" WHERE id = ? AND \"organizationId\" = ?")
                .params(o.get("siteId"), FleetDb.org()));
        Map<String, Object> trips = SqlRows.one(db.jdbc().sql("SELECT count(*) AS n, COALESCE(SUM(\"distanceKm\"), 0) AS km"
                + " FROM \"FleetLoad\" WHERE \"organizationId\" = ? AND \"deliveryOrderId\" = ? AND status IN"
                + " ('delivered', 'closed')").params(FleetDb.org(), o.get("id")));
        BigDecimal tonnes = BigDecimal.ZERO;
        BigDecimal m3 = BigDecimal.ZERO;
        for (Map<String, Object> l : SqlRows.list(db.jdbc().sql("SELECT ll.\"productId\", ll.\"uomFactor\","
                + " ll.\"deliveredQty\" FROM \"FleetLoadLine\" ll JOIN \"FleetLoad\" t ON t.id = ll.\"loadId\" WHERE"
                + " ll.\"organizationId\" = ? AND t.\"deliveryOrderId\" = ? AND t.status IN ('delivered', 'closed') AND"
                + " ll.\"deliveredQty\" > 0").params(FleetDb.org(), o.get("id")))) {
            FleetLoadFactors.Factor f = factors.forProduct((String) l.get("productId"))
                    .perLineUnit(SqlRows.dec(l, "uomFactor"));
            BigDecimal q = SqlRows.dec(l, "deliveredQty");
            if (f.tonnesPerUnit() != null) {
                tonnes = tonnes.add(q.multiply(f.tonnesPerUnit()));
            }
            if (f.m3PerUnit() != null) {
                m3 = m3.add(q.multiply(f.m3PerUnit()));
            }
        }
        return new DeliveryChargeCalculator.Facts((String) o.get("partnerId"), site == null ? null
                : (String) site.get("zone"), site == null ? null : (BigDecimal) site.get("distanceKm"),
                ((Number) trips.get("n")).intValue(), tonnes.setScale(3, RoundingMode.HALF_UP),
                m3.setScale(3, RoundingMode.HALF_UP), SqlRows.dec(trips, "km"));
    }
}
