package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.seed.SeedContributor;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * POS part of the demo seed ({@code seed.ts}): "Sunrise Cafe" product catalog with opening stock and the
 * {@code MAIN-REG} cash register ({@code seedSunriseCafe}), the 79-item demo menu with its products, recipes and
 * coffee size variants ({@code seedDemoMenu}), and the opening-inventory journal ({@code postSeedOpeningInventory}).
 * Products are written like Prisma's {@code create} branch of the upserts (a fresh org).
 */
@Component
public class PosSeedContributor implements SeedContributor {

    /** code | sku | name | price | cost | category | stockable | stock | minStock. */
    private static final List<String> PRODUCTS = List.of(
            "P-COFFEE-S|COF-S|Small Espresso|5000|1500|Drinks|0|0|0",
            "P-COFFEE-M|COF-M|Medium Cappuccino|8000|2500|Drinks|0|0|0",
            "P-LATTE-L|LAT-L|Large Café Latte|12000|3500|Drinks|0|0|0",
            "P-MOCHA|MOCH|Caramel Mocha|14000|4000|Drinks|0|0|0",
            "P-HOT-CHOC|HOT-CHOC|Hot Chocolate|7000|2200|Drinks|0|0|0",
            "P-ICED-LATTE|ICED-LAT|Iced Latte|13000|3800|Drinks|0|0|0",
            "P-FLAT-WHITE|FLAT-W|Flat White|9500|2800|Drinks|0|0|0",
            "P-AMERICANO|AMER-M|Medium Americano|7000|2000|Drinks|0|0|0",
            "P-TEA|TEA|English Breakfast Tea|6000|1000|Tea & Other Drinks|0|0|0",
            "P-GREEN-TEA|GRN-TEA|Green Tea|7000|1200|Tea & Other Drinks|0|0|0",
            "P-GINGER-TEA|GING-TEA|Ginger Tea|8000|1500|Tea & Other Drinks|0|0|0",
            "P-ICED-LEMON|ICED-LMN|Iced Lemon Tea|9000|2000|Tea & Other Drinks|0|0|0",
            "P-SMOOTHIE|SMTH|Mango Smoothie|15000|5000|Tea & Other Drinks|1|30|10",
            "P-ORANGE-J|ORG-J|Fresh Orange Juice|8000|3000|Tea & Other Drinks|1|20|5",
            "P-SAND-CLUB|SND-CLUB|Club Sandwich|18000|6000|Mains|1|20|5",
            "P-BURGER|BRG|Beef Burger + Chips|22000|8000|Mains|1|15|5",
            "P-PIZZA-M|PZA-M|Margherita Pizza (M)|25000|9000|Mains|1|12|4",
            "P-FISH-CHIPS|FISH-CH|Fish & Chips|20000|7000|Mains|1|10|3",
            "P-WINGS|WING-10|Chicken Wings (10pc)|18000|6500|Mains|1|10|3",
            "P-CAESAR|CAESAR|Caesar Salad|16000|5000|Mains|1|15|4",
            "P-WRAP|WRAP-CH|Chicken Wrap|14000|4500|Mains|1|12|3",
            "P-CROISSANT|CRS|Butter Croissant|6000|2000|Pastries|1|24|6",
            "P-CINNABON|CNN|Cinnamon Roll|8000|2500|Pastries|1|18|5",
            "P-APPLE-PIE|APL-PIE|Apple Pie Slice|9000|3000|Pastries|1|10|3",
            "P-MUFFIN|MUF-BL|Blueberry Muffin|7000|2200|Pastries|1|16|4",
            "P-CAKE|CAKE|Chocolate Cake Slice|12000|4000|Desserts|1|16|4",
            "P-CHEESECAKE|CHS-CAKE|Vanilla Cheesecake|14000|5000|Desserts|1|10|3",
            "P-TIRAMISU|TIRA|Tiramisu|15000|5500|Desserts|1|8|2",
            "P-ICED-TEA|ICED-TEA|Iced Tea|5000|800|Drinks|1|50|10",
            "P-SODA|SODA|Soda / Soft Drink|3000|600|Drinks|1|60|15",
            "P-WATER|WATER|Mineral Water|2000|400|Drinks|1|80|20",
            "P-PASTA-BOLO|PST-BOLO|Spaghetti Bolognese|18000|7000|Mains|1|10|3",
            "P-RICE-CHICKEN|RICE-CHK|Rice & Chicken|20000|8000|Mains|1|10|3",
            "P-OMELETTE|OMEL-GRN|Cheese Omelette|12000|4500|Mains|1|10|3",
            "P-DONUT|DONUT-GL|Glazed Donut|4000|1500|Pastries|1|20|5",
            "P-SCONE|SCONE-JM|Scone with Jam|5000|1800|Pastries|1|15|4",
            "P-PUDDING|PUDD-BAN|Banana Pudding|10000|3500|Desserts|1|10|3",
            "P-GELATO|GELATO|Gelato (2 scoops)|8000|2500|Desserts|1|15|4",
            "P-BFST-BREA|BFST-BRD|Toast with Butter|3000|800|Breakfast|1|30|8",
            "P-BFST-EGGS|BFST-EGG|Fried Eggs (2pc)|5000|2000|Breakfast|1|20|5",
            "P-BFST-PORR|BFST-POR|Porridge with Honey|4000|1200|Breakfast|1|15|4",
            "P-BFST-CROISSANT|BFST-CRS|Croissant Sandwich|8000|3000|Breakfast|1|15|4",
            "P-BFST-AVOCADO|BFST-AVO|Avocado Toast|12000|4500|Breakfast|1|10|3",
            "P-BFST-PANCAKE|BFST-PNC|Pancakes (3pc + Syrup)|10000|3500|Breakfast|1|12|3",
            "P-BFST-SET|BFST-SET|Full Breakfast Set|18000|7000|Breakfast|1|8|2",
            "P-SIDE-FRIES|SIDE-FRI|French Fries|6000|2000|Sides & Snacks|1|30|8",
            "P-SIDE-NACHOS|SIDE-NAC|Nachos with Cheese|9000|3500|Sides & Snacks|1|10|3",
            "P-SIDE-ONION|SIDE-ONI|Onion Rings|7000|2500|Sides & Snacks|1|15|4",
            "P-SIDE-WINGS|SIDE-WNG|BBQ Wings (6pc)|12000|5000|Sides & Snacks|1|10|3",
            "P-SIDE-BBS|SIDE-BBS|Wings Combo Bucket|22000|9000|Sides & Snacks|1|8|2",
            "P-SHake-VAN|SHK-VAN|Vanilla Milkshake|12000|4000|Milkshakes|1|15|4",
            "P-SHake-CHOC|SHK-CHOC|Chocolate Milkshake|12000|4000|Milkshakes|1|15|4",
            "P-SHake-STRAW|SHK-STR|Strawberry Milkshake|12000|4000|Milkshakes|1|15|4");

