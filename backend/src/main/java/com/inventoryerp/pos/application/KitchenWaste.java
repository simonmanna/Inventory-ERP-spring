package com.inventoryerp.pos.application;

import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.crud.SqlRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Food the kitchen had started that leaves an unpaid order is issued as {@code waste} (port of
 * {@code order/kitchen-waste.ts}). Runs after the cancellation commits; failures are logged, never undo it.
 */
@Component
public class KitchenWaste {

    private static final Logger log = LoggerFactory.getLogger(KitchenWaste.class);

    /** One wasted line. */
    public record Line(String orderItemId, double quantity) {}

    private final JdbcClient jdbc;
    private final StockService stock;
    private final PosStockLocation locations;

    public KitchenWaste(JdbcClient jdbc, StockService stock, PosStockLocation locations) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.locations = locations;
    }

    public void record(String organizationId, String orderId, List<Line> lines, String reason, String approvedById) {
        if (lines.isEmpty()) {
            return;
        }
        Map<String, Object> order = SqlRows.one(jdbc.sql("SELECT \"orderNumber\", \"cashSessionId\" FROM \"Order\" WHERE id = ?"
                + " AND \"organizationId\" = ?").params(orderId, organizationId));
        String registerLocationId = order == null || order.get("cashSessionId") == null ? null
                : jdbc.sql("SELECT \"registerLocationId\" FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(order.get("cashSessionId"), organizationId).query(String.class).optional().orElse(null);
        String location = locations.resolve(organizationId, registerLocationId);
        if (location == null) {
            log.warn("[waste] no stock location for order {}; kitchen waste not recorded", orderId);
            return;
        }
        String orderNumber = order == null ? orderId : (String) order.get("orderNumber");
        for (Line line : lines) {
            if (!(line.quantity() > 0)) {
                continue;
            }
            Map<String, Object> item = SqlRows.one(jdbc.sql("SELECT id, description, \"menuItemId\", \"productId\","
                    + " \"variantId\", quantity, \"voidedQty\" FROM \"OrderItem\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(line.orderItemId(), organizationId));
            if (item == null) {
                continue;
            }
            double voided = PosSql.num(item.get("voidedQty"));
            String sourceId = item.get("id") + ":" + JsFormat.str(voided != 0 ? voided : line.quantity());
            long already = jdbc.sql("SELECT count(*) FROM \"InventoryLedger\" WHERE \"organizationId\" = ?"
                            + " AND \"referenceType\" = 'pos_kitchen_waste' AND \"referenceId\" = ?")
                    .params(organizationId, sourceId).query(Long.class).single();
            if (already > 0) {
                continue;
            }
            List<Object[]> components = new ArrayList<>();
            if (item.get("menuItemId") != null) {
                Boolean tracked = jdbc.sql("SELECT \"isInventoryTracked\" FROM \"MenuItem\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(item.get("menuItemId"), organizationId).query(Boolean.class)
                        .optional().orElse(null);
                if (Boolean.FALSE.equals(tracked)) {
                    continue;
                }
                double multiplier = 1;
                if (item.get("variantId") != null) {
                    Object m = jdbc.sql("SELECT \"qtyMultiplier\" FROM \"MenuItemVariant\" WHERE id = ? AND \"organizationId\" = ?"
                            + " AND \"deletedAt\" IS NULL").params(item.get("variantId"), organizationId)
                            .query(PosSql.SCALAR).optional().orElse(null);
                    double mv = m == null ? 1 : PosSql.num(m);
                    if (Double.isFinite(mv) && mv > 0) {
                        multiplier = mv;
                    }
                }
                for (Map<String, Object> r : jdbc.sql("SELECT \"productId\", quantity, \"uomId\" FROM \"MenuProduct\""
                        + " WHERE \"menuItemId\" = ? AND \"organizationId\" = ?").params(item.get("menuItemId"), organizationId)
                        .query().listOfRows()) {
                    components.add(new Object[] {r.get("productId"), PosSql.num(r.get("quantity")) * line.quantity()
                            * multiplier, r.get("uomId")});
                }
            } else if (item.get("productId") != null) {
                Boolean track = jdbc.sql("SELECT \"trackInventory\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(item.get("productId"), organizationId).query(Boolean.class)
                        .optional().orElse(null);
                if (Boolean.TRUE.equals(track)) {
                    components.add(new Object[] {item.get("productId"), line.quantity(), null});
                }
            }
            for (Object[] c : components) {
                double q = (double) c[1];
                if (!(q > 0)) {
                    continue;
                }
                try {
                    StockMove mv = StockMove.of((String) c[0], location, PosSql.decOf(q));
                    mv.uomId = (String) c[2];
                    mv.moveType = "waste";
                    mv.sourceType = "pos_kitchen_waste";
                    mv.sourceId = sourceId;
                    mv.reference = "Kitchen waste " + orderNumber;
                    mv.notes = item.get("description") + " ×" + JsFormat.str(line.quantity()) + " cancelled after kitchen: "
                            + reason;
                    mv.approvedById = approvedById;
                    stock.issue(mv);
                } catch (RuntimeException e) {
                    log.warn("[waste] {} on {}: {}", item.get("description"), orderNumber, e.getMessage());
                }
            }
        }
    }
}
