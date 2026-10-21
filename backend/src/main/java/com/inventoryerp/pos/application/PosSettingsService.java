package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.inventory.domain.StockItem;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.pos.domain.MenuItem;
import com.inventoryerp.shared.web.EntityJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * POS module config, payment accounts and barcode lookup (the settings / lookup half of {@code pos.service.ts}).
 */
@Service
public class PosSettingsService {

    private final JdbcClient jdbc;
    private final PricingPolicy pricing;

    public PosSettingsService(JdbcClient jdbc, PricingPolicy pricing) {
        this.jdbc = jdbc;
        this.pricing = pricing;
    }

    /** Finance accounts a POS tender may land in (cash, bank, mobile money + the card clearing account). */
    public List<Map<String, Object>> paymentAccounts() {
        String orgId = org();
        String clearing = jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key ="
                        + " 'card_clearing' AND \"deletedAt\" IS NULL LIMIT 1")
                .param(orgId).query(String.class).optional().orElse(null);
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT a.id, a.code, a.name, c.key AS \"categoryKey\""
                + " FROM \"Account\" a JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ?"
                + " AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL AND c.key IN ('cash', 'petty_cash', 'bank',"
                + " 'mobile_money', 'current_asset') ORDER BY a.code ASC").param(orgId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : rows) {
            if ("current_asset".equals(a.get("categoryKey")) && !a.get("id").equals(clearing)) {
                continue;
            }
            out.add(Obj.of("id", a.get("id"), "code", a.get("code"), "name", a.get("name"),
                    "accountType", a.get("categoryKey")));
        }
        return out;
    }

    /** Server-authoritative barcode / SKU lookup: menu items first, then products; at most 5. */
    public List<Map<String, Object>> findBySku(String sku) {
        String orgId = org();
        List<Map<String, Object>> menuItems = SqlRows.list(MenuItem.class, jdbc.sql("SELECT * FROM \"MenuItem\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isAvailable\" = true"
                + (sku == null ? "" : " AND code ILIKE ?") + " LIMIT 5").params(sku == null ? List.of(orgId)
                        : List.of(orgId, sku)));
        String productWhere = sku == null ? "" : " AND (sku ILIKE ? OR code ILIKE ? OR barcode ILIKE ?)";
        List<Map<String, Object>> products = SqlRows.list(Product.class, jdbc.sql("SELECT * FROM \"Product\" WHERE"
                + " \"organizationId\" = ? AND \"deletedAt\" IS NULL AND \"isActive\" = true" + productWhere + " LIMIT 5")
                .params(sku == null ? List.of(orgId) : List.of(orgId, sku, sku, sku)));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : menuItems) {
            Map<String, Object> r = new LinkedHashMap<>(m);
            r.put("kind", "menu_item");
            r.put("menuItemId", m.get("id"));
            r.put("sku", m.get("code"));
            r.put("salesPrice", m.get("basePrice"));
            out.add(r);
        }
        for (Map<String, Object> p : products) {
            Map<String, Object> r = new LinkedHashMap<>(p);
            r.put("stockItems", SqlRows.list(StockItem.class, jdbc.sql("SELECT * FROM \"StockItem\" WHERE \"productId\" = ?")
                    .param(p.get("id"))));
            r.put("kind", "product");
            out.add(r);
        }
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    /** The {@code OrganizationModule('pos').config} JSON, or {@code null} when the row is missing. */
    private Map<String, Object> moduleConfig(String orgId) {
        Map<String, Object> mod = SqlRows.one(jdbc.sql("SELECT config FROM \"OrganizationModule\" WHERE"
                + " \"organizationId\" = ? AND \"moduleName\" = 'pos'").param(orgId));
        if (mod == null) {
            return null;
        }
        Object cfg = PosSql.java(mod.get("config"));
        return cfg instanceof Map<?, ?> ? new LinkedHashMap<>(PosSql.map(cfg)) : null;
    }

    /** POS module config plus the discount policy the terminal must obey. */
    public Map<String, Object> getPosSettings() {
        String orgId = org();
        Map<String, Object> config = moduleConfig(orgId);
        if (config == null) {
            config = Obj.of("posMode", "cafe");
        }
        Map<String, Object> out = new LinkedHashMap<>(config);
        out.put("connectionMode", "online".equals(config.get("connectionMode")) ? "online" : "offline");
        out.put("showClosingAmounts", !Boolean.FALSE.equals(config.get("showClosingAmounts")));
        JsonNode approval = pricing.discountApproval();
        out.put("discountApproval", Obj.of("tier1",
                PosSql.js(PricingPolicy.resolveDiscountThreshold(approval == null ? null : approval.get("tier1")))));
        return out;
    }

    /** {@code getPosSettings().sharedDrawer === true}. */
    public boolean sharedDrawer() {
        return Boolean.TRUE.equals(getPosSettings().get("sharedDrawer"));
    }

    /** Merge {@code dto} into the module config; mirror {@code posMode} as the {@code pos.mode} setting. */
    public Map<String, Object> updatePosSettings(Map<String, Object> dto) {
        String orgId = org();
        Map<String, Object> existing = moduleConfig(orgId);
        Map<String, Object> config = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        config.putAll(dto);
        String json = EntityJson.toJson(config);
        jdbc.sql("INSERT INTO \"OrganizationModule\" (id, \"organizationId\", \"moduleName\", \"isActive\", config,"
                        + " \"enabledAt\") VALUES (?, ?, 'pos', true, ?::jsonb, " + NOW + ") ON CONFLICT (\"organizationId\","
                        + " \"moduleName\") DO UPDATE SET config = EXCLUDED.config")
                .params(UUID.randomUUID().toString(), orgId, json).update();
        Object posMode = dto.get("posMode");
        if (PosSql.truthy(posMode)) {
            String value = EntityJson.toJson(posMode);
            jdbc.sql("INSERT INTO \"Setting\" (id, \"organizationId\", scope, \"scopeType\", \"scopeId\", key, value,"
                            + " \"createdAt\", \"updatedAt\") VALUES (?, ?, 'organization', 'organization', '', 'pos.mode',"
                            + " ?::jsonb, " + NOW + ", " + NOW + ") ON CONFLICT (\"organizationId\", \"scopeType\", \"scopeId\","
                            + " key) DO UPDATE SET value = EXCLUDED.value, \"updatedAt\" = " + NOW)
                    .params(UUID.randomUUID().toString(), orgId, value).update();
        }
        return config;
    }

}
