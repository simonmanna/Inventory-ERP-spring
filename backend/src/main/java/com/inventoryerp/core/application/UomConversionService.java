package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.ProductUomConversion;
import com.inventoryerp.core.domain.UnitOfMeasure;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central unit conversion (port of {@code uom-conversion.service.ts}). Units convert within a category
 * via {@code factor} (= base units per 1 of this unit): {@code qty * from.factor / to.factor}.
 * Cross-category conversion needs a {@code ProductUomConversion} bridge (either direction). Results are
 * rounded to the target unit's {@code roundingPrecision} step. Decimal math uses decimal.js defaults
 * (20 significant digits, HALF_UP).
 */
@Service
public class UomConversionService {

    /** Prisma.Decimal (decimal.js) default context. */
    public static final MathContext DECIMAL_JS = new MathContext(20, RoundingMode.HALF_UP);

    private final EntityManager em;

    public UomConversionService(EntityManager em) {
        this.em = em;
    }

    /** Pure factor conversion within a category. */
    public static BigDecimal convertByFactor(Object qty, Object fromFactor, Object toFactor) {
        BigDecimal tf = Money.dec(toFactor);
        if (tf.signum() <= 0) {
            throw ApiException.badRequest("Target unit factor must be positive");
        }
        return Money.dec(qty).multiply(Money.dec(fromFactor), DECIMAL_JS).divide(tf, DECIMAL_JS);
    }

    /** Round to the nearest multiple of {@code step} (0/negative → no rounding). */
    public static BigDecimal roundToStep(Object qty, Object step) {
        BigDecimal s = Money.dec(step);
        if (s.signum() <= 0) {
            return Money.dec(qty);
        }
        return Money.dec(qty).divide(s, DECIMAL_JS).setScale(0, RoundingMode.HALF_UP).multiply(s, DECIMAL_JS);
    }

    private UnitOfMeasure getUom(String id) {
        List<UnitOfMeasure> rows = em.createQuery("select u from UnitOfMeasure u where u.id = :id", UnitOfMeasure.class)
                .setParameter("id", id).getResultList();
        if (rows.isEmpty()) {
            throw ApiException.badRequest("Unit of measure " + id + " not found");
        }
        return rows.getFirst();
    }

    private static boolean sameCategory(UnitOfMeasure a, UnitOfMeasure b) {
        if (a.getCategoryId() != null && !a.getCategoryId().isEmpty()
                && b.getCategoryId() != null && !b.getCategoryId().isEmpty()) {
            return a.getCategoryId().equals(b.getCategoryId());
        }
        // Pre-backfill fallback: compare the legacy free-text category tag.
        return Objects.equals(a.getCategory(), b.getCategory());
    }

    /** Convert {@code qty} between units (rounded to the target precision). */
    @Transactional(readOnly = true)
    public BigDecimal convert(Object qty, String fromUomId, String toUomId, String productId) {
        return convert(qty, fromUomId, toUomId, productId, true);
    }

    @Transactional(readOnly = true)
    public BigDecimal convert(Object qty, String fromUomId, String toUomId, String productId, boolean round) {
        if (Objects.equals(fromUomId, toUomId)) {
            return Money.dec(qty);
        }
        UnitOfMeasure from = getUom(fromUomId);
        UnitOfMeasure to = getUom(toUomId);

        BigDecimal result;
        if (sameCategory(from, to)) {
            result = convertByFactor(qty, from.getFactor(), to.getFactor());
        } else {
            ProductUomConversion bridge = productId == null || productId.isEmpty() ? null : bridge(productId, fromUomId, toUomId);
            if (bridge != null) {
                result = Money.dec(qty).multiply(bridge.getFactor(), DECIMAL_JS);
            } else {
                ProductUomConversion inverse = productId == null || productId.isEmpty() ? null
                        : bridge(productId, toUomId, fromUomId);
                if (inverse != null) {
                    if (inverse.getFactor().signum() <= 0) {
                        throw ApiException.badRequest("Invalid product conversion factor");
                    }
                    result = Money.dec(qty).divide(inverse.getFactor(), DECIMAL_JS);
                } else {
                    throw ApiException.badRequest("No conversion from " + from.getCode() + " to " + to.getCode()
                            + ": different categories — add a product-specific conversion.");
                }
            }
        }
        return round ? roundToStep(result, to.getRoundingPrecision()) : result;
    }

    /** Convert into the product's base (stock) unit; no-op when {@code fromUomId} is null/base. */
    @Transactional(readOnly = true)
    public BigDecimal toBase(Object qty, String fromUomId, String productId, String productUomId, boolean round) {
        if (fromUomId == null || fromUomId.isEmpty() || productUomId == null || productUomId.isEmpty()
                || fromUomId.equals(productUomId)) {
            return Money.dec(qty);
        }
        return convert(qty, fromUomId, productUomId, productId, round);
    }

    /** Convert from the product's base (stock) unit into {@code toUomId}. */
    @Transactional(readOnly = true)
    public BigDecimal fromBase(Object qty, String toUomId, String productId, String productUomId, boolean round) {
        if (toUomId == null || toUomId.isEmpty() || productUomId == null || productUomId.isEmpty()
                || toUomId.equals(productUomId)) {
            return Money.dec(qty);
        }
        return convert(qty, productUomId, toUomId, productId, round);
    }

    private ProductUomConversion bridge(String productId, String fromUomId, String toUomId) {
        return em.createQuery("select c from ProductUomConversion c where c.productId = :p and c.fromUomId = :f"
                        + " and c.toUomId = :t", ProductUomConversion.class)
                .setParameter("p", productId).setParameter("f", fromUomId).setParameter("t", toUomId)
                .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }
}