    private static final List<String> PRODUCT_CATEGORIES = List.of("Drinks", "Mains", "Desserts", "Pastries",
            "Breakfast", "Sides & Snacks", "Milkshakes");

    private static final List<String> MENU_CATEGORIES = List.of("PASTRIES", "CAKES", "COOKIES",
            "FULL (OCCASION) CAKES", "BREAKFAST & SNACKS", "COFFEE", "TEA", "COLD DRINKS");

    /** code | name | category | price | variants ("S:4000,M:5000"). */
    private static final List<String> MENU = menu();

    private static List<String> menu() {
        List<String> out = new java.util.ArrayList<>();
        String[] pastries = {"Boxenia", "Mini-Boxenia", "Eclair", "Mini-Eclair", "Bigne", "Mini-Bigne", "Milefolli",
            "Tarts", "Mini Tarts", "Croissant", "Mi-Croissant", "Brioch"};
        int[] pastryPrices = {5000, 3000, 7000, 4000, 6000, 4000, 8000, 6000, 4000, 7000, 5000, 7000};
        for (int i = 0; i < pastries.length; i++) {
            out.add(String.format("PRD-PAST-%03d|%s|PASTRIES|%d|", i + 1, pastries[i], pastryPrices[i]));
        }
        String[] cakes = {"Panna", "Tiramisu", "Vanilla", "Caramel", "Chees Cake", "Carrot Cake", "Browni", "Crostata",
            "Crosta-ricotta", "Crosta-Choco", "Choco-Cake", "Choco-Fudge"};
        int[] cakePrices = {8000, 10000, 9000, 10000, 14000, 10000, 7000, 8000, 10000, 10000, 10000, 10000};
        for (int i = 0; i < cakes.length; i++) {
            out.add(String.format("PRD-CAKE-%03d|%s|CAKES|%d|", i + 1, cakes[i], cakePrices[i]));
        }
        out.addAll(List.of(
                "PRD-COOK-001|Biscoti 1/2 kg|COOKIES|25000|",
                "PRD-COOK-002|Cookies 1/2 kg|COOKIES|30000|",
                "PRD-FULL-001|Pound Cake|FULL (OCCASION) CAKES|30000|",
                "PRD-FULL-002|Pound Cake-P|FULL (OCCASION) CAKES|5000|",
                "PRD-BFST-001|Donuts plain|BREAKFAST & SNACKS|4000|",
                "PRD-BFST-002|Donuts|BREAKFAST & SNACKS|5000|",
                "PRD-BFST-003|Mi-Donut pl|BREAKFAST & SNACKS|2000|",
                "PRD-BFST-004|Mini-Donuts|BREAKFAST & SNACKS|3000|",
                "PRD-BFST-005|Mini-Donut 1/2 kg|BREAKFAST & SNACKS|25000|",
                "PRD-BFST-006|Cupcake|BREAKFAST & SNACKS|5000|",
                "PRD-BFST-007|Mi-Cupcake|BREAKFAST & SNACKS|4000|",
                "PRD-BFST-008|Span. Omelette|BREAKFAST & SNACKS|12000|",
                "PRD-BFST-009|Omelette C&H|BREAKFAST & SNACKS|18000|",
                "PRD-BFST-010|Frittata|BREAKFAST & SNACKS|12000|",
                "PRD-BFST-011|Fritta Special|BREAKFAST & SNACKS|16000|",
                "PRD-BFST-012|Croissant Sand.|BREAKFAST & SNACKS|18000|",
                "PRD-BFST-013|Kitcha Ftf Flour|BREAKFAST & SNACKS|16000|",
                "PRD-BFST-014|Kitcha Ftf Taff|BREAKFAST & SNACKS|18000|",
                "PRD-BFST-015|Kitcha Ftf Sgem|BREAKFAST & SNACKS|18000|",
                "PRD-BFST-016|Geat|BREAKFAST & SNACKS|15000|",
                "PRD-BFST-017|Full medemes|BREAKFAST & SNACKS|13000|",
                "PRD-BFST-018|Full special|BREAKFAST & SNACKS|15000|",
                "PRD-BFST-019|Pizza S Margerita|BREAKFAST & SNACKS|12000|",
                "PRD-BFST-020|Pizza S Chiken|BREAKFAST & SNACKS|18000|",
                "PRD-BFST-021|Pizza S Africana|BREAKFAST & SNACKS|18000|",
                "PRD-BFST-022|Pizza M Margerita|BREAKFAST & SNACKS|25000|",
                "PRD-BFST-023|Pizza M Chiken|BREAKFAST & SNACKS|28000|",
                "PRD-BFST-024|Pizza M Africana|BREAKFAST & SNACKS|28000|",
                "PRD-BFST-025|Pizza L Margerita|BREAKFAST & SNACKS|30000|",
                "PRD-BFST-026|Pizza L Chiken|BREAKFAST & SNACKS|32000|",
                "PRD-BFST-027|Pizza L Africana|BREAKFAST & SNACKS|32000|",
                "PRD-BFST-028|Mini-Pizza|BREAKFAST & SNACKS|2000|",
                "PRD-BFST-029|Mini-Pizza 1/2 kg|BREAKFAST & SNACKS|25000|",
                "PRD-COF-001|Espresso|COFFEE|4000|S:4000,M:5000",
                "PRD-COF-002|Cappuccino|COFFEE|7000|S:7000,M:9000",
                "PRD-COF-003|Macchiato|COFFEE|5000|S:5000,M:7000",
                "PRD-COF-004|Café Mocha|COFFEE|9000|S:9000,M:12000",
                "PRD-COF-005|Café Americano|COFFEE|5000|S:5000,M:7000",
                "PRD-COF-006|Café Late|COFFEE|7000|S:7000,M:9000",
                "PRD-COF-007|Hot Chocolate|COFFEE|10000|",
                "PRD-TEA-001|Balk Tea|TEA|3000|",
                "PRD-TEA-002|African Tea|TEA|5000|",
                "PRD-TEA-003|Green Tea|TEA|5000|",
                "PRD-TEA-004|Herbal Tea|TEA|5000|",
                "PRD-TEA-005|Dawa Tea|TEA|6000|",
                "PRD-COLD-001|Water/Rwenzori|COLD DRINKS|3000|",
                "PRD-COLD-002|Soda Water|COLD DRINKS|3000|",
                "PRD-COLD-003|Soda Glass|COLD DRINKS|3000|"));
        return List.copyOf(out);
    }

