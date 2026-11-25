package com.inventoryerp.qualitycontrol.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Pure check generation (ADR-S087 §3–§4): which checks a document needs per control point, and how existing checks
 * are reconciled with it when the document changes. No database access; {@link QcGate} applies the result.
 */
public final class QcCheckGenerator {

    private QcCheckGenerator() {}

    /** Product facts the matcher needs: its category and the decimal scale of its base unit. */
    public record ProductInfo(String categoryId, int scale) {}

    /** One check the document needs: identity ({@code baseKey}), scope and covered / test quantity. */
    public record Requirement(
            String pointId,
            String baseKey,
            String scheduleScope,
            String productId,
            String variantId,
            String lineId,
            String batchNumber,
            BigDecimal coveredQty,
            BigDecimal testQty) {}

    /** An existing, non-cancelled generated check of the document. */
    public record Existing(String id, String baseKey, int seq, String status, BigDecimal coveredQty) {
        boolean done() {
            return "passed".equals(status) || "failed".equals(status) || "waived".equals(status);
        }
    }

    public sealed interface Action permits Create, Recompute, Cancel {}

    /** Create a check; {@code supplementary} = covers an increase after the original was done. */
    public record Create(Requirement req, BigDecimal coveredQty, BigDecimal testQty, boolean supplementary)
            implements Action {}

    public record Recompute(String checkId, BigDecimal coveredQty, BigDecimal testQty) implements Action {}

    public record Cancel(String checkId, String reason) implements Action {}

    static final String LINE_REMOVED = "source line removed";

    /** Does the point apply to this document (supplier / routing operation), ignoring products? */
    public static boolean appliesTo(Map<String, Object> point, QcSourceDoc doc) {
        String supplier = QcUtil.str(point, "supplierPartnerId");
        if (supplier != null && !supplier.equals(doc.supplierPartnerId())) {
            return false;
        }
        String op = QcUtil.str(point, "routingOperationId");
        return op == null || op.equals(doc.routingOperationId());
    }

    /** Does the point cover this product (empty product and category lists = every product)? */
    public static boolean coversProduct(Map<String, Object> point, String productId, ProductInfo info) {
        List<String> products = ids(point.get("productIds"));
        List<String> categories = ids(point.get("categoryIds"));
        if (products.isEmpty() && categories.isEmpty()) {
            return true;
        }
        return products.contains(productId)
                || (info != null && info.categoryId() != null && categories.contains(info.categoryId()));
    }

    /** The requirements of one point for a document (empty when nothing matches). */
    public static List<Requirement> requirements(Map<String, Object> point, QcSourceDoc doc,
            Function<String, ProductInfo> products) {
        if (!appliesTo(point, doc)) {
            return List.of();
        }
        List<QcSourceDoc.Line> lines = doc.lines().stream()
                .filter(l -> l.productId() != null && coversProduct(point, l.productId(), products.apply(l.productId())))
                .toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        String pointId = QcUtil.str(point, "id");
        String prefix = String.join("|", pointId, doc.sourceType(), doc.sourceId(),
                Objects.requireNonNullElse(doc.workOrderId(), "-"));
        String per = Objects.requireNonNullElse(QcUtil.str(point, "controlPer"), "operation");
        List<Requirement> out = new ArrayList<>();
        switch (per) {
            case "quantity" -> {
                BigDecimal pct = QcUtil.dec(point, "testQtyPct");
                BigDecimal min = point.get("minSampleQty") == null ? null : QcUtil.dec(point, "minSampleQty");
                int i = 0;
                for (QcSourceDoc.Line l : lines) {
                    String lineKey = l.lineId() != null ? l.lineId() : "#" + i;
                    i++;
                    BigDecimal covered = nz(l.baseQty());
                    out.add(new Requirement(pointId, prefix + "|" + lineKey, product(l), l.productId(), l.variantId(),
                            l.lineId(), l.batchNumber(), covered,
                            TestQuantityCalculator.testQty(covered, pct, min, scale(products, l.productId()))));
                }
            }
            case "product" -> {
                Map<String, List<QcSourceDoc.Line>> byProduct = new LinkedHashMap<>();
                for (QcSourceDoc.Line l : lines) {
                    byProduct.computeIfAbsent(product(l), k -> new ArrayList<>()).add(l);
                }
                byProduct.forEach((scope, group) -> {
                    QcSourceDoc.Line first = group.getFirst();
                    out.add(new Requirement(pointId, prefix + "|" + scope, scope, first.productId(), first.variantId(),
                            null, oneBatch(group), sum(group), null));
                });
            }
            default -> {
                // operation: one check per document; product / quantity filled in when the document has one product.
                boolean single = lines.stream().map(QcCheckGenerator::product).distinct().count() == 1;
                QcSourceDoc.Line first = lines.getFirst();
                out.add(new Requirement(pointId, prefix + "|-", "-", single ? first.productId() : null,
                        single ? first.variantId() : null, null, single ? oneBatch(lines) : null,
                        single ? sum(lines) : null, null));
            }
        }
        return out;
    }

