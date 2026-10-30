package com.inventoryerp.quarry.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Cost build-up and per-tonne allocation of an extraction log (ADR-S012 §4). Pure: the service resolves the rates,
 * this class does the arithmetic ({@link BigDecimal}, amounts at 2 decimals and the unit cost at 6, {@code HALF_UP}).
 *
 * <ul>
 *   <li><b>Equipment</b>: hours × hourly rate, plus fuel litres × fuel cost per litre. <b>Labour</b>: hours × rate.</li>
 *   <li><b>Blasting</b>: the blast's cost pool per estimated tonne × net tonnes, capped at what the pool has left (a
 *       blast is never absorbed twice).</li>
 *   <li><b>Royalties / levies</b>: rate per tonne × the net or gross tonnes (per rate).</li>
 *   <li><b>Other</b>: the log's direct other cost.</li>
 * </ul>
 *
 * Allocation basis {@code net_tonnes}: the whole cost is capitalised on the net tonnes that reach the ROM stockpile
 * (losses are carried by the good stone). {@code gross_tonnes}: cost per gross tonne; the net tonnes carry their share
 * and the loss share ({@code total − capitalised}) is written off.
 */
public final class QuarryCostCalculator {

    public static final String NET = "net_tonnes";
    public static final String GROSS = "gross_tonnes";

    private QuarryCostCalculator() {}

    /** An equipment or labour line with its resolved rates. */
    public record Resource(String kind, BigDecimal hours, BigDecimal ratePerHour, BigDecimal fuelLitres,
            BigDecimal fuelCostPerLitre) {}

    /** A royalty / levy rate that applies to the log. */
    public record Royalty(String id, String name, String basis, BigDecimal ratePerTonne, String partnerId) {}

    /** The linked blast's pool: total cost, already absorbed, estimated tonnes. */
    public record Blast(String id, String code, BigDecimal pool, BigDecimal absorbed, BigDecimal estimatedTonnes) {}

    public record Input(BigDecimal grossTonnes, BigDecimal lossTonnes, String allocationBasis, List<Resource> resources,
            Blast blast, List<Royalty> royalties, BigDecimal otherCost) {}

    /** A cost line; {@code index} is the resource line index for equipment / fuel / labour lines, else -1. */
    public record Line(String component, int index, String refId, String description, BigDecimal quantity,
            BigDecimal rate, BigDecimal amount, String partnerId) {}

    /** Per-resource cost (same order as the input). */
    public record ResourceCost(BigDecimal hoursCost, BigDecimal fuelCost) {}

    public record Result(BigDecimal grossTonnes, BigDecimal lossTonnes, BigDecimal netTonnes, BigDecimal fuel,
            BigDecimal labour, BigDecimal equipment, BigDecimal blasting, BigDecimal royalty, BigDecimal other,
            BigDecimal total, BigDecimal capitalised, BigDecimal lossCost, BigDecimal unitCost, BigDecimal blastRate,
            List<ResourceCost> resourceCosts, List<Line> lines) {}

