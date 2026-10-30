package com.inventoryerp.manufacturing.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Batch scaling of a block / precast mix design (ADR-S014, Phase 2.4). A mix design makes {@code unitsPerBatch} blocks
 * per mixer batch from its materials per batch; a requirement given as units, batches, cement bags, m³ of concrete,
 * machine (mould) cycles or pallets is turned into a number of batches (whole batches by default: rounded up, except
 * cement bags, which never exceed the bags available), then into units, expected good units, pallets, cycles, volume
 * and the quantity of every material ({@code qtyPerBatch × batches × (1 + scrap %)}, the same rule as the BOM
 * explosion). Pure; quantities at scale 6, {@code HALF_UP}.
 */
public final class BatchScaler {

    static final int SCALE = 6;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    public static final Set<String> BASES = Set.of("units", "batches", "cement_bags", "volume_m3", "cycles",
            "pallets");

    private BatchScaler() {}

    /** One material of the mix per batch. */
    public record Material(String productId, String productName, String uomId, BigDecimal qtyPerBatch,
            BigDecimal scrapPct) {}

    /** The mix design as the scaler needs it. */
    public record Recipe(BigDecimal unitsPerBatch, BigDecimal expectedYieldPct, List<Material> materials,
            String cementProductId, BigDecimal cementPerBag, BigDecimal batchVolumeM3, int unitsPerPallet,
            int unitsPerCycle) {}

    /** Scaled material. {@code bags} only for the cement line. */
    public record MaterialQty(String productId, String productName, String uomId, BigDecimal qtyPerBatch,
            BigDecimal quantity, boolean cement, BigDecimal bags) {}

    /** Scaling result. */
    public record Result(String basis, BigDecimal requested, BigDecimal batches, BigDecimal units,
            BigDecimal expectedGood, long pallets, long cycles, BigDecimal volumeM3, BigDecimal cementQty,
            BigDecimal cementBags, long cementBagsToDraw, List<MaterialQty> materials) {}

    public static Result scale(Recipe r, String basis, BigDecimal value, boolean wholeBatches) {
        if (!BASES.contains(basis)) {
            throw ApiException.badRequest("basis must be one of the following values: units, batches, cement_bags,"
                    + " volume_m3, cycles, pallets");
        }
        if (value == null || value.signum() <= 0) {
            throw ApiException.badRequest("value must be a positive number");
        }
        BigDecimal perBatch = r.unitsPerBatch();
        if (perBatch == null || perBatch.signum() <= 0) {
            throw ApiException.badRequest("The mix design makes no units per batch.");
        }
        BigDecimal yieldPct = r.expectedYieldPct() == null || r.expectedYieldPct().signum() <= 0 ? HUNDRED
                : r.expectedYieldPct();
        BigDecimal cementPerBatch = cementPerBatch(r);
        BigDecimal raw = switch (basis) {
            case "units" -> div(value, perBatch);
            case "batches" -> value;
            case "cement_bags" -> {
                if (cementPerBatch.signum() <= 0) {
                    throw ApiException.badRequest("The mix design has no cement line to scale by bags.");
                }
                yield div(value, div(cementPerBatch, r.cementPerBag()));
            }
            case "volume_m3" -> {
                if (r.batchVolumeM3() == null || r.batchVolumeM3().signum() <= 0) {
                    throw ApiException.badRequest("The mix design has no batch volume (m³) to scale by.");
                }
                yield div(value, r.batchVolumeM3());
            }
            case "cycles" -> div(value.multiply(BigDecimal.valueOf(Math.max(1, r.unitsPerCycle()))), perBatch);
            default -> div(div(value.multiply(BigDecimal.valueOf(Math.max(1, r.unitsPerPallet())))
                    .multiply(HUNDRED), yieldPct), perBatch);
        };
        BigDecimal batches = !wholeBatches ? raw
                : "cement_bags".equals(basis) ? raw.setScale(0, RoundingMode.FLOOR)
                : raw.setScale(0, RoundingMode.CEILING);
        if (batches.signum() <= 0) {
            throw ApiException.badRequest("Not enough for one full batch of this mix design.");
        }
        batches = batches.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal units = q(batches.multiply(perBatch));
        BigDecimal good = q(units.multiply(yieldPct).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        long pallets = ceil(good, r.unitsPerPallet());
        long cycles = ceil(units, r.unitsPerCycle());
        BigDecimal volume = r.batchVolumeM3() == null ? null : q(r.batchVolumeM3().multiply(batches));
        List<MaterialQty> out = new ArrayList<>();
        BigDecimal cementQty = BigDecimal.ZERO;
        for (Material m : r.materials()) {
            BigDecimal qty = q(withScrap(m).multiply(batches));
            boolean cement = m.productId().equals(r.cementProductId());
            BigDecimal bags = null;
            if (cement) {
                cementQty = cementQty.add(qty);
                bags = q(div(qty, r.cementPerBag()));
            }
            out.add(new MaterialQty(m.productId(), m.productName(), m.uomId(), m.qtyPerBatch(), qty, cement, bags));
        }
        BigDecimal bags = r.cementPerBag() == null || r.cementPerBag().signum() <= 0 ? BigDecimal.ZERO
                : q(div(cementQty, r.cementPerBag()));
        return new Result(basis, value, batches, units, good, pallets, cycles, volume, q(cementQty), bags,
                bags.setScale(0, RoundingMode.CEILING).longValueExact(), out);
    }

    /** Cement per batch including its scrap allowance (0 when the recipe has no cement line). */
    static BigDecimal cementPerBatch(Recipe r) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Material m : r.materials()) {
            if (m.productId().equals(r.cementProductId())) {
                sum = sum.add(withScrap(m));
            }
        }
        return sum;
    }

    private static BigDecimal withScrap(Material m) {
        BigDecimal scrap = m.scrapPct() == null ? BigDecimal.ZERO : m.scrapPct();
        return m.qtyPerBatch().multiply(BigDecimal.ONE.add(scrap.divide(HUNDRED, 10, RoundingMode.HALF_UP)));
    }

    /** {@code ceil(qty / per)} for a positive {@code per} (1 when not set). */
    static long ceil(BigDecimal qty, int per) {
        return qty.divide(BigDecimal.valueOf(Math.max(1, per)), 0, RoundingMode.CEILING).longValueExact();
    }

    private static BigDecimal div(BigDecimal a, BigDecimal b) {
        return a.divide(b, 10, RoundingMode.HALF_UP);
    }

    private static BigDecimal q(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
