package com.inventoryerp.customization.application;

import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The entities custom fields can be attached to (ADR-S029 §1): core records by table (plus a document-type filter for
 * the shared {@code Document} table) and every custom object as {@code x_<key>}.
 */
@Component
public class CustomEntities {

    /** A core entity: its table, an optional {@code documentType} filter and a soft-delete column. */
    public record Entity(String key, String label, String table, String documentType, boolean softDelete) {}

    public static final Map<String, Entity> CORE = core();

    private static Map<String, Entity> core() {
        Map<String, Entity> m = new LinkedHashMap<>();
        m.put("partner", new Entity("partner", "Partner", "Partner", null, true));
        m.put("product", new Entity("product", "Product", "Product", null, true));
        m.put("sales_invoice", new Entity("sales_invoice", "Sales invoice", "Document", "sales_invoice", false));
        m.put("credit_note", new Entity("credit_note", "Credit note", "Document", "credit_note", false));
        m.put("vendor_bill", new Entity("vendor_bill", "Vendor bill", "Document", "vendor_bill", false));
        m.put("purchase_order", new Entity("purchase_order", "Purchase order", "PurchaseOrder", null, true));
        m.put("sales_order", new Entity("sales_order", "Sales order", "SalesOrder", null, true));
        m.put("sales_quotation", new Entity("sales_quotation", "Sales quotation", "SalesQuotation", null, true));
        m.put("deal", new Entity("deal", "Deal", "Deal", null, true));
        m.put("asset", new Entity("asset", "Fixed asset", "Asset", null, true));
        m.put("task", new Entity("task", "Task", "Task", null, true));
        return java.util.Collections.unmodifiableMap(m);
    }

    private final JdbcClient jdbc;

    public CustomEntities(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code x_<key>} → the custom object's id in {@code org}, else null. */
    public String customObjectId(String org, String entity) {
        if (entity == null || !entity.startsWith("x_")) {
            return null;
        }
        return jdbc.sql("SELECT id FROM \"CustomObjectDefinition\" WHERE \"organizationId\" = ? AND key = ?")
                .params(org, entity.substring(2)).query(String.class).optional().orElse(null);
    }

    /** 400 unless {@code entity} is a core entity or an existing custom object of {@code org}. */
    public void assertEntity(String org, String entity) {
        if (CORE.containsKey(entity)) {
            return;
        }
        if (customObjectId(org, entity) == null) {
            throw ApiException.badRequest("Unknown entity '" + entity + "'");
        }
    }

    /** Does record {@code id} of {@code entity} exist (not deleted) in {@code org}? */
    public boolean exists(String org, String entity, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        Entity e = CORE.get(entity);
        if (e == null) {
            String objectId = customObjectId(org, entity);
            return objectId != null && jdbc.sql("SELECT count(*) FROM \"CustomObjectRecord\" WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"objectId\" = ? AND \"deletedAt\" IS NULL")
                    .params(id, org, objectId).query(Long.class).single() > 0;
        }
        String sql = "SELECT count(*) FROM \"" + e.table() + "\" WHERE id = ? AND \"organizationId\" = ?"
                + (e.softDelete() ? " AND \"deletedAt\" IS NULL" : "")
                + (e.documentType() == null ? "" : " AND \"documentType\"::text = '" + e.documentType() + "'");
        return jdbc.sql(sql).params(id, org).query(Long.class).single() > 0;
    }
}
