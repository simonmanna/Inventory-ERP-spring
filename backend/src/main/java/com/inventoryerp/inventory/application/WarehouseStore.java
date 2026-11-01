package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Shared reads and the bin sub-ledger writes of warehouse management (roadmap 2.1, ADR-S026). Every quantity inside a
 * location splits into binned ({@code WarehouseBinStock}), staged (picked, not shipped) and unbinned (the rest of the
 * location's {@code StockItem} quantity). Callers run inside their own transaction.
 */
@Component
public class WarehouseStore {

    static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Per-location settings; defaults when the location has no row yet. */
    public record Settings(String locationId, boolean enabled, int outboundSteps, BigDecimal abcAPercent,
            BigDecimal abcBPercent, int abcLookbackDays, int countDaysA, int countDaysB, int countDaysC,
            boolean stored) {

        int countDays(char abcClass) {
            return abcClass == 'A' ? countDaysA : abcClass == 'B' ? countDaysB : countDaysC;
        }
    }

    private final JdbcClient jdbc;
    private final InventoryLookups lookups;
    private final LocationScope locationScope;
    private final SequenceService seq;

    public WarehouseStore(JdbcClient jdbc, InventoryLookups lookups, LocationScope locationScope,
            SequenceService seq) {
        this.jdbc = jdbc;
        this.lookups = lookups;
        this.locationScope = locationScope;
        this.seq = seq;
    }

    /** One task row to insert. */
    record NewTask(String locationId, String type, String productId, String variantKey, String batchKey,
            BigDecimal quantity, String fromBinId, String toBinId, String pickOrderId, String pickLineId,
            String waveId, int sequence) {}

    String nextCode(String key, String prefix) {
        return seq.next(key, prefix, 6);
    }

    /** Insert an open task and return its id. */
    String insertTask(NewTask t) {
        String id = id();
        jdbc.sql("INSERT INTO \"WarehouseTask\" (id, \"organizationId\", \"locationId\", code, type, status,"
                        + " \"productId\", \"variantKey\", \"batchKey\", quantity, \"fromBinId\", \"toBinId\","
                        + " \"pickOrderId\", \"pickLineId\", \"waveId\", sequence, \"createdBy\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, 'open', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ", "
                        + NOW + ")")
                .params(id, org(), t.locationId(), nextCode("wms_task", "WT-"), t.type(), t.productId(),
                        key(t.variantKey()), key(t.batchKey()), t.quantity(), t.fromBinId(), t.toBinId(),
                        t.pickOrderId(), t.pickLineId(), t.waveId(), t.sequence(), user())
                .update();
        return id;
    }

    JdbcClient jdbc() {
        return jdbc;
    }

    static String org() {
        return TenantContext.organizationId();
    }

    static String user() {
        return TenantContext.userId();
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    static String key(String value) {
        return value == null ? "" : value;
    }

    /** The location (404 {@code Location not found}), checked against the caller's branch scope. */
    Map<String, Object> location(String locationId) {
        if (locationId == null || locationId.isBlank()) {
            throw ApiException.badRequest("locationId is required");
        }
        Map<String, Object> loc = lookups.requireLocation(locationId);
        locationScope.assertAllowed(locationId);
        return loc;
    }

    Map<String, Object> product(String productId) {
        Map<String, Object> p = lookups.product(productId);
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        return p;
    }

    String productName(String productId) {
        Map<String, Object> p = lookups.product(productId);
        return p == null ? productId : SqlRows.str(p, "name");
    }

    Settings settings(String locationId) {
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"WarehouseSettings\" WHERE \"locationId\" = ?"
                + " AND \"organizationId\" = ?").params(locationId, org()));
        if (row == null) {
            return new Settings(locationId, false, 1, BigDecimal.valueOf(80), BigDecimal.valueOf(95), 90, 30, 90, 180,
                    false);
        }
        return new Settings(locationId, Boolean.TRUE.equals(row.get("enabled")), ((Number) row.get("outboundSteps"))
                .intValue(), SqlRows.dec(row, "abcAPercent"), SqlRows.dec(row, "abcBPercent"),
                ((Number) row.get("abcLookbackDays")).intValue(), ((Number) row.get("countDaysA")).intValue(),
                ((Number) row.get("countDaysB")).intValue(), ((Number) row.get("countDaysC")).intValue(), true);
    }

    /** A bin, zone, aisle or rack of the organization (404 {@code Bin not found}). */
    Map<String, Object> bin(String binId) {
        Map<String, Object> bin = binId == null ? null : SqlRows.one(jdbc.sql("SELECT * FROM \"WarehouseBin\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(binId, org()));
        if (bin == null) {
            throw ApiException.notFound("Bin not found");
        }
        return bin;
    }

    /** An active bin of kind {@code bin} inside {@code locationId}: the only kind that holds stock. */
    Map<String, Object> stockBin(String binId, String locationId) {
        Map<String, Object> bin = bin(binId);
        if (!locationId.equals(bin.get("locationId"))) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " is not in this location");
        }
        if (!"bin".equals(bin.get("kind"))) {
            throw ApiException.badRequest("Only bins hold stock; " + bin.get("code") + " is a " + bin.get("kind"));
        }
        if (!Boolean.TRUE.equals(bin.get("isActive"))) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " is inactive");
        }
        return bin;
    }

    /** Serialise warehouse work on one product in one location (putaway availability, picks). */
    void lock(String locationId, String productId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?))").param("wms:" + locationId + ":" + productId)
                .query().singleRow();
    }

    BigDecimal locationQty(String locationId, String productId, String variantKey) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ?")
                .params(org(), locationId, productId, variantKey).query(BigDecimal.class).single();
    }

    BigDecimal binnedQty(String locationId, String productId, String variantKey) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseBinStock\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ?")
                .params(org(), locationId, productId, variantKey).query(BigDecimal.class).single();
    }

    /** Picked and not yet shipped: out of the bins, still in the location's ledger quantity. */
    BigDecimal stagedQty(String locationId, String productId, String variantKey) {
        return jdbc.sql("SELECT coalesce(sum(l.\"pickedQty\" - l.\"shippedQty\"), 0) FROM \"WarehousePickLine\" l"
                + " JOIN \"WarehousePickOrder\" o ON o.id = l.\"pickOrderId\" WHERE o.\"organizationId\" = ?"
                + " AND o.\"locationId\" = ? AND o.status <> 'cancelled' AND l.\"productId\" = ? AND l.\"variantKey\" = ?")
                .params(org(), locationId, productId, variantKey).query(BigDecimal.class).single();
    }

    /** Quantity in open putaway tasks, optionally ignoring one task. */
    BigDecimal openPutawayQty(String locationId, String productId, String variantKey, String exceptTaskId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseTask\" WHERE \"organizationId\" = ?"
                + " AND \"locationId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND type = 'putaway'"
                + " AND status = 'open' AND id <> ?")
                .params(org(), locationId, productId, variantKey, exceptTaskId == null ? "" : exceptTaskId)
                .query(BigDecimal.class).single();
    }

    /** Received into the location but in no bin and not staged (never negative). */
    BigDecimal unbinnedQty(String locationId, String productId, String variantKey) {
        return locationQty(locationId, productId, variantKey).subtract(binnedQty(locationId, productId, variantKey))
                .subtract(stagedQty(locationId, productId, variantKey)).max(BigDecimal.ZERO);
    }

    BigDecimal binQty(String binId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseBinStock\" WHERE \"binId\" = ?")
                .param(binId).query(BigDecimal.class).single();
    }

    /** Quantity on its way into a bin through open putaway / move tasks. */
    BigDecimal incomingQty(String binId) {
        return jdbc.sql("SELECT coalesce(sum(quantity), 0) FROM \"WarehouseTask\" WHERE \"toBinId\" = ?"
                + " AND status = 'open' AND type IN ('putaway', 'move')").param(binId).query(BigDecimal.class).single();
    }

    boolean holdsOtherProduct(String binId, String productId, String variantKey) {
        return jdbc.sql("SELECT count(*) FROM \"WarehouseBinStock\" WHERE \"binId\" = ? AND quantity > 0"
                        + " AND (\"productId\" <> ? OR \"variantKey\" <> ?)")
                .params(binId, productId, variantKey).query(Long.class).single() > 0;
    }

    /** Put {@code qty} into a bin, enforcing its capacity and single-product flag. */
    void addStock(Map<String, Object> bin, String productId, String variantKey, String batchKey, BigDecimal qty) {
        String binId = SqlRows.str(bin, "id");
        BigDecimal max = (BigDecimal) bin.get("maxQty");
        if (max != null && binQty(binId).add(qty).compareTo(max) > 0) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " capacity exceeded (max " + max.stripTrailingZeros()
                    .toPlainString() + ")");
        }
        if (!Boolean.TRUE.equals(bin.get("mixedProducts")) && holdsOtherProduct(binId, productId, variantKey)) {
            throw ApiException.badRequest("Bin " + bin.get("code") + " already holds another product");
        }
        jdbc.sql("INSERT INTO \"WarehouseBinStock\" (id, \"organizationId\", \"locationId\", \"binId\", \"productId\","
                        + " \"variantKey\", \"batchKey\", quantity, \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + NOW + ")"
                        + " ON CONFLICT (\"binId\", \"productId\", \"variantKey\", \"batchKey\") DO UPDATE"
                        + " SET quantity = \"WarehouseBinStock\".quantity + EXCLUDED.quantity, \"updatedAt\" = " + NOW)
                .params(id(), org(), bin.get("locationId"), binId, productId, variantKey, batchKey, qty).update();
    }

    /** Take {@code qty} out of a bin (400 when the bin holds less). */
    void removeStock(Map<String, Object> bin, String productId, String variantKey, String batchKey, BigDecimal qty) {
        int n = jdbc.sql("UPDATE \"WarehouseBinStock\" SET quantity = quantity - ?, \"updatedAt\" = " + NOW
                        + " WHERE \"binId\" = ? AND \"productId\" = ? AND \"variantKey\" = ? AND \"batchKey\" = ?"
                        + " AND \"organizationId\" = ? AND quantity >= ?")
                .params(qty, bin.get("id"), productId, variantKey, batchKey, org(), qty).update();
        if (n == 0) {
            throw ApiException.badRequest("Not enough stock in bin " + bin.get("code") + " for "
                    + productName(productId));
        }
        jdbc.sql("DELETE FROM \"WarehouseBinStock\" WHERE \"binId\" = ? AND quantity = 0").param(bin.get("id")).update();
    }
}
