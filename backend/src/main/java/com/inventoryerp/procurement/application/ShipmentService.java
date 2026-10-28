package com.inventoryerp.procurement.application;

import static com.inventoryerp.procurement.application.ProcurementSql.NOW;
import static com.inventoryerp.procurement.application.ProcurementSql.org;
import static com.inventoryerp.procurement.application.ProcurementSql.user;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Import shipments (ADR-S007 §2): one consignment groups its goods receipts (partial deliveries) and the landed cost
 * vouchers raised against it, so cost per item can be reported per shipment.
 */
@Service
public class ShipmentService {

    private static final Set<String> STATUSES = Set.of("open", "in_transit", "arrived", "closed");

    /** Create / update body (dates YYYY-MM-DD). */
    public record ShipmentInput(String reference, String supplierId, String transportMode, String billOfLading,
            String origin, String etd, String eta, String arrivedAt, String status, String costCenterId, String notes) {}

    private final JdbcClient jdbc;
    private final SequenceService seq;

    public ShipmentService(JdbcClient jdbc, SequenceService seq) {
        this.jdbc = jdbc;
        this.seq = seq;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        return SqlRows.list(jdbc.sql("SELECT s.*, p.name AS \"supplierName\", (SELECT count(*) FROM \"ShipmentReceipt\" r"
                + " WHERE r.\"shipmentId\" = s.id) AS \"receiptCount\", (SELECT count(*) FROM \"LandedCost\" l WHERE"
                + " l.\"shipmentId\" = s.id AND l.status NOT IN ('cancelled', 'reversed')) AS \"voucherCount\""
                + " FROM \"Shipment\" s LEFT JOIN \"Partner\" p ON p.id = s.\"supplierId\" WHERE s.\"organizationId\" = ?"
                + (status != null && !status.isEmpty() ? " AND s.status = ?" : " AND ?::text IS NULL")
                + " ORDER BY s.\"createdAt\" DESC LIMIT 500").params(org(), status == null || status.isEmpty() ? null
                : status));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT s.*, p.name AS \"supplierName\" FROM \"Shipment\" s"
                + " LEFT JOIN \"Partner\" p ON p.id = s.\"supplierId\" WHERE s.id = ? AND s.\"organizationId\" = ?")
                .params(id, org()));
        if (s == null) {
            throw ApiException.notFound("Shipment not found");
        }
        s.put("receipts", SqlRows.list(jdbc.sql("SELECT g.id, g.\"receiptNumber\", g.status::text AS status,"
                + " g.\"receivedAt\", (SELECT COALESCE(SUM(l.\"totalValue\"), 0) FROM \"InventoryLedger\" l WHERE"
                + " l.\"organizationId\" = g.\"organizationId\" AND l.\"referenceType\" = 'goods_receipt'"
                + " AND l.\"referenceId\" = g.id AND l.\"quantityChange\" > 0) AS \"stockValue\""
                + " FROM \"ShipmentReceipt\" r JOIN \"GoodsReceiptNote\" g ON g.id = r.\"goodsReceiptId\""
                + " WHERE r.\"organizationId\" = ? AND r.\"shipmentId\" = ? ORDER BY g.\"receiptNumber\"")
                .params(org(), id)));
        s.put("vouchers", SqlRows.list(jdbc.sql("SELECT id, code, status, date, \"totalAmount\", \"capitalizedAmount\","
                + " \"expensedAmount\", \"taxAmount\" FROM \"LandedCost\" WHERE \"organizationId\" = ? AND \"shipmentId\" = ?"
                + " ORDER BY \"createdAt\"").params(org(), id)));
        return s;
    }

    @Transactional
    public Map<String, Object> create(ShipmentInput in) {
        validate(in);
        String id = UUID.randomUUID().toString();
        String code = seq.next("shipment", "SHP-", 5);
        jdbc.sql("INSERT INTO \"Shipment\" (id, \"organizationId\", code, reference, \"supplierId\", \"transportMode\","
                        + " \"billOfLading\", origin, etd, eta, \"arrivedAt\", status, \"costCenterId\", notes, \"createdBy\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, ?::date, ?, ?, ?, ?)")
                .params(id, org(), code, in.reference(), in.supplierId(), in.transportMode(), in.billOfLading(),
                        in.origin(), in.etd(), in.eta(), in.arrivedAt(), in.status() == null ? "open" : in.status(),
                        in.costCenterId(), in.notes(), user())
                .update();
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(String id, ShipmentInput in) {
        get(id);
        validate(in);
        jdbc.sql("UPDATE \"Shipment\" SET reference = COALESCE(?, reference), \"supplierId\" = COALESCE(?, \"supplierId\"),"
                        + " \"transportMode\" = COALESCE(?, \"transportMode\"), \"billOfLading\" = COALESCE(?,"
                        + " \"billOfLading\"), origin = COALESCE(?, origin), etd = COALESCE(?::date, etd), eta ="
                        + " COALESCE(?::date, eta), \"arrivedAt\" = COALESCE(?::date, \"arrivedAt\"), status = COALESCE(?,"
                        + " status), \"costCenterId\" = COALESCE(?, \"costCenterId\"), notes = COALESCE(?, notes),"
                        + " \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?")
                .params(in.reference(), in.supplierId(), in.transportMode(), in.billOfLading(), in.origin(), in.etd(),
                        in.eta(), in.arrivedAt(), in.status(), in.costCenterId(), in.notes(), id, org())
                .update();
        return get(id);
    }

    /** Attach a goods receipt (a receipt belongs to at most one shipment). */
    @Transactional
    public Map<String, Object> addReceipt(String id, String goodsReceiptId) {
        get(id);
        long grn = jdbc.sql("SELECT count(*) FROM \"GoodsReceiptNote\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(goodsReceiptId, org()).query(Long.class).single();
        if (grn == 0) {
            throw ApiException.notFound("Goods receipt not found");
        }
        String other = jdbc.sql("SELECT s.code FROM \"ShipmentReceipt\" r JOIN \"Shipment\" s ON s.id = r.\"shipmentId\""
                + " WHERE r.\"organizationId\" = ? AND r.\"goodsReceiptId\" = ? AND r.\"shipmentId\" <> ?")
                .params(org(), goodsReceiptId, id).query(String.class).optional().orElse(null);
        if (other != null) {
            throw ApiException.conflict("The goods receipt already belongs to shipment " + other);
        }
        jdbc.sql("INSERT INTO \"ShipmentReceipt\" (id, \"organizationId\", \"shipmentId\", \"goodsReceiptId\")"
                        + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")
                .params(UUID.randomUUID().toString(), org(), id, goodsReceiptId).update();
        return get(id);
    }

    @Transactional
    public Map<String, Object> removeReceipt(String id, String goodsReceiptId) {
        jdbc.sql("DELETE FROM \"ShipmentReceipt\" WHERE \"organizationId\" = ? AND \"shipmentId\" = ?"
                + " AND \"goodsReceiptId\" = ?").params(org(), id, goodsReceiptId).update();
        return get(id);
    }

    /** Receipt ids of a shipment. */
    List<String> receiptIds(String id) {
        return jdbc.sql("SELECT \"goodsReceiptId\" FROM \"ShipmentReceipt\" WHERE \"organizationId\" = ?"
                + " AND \"shipmentId\" = ?").params(org(), id).query(String.class).list();
    }

    private void validate(ShipmentInput in) {
        if (in.status() != null && !STATUSES.contains(in.status())) {
            throw ApiException.badRequest("status must be one of the following values: open, in_transit, arrived, closed");
        }
        if (in.supplierId() != null) {
            long n = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(in.supplierId(), org()).query(Long.class).single();
            if (n == 0) {
                throw ApiException.badRequest("Supplier not found");
            }
        }
    }
}