    private final JdbcClient jdbc;

    public PosSeedContributor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int order() {
        return 400;
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private String one(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(String.class).optional().orElse(null);
    }

    @Override
    public void seed(String org) {
        String warehouse = one("SELECT id FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = 'MAIN'", org);
        String taxId = one("SELECT id FROM \"Tax\" WHERE \"organizationId\" = ? AND code = 'NOTAX' LIMIT 1", org);
        String cash = one("SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = '1100' LIMIT 1", org);
        String unit = one("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND code = 'UNIT' LIMIT 1", org);
        if (warehouse == null || cash == null) {
            return;
        }
        seedSunriseCafe(org, warehouse, taxId, cash);
        seedDemoMenu(org, taxId, unit);
        postOpeningInventory(org);
    }

    private void seedSunriseCafe(String org, String warehouse, String taxId, String cash) {
        Map<String, String> categories = new LinkedHashMap<>();
        for (String name : PRODUCT_CATEGORIES) {
            jdbc.sql("INSERT INTO \"ProductCategory\" (id, \"organizationId\", name, \"updatedAt\") VALUES (?, ?, ?, now())"
                    + " ON CONFLICT (\"organizationId\", name) DO NOTHING").params(id(), org, name).update();
            categories.put(name, one("SELECT id FROM \"ProductCategory\" WHERE \"organizationId\" = ? AND name = ?",
                    org, name));
        }
        for (String def : PRODUCTS) {
            String[] p = def.split("\\|");
            boolean stockable = "1".equals(p[6]);
            String productId = one("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? AND code = ?", org, p[0]);
            if (productId == null) {
                productId = id();
                jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, sku, name, \"salesPrice\", \"costPrice\","
                        + " \"productType\", \"trackInventory\", \"minQuantity\", \"categoryId\", \"taxId\", \"isActive\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, now())")
                        .params(productId, org, p[0], p[1], p[2], new BigDecimal(p[3]), new BigDecimal(p[4]),
                                stockable ? "stockable" : "service", stockable, new BigDecimal(p[8]), categories.get(p[5]),
                                taxId).update();
            }
            int stock = Integer.parseInt(p[7]);
            if (stockable && stock > 0) {
                long cell = jdbc.sql("SELECT count(*) FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                        + " AND \"variantKey\" = '' AND \"locationId\" = ?").params(org, productId, warehouse)
                        .query(Long.class).single();
                if (cell == 0) {
                    BigDecimal cost = new BigDecimal(p[4]);
                    jdbc.sql("INSERT INTO \"StockItem\" (id, \"organizationId\", \"productId\", \"variantKey\", \"locationId\","
                            + " quantity, \"runningAverageCost\", \"updatedAt\") VALUES (?, ?, ?, '', ?, ?, ?, now())")
                            .params(id(), org, productId, warehouse, stock, cost).update();
                    jdbc.sql("INSERT INTO \"InventoryLedger\" (id, \"organizationId\", \"ledgerCode\", \"productId\","
                            + " \"locationId\", type, \"qtyBefore\", \"quantityChange\", \"balanceAfter\", \"unitCost\","
                            + " \"totalValue\", \"referenceType\", \"referenceId\", notes) VALUES (?, ?, 'STK/OPENING', ?, ?,"
                            + " 'opening_balance', 0, ?, ?, ?, ?, 'opening_balance', 'seed', 'Seeded opening stock')")
                            .params(id(), org, productId, warehouse, stock, stock, cost,
                                    cost.multiply(BigDecimal.valueOf(stock))).update();
                }
            }
        }
        jdbc.sql("INSERT INTO \"CashRegister\" (id, \"organizationId\", code, name, \"isActive\", \"defaultAccountId\","
                + " \"updatedAt\") VALUES (?, ?, 'MAIN-REG', 'Main Counter Register', true, ?, now())"
                + " ON CONFLICT (\"organizationId\", code) DO UPDATE SET name = EXCLUDED.name, \"isActive\" = true,"
                + " \"defaultAccountId\" = EXCLUDED.\"defaultAccountId\", \"updatedAt\" = now()")
                .params(id(), org, cash).update();
    }

