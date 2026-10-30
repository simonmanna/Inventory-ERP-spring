package com.inventoryerp.manufacturing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Status roll-up of a multi-level production order tree (ADR-S011 §2), pure and DB-free.
 *
 * <ul>
 *   <li>A cancelled order rolls up {@code cancelled}; a completed one {@code completed}.</li>
 *   <li>An order whose children (rolled up) are not all completed / cancelled is {@code awaiting_components} while
 *       it has not started, otherwise its own status ({@code in_progress}, {@code qc_hold}).</li>
 *   <li>A draft / confirmed order whose children are all done is {@code ready}; without children it keeps its
 *       status.</li>
 *   <li>Progress = mean of the node's own progress and its children's rolled-up progress (0–100, 2 dp).</li>
 * </ul>
 */
public final class OrderRollup {

    private OrderRollup() {}

    /** A child's rolled-up state. */
    public record Child(String rolledStatus, BigDecimal progressPct) {}

    /** The node's rolled-up state. */
    public record Rolled(String status, BigDecimal progressPct, int childrenDone, int childrenOpen) {}

    public static Rolled roll(String ownStatus, BigDecimal ownProgressPct, List<Child> children) {
        int done = 0;
        int open = 0;
        BigDecimal progress = ownProgressPct == null ? BigDecimal.ZERO : ownProgressPct;
        for (Child c : children) {
            if (isDone(c.rolledStatus())) {
                done++;
            } else {
                open++;
            }
            progress = progress.add(c.progressPct() == null ? BigDecimal.ZERO : c.progressPct());
        }
        BigDecimal mean = progress.divide(BigDecimal.valueOf(children.size() + 1L), 2, RoundingMode.HALF_UP);
        String status;
        if ("cancelled".equals(ownStatus) || "completed".equals(ownStatus)) {
            status = ownStatus;
            mean = "completed".equals(ownStatus) ? BigDecimal.valueOf(100).setScale(2) : mean;
        } else if (open > 0) {
            status = notStarted(ownStatus) ? "awaiting_components" : ownStatus;
        } else if (!children.isEmpty() && notStarted(ownStatus)) {
            status = "ready";
        } else {
            status = ownStatus;
        }
        return new Rolled(status, mean, done, open);
    }

    /** Own progress of an order: completed 100, else the share of completed stages (legacy: in progress = 50). */
    public static BigDecimal ownProgress(String status, int stages, int stagesCompleted) {
        if ("completed".equals(status)) {
            return BigDecimal.valueOf(100).setScale(2);
        }
        if ("cancelled".equals(status) || "draft".equals(status) || "confirmed".equals(status) && stagesCompleted == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (stages > 0) {
            return BigDecimal.valueOf(stagesCompleted * 100L).divide(BigDecimal.valueOf(stages), 2,
                    RoundingMode.HALF_UP);
        }
        return "qc_hold".equals(status) ? BigDecimal.valueOf(90).setScale(2) : BigDecimal.valueOf(50).setScale(2);
    }

    static boolean isDone(String status) {
        return "completed".equals(status) || "cancelled".equals(status);
    }

    private static boolean notStarted(String status) {
        return "draft".equals(status) || "confirmed".equals(status);
    }
}
