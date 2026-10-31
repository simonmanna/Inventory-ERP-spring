package com.inventoryerp.fleet.application;

import com.inventoryerp.core.application.UomConversionService;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Weight / volume per product unit for load planning (fleet 1.3) until Phase 4 brings full UoM densities. Per product
 * base unit, first match wins:
 * <ol>
 *   <li>the fleet factor {@code FleetProductLoadFactor} (tonnes / m³ per base unit, set in Dispatch settings);</li>
 *   <li>the product master: {@code Product.weight} in kg and {@code Product.volume} in m³ per base unit (Odoo
 *       convention);</li>
 *   <li>the base unit itself: a tonne unit (t, ton, tonne, mt) weighs 1 t, a kg unit 0.001 t, a cubic-metre unit
 *       (m3, m³, cbm) is 1 m³;</li>
 *   <li>the product's density and unit profile (UOM-1): whatever is still unknown, through
 *       {@link UomConversionService#conversionRate}.</li>
 * </ol>
 * A line in another unit multiplies by its snapshot {@code uomFactor} (base units per line unit).
 */
@Component
public class FleetLoadFactors {

    private static final Set<String> TONNE = Set.of("t", "ton", "tons", "tonne", "tonnes", "mt");
    private static final Set<String> KG = Set.of("kg", "kgs", "kilogram", "kilograms");
    private static final Set<String> CUBIC = Set.of("m3", "m³", "cbm", "cubic metre", "cubic meter", "cu m");

    private final FleetDb db;
    private final UomConversionService uoms;

    public FleetLoadFactors(FleetDb db, UomConversionService uoms) {
        this.db = db;
        this.uoms = uoms;
    }

    /** Tonnes / m³ per product base unit (null = unknown) and where they came from. */
    public record Factor(BigDecimal tonnesPerUnit, BigDecimal m3PerUnit, String source) {

        public Factor perLineUnit(BigDecimal uomFactor) {
            BigDecimal f = uomFactor == null || uomFactor.signum() <= 0 ? BigDecimal.ONE : uomFactor;
            return new Factor(tonnesPerUnit == null ? null : tonnesPerUnit.multiply(f).setScale(6, RoundingMode.HALF_UP),
                    m3PerUnit == null ? null : m3PerUnit.multiply(f).setScale(6, RoundingMode.HALF_UP), source);
        }
    }

    public Factor forProduct(String productId) {
        String org = FleetDb.org();
        Map<String, Object> f = SqlRows.one(db.jdbc().sql("SELECT \"tonnesPerUnit\", \"cubicMetresPerUnit\" FROM"
                + " \"FleetProductLoadFactor\" WHERE \"organizationId\" = ? AND \"productId\" = ?").params(org, productId));
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT p.\"uomId\", p.weight, p.volume, u.code AS \"uomCode\","
                + " u.symbol AS \"uomSymbol\" FROM \"Product\" p LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE p.id = ?"
                + " AND p.\"organizationId\" = ?").params(productId, org));
        BigDecimal t = f == null ? null : (BigDecimal) f.get("tonnesPerUnit");
        BigDecimal m = f == null ? null : (BigDecimal) f.get("cubicMetresPerUnit");
        String source = f != null && (t != null || m != null) ? "fleet_factor" : null;
        if (p != null) {
            if (t == null && p.get("weight") != null && ((BigDecimal) p.get("weight")).signum() > 0) {
                t = ((BigDecimal) p.get("weight")).divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
                source = source == null ? "product" : source;
            }
            if (m == null && p.get("volume") != null && ((BigDecimal) p.get("volume")).signum() > 0) {
                m = (BigDecimal) p.get("volume");
                source = source == null ? "product" : source;
            }
            String code = unit(p);
            if (t == null && TONNE.contains(code)) {
                t = BigDecimal.ONE;
                source = source == null ? "unit" : source;
            } else if (t == null && KG.contains(code)) {
                t = new BigDecimal("0.001");
                source = source == null ? "unit" : source;
            } else if (m == null && CUBIC.contains(code)) {
                m = BigDecimal.ONE;
                source = source == null ? "unit" : source;
            }
            String baseId = (String) p.get("uomId");
            if (t == null) {
                t = perBase(productId, baseId, "TON");
                source = source == null && t != null ? "density" : source;
            }
            if (m == null) {
                m = perBase(productId, baseId, "M3");
                source = source == null && m != null ? "density" : source;
            }
        }
        return new Factor(t, m, source);
    }

    /** Units with this code per 1 base unit, from the product's density and unit profile; null when unknown. */
    private BigDecimal perBase(String productId, String baseUomId, String code) {
        if (baseUomId == null) {
            return null;
        }
        String target = db.jdbc().sql("SELECT id FROM \"UnitOfMeasure\" WHERE \"organizationId\" = ? AND upper(code) = ?"
                + " AND \"deletedAt\" IS NULL ORDER BY \"isActive\" DESC LIMIT 1").params(FleetDb.org(), code)
                .query(String.class).optional().orElse(null);
        BigDecimal rate = target == null ? null : uoms.conversionRate(productId, baseUomId, target);
        return rate == null ? null : rate.setScale(6, RoundingMode.HALF_UP);
    }

    /** Smallest quantity step of a unit: whole units when its rounding precision is ≥ 1 (pieces, bags), else 0.001. */
    public BigDecimal step(String uomId) {
        if (uomId == null) {
            return LoadPlanner.MIN_STEP;
        }
        BigDecimal r = db.jdbc().sql("SELECT \"roundingPrecision\" FROM \"UnitOfMeasure\" WHERE id = ?").param(uomId)
                .query(BigDecimal.class).optional().orElse(null);
        return r != null && r.compareTo(BigDecimal.ONE) >= 0 ? BigDecimal.ONE : LoadPlanner.MIN_STEP;
    }

    /** Is {@code unit} (a code, symbol or the word) a tonne / kg unit? Returns tonnes per unit or null. */
    static BigDecimal tonnesPerWeighUnit(String unit) {
        String u = unit == null ? "" : unit.trim().toLowerCase();
        if (TONNE.contains(u)) {
            return BigDecimal.ONE;
        }
        if (KG.contains(u)) {
            return new BigDecimal("0.001");
        }
        return null;
    }

    private static String unit(Map<String, Object> p) {
        Object c = p.get("uomCode") != null ? p.get("uomCode") : p.get("uomSymbol");
        String s = c == null ? "" : c.toString().trim().toLowerCase();
        if (!TONNE.contains(s) && !KG.contains(s) && !CUBIC.contains(s) && p.get("uomSymbol") != null) {
            s = p.get("uomSymbol").toString().trim().toLowerCase();
        }
        return s;
    }
}