    private void seedDemoMenu(String org, String taxId, String unit) {
        Map<String, String> cats = new LinkedHashMap<>();
        for (int i = 0; i < MENU_CATEGORIES.size(); i++) {
            String cid = id();
            jdbc.sql("INSERT INTO \"MenuCategory\" (id, \"organizationId\", name, \"displayOrder\", \"updatedAt\")"
                    + " VALUES (?, ?, ?, ?, now())").params(cid, org, MENU_CATEGORIES.get(i), i + 1).update();
            cats.put(MENU_CATEGORIES.get(i), cid);
        }
        for (int i = 0; i < MENU.size(); i++) {
            String[] it = MENU.get(i).split("\\|", -1);
            BigDecimal price = new BigDecimal(it[3]);
            String productId = one("SELECT id FROM \"Product\" WHERE \"organizationId\" = ? AND code = ?", org, it[0]);
            if (productId == null) {
                productId = id();
                jdbc.sql("INSERT INTO \"Product\" (id, \"organizationId\", code, sku, name, \"salesPrice\", \"costPrice\","
                        + " \"productType\", \"trackInventory\", \"minQuantity\", \"uomId\", \"taxId\", \"isActive\","
                        + " \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, 0, 'service', false, 0, ?, ?, true, now())")
                        .params(productId, org, it[0], it[0], it[1], price, unit, taxId).update();
            }
            String menuItemId = id();
            jdbc.sql("INSERT INTO \"MenuItem\" (id, \"organizationId\", code, name, \"basePrice\", \"categoryId\","
                    + " \"isAvailable\", \"displayOrder\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, true, ?, now())")
                    .params(menuItemId, org, it[0], it[1], price, cats.get(it[2]), i).update();
            jdbc.sql("INSERT INTO \"MenuProduct\" (id, \"organizationId\", \"menuItemId\", \"productId\", quantity)"
                    + " VALUES (?, ?, ?, ?, 1)").params(id(), org, menuItemId, productId).update();
            if (!it[4].isEmpty()) {
                for (String v : it[4].split(",")) {
                    String[] nv = v.split(":");
                    jdbc.sql("INSERT INTO \"MenuItemVariant\" (id, \"organizationId\", \"menuItemId\", name, price,"
                            + " \"sortOrder\", \"isActive\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, true, now())")
                            .params(id(), org, menuItemId, nv[0], new BigDecimal(nv[1]), "S".equals(nv[0]) ? 0 : 1).update();
                }
            }
        }
    }

