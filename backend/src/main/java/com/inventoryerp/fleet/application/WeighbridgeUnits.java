package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Weight ↔ document-unit conversion of the weighbridge (fleet 1.4). It reuses the product unit data of ADR-S003 and
 * adds one weighbridge fallback; Phase 4 builds the full UoM engine. Kilograms per 1 document unit {@code U} of a
 * product, first match wins:
 *
 * <ol>
 *   <li>{@code U} is a weight unit (the KG unit's category): its factor relative to KG (t = 1000);
 *   <li>a product conversion ({@code ProductUomConversion}, either direction) between {@code U} and a weight unit —
 *       the density bridge of ADR-S003 (e.g. 1 m³ = 1.6 t);
 *   <li>{@code U} expressed in the product's base unit (base itself, a {@code ProductUom} factor, or the same unit
 *       category) when the base unit is a weight unit (bag of 50 kg);
 *   <li>the same, times the product's weighbridge setting {@code kgPerUnit} (kg per 1 base unit: density for m³
 *       products, bag weight for bags).
 * </ol>
 *
 * Plain SQL reads only, so a failed lookup never marks the caller's transaction rollback-only.
 */
@Component
public class WeighbridgeUnits {

    static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private final FleetDb db;

    public WeighbridgeUnits(FleetDb db) {
        this.db = db;
    }

    /** Kilograms per 1 {@code uomId} of the product, or {@code null} when there is no weight conversion. */
    public BigDecimal kgPerUnit(String productId, String uomId) {
        Map<String, Object> kg = kgUnit();
        if (kg == null) {
            throw ApiException.badRequest("Unit KG (kilogram) is missing in Units of Measure");
        }
        Map<String, Object> u = unit(uomId);
        if (u == null) {
            throw ApiException.notFound("Unit of measure not found");
        }
        if (isWeight(u, kg)) {
            return ratio(u, kg);
        }
        if (productId == null) {
            return null;
        }
        for (Map<String, Object> c : SqlRows.list(db.jdbc().sql("SELECT \"fromUomId\", \"toUomId\", factor FROM"
                + " \"ProductUomConversion\" WHERE \"organizationId\" = ? AND \"productId\" = ? AND (\"fromUomId\" = ?"
                + " OR \"toUomId\" = ?)").params(FleetDb.org(), productId, uomId, uomId))) {
            BigDecimal factor = SqlRows.dec(c, "factor");
            if (factor.signum() <= 0) {
                continue;
            }
            boolean from = uomId.equals(c.get("fromUomId"));
            Map<String, Object> w = unit((String) (from ? c.get("toUomId") : c.get("fromUomId")));
            if (w != null && isWeight(w, kg)) {
                // from: 1 U = factor W; to: 1 W = factor U.
                return from ? factor.multiply(ratio(w, kg), MC) : ratio(w, kg).divide(factor, MC);
            }
        }
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT \"uomId\" FROM \"Product\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(productId, FleetDb.org()));
        if (p == null) {
            throw ApiException.notFound("Product not found");
        }
        String baseId = (String) p.get("uomId");
        Map<String, Object> base = baseId == null ? null : unit(baseId);
        BigDecimal toBase = null;
        if (uomId.equals(baseId)) {
            toBase = BigDecimal.ONE;
        } else if (baseId != null) {
            BigDecimal own = db.jdbc().sql("SELECT factor FROM \"ProductUom\" WHERE \"organizationId\" = ? AND"
                    + " \"productId\" = ? AND \"uomId\" = ? AND \"isActive\"").params(FleetDb.org(), productId, uomId)
                    .query(BigDecimal.class).optional().orElse(null);
            if (own != null && own.signum() > 0) {
                toBase = own;
            } else if (base != null && sameCategory(u, base)) {
                toBase = ratio(u, base);
            }
        }
        if (toBase == null) {
            return null;
        }
        if (base != null && isWeight(base, kg)) {
            return toBase.multiply(ratio(base, kg), MC);
        }
        BigDecimal perBase = db.jdbc().sql("SELECT \"kgPerUnit\" FROM \"WeighbridgeProductSetting\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ?").params(FleetDb.org(), productId)
                .query(BigDecimal.class).optional().orElse(null);
        return perBase == null ? null : toBase.multiply(perBase, MC);
    }

    /** Like {@link #kgPerUnit} but 400 when there is no conversion. */
    public BigDecimal requireKgPerUnit(String productId, String uomId) {
        BigDecimal f = kgPerUnit(productId, uomId);
        if (f == null || f.signum() <= 0) {
            String product = productId == null ? null : db.jdbc().sql("SELECT name FROM \"Product\" WHERE id = ? AND"
                    + " \"organizationId\" = ?").params(productId, FleetDb.org()).query(String.class).optional()
                    .orElse(productId);
            Map<String, Object> u = unit(uomId);
            String code = u == null ? uomId : (String) u.get("code");
            throw ApiException.badRequest("No weight conversion for " + (product == null ? "this ticket"
                    : "product " + product) + " in unit " + code + ": add a conversion of the unit to KG or TON, or"
                    + " set the product's kg per unit in the weighbridge product settings");
        }
        return f;
    }

    public static BigDecimal toKg(BigDecimal qty, BigDecimal kgPerUnit) {
        return qty.multiply(kgPerUnit, MC).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal fromKg(BigDecimal kg, BigDecimal kgPerUnit) {
        return kg.divide(kgPerUnit, MC).setScale(4, RoundingMode.HALF_UP);
    }

    Map<String, Object> unit(String id) {
        if (id == null) {
            return null;
        }
        return SqlRows.one(db.jdbc().sql("SELECT id, code, factor, \"categoryId\", category FROM \"UnitOfMeasure\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, FleetDb.org()));
    }

    Map<String, Object> kgUnit() {
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("SELECT id, code, factor, \"categoryId\", category"
                + " FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND upper(code) = 'KG' AND \"deletedAt\" IS"
                + " NULL ORDER BY \"isActive\" DESC LIMIT 1").params(FleetDb.org()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static boolean isWeight(Map<String, Object> u, Map<String, Object> kg) {
        if (u.get("id").equals(kg.get("id"))) {
            return true;
        }
        return sameCategory(u, kg) || "weight".equals(String.valueOf(u.get("category")).toLowerCase(Locale.ROOT))
                && "weight".equals(String.valueOf(kg.get("category")).toLowerCase(Locale.ROOT));
    }

    private static boolean sameCategory(Map<String, Object> a, Map<String, Object> b) {
        if (a.get("categoryId") != null && b.get("categoryId") != null) {
            return Objects.equals(a.get("categoryId"), b.get("categoryId"));
        }
        return a.get("category") != null && Objects.equals(a.get("category"), b.get("category"));
    }

    /** {@code a} expressed in {@code b} (same category): a.factor / b.factor. */
    private static BigDecimal ratio(Map<String, Object> a, Map<String, Object> b) {
        return SqlRows.dec(a, "factor").divide(SqlRows.dec(b, "factor"), MC);
    }
}
