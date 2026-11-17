package com.inventoryerp.shared.costing;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The shared allocation primitive (Cost Management CM-3, ADR-S033, AD-8), pure and DB-free: split a total over
 * receivers in proportion to a non-negative basis, rounded to {@code scale}, the shares always summing
 * <em>exactly</em> to the total.
 *
 * <ul>
 *   <li>{@code LARGEST_REMAINDER} (default): every share rounded down, the remaining units to the largest remainders
 *       (ties: list order) — the fairest split;</li>
 *   <li>{@code LAST}: each share rounded half-up, the last receiver takes the difference (landed cost style);</li>
 *   <li>{@code MAIN}: each share rounded half-up, the first (main) receiver takes the difference (joint cost
 *       style).</li>
 * </ul>
 */
public final class AllocationCalculator {

    private static final MathContext MC = new MathContext(34, RoundingMode.HALF_EVEN);

    /** Rounding policies. */
    public enum Rounding {
        LARGEST_REMAINDER, LAST, MAIN;

        public static Rounding of(String v) {
            if (v == null || v.isBlank()) {
                return LARGEST_REMAINDER;
            }
            return switch (v) {
                case "largest_remainder" -> LARGEST_REMAINDER;
                case "last" -> LAST;
                case "main" -> MAIN;
                default -> throw ApiException.badRequest("rounding must be one of the following values:"
                        + " largest_remainder, last, main");
            };
        }
    }

    /** One receiver and its basis (driver quantity). */
    public record Receiver(String key, BigDecimal basis) {}

    /** One share: receiver, basis, fraction of the total, allocated amount. */
    public record Share(String key, BigDecimal basis, BigDecimal fraction, BigDecimal amount) {}

    private AllocationCalculator() {}