    public static Result compute(Input in) {
        BigDecimal gross = qty(in.grossTonnes());
        BigDecimal loss = qty(in.lossTonnes());
        if (gross.signum() <= 0) {
            throw ApiException.badRequest("An extraction log needs at least one quantity line");
        }
        if (loss.compareTo(gross) >= 0) {
            throw ApiException.badRequest("Losses (" + plain(loss) + " t) must be less than the gross tonnes ("
                    + plain(gross) + " t)");
        }
        BigDecimal net = gross.subtract(loss);
        String basis = GROSS.equals(in.allocationBasis()) ? GROSS : NET;
        List<Line> lines = new ArrayList<>();
        List<ResourceCost> resourceCosts = new ArrayList<>();
        BigDecimal fuel = BigDecimal.ZERO;
        BigDecimal labour = BigDecimal.ZERO;
        BigDecimal equipment = BigDecimal.ZERO;
        List<Resource> resources = in.resources() == null ? List.of() : in.resources();
        for (int i = 0; i < resources.size(); i++) {
            Resource r = resources.get(i);
            BigDecimal hours = nz(r.hours());
            BigDecimal rate = nz(r.ratePerHour());
            BigDecimal hoursCost = money(hours.multiply(rate));
            BigDecimal fuelCost = BigDecimal.ZERO;
            if ("labour".equals(r.kind())) {
                labour = labour.add(hoursCost);
                lines.add(new Line("labour", i, null, "Labour", hours, rate, hoursCost, null));
            } else {
                equipment = equipment.add(hoursCost);
                lines.add(new Line("equipment", i, null, "Equipment hours", hours, rate, hoursCost, null));
                BigDecimal litres = nz(r.fuelLitres());
                if (litres.signum() > 0) {
                    BigDecimal fuelRate = nz(r.fuelCostPerLitre());
                    fuelCost = money(litres.multiply(fuelRate));
                    fuel = fuel.add(fuelCost);
                    lines.add(new Line("fuel", i, null, "Fuel", litres, fuelRate, fuelCost, null));
                }
            }
            resourceCosts.add(new ResourceCost(hoursCost, fuelCost));
        }
        BigDecimal blasting = BigDecimal.ZERO;
        BigDecimal blastRate = null;
        if (in.blast() != null) {
            Blast b = in.blast();
            BigDecimal pool = nz(b.pool());
            BigDecimal remaining = pool.subtract(nz(b.absorbed())).max(BigDecimal.ZERO);
            if (b.estimatedTonnes() != null && b.estimatedTonnes().signum() > 0) {
                blastRate = pool.divide(b.estimatedTonnes(), 6, RoundingMode.HALF_UP);
                blasting = money(blastRate.multiply(net)).min(remaining);
            } else {
                blasting = money(remaining);
            }
            if (blasting.signum() > 0) {
                lines.add(new Line("blasting", -1, b.id(), "Blasting " + b.code(), net, blastRate, blasting, null));
            }
        }
        BigDecimal royalty = BigDecimal.ZERO;
        for (Royalty r : in.royalties() == null ? List.<Royalty>of() : in.royalties()) {
            BigDecimal base = GROSS.equals(r.basis()) ? gross : net;
            BigDecimal amount = money(base.multiply(nz(r.ratePerTonne())));
            if (amount.signum() > 0) {
                royalty = royalty.add(amount);
                lines.add(new Line("royalty", -1, r.id(), r.name(), base, r.ratePerTonne(), amount, r.partnerId()));
            }
        }
        BigDecimal other = money(nz(in.otherCost()));
        if (other.signum() > 0) {
            lines.add(new Line("other", -1, null, "Other cost", null, null, other, null));
        }
        BigDecimal total = fuel.add(labour).add(equipment).add(blasting).add(royalty).add(other);
        BigDecimal capitalised;
        BigDecimal lossCost;
        BigDecimal unit;
        if (GROSS.equals(basis) && loss.signum() > 0) {
            BigDecimal perGross = total.divide(gross, 6, RoundingMode.HALF_UP);
            capitalised = money(perGross.multiply(net)).min(total);
            lossCost = total.subtract(capitalised);
            unit = capitalised.divide(net, 6, RoundingMode.HALF_UP);
        } else {
            capitalised = total;
            lossCost = BigDecimal.ZERO;
            unit = total.divide(net, 6, RoundingMode.HALF_UP);
        }
        return new Result(gross, loss, net, fuel, labour, equipment, blasting, royalty, other, total, capitalised,
                lossCost, unit, blastRate, resourceCosts, lines);
    }

    /** Tonnes of {@code loads} loads of {@code capacity} t each (3 decimals). */
    public static BigDecimal loadTonnes(int loads, BigDecimal capacity) {
        return qty(capacity.multiply(BigDecimal.valueOf(loads)));
    }

    /** Net weight kg → tonnes (3 decimals). */
    public static BigDecimal kgToTonnes(BigDecimal kg) {
        return kg.divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal qty(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(3) : v.setScale(3, RoundingMode.HALF_UP);
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
