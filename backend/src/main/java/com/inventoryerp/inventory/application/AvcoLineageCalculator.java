package com.inventoryerp.inventory.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * AVCO cost lineage (ADR-S031 AD-2a), pure and DB-free. An average-cost pool (product, variant, location) is a
 * homogeneous mix, so every outbound movement depletes every layer still in the pool <em>pro rata</em>. For a layer X
 * received when the pool held {@code Q₀} (possibly negative), the share of a later value adjustment {@code A} that
 * belongs to each movement after X is given by the cumulative survival factor {@code S}:
 *
 * <ul>
 *   <li>units of X that filled negative stock (sold before they arrived) → {@code negative_stock};</li>
 *   <li>each outbound movement {@code i} of {@code q} from a pool of {@code Q}: share {@code A·S·q/Q}, then
 *       {@code S ← S·(1 − q/Q)};</li>
 *   <li>whatever survives ({@code A·S_now}) is still on hand.</li>
 * </ul>
 *
 * <p>The shares telescope, so Σ consumed + on hand = A exactly; each share is rounded to {@code scale} and the rounding
 * remainder goes to the on-hand share (or the last consumption when nothing is left). When the pool runs empty, X is
 * fully consumed and later movements are not visited (epoch reset). Inbound movements and value-only rows do not
 * change S.
 */
public final class AvcoLineageCalculator {

    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    private AvcoLineageCalculator() {}

    /** One pool movement after the layer, in posting order. {@code change} &lt; 0 = outbound. */
    public record Event(String ledgerId, String moveType, BigDecimal qtyBefore, BigDecimal change, String referenceType,
            String referenceId) {}

    /** One share of the adjustment. {@code ledgerId} null = still on hand / negative stock. */
    public record Share(String ledgerId, String moveType, String referenceType, String referenceId, BigDecimal amount) {}

    /** The distribution: negative-stock share, consumption shares, on hand, and the final survival factor. */
    public record Distribution(BigDecimal negativeStock, List<Share> consumed, BigDecimal onHand, BigDecimal survival) {

        /** Σ of every part (always the adjusted amount). */
        public BigDecimal total() {
            return consumed.stream().map(Share::amount).reduce(negativeStock.add(onHand), BigDecimal::add);
        }
    }

    /**
     * Distribute {@code amount} for a layer of {@code layerQty} received when the pool held {@code qtyBeforeLayer}.
     *
     * @param after the pool's movements after the layer, in posting order
     */
    public static Distribution distribute(BigDecimal amount, BigDecimal layerQty, BigDecimal qtyBeforeLayer,
            List<Event> after, int scale) {
        if (amount.signum() == 0 || layerQty.signum() <= 0) {
            return new Distribution(BigDecimal.ZERO, List.of(), amount, BigDecimal.ONE);
        }
        // Units of the layer that only filled negative stock were already consumed before it arrived.
        BigDecimal negativeUnits = qtyBeforeLayer.signum() < 0 ? layerQty.min(qtyBeforeLayer.negate()) : BigDecimal.ZERO;
        BigDecimal s = BigDecimal.ONE.subtract(negativeUnits.divide(layerQty, MC), MC);
        BigDecimal negRaw = amount.multiply(BigDecimal.ONE.subtract(s, MC), MC);

        List<Event> outbound = new ArrayList<>();
        List<BigDecimal> raw = new ArrayList<>();
        for (Event e : after) {
            if (s.signum() <= 0) {
                break; // epoch reset: nothing of the layer left
            }
            if (e.change() == null || e.change().signum() >= 0) {
                continue;
            }
            BigDecimal pool = e.qtyBefore();
            if (pool == null || pool.signum() <= 0) {
                s = BigDecimal.ZERO;
                break;
            }
            BigDecimal q = e.change().negate().min(pool);
            BigDecimal fraction = q.divide(pool, MC);
            raw.add(amount.multiply(s, MC).multiply(fraction, MC));
            outbound.add(e);
            s = s.multiply(BigDecimal.ONE.subtract(fraction, MC), MC);
        }
        if (s.signum() < 0) {
            s = BigDecimal.ZERO;
        }

        BigDecimal negative = negRaw.setScale(scale, RoundingMode.HALF_UP);
        List<Share> shares = new ArrayList<>();
        BigDecimal sum = negative;
        for (int i = 0; i < outbound.size(); i++) {
            Event e = outbound.get(i);
            BigDecimal v = raw.get(i).setScale(scale, RoundingMode.HALF_UP);
            shares.add(new Share(e.ledgerId(), e.moveType(), e.referenceType(), e.referenceId(), v));
            sum = sum.add(v);
        }
        BigDecimal onHand = amount.setScale(scale, RoundingMode.HALF_UP).subtract(sum);
        if (s.signum() == 0 && onHand.signum() != 0) {
            // Nothing left on hand: the rounding remainder belongs to the largest consumption (or negative stock).
            if (!shares.isEmpty()) {
                int idx = shares.indexOf(shares.stream().max(Comparator.comparing(x -> x.amount().abs())).orElseThrow());
                Share big = shares.get(idx);
                shares.set(idx, new Share(big.ledgerId(), big.moveType(), big.referenceType(), big.referenceId(),
                        big.amount().add(onHand)));
            } else {
                negative = negative.add(onHand);
            }
            onHand = BigDecimal.ZERO.setScale(scale);
        }
        return new Distribution(negative, List.copyOf(shares), onHand, s);
    }

    /**
     * Where a consumption share goes (ADR-S031 AD-2b): transfers stay in inventory, waste / expiry / internal use is
     * scrap, production consumption is WIP while the order is open, everything else is cost of sales.
     */
    public static String target(String moveType, boolean openProductionOrder) {
        if (moveType == null) {
            return "cogs";
        }
        return switch (moveType) {
            case "transfer_out" -> "transfer";
            case "waste", "expiry_write_off", "internal_use", "promo_sample" -> "scrap";
            case "production_consume" -> openProductionOrder ? "wip" : "cogs";
            default -> "cogs";
        };
    }
}