    /**
     * Allocate {@code total} over {@code receivers}. A negative basis is refused; a basis summing to zero is refused
     * (there is nothing to allocate by).
     */
    public static List<Share> allocate(BigDecimal total, List<Receiver> receivers, Rounding rounding, int scale) {
        if (receivers.isEmpty()) {
            throw ApiException.badRequest("Add at least one receiver");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Receiver r : receivers) {
            if (r.basis() == null || r.basis().signum() < 0) {
                throw ApiException.badRequest("The allocation basis of " + r.key() + " must not be negative");
            }
            sum = sum.add(r.basis());
        }
        if (sum.signum() == 0) {
            throw ApiException.badRequest("The allocation basis is zero for every receiver; nothing to allocate by");
        }
        BigDecimal target = total.setScale(scale, RoundingMode.HALF_UP);
        int n = receivers.size();
        BigDecimal[] exact = new BigDecimal[n];
        BigDecimal[] amount = new BigDecimal[n];
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            exact[i] = target.multiply(receivers.get(i).basis(), MC).divide(sum, MC);
            amount[i] = rounding == Rounding.LARGEST_REMAINDER
                    ? exact[i].setScale(scale, target.signum() >= 0 ? RoundingMode.FLOOR : RoundingMode.CEILING)
                    : exact[i].setScale(scale, RoundingMode.HALF_UP);
            allocated = allocated.add(amount[i]);
        }
        BigDecimal rest = target.subtract(allocated);
        switch (rounding) {
            case LAST -> amount[n - 1] = amount[n - 1].add(rest);
            case MAIN -> amount[0] = amount[0].add(rest);
            case LARGEST_REMAINDER -> {
                BigDecimal unit = BigDecimal.ONE.movePointLeft(scale);
                long units = rest.abs().divide(unit, 0, RoundingMode.HALF_UP).longValue();
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    order.add(i);
                }
                order.sort(Comparator.comparing((Integer i) -> exact[i].subtract(amount[i]).abs()).reversed());
                BigDecimal step = rest.signum() >= 0 ? unit : unit.negate();
                for (int k = 0; k < units; k++) {
                    int i = order.get(k % n);
                    amount[i] = amount[i].add(step);
                }
            }
            default -> { }
        }
        List<Share> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Receiver r = receivers.get(i);
            out.add(new Share(r.key(), r.basis(), r.basis().divide(sum, 10, RoundingMode.HALF_UP), amount[i]));
        }
        return List.copyOf(out);
    }

    /**
     * Reciprocal (simultaneous) service-department costs: {@code T_i = D_i + Σ_j f_ji · T_j}, where {@code f_ji} is the
     * fraction of service centre j's total that goes to service centre i. Solved exactly, {@code (I − Fᵀ) · T = D} by
     * Gaussian elimination with partial pivoting at 34 significant digits; refused when a service centre allocates
     * (almost) everything to other service centres, which has no solution.
     *
     * <p>Review 2026-11-17 (ACR-7): this used to be a fixed-point iteration capped at 100 rounds. When service centres
     * pass most of their cost to each other the iteration converges slowly (its error shrinks by the internal share
     * each round: 0.99¹⁰⁰ still leaves a third of it), and the cap stopped it early without saying so — cost stayed in
     * the service centres and the receivers were under-charged.
     *
     * @param direct    D_i per service centre
     * @param fractions f_ji: sender j → (receiver i → fraction); only service-centre receivers matter
     * @return T_i per service centre
     */
    public static Map<String, BigDecimal> reciprocal(Map<String, BigDecimal> direct,
            Map<String, Map<String, BigDecimal>> fractions) {
        for (Map.Entry<String, Map<String, BigDecimal>> e : fractions.entrySet()) {
            BigDecimal internal = e.getValue().entrySet().stream().filter(x -> direct.containsKey(x.getKey())
                    && !x.getKey().equals(e.getKey())).map(Map.Entry::getValue).reduce(BigDecimal.ZERO,
                    BigDecimal::add);
            if (internal.compareTo(new BigDecimal("0.999999")) > 0) {
                throw ApiException.badRequest("Service cost centre " + e.getKey() + " allocates everything to other"
                        + " service centres; reciprocal allocation has no solution");
            }
        }
        List<String> keys = new ArrayList<>(direct.keySet());
        int n = keys.size();
        Map<String, Integer> index = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(keys.get(i), i);
        }
        // Augmented matrix [I − Fᵀ | D]: row i is service centre i's equation.
        BigDecimal[][] a = new BigDecimal[n][n + 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                a[i][j] = i == j ? BigDecimal.ONE : BigDecimal.ZERO;
            }
            BigDecimal d = direct.get(keys.get(i));
            a[i][n] = d == null ? BigDecimal.ZERO : d;
        }
        for (Map.Entry<String, Map<String, BigDecimal>> e : fractions.entrySet()) {
            Integer j = index.get(e.getKey());
            if (j == null) {
                continue;
            }
            for (Map.Entry<String, BigDecimal> f : e.getValue().entrySet()) {
                Integer i = index.get(f.getKey());
                if (i != null && !i.equals(j) && f.getValue() != null) {
                    a[i][j] = a[i][j].subtract(f.getValue(), MC);
                }
            }
        }
        BigDecimal singular = new BigDecimal("1e-12");
        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int r = col + 1; r < n; r++) {
                if (a[r][col].abs().compareTo(a[pivot][col].abs()) > 0) {
                    pivot = r;
                }
            }
            if (a[pivot][col].abs().compareTo(singular) < 0) {
                throw ApiException.badRequest("The service cost centres allocate all their cost among themselves;"
                        + " reciprocal allocation has no solution");
            }
            if (pivot != col) {
                BigDecimal[] tmp = a[pivot];
                a[pivot] = a[col];
                a[col] = tmp;
            }
            for (int r = col + 1; r < n; r++) {
                if (a[r][col].signum() == 0) {
                    continue;
                }
                BigDecimal factor = a[r][col].divide(a[col][col], MC);
                for (int c = col; c <= n; c++) {
                    a[r][c] = a[r][c].subtract(factor.multiply(a[col][c], MC), MC);
                }
            }
        }
        BigDecimal[] x = new BigDecimal[n];
        for (int i = n - 1; i >= 0; i--) {
            BigDecimal v = a[i][n];
            for (int c = i + 1; c < n; c++) {
                v = v.subtract(a[i][c].multiply(x[c], MC), MC);
            }
            x[i] = v.divide(a[i][i], MC);
        }
        Map<String, BigDecimal> t = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            t.put(keys.get(i), x[i]);
        }
        return t;
    }
}
