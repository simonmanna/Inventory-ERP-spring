package com.inventoryerp.core.application;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Default UoM categories + units for a new organization (port of {@code uom-seed.ts}). {@code factor} =
 * reference units per 1 of this unit; {@code rounding} = quantity rounding step. Idempotent: re-running
 * updates existing units and links each category's reference unit.
 */
public final class UomSeed {

    private UomSeed() {}

    record Unit(String code, String name, String symbol, String factor, String rounding, String uomType) {}

    record Category(String name, String ref, List<Unit> units) {}

    static final List<Category> SEED = List.of(
            new Category("Quantity", "UNIT", List.of(
                    new Unit("UNIT", "Piece", "pc", "1", "1", "reference"),
                    new Unit("DOZEN", "Dozen", "dz", "12", "1", "bigger"))),
            new Category("Weight", "KG", List.of(
                    new Unit("KG", "Kilogram", "kg", "1", "0.001", "reference"),
                    new Unit("G", "Gram", "g", "0.001", "0.001", "smaller"),
                    new Unit("TON", "Tonne", "t", "1000", "0.001", "bigger"))),
            new Category("Volume", "L", List.of(
                    new Unit("L", "Liter", "L", "1", "0.001", "reference"),
                    new Unit("ML", "Milliliter", "ml", "0.001", "0.01", "smaller"))),
            new Category("Length", "M", List.of(
                    new Unit("M", "Meter", "m", "1", "0.001", "reference"),
                    new Unit("CM", "Centimeter", "cm", "0.01", "0.01", "smaller"),
                    new Unit("MM", "Millimeter", "mm", "0.001", "0.01", "smaller"))),
            new Category("Time", "HR", List.of(
                    new Unit("HR", "Hour", "h", "1", "0.01", "reference"),
                    new Unit("DAY", "Day", "d", "24", "0.01", "bigger"))));

    /** Upsert the categories + units for {@code organizationId}. */
    public static void seedUomCategories(JdbcClient jdbc, String organizationId) {
        for (Category cat : SEED) {
            jdbc.sql("INSERT INTO \"UomCategory\" (id, \"organizationId\", name, \"updatedAt\")"
                            + " VALUES (gen_random_uuid()::text, ?, ?, now()) ON CONFLICT (\"organizationId\", name) DO NOTHING")
                    .params(organizationId, cat.name()).update();
            String categoryId = jdbc.sql("SELECT id FROM \"UomCategory\" WHERE \"organizationId\" = ? AND name = ?")
                    .params(organizationId, cat.name()).query(String.class).single();
            String refUomId = null;
            for (Unit u : cat.units()) {
                String id = jdbc.sql("INSERT INTO \"UnitOfMeasure\" (id, \"organizationId\", code, name, symbol,"
                                + " \"categoryId\", category, factor, ratio, \"roundingPrecision\", \"uomType\", \"isBase\","
                                + " \"isActive\", \"updatedAt\")"
                                + " VALUES (gen_random_uuid()::text, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, now())"
                                + " ON CONFLICT (\"organizationId\", code) DO UPDATE SET name = EXCLUDED.name,"
                                + " symbol = EXCLUDED.symbol, \"categoryId\" = EXCLUDED.\"categoryId\","
                                + " category = EXCLUDED.category, factor = EXCLUDED.factor, ratio = EXCLUDED.ratio,"
                                + " \"roundingPrecision\" = EXCLUDED.\"roundingPrecision\", \"uomType\" = EXCLUDED.\"uomType\","
                                + " \"isBase\" = EXCLUDED.\"isBase\", \"isActive\" = true, \"updatedAt\" = now()"
                                + " RETURNING id")
                        .params(organizationId, u.code(), u.name(), u.symbol(), categoryId, cat.name().toLowerCase(),
                                new BigDecimal(u.factor()), new BigDecimal(u.factor()), new BigDecimal(u.rounding()),
                                u.uomType(), u.code().equals(cat.ref()))
                        .query(String.class).single();
                if (u.code().equals(cat.ref())) {
                    refUomId = id;
                }
            }
            if (refUomId != null) {
                jdbc.sql("UPDATE \"UomCategory\" SET \"referenceUomId\" = ?, \"updatedAt\" = now() WHERE id = ?")
                        .params(refUomId, categoryId).update();
            }
        }
    }
}
