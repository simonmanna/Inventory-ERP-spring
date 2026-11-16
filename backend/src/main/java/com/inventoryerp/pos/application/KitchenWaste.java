package com.inventoryerp.pos.application;

import com.inventoryerp.inventory.application.PosStockLocation;
import com.inventoryerp.inventory.application.StockMove;
import com.inventoryerp.inventory.application.StockService;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Food the kitchen had started that leaves an unpaid order is issued as {@code waste} (port of
 * {@code order/kitchen-waste.ts}). Runs after the cancellation commits and never undoes it. Each line posts in its own
 * transaction; a line that cannot be issued is recorded as an {@code InventoryException} (kind {@code kitchen_waste}).
 */
@Component
public class KitchenWaste {

    private static final Logger log = LoggerFactory.getLogger(KitchenWaste.class);

    /** One wasted line. */
    public record Line(String orderItemId, double quantity) {}

    private final JdbcClient jdbc;
    private final StockService stock;
    private final PosStockLocation locations;

    private final TransactionTemplate lineTx;

    public KitchenWaste(JdbcClient jdbc, StockService stock, PosStockLocation locations, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.locations = locations;
        // Its own transaction even if a caller ever runs it inside one: a failed line must never mark that rollback-only.
        this.lineTx = new TransactionTemplate(txm);
        this.lineTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            BigDecimal lineQty = BigDecimal.valueOf(line.quantity());
            // {productId, quantity, uomId, variantId}
            List<Object[]> components = new ArrayList<>();
            if (item.get("menuItemId") != null) {
                Boolean tracked = jdbc.sql("SELECT \"isInventoryTracked\" FROM \"MenuItem\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(item.get("menuItemId"), organizationId).query(Boolean.class)
                        .optional().orElse(null);
                if (Boolean.FALSE.equals(tracked)) {
                    continue;
                }
                BigDecimal multiplier = BigDecimal.ONE;
                if (item.get("variantId") != null) {
                    Object m = jdbc.sql("SELECT \"qtyMultiplier\" FROM \"MenuItemVariant\" WHERE id = ? AND \"organizationId\" = ?"
                            + " AND \"deletedAt\" IS NULL").params(item.get("variantId"), organizationId)
                            .query(PosSql.SCALAR).optional().orElse(null);
                    BigDecimal mv = m == null ? null : PosSql.dec(m);
                    if (mv != null && mv.signum() > 0) {
                        multiplier = mv;
                    }
                }
                for (Map<String, Object> r : jdbc.sql("SELECT \"productId\", quantity, \"uomId\" FROM \"MenuProduct\""
                        + " WHERE \"menuItemId\" = ? AND \"organizationId\" = ?").params(item.get("menuItemId"), organizationId)
                        .query().listOfRows()) {
                    components.add(new Object[] {r.get("productId"), Money.qtyMul(Money.qtyMul(r.get("quantity"), lineQty),
                            multiplier), r.get("uomId"), null});
                }
            } else if (item.get("productId") != null) {
                Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT \"trackInventory\", \"salesUomId\" FROM \"Product\""
                        + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                        .params(item.get("productId"), organizationId));
                if (product != null && Boolean.TRUE.equals(product.get("trackInventory"))) {
                    // Post-parity (ADR-S072): the order line is in the product's sales unit and names its
                    // ProductVariant; Nest wasted the parent product in its base unit.
                    components.add(new Object[] {item.get("productId"), lineQty, product.get("salesUomId"),
                            item.get("variantId")});
                }
            }
            List<Object[]> moves = components.stream().filter(c -> ((BigDecimal) c[1]).signum() > 0).toList();
            if (moves.isEmpty()) {
                continue;
            }
            String notes = item.get("description") + " ×" + JsFormat.str(line.quantity()) + " cancelled after kitchen: "
                    + reason;
            try {
                // Post-parity (ADR-S072): one line's components post together in their own transaction (a recipe is
                // all or nothing) and a failure is recorded as an InventoryException instead of only logged.
                lineTx.executeWithoutResult(st -> {
                    for (Object[] c : moves) {
                        StockMove mv = StockMove.of((String) c[0], location, (BigDecimal) c[1]);
                        mv.uomId = (String) c[2];
                        mv.variantId = (String) c[3];
                        mv.moveType = "waste";
                        mv.sourceType = "pos_kitchen_waste";
                        mv.sourceId = sourceId;
                        mv.reference = "Kitchen waste " + orderNumber;
                        mv.notes = notes;
                        mv.approvedById = approvedById;
                        stock.issue(mv);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("[waste] {} on {}: {}", item.get("description"), orderNumber, e.getMessage());
                recordFailure(organizationId, orderId, orderNumber, location, item, lineQty, sourceId, e);
            }
        }
    }

    private void recordFailure(String organizationId, String orderId, String orderNumber, String location,
            Map<String, Object> item, BigDecimal quantity, String sourceId, RuntimeException e) {
        try {
            long open = jdbc.sql("SELECT count(*) FROM \"InventoryException\" WHERE \"organizationId\" = ? AND"
                    + " kind = 'kitchen_waste' AND status = 'open' AND payload->>'sourceId' = ?")
                    .params(organizationId, sourceId).query(Long.class).single();
            if (open > 0) {
                return;
            }
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            jdbc.sql("INSERT INTO \"InventoryException\" (id, \"organizationId\", \"invoiceNumber\", \"locationId\","
                    + " \"productId\", \"menuItemId\", description, quantity, kind, reason, payload, status)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'kitchen_waste', ?, ?::jsonb, 'open')")
                    .params(UUID.randomUUID().toString(), organizationId, orderNumber, location, item.get("productId"),
                            item.get("menuItemId"), "Kitchen waste: " + item.get("description"), quantity,
                            reason.length() > 1000 ? reason.substring(0, 1000) : reason,
                            EntityJson.toJson(Map.of("orderId", orderId, "orderItemId", item.get("id"), "sourceId",
                                    sourceId)))
                    .update();
        } catch (RuntimeException re) {
            log.error("[waste] failed to record the kitchen-waste exception for {}: {}", orderNumber, re.toString());
        }
    }
}
