package com.inventoryerp.kernel.seed;

import com.inventoryerp.kernel.auth.PasswordService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Demo seed (profile {@code seed}), port of {@code reference/nest-api/prisma/seed.ts}. Refuses to run
 * when the {@code Organization} table is not empty. P1 seeds the kernel-owned and login-critical data
 * 1:1 with the Nest seed: currencies, the permission catalog, organization {@code DEMO}, the
 * Administrator role, {@code admin@demo.test}, taxes, partner/product categories, sample partners,
 * fiscal period, branch {@code MAIN}, table zones, staff roles (Cashier/Waiter/Supervisor/Manager/Kitchen)
 * with demo staff + PINs, kitchen stations and POS tables. Later phases add the parts their modules
 * own (UoM, products + stock, chart of accounts, menu, DMS registry, approval workflows).
 *
 * <p>With {@code SEED_CONSTRUCTION_DEMO=true} a second organization {@code CMDEMO} follows, in its own tenant and never
 * touching {@code DEMO}: see {@link #seedConstructionOrganization()}.
 */
@Component
@Profile("seed")
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    /** Code of the construction demo organization (the sign-in's organization field). */
    public static final String CONSTRUCTION_ORG_CODE = "CMDEMO";
    /** Contributors up to this order are the base every organization needs: units, accounts, the MAIN warehouse. */
    private static final int BASE_CONTRIBUTORS = 300;

    static final List<String> CASHIER_PERMS = List.of(
            "pos:read", "pos:checkout", "pos:hold", "pos:discount", "pos:void", "pos:kds",
            "pos:credit",
            "tables:split",
            "cash_session:open", "cash_session:read", "cash_session:close",
            "tables:view", "tables:transfer", "tables:edit",
            "branch:read",
            "partner:read",
            "partners.view",
            "products.view",
            "menu.view");

    static final List<String> WAITER_PERMS = List.of(
            "pos:read",
            "pos:checkout", "pos:hold", "pos:kds",
            "tables:view", "tables:transfer", "tables:merge", "tables:split", "tables:edit",
            "branch:read",
            "partner:read",
            "partners.view",
            "products.view",
            "menu.view",
            "menu_categories.view");

    static final List<String> SUPERVISOR_PERMS = List.of(
            "inventory_count:read", "inventory_count:count", "inventory_count:submit",
            "inventory:read", "inventory:move", "inventory_location:read",
            "inventory_doc:read", "inventory_doc:create", "inventory_doc:approve",
            "product:read", "products.view", "partner:read",
            "pos:read", "pos:reports", "pos:kds",
            "tables:split",
            "pos:void",
            "beverage:read", "beverage:count", "beverage:setup");

    static final List<String> KITCHEN_PERMS = List.of("pos:read", "pos:kds", "tables:split");

    private record Staff(String email, String firstName, String lastName, String roleName, String password, String pin) {}

    private record Zone(String key, String name, int sortOrder, String color) {}

    private record Station(String name, String code, String color, String icon, int displayOrder, boolean isDefault) {}

    private record Table(String name, int number, int seats, String zone, int posX, int posY) {}

    private final JdbcClient jdbc;
    private final PasswordService passwords;
    private final TransactionTemplate tx;
    private final String adminPassword;
    private final List<SeedContributor> contributors;
    private final boolean constructionDemo;
    private final List<ConstructionDemoPart> constructionParts;
    private final List<DemoOrganization> demoOrganizations;

    public SeedRunner(JdbcClient jdbc, PasswordService passwords, PlatformTransactionManager txm,
            @Value("${SEED_ADMIN_PASSWORD:}") String adminPassword, List<SeedContributor> contributors,
            @Value("${SEED_CONSTRUCTION_DEMO:false}") boolean constructionDemo,
            List<ConstructionDemoPart> constructionParts, List<DemoOrganization> demoOrganizations) {
        this.jdbc = jdbc;
        this.contributors = contributors == null ? List.of()
                : contributors.stream().sorted(java.util.Comparator.comparingInt(SeedContributor::order)).toList();
        this.constructionDemo = constructionDemo;
        this.constructionParts = constructionParts == null ? List.of() : constructionParts.stream()
                .sorted(java.util.Comparator.comparingInt(ConstructionDemoPart::constructionOrder)).toList();
        this.demoOrganizations = demoOrganizations == null ? List.of() : List.copyOf(demoOrganizations);
        this.passwords = passwords;
        this.tx = new TransactionTemplate(txm);
        this.adminPassword = adminPassword == null || adminPassword.isEmpty() ? "Admin@123" : adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        long orgs = jdbc.sql("SELECT count(*) FROM \"Organization\"").query(Long.class).single();
        if (orgs > 0) {
            log.warn("Seed skipped: the Organization table is not empty ({} row(s)).", orgs);
        } else {
            tx.executeWithoutResult(s -> seed());
            log.info("Seed complete. Login -> organization: \"DEMO\", email: \"admin@demo.test\", password: \"{}\"",
                    adminPassword.equals("Admin@123") ? "Admin@123" : "<SEED_ADMIN_PASSWORD>");
            if (constructionDemo) {
                seedConstructionOrganization();
                log.info("Construction demo complete. Login -> organization: \"{}\", email:"
                        + " \"admin@construction.test\"", CONSTRUCTION_ORG_CODE);
            }
        }
        for (DemoOrganization demo : demoOrganizations) {
            if (demo.enabled()) {
                seedDemoOrganization(demo);
            }
        }
    }

    /**
     * A {@link DemoOrganization}: its organization with the construction demo's base in one transaction, then
     * {@link DemoOrganization#seed} in its tenant. Skipped when the code exists, so it can be switched on for a
     * database in use. Returns the organization id.
     */
    String seedDemoOrganization(DemoOrganization demo) {
        String existing = jdbc.sql("SELECT id FROM \"Organization\" WHERE code = ?").param(demo.code())
                .query(String.class).optional().orElse(null);
        if (existing != null) {
            log.warn("Demo organization {} skipped: it exists.", demo.code());
            return existing;
        }
        String orgId = uuid();
        String adminId = tx.execute(s -> {
            currency("USD", "$", "US Dollar", 2);
            currency("UGX", "USh", "Ugandan Shilling", 0);
            return base(orgId, demo.code(), demo.name(), demo.emailDomain());
        });
        TenantContext.run(TenantStore.user(orgId, adminId), () -> {
            TenantContext.setPermissions(List.copyOf(Permissions.ALL));
            demo.seed(orgId);
        });
        log.info("Demo organization complete. Login -> organization: \"{}\", email: \"admin@{}\"", demo.code(),
                demo.emailDomain());
        return orgId;
    }

    /**
     * The opt-in construction materials demo: organization {@value #CONSTRUCTION_ORG_CODE} with its roles, users, two
     * branches and base accounting data in one transaction, then each {@link ConstructionDemoPart} in the tenant of
     * that organization. Runs after the {@code DEMO} transaction committed, so the POS demo never holds construction
     * data. Skipped when the organization exists. Returns its id.
     */
    String seedConstructionOrganization() {
        String existing = jdbc.sql("SELECT id FROM \"Organization\" WHERE code = ?").param(CONSTRUCTION_ORG_CODE)
                .query(String.class).optional().orElse(null);
        if (existing != null) {
            log.warn("Construction demo skipped: organization {} exists.", CONSTRUCTION_ORG_CODE);
            return existing;
        }
        String orgId = uuid();
        String adminId = tx.execute(s -> base(orgId, CONSTRUCTION_ORG_CODE, "Construction Materials Demo",
                "construction.test"));
        TenantStore store = TenantStore.user(orgId, adminId);
        for (ConstructionDemoPart part : constructionParts) {
            TenantContext.run(store, () -> {
                TenantContext.setPermissions(List.copyOf(Permissions.ALL));
                part.seedConstructionDemo(orgId);
            });
        }
        return orgId;
    }

    /** A construction-style organization: roles, users {@code @<domain>}, taxes, periods, branches, base data. */
    private String base(String orgId, String code, String name, String domain) {
        jdbc.sql("""
                        INSERT INTO "Organization" (id, code, name, "currencyCode", timezone, "updatedAt")
                        VALUES (?, ?, ?, 'UGX', 'Africa/Kampala', now())""")
                .params(orgId, code, name)
                .update();
        jdbc.sql("SELECT set_config('app.org_id', ?, true)").param(orgId).query(String.class).single();
        String adminRoleId = role(orgId, "Administrator", "Full platform access", Permissions.ALL);
        String adminId = user(orgId, "admin@" + domain, passwords.hash(adminPassword), "Admin", "Construction",
                null, null);
        link(adminRoleId, adminId);
        // A try-everything account with every permission and the shared demo password, for people testing the demo.
        link(adminRoleId, user(orgId, "demo@" + domain, passwords.hash("Demo@123"), "Demo", "User", null, null));
        jdbc.sql("""
                        INSERT INTO "Tax" (id, "organizationId", name, code, type, rate, "updatedAt")
                        VALUES (?, ?, 'No Tax', 'NOTAX', 'vat', 0, now()), (?, ?, 'VAT 18%', 'VAT18', 'vat', 18, now())""")
                .params(uuid(), orgId, uuid(), orgId)
                .update();
        jdbc.sql("INSERT INTO \"ProductCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, 'General', now())")
                .params(uuid(), orgId)
                .update();
        jdbc.sql("INSERT INTO \"PartnerCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, 'General', now())")
                .params(uuid(), orgId)
                .update();
        // The three months of the demo can start in the previous year.
        int year = Year.now().getValue();
        for (int y = year - 1; y <= year; y++) {
            jdbc.sql("""
                            INSERT INTO "FiscalPeriod" (id, "organizationId", name, "startDate", "endDate", status, "updatedAt")
                            VALUES (?, ?, ?, ?, ?, 'open', now())""")
                    .params(uuid(), orgId, "FY" + y, Timestamp.valueOf(LocalDateTime.of(y, 1, 1, 0, 0)),
                            Timestamp.valueOf(LocalDateTime.of(y, 12, 31, 0, 0)))
                    .update();
        }
        Map<String, String> branches = new HashMap<>();
        branches.put("QRY", branch(orgId, "QRY", "Quarry and plant"));
        branches.put("KLA", branch(orgId, "KLA", "Kampala depot"));
        for (ConstructionDemoRoles.Staff s : ConstructionDemoRoles.STAFF) {
            String roleId = role(orgId, s.roleName(), s.description(), s.permissions());
            String id = user(orgId, s.email().replace("@construction.test", "@" + domain), passwords.hash("Demo@123"),
                    s.firstName(), s.lastName(), null, branches.get(s.branchCode()));
            link(roleId, id);
        }
        for (SeedContributor c : contributors) {
            if (c.order() <= BASE_CONTRIBUTORS) {
                c.seed(orgId);
            }
        }
        return adminId;
    }

    private String branch(String orgId, String code, String name) {
        String id = uuid();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, ?, ?, now())")
                .params(id, orgId, code, name)
                .update();
        return id;
    }

    void seed() {
        // --- Global currencies ---
        currency("USD", "$", "US Dollar", 2);
        currency("EUR", "EUR", "Euro", 2);
        currency("UGX", "USh", "Ugandan Shilling", 0);

        // --- Global permission catalog ---
        for (String key : Permissions.ALL) {
            String sep = key.contains(":") ? ":" : ".";
            int idx = key.lastIndexOf(sep);
            String resource = idx >= 0 ? key.substring(0, idx) : key;
            String action = idx >= 0 ? key.substring(idx + 1) : key;
            jdbc.sql("""
                            INSERT INTO "Permission" (id, key, resource, action, "createdAt") VALUES (?, ?, ?, ?, now())
                            ON CONFLICT (key) DO UPDATE SET resource = EXCLUDED.resource, action = EXCLUDED.action""")
                    .params(uuid(), key, resource, action)
                    .update();
        }

        // --- Organization (tenant) ---
        String orgId = uuid();
        jdbc.sql("""
                        INSERT INTO "Organization" (id, code, name, "currencyCode", timezone, "receiptHeader", "receiptFooter", "updatedAt")
                        VALUES (?, 'DEMO', 'Demo Organization', 'UGX', 'Africa/Kampala', ?::jsonb, ?::jsonb, now())""")
                .params(orgId,
                        "{\"businessName\":\"Abiliz Cafe\",\"addressLine1\":\"AFEE COMPLEX, KASANGA\",\"addressLine2\":\"Kampala, Uganda\",\"phone\":\"+256757920771\",\"taxId\":\"\"}",
                        "{\"message\":\"Thank you!\"}")
                .update();
        jdbc.sql("SELECT set_config('app.org_id', ?, true)").param(orgId).query(String.class).single();

        // --- Administrator role (all permissions) ---
        String adminRoleId = role(orgId, "Administrator", "Full platform access", Permissions.ALL);

        // --- Default table zones (dining areas) ---
        for (Zone z : List.of(
                new Zone("indoor", "Indoor", 1, "#10b981"),
                new Zone("outdoor", "Outdoor", 2, "#f59e0b"),
                new Zone("terrace", "Terrace", 3, "#fb923c"),
                new Zone("vip", "VIP", 4, "#a855f7"),
                new Zone("garden", "Garden", 5, "#22c55e"),
                new Zone("bar", "Bar", 6, "#ec4899"))) {
            jdbc.sql("""
                            INSERT INTO "PosTableZone" (id, "organizationId", key, name, "sortOrder", color, active, "updatedAt")
                            VALUES (?, ?, ?, ?, ?, ?, true, now())""")
                    .params(uuid(), orgId, z.key(), z.name(), z.sortOrder(), z.color())
                    .update();
        }

        // --- Admin user ---
        String adminId = user(orgId, "admin@demo.test", passwords.hash(adminPassword), "Admin", "User", null, null);
        link(adminRoleId, adminId);

        // --- Tax ---
        jdbc.sql("""
                        INSERT INTO "Tax" (id, "organizationId", name, code, type, rate, "updatedAt")
                        VALUES (?, ?, 'No Tax', 'NOTAX', 'vat', 0, now()), (?, ?, 'VAT 18%', 'VAT18', 'vat', 18, now())""")
                .params(uuid(), orgId, uuid(), orgId)
                .update();

        // --- Categories ---
        jdbc.sql("INSERT INTO \"ProductCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, 'General', now())")
                .params(uuid(), orgId)
                .update();
        String partnerCategoryId = uuid();
        jdbc.sql("INSERT INTO \"PartnerCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, 'General', now())")
                .params(partnerCategoryId, orgId)
                .update();

        // --- Sample partners ---
        jdbc.sql("""
                        INSERT INTO "Partner" (id, "organizationId", "categoryId", code, name, "isCustomer", "isCompany", email, "updatedAt")
                        VALUES (?, ?, ?, 'CUST-001', 'Acme Retail Ltd', true, true, 'orders@acme.test', now())""")
                .params(uuid(), orgId, partnerCategoryId)
                .update();
        jdbc.sql("""
                        INSERT INTO "Partner" (id, "organizationId", "categoryId", code, name, "isSupplier", "isCompany", email, "updatedAt")
                        VALUES (?, ?, ?, 'SUPP-001', 'Global Supplies Co', true, true, 'sales@globalsupplies.test', now())""")
                .params(uuid(), orgId, partnerCategoryId)
                .update();

        // --- Fiscal period (current year) ---
        int year = Year.now().getValue();
        jdbc.sql("""
                        INSERT INTO "FiscalPeriod" (id, "organizationId", name, "startDate", "endDate", status, "updatedAt")
                        VALUES (?, ?, ?, ?, ?, 'open', now())""")
                .params(uuid(), orgId, "FY" + year, Timestamp.valueOf(LocalDateTime.of(year, 1, 1, 0, 0)),
                        Timestamp.valueOf(LocalDateTime.of(year, 12, 31, 0, 0)))
                .update();

        // --- Branch ---
        String branchId = uuid();
        jdbc.sql("INSERT INTO \"Branch\" (id, \"organizationId\", code, name, \"updatedAt\") VALUES (?, ?, 'MAIN', 'Head Office', now())")
                .params(branchId, orgId)
                .update();

        // --- Demo staff, PINs and tables ---
        jdbc.sql("UPDATE \"User\" SET \"pinHash\" = ?, \"pinHashRounds\" = 10 WHERE id = ?")
                .params(passwords.hash("1234"), adminId)
                .update();
        String cashierRole = role(orgId, "Cashier", "Can operate the POS terminal", CASHIER_PERMS);
        String waiterRole = role(orgId, "Waiter", "Can take orders and manage tables", WAITER_PERMS);
        String supervisorRole = role(orgId, "Supervisor", "Runs stock counts and inventory adjustments", SUPERVISOR_PERMS);
        String managerRole = role(orgId, "Manager",
                "Approves shift variances, force-closes and corrects shifts, runs treasury and expenses", Permissions.MANAGER);
        String kitchenRole = role(orgId, "Kitchen", "Kitchen Display — prepares and advances orders", KITCHEN_PERMS);

        for (Station st : List.of(
                new Station("Kitchen", "kitchen", "#f43f5e", "ChefHat", 0, false),
                new Station("Bar", "bar", "#f59e0b", "Coffee", 1, false),
                new Station("Cafe", "cafe", "#10b981", "Sandwich", 2, true))) {
            jdbc.sql("""
                            INSERT INTO "KitchenStation" (id, "organizationId", name, code, color, icon, "displayOrder", "isDefault", "updatedAt")
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())""")
                    .params(uuid(), orgId, st.name(), st.code(), st.color(), st.icon(), st.displayOrder(), st.isDefault())
                    .update();
        }

        for (Staff s : List.of(
                new Staff("sarah@demo.test", "Sarah", "Cashier", cashierRole, "Demo@123", "1111"),
                new Staff("john@demo.test", "John", "Waiter", waiterRole, "Demo@123", "2222"),
                new Staff("mary@demo.test", "Mary", "Supervisor", supervisorRole, "Demo@123", "3333"),
                new Staff("chef@demo.test", "Chef", "Kitchen", kitchenRole, "Demo@123", "4444"),
                new Staff("manager@demo.test", "Mike", "Manager", managerRole, "Demo@123", "5555"))) {
            String id = user(orgId, s.email(), passwords.hash(s.password()), s.firstName(), s.lastName(),
                    passwords.hash(s.pin()), branchId);
            link(s.roleName(), id);
        }

        for (Table t : List.of(
                new Table("Table 1", 1, 2, "indoor", 40, 40),
                new Table("Table 2", 2, 2, "indoor", 180, 40),
                new Table("Table 3", 3, 4, "indoor", 40, 180),
                new Table("Table 4", 4, 4, "indoor", 180, 180),
                new Table("Table 5", 5, 6, "outdoor", 40, 320),
                new Table("Table 6", 6, 6, "outdoor", 180, 320),
                new Table("Bar Counter", 7, 3, "bar", 320, 40))) {
            jdbc.sql("""
                            INSERT INTO "PosTable" (id, "organizationId", name, number, seats, zone, active, status, shape,
                              "posX", "posY", width, height, "updatedAt")
                            VALUES (?, ?, ?, ?, ?, ?, true, 'available', 'square', ?, ?, 120, 120, now())""")
                    .params(uuid(), orgId, t.name(), t.number(), t.seats(), t.zone(), t.posX(), t.posY())
                    .update();
        }
        log.info("Demo staff (Sarah cashier 1111, John waiter 2222) and 7 tables seeded. Admin PIN: 1234");
        for (SeedContributor c : contributors) {
            c.seed(orgId);
        }
    }

    private void currency(String code, String symbol, String name, int decimals) {
        jdbc.sql("""
                        INSERT INTO "Currency" (id, code, symbol, name, "decimalPlaces", "updatedAt") VALUES (?, ?, ?, ?, ?, now())
                        ON CONFLICT (code) DO UPDATE SET symbol = EXCLUDED.symbol, name = EXCLUDED.name,
                          "decimalPlaces" = EXCLUDED."decimalPlaces", "updatedAt" = now()""")
                .params(uuid(), code, symbol, name, decimals)
                .update();
    }

    private String role(String orgId, String name, String description, List<String> permissions) {
        String id = uuid();
        jdbc.sql("""
                        INSERT INTO "Role" (id, "organizationId", name, description, "isSystem", permissions, "updatedAt")
                        VALUES (?, ?, ?, ?, true, ?, now())""")
                .params(id, orgId, name, description, permissions.toArray(String[]::new))
                .update();
        return id;
    }

    private String user(String orgId, String email, String passwordHash, String firstName, String lastName, String pinHash,
            String branchId) {
        String id = uuid();
        jdbc.sql("""
                        INSERT INTO "User" (id, "organizationId", email, "passwordHash", "firstName", "lastName", "pinHash",
                          "pinHashRounds", "defaultBranchId", "updatedAt")
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())""")
                .params(id, orgId, email, passwordHash, firstName, lastName, pinHash, pinHash == null ? null : 10, branchId)
                .update();
        return id;
    }

    private void link(String roleId, String userId) {
        jdbc.sql("INSERT INTO \"_UserRoles\" (\"A\", \"B\") VALUES (?, ?)").params(roleId, userId).update();
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