    /** Dr Stock Valuation / Cr Retained Earnings for the seeded opening stock (once per org). */
    private void postOpeningInventory(String org) {
        String postingKey = "seed_opening_inventory:" + org;
        if (one("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = ? AND \"postingKey\" = ? LIMIT 1", org,
                postingKey) != null) {
            return;
        }
        String stockAccount = mapped(org, "stock_valuation");
        String equityAccount = mapped(org, "retained_earnings");
        String journal = one("SELECT id FROM \"Journal\" WHERE \"organizationId\" = ? AND code = 'INV' LIMIT 1", org);
        if (journal == null) {
            journal = one("SELECT id FROM \"Journal\" WHERE \"organizationId\" = ? AND code = 'GEN' LIMIT 1", org);
        }
        if (stockAccount == null || equityAccount == null || journal == null) {
            return;
        }
        BigDecimal value = jdbc.sql("SELECT coalesce(sum(\"totalValue\"), 0) FROM \"InventoryLedger\" WHERE"
                + " \"organizationId\" = ? AND \"referenceType\" = 'opening_balance'").param(org)
                .query(BigDecimal.class).single();
        if (value.signum() <= 0) {
            return;
        }
        String entry = id();
        jdbc.sql("INSERT INTO \"JournalEntry\" (id, \"organizationId\", \"journalId\", \"entryNumber\", \"postingDate\", status,"
                + " \"postedAt\", description, \"sourceType\", \"sourceId\", \"postingKey\", \"updatedAt\") VALUES (?, ?, ?,"
                + " 'SEED-OPEN-INV', now(), 'posted', now(), 'Opening inventory (seeded stock)', 'opening_balance', 'seed',"
                + " ?, now())").params(entry, org, journal, postingKey).update();
        jdbc.sql("INSERT INTO \"JournalLine\" (id, \"organizationId\", \"journalEntryId\", \"accountId\", debit, \"baseDebit\","
                + " description, \"lineNumber\") VALUES (?, ?, ?, ?, ?, ?, 'Opening inventory', 1)")
                .params(id(), org, entry, stockAccount, value, value).update();
        jdbc.sql("INSERT INTO \"JournalLine\" (id, \"organizationId\", \"journalEntryId\", \"accountId\", credit, \"baseCredit\","
                + " description, \"lineNumber\") VALUES (?, ?, ?, ?, ?, ?, 'Opening inventory', 2)")
                .params(id(), org, entry, equityAccount, value, value).update();
    }

    private String mapped(String org, String key) {
        return one("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ? LIMIT 1", org, key);
    }
}
