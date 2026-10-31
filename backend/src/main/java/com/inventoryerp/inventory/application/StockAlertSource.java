package com.inventoryerp.inventory.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock below reorder level: open replenishment alerts (INV-3, per product and location, kept current by the hourly
 * replenishment scan) and, for products no rule watches, total stock below the product's minimum quantity. An alert
 * already answered with a requisition stays out: somebody is on it.
 */
@Component
public class StockAlertSource implements AlertSource {

    private static final String LINK = "/inventory/replenishment";

    private final JdbcClient jdbc;

    public StockAlertSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String alertType() {
        return AlertTypes.STOCK_BELOW_REORDER;
    }

    private static String plain(BigDecimal v) {
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertItem> collect(AlertScan scan) {
        List<AlertItem> out = new ArrayList<>();
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT a.\"ruleId\", a.available, a.\"minQty\","
                + " a.\"suggestedQty\", p.code, p.name, l.name AS location FROM \"InventoryReplenishmentAlert\" a"
                + " JOIN \"Product\" p ON p.id = a.\"productId\" JOIN \"InventoryLocation\" l ON l.id ="
                + " a.\"locationId\" WHERE a.\"organizationId\" = ? AND a.status = 'open' ORDER BY a.\"createdAt\""
                + " LIMIT 200")
                .param(scan.organizationId()))) {
            BigDecimal available = SqlRows.dec(a, "available");
            out.add(new AlertItem("rule:" + a.get("ruleId"), available.signum() <= 0 ? AlertItem.CRITICAL
                    : AlertItem.WARNING, a.get("code") + " " + a.get("name") + " at " + a.get("location") + ": "
                    + plain(available) + " available, minimum " + plain(SqlRows.dec(a, "minQty")),
                    "Suggested order: " + plain(SqlRows.dec(a, "suggestedQty")) + ".", LINK,
                    Obj.of("available", available, "minQty", a.get("minQty"), "suggestedQty", a.get("suggestedQty"))));
        }
        for (Map<String, Object> p : SqlRows.list(jdbc.sql("SELECT p.id, p.code, p.name, p.\"minQuantity\","
                + " COALESCE(s.qty, 0) AS \"onHand\" FROM \"Product\" p LEFT JOIN (SELECT \"productId\", SUM(quantity)"
                + " AS qty FROM \"StockItem\" WHERE \"organizationId\" = ? GROUP BY \"productId\") s ON s.\"productId\""
                + " = p.id WHERE p.\"organizationId\" = ? AND p.\"deletedAt\" IS NULL AND p.\"isActive\" AND"
                + " p.\"trackInventory\" AND p.\"minQuantity\" > 0 AND COALESCE(s.qty, 0) < p.\"minQuantity\" AND"
                + " NOT EXISTS (SELECT 1 FROM \"InventoryReplenishmentRule\" r WHERE r.\"productId\" = p.id AND"
                + " r.\"organizationId\" = p.\"organizationId\" AND r.\"isActive\" AND r.\"deletedAt\" IS NULL)"
                + " ORDER BY p.code LIMIT 200").params(scan.organizationId(), scan.organizationId()))) {
            BigDecimal onHand = SqlRows.dec(p, "onHand");
            out.add(new AlertItem("product:" + p.get("id"), onHand.signum() <= 0 ? AlertItem.CRITICAL
                    : AlertItem.WARNING, p.get("code") + " " + p.get("name") + ": " + plain(onHand)
                    + " on hand, minimum " + plain(SqlRows.dec(p, "minQuantity")),
                    "Raise a purchase request or a replenishment rule.", LINK,
                    Obj.of("onHand", onHand, "minQty", p.get("minQuantity"))));
        }
        return out;
    }
}
