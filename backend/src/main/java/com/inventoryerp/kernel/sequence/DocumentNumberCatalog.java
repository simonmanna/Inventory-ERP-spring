package com.inventoryerp.kernel.sequence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sequences whose format a tenant may change (CTRL-2). A key is the logical part before the first {@code :}, so
 * {@code po} covers the yearly {@code po:2026}. Left out on purpose: POS keys (invoice, receipt, order, kitchen ticket,
 * which are frozen), journals (prefix comes from the journal) and keys used with several prefixes.
 */
public final class DocumentNumberCatalog {

    /** One configurable sequence. {@code yearly}: the code starts a new counter every year. */
    public record Entry(String key, String label, String group, String defaultPrefix, int defaultPadding,
            boolean yearly) {}

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        for (Entry e : List.of(
                new Entry("pr", "Purchase request", "Procurement", "PR-{YYYY}-", 5, true),
                new Entry("po", "Purchase order", "Procurement", "PO-{YYYY}-", 5, true),
                new Entry("grn", "Goods receipt", "Procurement", "GRN-{YYYY}-", 5, true),
                new Entry("supplier-payment", "Supplier payment", "Procurement", "PV-{YYYY}-", 6, true),
                new Entry("landed_cost", "Landed cost", "Procurement", "LC-", 5, false),
                new Entry("crm_quotation", "Quotation", "Sales", "QT-{YYYY}-", 5, true),
                new Entry("fleet_delivery_order", "Delivery order", "Sales", "DO-", 6, false),
                new Entry("fleet_delivery_note", "Delivery note", "Sales", "DN-", 6, false),
                new Entry("expense", "Expense", "Finance", "EXP-", 5, true),
                new Entry("stock_adj", "Stock adjustment", "Inventory", "ADJ-", 5, false),
                new Entry("stock_out", "Stock out", "Inventory", "SO-", 5, false),
                new Entry("stock_transfer", "Stock transfer", "Inventory", "TRF-", 5, false),
                new Entry("waste_doc", "Waste", "Inventory", "WST-", 5, false),
                new Entry("inv_count", "Inventory count", "Inventory", "CNT-", 5, false),
                new Entry("production_order", "Production order", "Production", "MO-", 5, false),
                new Entry("production_plan", "Production plan", "Production", "PLN-", 5, false),
                new Entry("crushing_run", "Crushing run", "Production", "CR-", 5, false),
                new Entry("quality_lot", "Quality lot", "Quality", "QL-", 6, false),
                new Entry("quality_ncr", "Non-conformance report", "Quality", "NCR-", 5, false),
                new Entry("quality_capa", "Corrective action", "Quality", "CAPA-", 5, false),
                new Entry("fleet_trip", "Trip", "Fleet", "TRP-", 6, false),
                new Entry("fleet_work_order", "Work order", "Fleet", "WO-", 5, false),
                new Entry("fleet_freight_bill", "Freight bill", "Fleet", "FB-", 5, false),
                new Entry("rental_agreement", "Rental agreement", "Rental", "RAG-", 5, false),
                new Entry("repair_order", "Repair order", "Repair", "RPR-", 6, false))) {
            ENTRIES.put(e.key(), e);
        }
    }

    private DocumentNumberCatalog() {}

    public static List<Entry> entries() {
        return List.copyOf(ENTRIES.values());
    }

    /** The entry of a logical key, or {@code null}. */
    public static Entry entry(String key) {
        return ENTRIES.get(key);
    }

    /** {@code po:2026} → {@code po}. */
    public static String baseKey(String sequenceKey) {
        int colon = sequenceKey.indexOf(':');
        return colon < 0 ? sequenceKey : sequenceKey.substring(0, colon);
    }

    /** Whether a format row can exist for this sequence, checked before any query. */
    public static boolean configurable(String sequenceKey) {
        return ENTRIES.containsKey(baseKey(sequenceKey));
    }

    /** {@code prefix + zero-padded digits + suffix}, with {@code {YYYY}}, {@code {YY}} and {@code {MM}} filled in. */
    public static String render(String prefix, String suffix, int padding, String digits, int year, int month) {
        return expand(prefix, year, month) + "0".repeat(Math.max(0, padding - digits.length())) + digits
                + expand(suffix, year, month);
    }

    private static String expand(String text, int year, int month) {
        String yyyy = String.valueOf(year);
        return text.replace("{YYYY}", yyyy).replace("{YY}", yyyy.substring(Math.max(0, yyyy.length() - 2)))
                .replace("{MM}", String.format("%02d", month));
    }
}
