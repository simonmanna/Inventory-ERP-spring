package com.inventoryerp.customization.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Triggers, condition operators and action types of automation rules (ADR-S029 §4). */
public final class AutomationCatalog {

    /** A trigger: the domain event, the entity it concerns and the payload field holding the record id. */
    public record Trigger(String event, String label, String entity, String idField) {}

    public static final Map<String, Trigger> TRIGGERS = triggers();

    public static final Set<String> OPERATORS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "contains", "in", "empty",
            "not_empty");

    public static final List<String> ACTIONS = List.of("notify", "email", "webhook", "set_custom_field", "create_task");

    public static final int MAX_ACTIVE_RULES = 50;
    public static final int MAX_ACTIONS = 10;
    public static final int MAX_CONDITIONS = 20;

    private static Map<String, Trigger> triggers() {
        Map<String, Trigger> m = new LinkedHashMap<>();
        add(m, "invoice.posted", "Sales invoice posted", "sales_invoice", "documentId");
        add(m, "creditnote.issued", "Credit note issued", "credit_note", "documentId");
        add(m, "bill.posted", "Vendor bill posted", "vendor_bill", "documentId");
        add(m, "partner.created", "Partner created", "partner", "id");
        add(m, "sales.order.confirmed", "Sales order confirmed", "sales_order", "orderId");
        add(m, "sales.order.cancelled", "Sales order cancelled", "sales_order", "orderId");
        add(m, "purchase_order.approved", "Purchase order approved", "purchase_order", "orderId");
        return java.util.Collections.unmodifiableMap(m);
    }

    private static void add(Map<String, Trigger> m, String event, String label, String entity, String idField) {
        m.put(event, new Trigger(event, label, entity, idField));
    }

    private AutomationCatalog() {}
}
