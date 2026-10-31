package com.inventoryerp.kernel.seed;

import java.util.List;

/**
 * Roles and sign-ins of the construction demo organization, one per role of
 * {@code docs/guides/construction-materials.md}. Password {@code Demo@123}, like the POS demo staff; a demo database
 * only.
 */
final class ConstructionDemoRoles {

    private ConstructionDemoRoles() {}

    /** {@code branchCode} is the user's default branch; no user is restricted to a branch in the demo. */
    record Staff(String email, String firstName, String lastName, String roleName, String description,
            String branchCode, List<String> permissions) {}

    private static final List<String> LOOKUPS = List.of("branch:read", "partner:read", "partners.view", "product:read",
            "products.view", "inventory_location:read");

    private static List<String> with(List<String> extra) {
        return java.util.stream.Stream.concat(LOOKUPS.stream(), extra.stream()).toList();
    }

    static final List<Staff> STAFF = List.of(
            new Staff("weighbridge@construction.test", "Grace", "Weighbridge", "Weighbridge Operator",
                    "Weighs trucks in and out at the gate", "QRY", with(List.of("weighbridge:read", "weighbridge:weigh",
                            "fleet_dispatch:read", "fleet_trip:read", "fleet_vehicle:read"))),
            new Staff("dispatcher@construction.test", "Daniel", "Dispatcher", "Dispatcher",
                    "Plans delivery orders and assigns trucks and drivers", "KLA", with(List.of("fleet_dispatch:read",
                            "fleet_dispatch:create", "fleet_dispatch:update", "fleet_dispatch:plan",
                            "fleet_dispatch:cancel", "fleet_trip:read", "fleet_trip:create", "fleet_trip:update",
                            "fleet_trip:dispatch", "fleet_trip:deliver", "fleet_trip:close", "fleet_trip:cancel",
                            "fleet_vehicle:read", "fleet_driver:read", "weighbridge:read", "inventory:read"))),
            new Staff("driver@construction.test", "Joseph", "Driver", "Driver",
                    "Own trips, proof of delivery, fuel drafts and pre-trip checks", "QRY",
                    List.of("fleet_trip:read", "fleet_trip:deliver", "fleet_vehicle:read", "fleet_fuel:read",
                            "fleet_fuel:create", "fleet_maintenance:inspect")),
            new Staff("plant@construction.test", "Patrick", "Plant", "Plant Supervisor",
                    "Runs the quarry, crusher line and block plant shifts", "QRY", with(List.of(
                            "quarry_site:read", "quarry_extraction:read", "quarry_extraction:create",
                            "quarry_extraction:update", "quarry_extraction:post", "quarry_extraction:haul",
                            "quarry_blasting:read", "crushing:read", "crushing_run:record", "crushing_run:post",
                            "crushing_survey:record", "production_order:read", "production_order:create",
                            "production_order:start", "production_order:complete", "production_order:qc",
                            "production_plan:read",
                            "production:report", "shop_floor:machine_data", "bom:read", "work_center:read",
                            "inventory:read"))),
            new Staff("quality@construction.test", "Queen", "Quality", "Quality Officer",
                    "Samples, tests, holds and releases material", "QRY", with(List.of("quality:read",
                            "quality:inspect", "quality:hold", "quality:release", "quality:dispose", "quality:ncr",
                            "quality:capa",
                            "production_order:read", "production_order:qc", "crushing:read", "inventory:read"))),
            new Staff("stores@construction.test", "Simon", "Stores", "Storekeeper",
                    "Receives, issues, transfers and counts stock", "KLA", with(List.of("inventory:read",
                            "inventory:move", "inventory_count:read", "inventory_count:count", "inventory_count:submit",
                            "inventory_doc:read", "inventory_doc:create", "goods_receipt:read",
                            "goods_receipt:create", "purchase_order:read", "purchase_order:receive",
                            "fleet_maintenance:read"))),
            new Staff("accounts@construction.test", "Amina", "Accounts", "Accountant",
                    "Invoices, receipts, supplier bills, journals and period close", "KLA", with(List.of("invoice:read",
                            "invoice:create", "invoice:update", "invoice:post", "invoice:send", "credit_note:read",
                            "credit_note:create", "credit_note:post", "payment:read", "payment:create",
                            "payment:allocate", "journal_entry:read", "journal_entry:create", "journal_entry:post",
                            "account:read", "account_mapping:read", "fiscal_period:read", "report:accounting",
                            "report:ar", "expense:read", "expense:create", "expense:post", "fleet_freight:read",
                            "fleet_freight:bill", "credit_control:read", "analytics:view", "analytics:finance"))),
            new Staff("gm@construction.test", "Martin", "Manager", "General Manager",
                    "Dashboards, approvals and exceptions across the business", "KLA", with(List.of("analytics:view",
                            "analytics:quarry", "analytics:aggregates", "analytics:blocks", "analytics:inventory",
                            "analytics:procurement", "analytics:sales", "analytics:fleet", "analytics:maintenance",
                            "analytics:finance", "analytics:branch", "alerts:read", "bi:export", "approvals:read",
                            "approvals:decide",
                            "weighbridge:read", "weighbridge:approve_exception", "fleet:report",
                            "fleet_dispatch:read", "fleet_dispatch:approve_discrepancy", "fleet_freight:read",
                            "fleet_freight:approve_variance", "quarry:report", "production:report",
                            "report:accounting", "report:ar", "credit_control:read", "credit_control:override"))));
}
