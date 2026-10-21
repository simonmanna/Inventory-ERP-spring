package com.inventoryerp.pos.application;

import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A cart line resolved to ledger-ready values ({@code ResolvedLine} in {@code pos-orders.service.ts}): numbers are JS
 * doubles like Nest, modifiers / variant / accompaniments already folded into {@code unitPrice}.
 */
public final class PosLines {

    private PosLines() {}

    /** A modifier on a line ({@code kitchenPrintName} only set when resolved from the catalog). */
    public record Mod(String modifierId, String name, String kitchenPrintName, double priceDelta) {

        public Map<String, Object> toMap(boolean withKitchenName) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("modifierId", modifierId);
            m.put("name", name);
            if (withKitchenName) {
                m.put("kitchenPrintName", kitchenPrintName);
            }
            m.put("priceDelta", PosSql.js(priceDelta));
            return m;
        }
    }

    /**
     * {@code ResolvedLine}. {@code combo} marks the combo shape (no variant keys); {@code resolvedMods} marks
     * modifiers that came from the catalog (they carry {@code kitchenPrintName}).
     */
    public record Resolved(String productId, String menuItemId, String comboId, String description, double quantity,
            double unitPrice, String taxId, double discountPercent, String discountType, Double discountAmount,
            String discountReason, String note, Boolean taxInclusive, List<Mod> modifiers, String variantId,
            String variantName, List<String> accompanimentNames, List<String> accompanimentOptionIds, Double course,
            boolean combo, boolean resolvedMods) {

        /** JSON as Nest serializes the object ({@code undefined} keys dropped). */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", productId);
            m.put("menuItemId", menuItemId);
            m.put("comboId", comboId);
            m.put("description", description);
            m.put("quantity", PosSql.js(quantity));
            m.put("unitPrice", PosSql.js(unitPrice));
            m.put("taxId", taxId);
            m.put("discountPercent", PosSql.js(discountPercent));
            if (discountType != null) {
                m.put("discountType", discountType);
            }
            if (discountAmount != null) {
                m.put("discountAmount", PosSql.js(discountAmount));
            }
            m.put("discountReason", discountReason);
            m.put("note", note);
            if (taxInclusive != null) {
                m.put("taxInclusive", taxInclusive);
            }
            List<Map<String, Object>> mods = new ArrayList<>();
            for (Mod x : modifiers) {
                mods.add(x.toMap(resolvedMods));
            }
            m.put("modifiers", mods);
            if (!combo) {
                if (variantId != null) {
                    m.put("variantId", variantId);
                }
                if (variantName != null) {
                    m.put("variantName", variantName);
                }
            }
            m.put("accompanimentNames", accompanimentNames);
            m.put("accompanimentOptionIds", accompanimentOptionIds);
            m.put("course", course == null ? null : PosSql.js(course));
            return m;
        }

        /** The pricing-policy view of the line. */
        public Map<String, Object> pricing() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("quantity", quantity);
            m.put("unitPrice", unitPrice);
            m.put("discountPercent", discountPercent);
            m.put("discountType", discountType);
            m.put("discountAmount", discountAmount);
            m.put("discountReason", discountReason);
            return m;
        }

        /** {@code toPreparedInput}: the tax-engine input as a JS object. */
        public Map<String, Object> preparedInput() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", productId);
            m.put("menuItemId", menuItemId);
            m.put("variantId", variantId);
            m.put("variantName", variantName);
            m.put("description", description);
            m.put("quantity", quantity);
            m.put("unitPrice", unitPrice);
            m.put("taxId", taxId);
            m.put("discountPercent", discountPercent);
            m.put("discountType", discountType);
            m.put("discountAmount", discountAmount);
            m.put("discountReason", discountReason);
            m.put("discountSource", "manual");
            m.put("taxInclusive", taxInclusive);
            return m;
        }

        public Resolved withQuantity(double q) {
            return new Resolved(productId, menuItemId, comboId, description, q, unitPrice, taxId, discountPercent,
                    discountType, discountAmount, discountReason, note, taxInclusive, modifiers, variantId, variantName,
                    accompanimentNames, accompanimentOptionIds, course, combo, resolvedMods);
        }
    }

    /** A JS tax-engine input object → {@link LineInput}. */
    public static LineInput lineInput(Map<String, Object> m) {
        return new LineInput((String) m.get("productId"), (String) m.get("menuItemId"), (String) m.get("variantId"),
                (String) m.get("variantName"), (String) m.get("accountId"), (String) m.get("description"),
                PosSql.decOrNull(m.get("quantity")), PosSql.decOrNull(m.get("unitPrice")),
                PosSql.decOrNull(m.get("discountPercent")), (String) m.get("discountType"),
                PosSql.decOrNull(m.get("discountAmount")), (String) m.get("discountReason"),
                (String) m.get("discountSource"), (String) m.get("taxId"), (String) m.get("lineType"),
                (Boolean) m.get("taxInclusive"));
    }

    public static List<LineInput> lineInputs(List<Map<String, Object>> maps) {
        List<LineInput> out = new ArrayList<>();
        maps.forEach(m -> out.add(lineInput(m)));
        return out;
    }

    /** F10 line signature: identity + variant + course + note + modifier set + accompaniment set. */
    public static String lineSignature(String productId, String menuItemId, String comboId, String variantName,
            String description, String note, Object course, List<String> modifierIds, List<String> accompanimentOptionIds) {
        String base = comboId != null && !comboId.isEmpty() ? "c:" + comboId
                : menuItemId != null && !menuItemId.isEmpty() ? "m:" + menuItemId
                : productId != null && !productId.isEmpty() ? "p:" + productId
                : "d:" + (description == null ? "" : description);
        List<String> mods = new ArrayList<>(modifierIds == null ? List.of() : modifierIds);
        mods.removeIf(s -> s == null || s.isEmpty());
        mods.sort(null);
        List<String> accs = new ArrayList<>(accompanimentOptionIds == null ? List.of() : accompanimentOptionIds);
        accs.removeIf(s -> s == null || s.isEmpty());
        accs.sort(null);
        String courseStr = course == null ? "" : course instanceof Number n ? JsFormat.str(n.doubleValue()) : course.toString();
        return String.join("|", base, variantName == null ? "" : variantName, courseStr,
                (note == null ? "" : note).trim(), String.join(",", mods), String.join(",", accs));
    }

    /** Signature of a persisted OrderItem row (with {@code modifiers}). */
    public static String rowSignature(Map<String, Object> row) {
        List<String> modIds = new ArrayList<>();
        for (Object o : PosSql.list(row.get("modifiers"))) {
            modIds.add((String) PosSql.map(o).get("modifierId"));
        }
        return lineSignature((String) row.get("productId"), (String) row.get("menuItemId"), (String) row.get("comboId"),
                (String) row.get("variantName"), (String) row.get("description"), (String) row.get("note"),
                row.get("course"), modIds, PosSql.strings(row.get("accompanimentOptionIds")));
    }

    /** Signature of a resolved line. */
    public static String resolvedSignature(Resolved l) {
        List<String> modIds = new ArrayList<>();
        for (Mod m : l.modifiers()) {
            modIds.add(m.modifierId());
        }
        return lineSignature(l.productId(), l.menuItemId(), l.comboId(), l.variantName(), l.description(), l.note(),
                l.course(), modIds, l.accompanimentOptionIds());
    }

    /** {@code itemToResolved}: a persisted OrderItem (with modifiers) back to a resolved line. */
    public static Resolved itemToResolved(Map<String, Object> it) {
        List<Mod> mods = new ArrayList<>();
        for (Object o : PosSql.list(it.get("modifiers"))) {
            Map<String, Object> m = PosSql.map(o);
            mods.add(new Mod((String) m.get("modifierId"), (String) m.get("name"), null, PosSql.num(m.get("priceDelta"))));
        }
        Object da = it.get("discountAmount");
        Double discountAmount = PosSql.truthy(da) ? PosSql.num(da) : null;
        Object course = it.get("course");
        return new Resolved((String) it.get("productId"), (String) it.get("menuItemId"), (String) it.get("comboId"),
                (String) it.get("description"), PosSql.num(it.get("quantity")), PosSql.num(it.get("unitPrice")),
                (String) it.get("taxId"), PosSql.num(it.get("discountPercent")),
                it.get("discountType") == null ? null : it.get("discountType").toString(), discountAmount,
                (String) it.get("discountReason"), (String) it.get("note"), (Boolean) it.get("taxInclusive"), mods,
                (String) it.get("variantId"), (String) it.get("variantName"),
                PosSql.strings(it.get("accompanimentNames")), PosSql.strings(it.get("accompanimentOptionIds")),
                course == null ? null : PosSql.num(course), false, false);
    }

    static boolean same(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
