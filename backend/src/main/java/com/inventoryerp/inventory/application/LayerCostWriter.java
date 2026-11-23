package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit S-3: the inventory API through which another module (landed cost) re-values stock layers in place. A caller
 * first takes the stock engine's quant locks ({@link #lockQuants}), so a concurrent issue, which reads lot / serial
 * / average cost under the same lock, never takes a cost the landed cost is about to change; then it writes a lot,
 * serial or quant-average cost here. The caller's transaction owns both.
 */
@Service
public class LayerCostWriter {

    /** One quant: product, variant (null = none) and location. */
    public record Quant(String productId, String variantId, String locationId) {}

    private final JdbcClient jdbc;

    public LayerCostWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * Take the engine's advisory lock of each quant (transaction-scoped), in the engine's lock order (product,
     * variant, location), so this cannot deadlock against a multi-line stock document. Hardening R5b (P2-3): first the
     * costing locks of each product, shared and sorted ({@link CostingLocks#shareProduct}), as a stock move takes them:
     * a landed cost then runs wholly before or after a cost policy / standard cost activation, which re-costs the same
     * lots and averages under the exclusive side.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockQuants(Collection<Quant> quants) {
        String org = org();
        List<Quant> sorted = quants.stream().filter(q -> q.productId() != null && q.locationId() != null).distinct()
                .sorted(Comparator.comparing(Quant::productId)
                        .thenComparing(q -> Objects.toString(q.variantId(), ""))
                        .thenComparing(Quant::locationId))
                .toList();
        sorted.stream().map(Quant::productId).distinct().forEach(p -> CostingLocks.shareProduct(jdbc, org, p));
        for (Quant q : sorted) {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)")
                    .param(StockService.quantLockKey(org, q.productId(), Objects.toString(q.variantId(), ""),
                            q.locationId()))
                    .query().listOfRows();
        }
    }

    /** {@link #lockQuants} for one quant. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockQuant(String productId, String variantId, String locationId) {
        lockQuants(List.of(new Quant(productId, variantId, locationId)));
    }

    /** Set a lot's unit cost (call under {@link #lockQuants}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setLotUnitCost(String batchId, BigDecimal unitCost) {
        jdbc.sql("UPDATE \"InventoryBatch\" SET \"unitCost\" = ?, \"updatedAt\" = " + StockService.NOW + " WHERE id = ?"
                + " AND \"organizationId\" = ?").params(unitCost, batchId, org()).update();
    }

    /** Set a serial unit's cost (call under {@link #lockQuants}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setSerialUnitCost(String serialId, BigDecimal unitCost) {
        jdbc.sql("UPDATE \"InventorySerial\" SET \"unitCost\" = ?, \"updatedAt\" = " + StockService.NOW + " WHERE id = ?"
                + " AND \"organizationId\" = ?").params(unitCost, serialId, org()).update();
    }

    /** Set a quant's running average cost (call under {@link #lockQuants}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setQuantAverageCost(String stockItemId, BigDecimal averageCost) {
        jdbc.sql("UPDATE \"StockItem\" SET \"runningAverageCost\" = ?, \"updatedAt\" = " + StockService.NOW + " WHERE id = ?"
                + " AND \"organizationId\" = ?").params(averageCost, stockItemId, org()).update();
    }
}
