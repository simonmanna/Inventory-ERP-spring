package com.inventoryerp.inventory.application;

import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IPR R6 (inventory production readiness, Phase 0, task T07): which inventory-related features one organisation uses,
 * and which deferred P2 findings those features turn into go-live blockers for it. Read-only: every feature is one
 * bounded {@code count} (at most {@value #COUNT_CAP} rows are counted) over indexed columns, so the map stays cheap on a
 * large organisation. The P2 → feature map ({@link #P2_FEATURES}) mirrors {@code docs/spec/inventory-feature-risk-map.md};
 * the daily integrity monitor raises a report-only {@code integrity_feature_risk} finding while any of them applies.
 */
@Service
public class InventoryFeatureUsageService {

    /** Rows counted per feature at most; a count of {@value} means "at least that many". */
    public static final int COUNT_CAP = 10_001;

    /** A catalogue above this many live products is "large" (S-9 / S-10). */
    public static final int LARGE_CATALOGUE = 10_000;

    /** One feature: {@code count} rows found (capped at {@link #COUNT_CAP}); {@code inUse} when the predicate holds. */
    public record Feature(String key, String label, boolean inUse, long count, String evidence) {}

    /** One deferred P2 promoted for this organisation because a feature it depends on is in use. */
    public record PromotedP2(String id, String title, List<String> matchedFeatures) {}

    /** The map of one organisation. */
    public record FeatureUsage(Instant checkedAt, String organizationId, List<Feature> features,
            List<PromotedP2> promotedP2s) {}

    /** A deferred P2 and its feature predicate: promoted when every feature of any one group is in use. */
    public record P2Rule(String id, String title, List<List<String>> anyOf) {}

    private record Def(String key, String label, String sql) {}

    /** Ledger rows of the organisation with one of the given reference types (index on org, referenceType). */
    private static String ledgerRef(String... types) {
        String in = java.util.Arrays.stream(types).map(t -> "'" + t + "'").collect(Collectors.joining(", "));
        return "SELECT 1 FROM \"InventoryLedger\" WHERE \"organizationId\" = :org AND \"referenceType\" IN (" + in + ")";
    }

    private static final String LIVE_PRODUCT = "SELECT 1 FROM \"Product\" WHERE \"organizationId\" = :org AND"
            + " \"deletedAt\" IS NULL AND ";

    /** Stock locations that hold goods (not the virtual and transit ones). */
    private static final String STOCK_LOCATION = "\"isActive\" = true AND \"deletedAt\" IS NULL AND type::text NOT IN"
            + " ('virtual', 'transit')";

    /** Every feature, in report order. {@code negative_stock_blocked} is resolved from settings, not SQL. */
    private static final List<Def> DEFS = List.of(
            new Def("inventory_in_use", "Stock is kept (any quant)",
                    "SELECT 1 FROM \"StockItem\" WHERE \"organizationId\" = :org"),
            new Def("serial_products", "Serial-tracked products", LIVE_PRODUCT + "\"serialTracking\" = true"),
            new Def("serial_lot_products", "Serial- and lot-tracked products",
                    LIVE_PRODUCT + "\"serialTracking\" = true AND \"batchTracking\" = true"),
            new Def("lot_products", "Lot-tracked products", LIVE_PRODUCT + "\"batchTracking\" = true"),
            new Def("lot_expiry_products", "Lot- or expiry-tracked products",
                    LIVE_PRODUCT + "(\"batchTracking\" = true OR \"expiryTracking\" = true)"),
            new Def("variants", "Product variants",
                    "SELECT 1 FROM \"ProductVariant\" WHERE \"organizationId\" = :org"),
            new Def("standard_products", "STANDARD-costed products", LIVE_PRODUCT + "\"costingMethod\"::text = 'STANDARD'"),
            new Def("fifo_products", "FIFO-costed products", LIVE_PRODUCT + "\"costingMethod\"::text = 'FIFO'"),
            new Def("avco_products", "AVCO-costed products", LIVE_PRODUCT + "\"costingMethod\"::text = 'AVCO'"),
            new Def("large_catalogue", "Live products (large catalogue above 10,000)",
                    LIVE_PRODUCT + "true"),
            new Def("fleet_delivery_orders", "Fleet delivery orders",
                    "SELECT 1 FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = :org"),
            new Def("quality_lots", "Quality-module inspection lots",
                    "SELECT 1 FROM \"QualityInspectionLot\" WHERE \"organizationId\" = :org"),
            new Def("qc_checks", "Quality-control checks", "SELECT 1 FROM \"QcCheck\" WHERE \"organizationId\" = :org"),
            new Def("manufacturing_orders", "Manufacturing (production) orders",
                    "SELECT 1 FROM \"ProductionOrder\" WHERE \"organizationId\" = :org"),
            new Def("quarry_extraction", "Quarry extraction logs",
                    "SELECT 1 FROM \"QuarryExtractionLog\" WHERE \"organizationId\" = :org"),
            new Def("crushing_runs", "Crushing runs", "SELECT 1 FROM \"CrushingRun\" WHERE \"organizationId\" = :org"),
            new Def("processing_jobs", "Processing jobs",
                    "SELECT 1 FROM \"ProcessingJob\" WHERE \"organizationId\" = :org"),
            new Def("process_costing", "Process-costing periods",
                    "SELECT 1 FROM \"ProcessCostPeriod\" WHERE \"organizationId\" = :org"),
            new Def("multi_currency_bills", "Vendor bills in a foreign currency",
                    "SELECT 1 FROM \"Document\" d JOIN \"Currency\" c ON c.id = d.\"currencyId\" JOIN \"Organization\" o"
                            + " ON o.id = d.\"organizationId\" WHERE d.\"organizationId\" = :org AND d.\"documentType\""
                            + " = 'vendor_bill' AND c.code <> o.\"currencyCode\""),
            new Def("vendor_bills", "Vendor bills",
                    "SELECT 1 FROM \"Document\" WHERE \"organizationId\" = :org AND \"documentType\" = 'vendor_bill'"),
            new Def("vendor_bill_auto_receive", "Vendor bills that received stock", ledgerRef("vendor_bill")),
            new Def("debit_notes", "Debit notes",
                    "SELECT 1 FROM \"Document\" WHERE \"organizationId\" = :org AND \"documentType\" = 'debit_note'"),
            new Def("procurement_receipts", "Goods receipt notes",
                    "SELECT 1 FROM \"GoodsReceiptNote\" WHERE \"organizationId\" = :org AND \"deletedAt\" IS NULL"),
            new Def("rtv_claims_stage", "Returns to supplier, supplier claims or stage postings",
                    ledgerRef("debit_note", "supplier_claim", "production_stage")),
            new Def("offline_devices", "Registered offline (sync) devices",
                    "SELECT 1 FROM \"PosDevice\" WHERE \"organizationId\" = :org AND \"revokedAt\" IS NULL"),
            new Def("warehouse_bins", "Warehouse bins",
                    "SELECT 1 FROM \"WarehouseBin\" WHERE \"organizationId\" = :org AND \"isActive\" = true"),
            new Def("rental", "Rental agreements", "SELECT 1 FROM \"RentalAgreement\" WHERE \"organizationId\" = :org"),
            new Def("rental_serial_units", "Rental units bound to a serial",
                    "SELECT 1 FROM \"RentalUnit\" WHERE \"organizationId\" = :org AND \"inventorySerialId\" IS NOT NULL"
                            + " AND \"deletedAt\" IS NULL"),
            new Def("repair", "Repair orders", "SELECT 1 FROM \"RepairOrder\" WHERE \"organizationId\" = :org"),
            new Def("count_sessions", "Inventory count sessions",
                    "SELECT 1 FROM \"InventoryCountSession\" WHERE \"organizationId\" = :org AND \"deletedAt\" IS NULL"),
            new Def("adjustments", "Stock adjustments",
                    "SELECT 1 FROM \"StockAdjustment\" WHERE \"organizationId\" = :org"),
            new Def("stock_outs", "Stock-outs", "SELECT 1 FROM \"StockOut\" WHERE \"organizationId\" = :org"),
            new Def("revaluations", "Inventory revaluations",
                    "SELECT 1 FROM \"InventoryRevaluation\" WHERE \"organizationId\" = :org"),
            new Def("reservations", "Stock reservations",
                    "SELECT 1 FROM \"StockReservation\" WHERE \"organizationId\" = :org"),
            new Def("sales_order_reservations", "Sales-order reservations",
                    "SELECT 1 FROM \"StockReservation\" WHERE \"organizationId\" = :org AND \"sourceType\""
                            + " = 'sales_order_line'"),
            new Def("sales_returns", "Sales returns", "SELECT 1 FROM \"SalesReturn\" WHERE \"organizationId\" = :org"),
            new Def("pos_orders", "POS orders", "SELECT 1 FROM \"Order\" WHERE \"organizationId\" = :org"),
            new Def("pos_refunds", "POS refunds that restocked", ledgerRef("pos_refund")),
            new Def("pos_kitchen_voids", "POS kitchen voids / waste", ledgerRef("pos_kitchen_waste")),
            new Def("branch_restricted_users", "Branch- or location-restricted users",
                    "SELECT 1 FROM \"UserDataScope\" WHERE \"organizationId\" = :org"),
            new Def("multi_warehouse", "Two or more stock locations",
                    "SELECT 1 FROM \"InventoryLocation\" WHERE \"organizationId\" = :org AND " + STOCK_LOCATION),
            new Def("multi_warehouse_branches", "Branches with two or more stock locations",
                    "SELECT 1 FROM \"InventoryLocation\" WHERE \"organizationId\" = :org AND \"branchId\" IS NOT NULL"
                            + " AND " + STOCK_LOCATION + " GROUP BY \"branchId\" HAVING count(*) >= 2"));

    /** Labels of the derived features (not one SQL count). */
    private static final Map<String, String> DERIVED = Map.of(
            "negative_stock_blocked", "Negative stock refused (inventory.allowNegativeStock = false at any scope)",
            "csv_exports", "CSV exports (no usage trail: assumed whenever stock is kept)");

    /**
     * The open deferred P2 findings of the 2026-11-27 inventory audit and the features that promote each to a go-live
     * blocker (IPR R6). Mirrors {@code docs/spec/inventory-feature-risk-map.md}; when a P2 is closed, remove it in both.
     */
    public static final List<P2Rule> P2_FEATURES = List.of(
            p2("X-3", "Manual moves out of QC quarantine or rental locations", List.of("qc_checks"),
                    List.of("quality_lots"), List.of("rental")),
            p2("X-4", "Offline sales against negative-stock refusal", List.of("offline_devices",
                    "negative_stock_blocked")),
            p2("X-6", "POS refund restock not linked to its issue", List.of("pos_refunds")),
            p2("X-7", "Rental serial units", List.of("rental_serial_units")),
            p2("X-8", "POS order stock paths", List.of("pos_orders")),
            p2("X-9", "Manufacturing simple orders", List.of("manufacturing_orders")),
            p2("X-10", "POS kitchen voids", List.of("pos_kitchen_voids")),
            p2("X-11", "Repair parts", List.of("repair")),
            p2("F4", "Sales returns of variant products", List.of("sales_returns", "variants")),
            p2("F5", "Sales-order reservations", List.of("sales_order_reservations")),
            p2("F6", "Vendor bills with automatic receipt", List.of("vendor_bill_auto_receive")),
            p2("F7", "Debit notes across warehouses", List.of("debit_notes", "multi_warehouse")),
            p2("F8", "Serial products received through procurement", List.of("serial_products",
                    "procurement_receipts")),
            p2("F9", "Branches with two or more warehouses", List.of("multi_warehouse_branches")),
            p2("F10", "Branch-restricted users and sales returns", List.of("branch_restricted_users",
                    "sales_returns")),
            p2("F11", "Vendor bills", List.of("vendor_bills")),
            p2("C-4", "Negative stock refused", List.of("negative_stock_blocked")),
            p2("C-6", "FIFO products and revaluations", List.of("fifo_products", "revaluations")),
            p2("C-8", "Tracking changes on stocked products", List.of("serial_products"),
                    List.of("lot_expiry_products")),
            p2("C-9", "Serial products", List.of("serial_products")),
            p2("S6", "Lot products and adjustments", List.of("lot_products", "adjustments")),
            p2("S8", "Stock-outs", List.of("stock_outs")),
            p2("S10", "Count sessions", List.of("count_sessions")),
            p2("S11", "Warehouse bins", List.of("warehouse_bins")),
            p2("S12", "Count sessions", List.of("count_sessions")),
            p2("S13", "Any inventory use", List.of("inventory_in_use")),
            p2("S14", "Reservations", List.of("reservations")),
            p2("GL-7", "AVCO with returns to supplier, claims or stage postings", List.of("avco_products",
                    "rtv_claims_stage")),
            p2("GL-9", "Revaluations", List.of("revaluations")),
            p2("S-6", "Any inventory use", List.of("inventory_in_use")),
            p2("S-8", "CSV exports", List.of("csv_exports")),
            p2("S-9", "Large catalogue (more than 10,000 products)", List.of("large_catalogue")),
            p2("S-10", "Large catalogue (more than 10,000 products)", List.of("large_catalogue")));

    @SafeVarargs
    private static P2Rule p2(String id, String title, List<String>... anyOf) {
        return new P2Rule(id, title, List.of(anyOf));
    }

    private final JdbcClient jdbc;
    private final SettingResolverService settings;
    private final LocationScope scope;

    public InventoryFeatureUsageService(JdbcClient jdbc, SettingResolverService settings, LocationScope scope) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.scope = scope;
    }

    /**
     * {@code GET /inventory/reports/feature-usage}: the map for the caller's organisation. Organisation-wide, so a user
     * restricted to some branches or locations is refused (as the other organisation-wide inventory reports).
     */
    @Transactional(readOnly = true)
    public FeatureUsage report() {
        scope.assertUnrestricted();
        return usage();
    }

    /** The feature-usage map of the current organisation, with the P2s it promotes. */
    @Transactional(readOnly = true)
    public FeatureUsage usage() {
        String org = TenantContext.organizationId();
        List<Feature> features = new ArrayList<>();
        Map<String, Feature> byKey = new LinkedHashMap<>();
        for (Def d : DEFS) {
            long n = jdbc.sql("SELECT count(*) FROM (" + d.sql() + " LIMIT " + COUNT_CAP + ") x").param("org", org)
                    .query(Long.class).single();
            boolean inUse = switch (d.key()) {
                case "large_catalogue" -> n > LARGE_CATALOGUE;
                case "multi_warehouse" -> n >= 2;
                default -> n > 0;
            };
            Feature f = new Feature(d.key(), d.label(), inUse, n, null);
            features.add(f);
            byKey.put(d.key(), f);
        }
        boolean orgAllows = settings.resolveBool("inventory.allowNegativeStock", SettingContext.EMPTY);
        long refusing = jdbc.sql("SELECT count(*) FROM \"Setting\" WHERE \"organizationId\" = ? AND key ="
                + " 'inventory.allowNegativeStock' AND value = 'false'::jsonb").param(org).query(Long.class).single();
        Feature negative = new Feature("negative_stock_blocked", DERIVED.get("negative_stock_blocked"),
                !orgAllows || refusing > 0, refusing, "organisation level " + (orgAllows ? "allows" : "refuses")
                        + "; " + refusing + " setting row(s) refuse");
        features.add(negative);
        byKey.put(negative.key(), negative);
        Feature inUse = byKey.get("inventory_in_use");
        Feature csv = new Feature("csv_exports", DERIVED.get("csv_exports"), inUse.inUse(), inUse.count(),
                "assumed from inventory_in_use");
        features.add(csv);
        byKey.put(csv.key(), csv);
        return new FeatureUsage(Instant.now(), org, features, promoted(byKey));
    }

    /** The P2s whose predicate holds for these features. */
    static List<PromotedP2> promoted(Map<String, Feature> byKey) {
        Set<String> used = byKey.values().stream().filter(Feature::inUse).map(Feature::key)
                .collect(Collectors.toSet());
        List<PromotedP2> out = new ArrayList<>();
        for (P2Rule rule : P2_FEATURES) {
            for (List<String> group : rule.anyOf()) {
                if (used.containsAll(group)) {
                    out.add(new PromotedP2(rule.id(), rule.title(), group));
                    break;
                }
            }
        }
        return out;
    }

    /** Every feature key the P2 map may name (for tests and the doc). */
    public static Set<String> featureKeys() {
        Set<String> keys = DEFS.stream().map(Def::key).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        keys.addAll(DERIVED.keySet());
        return keys;
    }
}