    /**
     * Reconciles the requirements of one point with its existing checks (ADR-S087 §4 table). A pending check follows
     * the quantity; a done check is kept and an increase gets a supplementary check; a check whose line is gone is
     * cancelled while pending and kept once done.
     */
    public static List<Action> reconcile(List<Requirement> reqs, Collection<Existing> existing,
            Map<String, Object> point, Function<String, ProductInfo> products) {
        Map<String, List<Existing>> byKey = new LinkedHashMap<>();
        for (Existing e : existing) {
            byKey.computeIfAbsent(e.baseKey(), k -> new ArrayList<>()).add(e);
        }
        List<Action> out = new ArrayList<>();
        boolean quantityBased = !"operation".equals(Objects.requireNonNullElse(QcUtil.str(point, "controlPer"),
                "operation"));
        for (Requirement r : reqs) {
            List<Existing> mine = byKey.remove(r.baseKey());
            if (mine == null || mine.isEmpty()) {
                out.add(new Create(r, r.coveredQty(), r.testQty(), false));
                continue;
            }
            if (!quantityBased || r.coveredQty() == null) {
                continue;
            }
            BigDecimal doneCovered = mine.stream().filter(Existing::done).map(e -> nz(e.coveredQty()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal open = r.coveredQty().subtract(doneCovered).max(BigDecimal.ZERO);
            Existing pending = mine.stream().filter(e -> "pending".equals(e.status())).findFirst().orElse(null);
            if (pending != null) {
                if (nz(pending.coveredQty()).compareTo(open) != 0) {
                    out.add(new Recompute(pending.id(), open, testQty(point, open, r.productId(), products)));
                }
            } else if (open.signum() > 0) {
                out.add(new Create(r, open, testQty(point, open, r.productId(), products), true));
            }
        }
        for (List<Existing> gone : byKey.values()) {
            for (Existing e : gone) {
                if ("pending".equals(e.status())) {
                    out.add(new Cancel(e.id(), LINE_REMOVED));
                }
            }
        }
        return out;
    }

    private static BigDecimal testQty(Map<String, Object> point, BigDecimal covered, String productId,
            Function<String, ProductInfo> products) {
        if (!"quantity".equals(QcUtil.str(point, "controlPer"))) {
            return null;
        }
        BigDecimal min = point.get("minSampleQty") == null ? null : QcUtil.dec(point, "minSampleQty");
        return TestQuantityCalculator.testQty(covered, QcUtil.dec(point, "testQtyPct"), min, scale(products, productId));
    }

    private static int scale(Function<String, ProductInfo> products, String productId) {
        ProductInfo info = productId == null ? null : products.apply(productId);
        return info == null ? 0 : info.scale();
    }

    private static String product(QcSourceDoc.Line l) {
        return l.productId() + ":" + Objects.requireNonNullElse(l.variantId(), "");
    }

    private static String oneBatch(List<QcSourceDoc.Line> lines) {
        List<String> batches = lines.stream().map(QcSourceDoc.Line::batchNumber).distinct().toList();
        return batches.size() == 1 ? batches.getFirst() : null;
    }

    private static BigDecimal sum(List<QcSourceDoc.Line> lines) {
        return lines.stream().map(l -> nz(l.baseQty())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** A JSON array column (JSON tree, list or JSON text) as ids. */
    static List<String> ids(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).toList();
        }
        tools.jackson.databind.JsonNode n = value instanceof tools.jackson.databind.JsonNode j ? j
                : com.inventoryerp.shared.web.EntityJson.tree(value.toString());
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.asString()));
        }
        return out;
    }
}
