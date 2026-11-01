package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.core.domain.ProductUom;
import com.inventoryerp.core.domain.ProductUomConversion;
import com.inventoryerp.core.domain.UnitOfMeasure;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Central unit conversion (port of {@code uom-conversion.service.ts}). Units convert within a category
 * via {@code factor} (= base units per 1 of this unit): {@code qty * from.factor / to.factor}.
 * Cross-category conversion needs a {@code ProductUomConversion} bridge (either direction). Results are
 * rounded to the target unit's {@code roundingPrecision} step. Decimal math uses decimal.js defaults
 * (20 significant digits, HALF_UP).
 *
 * <p>Deviation (ADR-S003): when a product is given and it declares its own unit in {@code ProductUom}
 * (Box = 24 for this product), that per-product factor wins over the organization-wide category factor.
 * {@link #resolveLineUnit} picks and validates the unit of a purchase/sales line and returns the factor to
 * snapshot on it, so later edits to the product's units never change a posted document.
 *
 * <p>UOM-1: a {@code ProductUomConversion} bridge also connects the two whole unit categories for that product, not
 * just its two end units, and bridges chain. "1 m³ = 1.6 t" therefore also answers L ↔ kg, and with "1 pc = 19 kg"
 * also pc ↔ m³. An exact bridge between the two units still wins; the chain is only tried where a 400 was returned
 * before. {@link #conversionRate} is the non-throwing form for callers that must not mark a transaction rollback-only.
 */
@Service
public class UomConversionService {

    /** Prisma.Decimal (decimal.js) default context. */
    public static final MathContext DECIMAL_JS = new MathContext(20, RoundingMode.HALF_UP);

    /** Which side of the business a line belongs to; decides the default unit and the allowed units. */
    public enum UnitRole {
        PURCHASE,
        SALES
    }

    /** A line's unit and its snapshot factor (base units per 1 line unit); factor is null when not applicable. */
    public record LineUnit(String uomId, BigDecimal factor) {}

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

    private UnitOfMeasure findUom(String id) {
        return em.createQuery("select u from UnitOfMeasure u where u.id = :id", UnitOfMeasure.class)
                .setParameter("id", id).getResultList().stream().findFirst().orElse(null);
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

        Product product = productId == null || productId.isEmpty() ? null : product(productId);
        if (product != null && product.getUomId() != null
                && ((!fromUomId.equals(product.getUomId()) && productUnit(productId, fromUomId) != null)
                        || (!toUomId.equals(product.getUomId()) && productUnit(productId, toUomId) != null))) {
            BigDecimal base = Money.dec(qty).multiply(factorToBase(product, fromUomId), DECIMAL_JS);
            BigDecimal result = base.divide(factorToBase(product, toUomId), DECIMAL_JS);
            return round ? roundToStep(result, to.getRoundingPrecision()) : result;
        }

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
                    BigDecimal chained = product == null ? null : bridgeRate(product.getId(), from, to);
                    if (chained == null) {
                        throw ApiException.badRequest("No conversion from " + from.getCode() + " to " + to.getCode()
                                + ": different categories — add a product-specific conversion.");
                    }
                    result = Money.dec(qty).multiply(chained, DECIMAL_JS);
                }
            }
        }
        return round ? roundToStep(result, to.getRoundingPrecision()) : result;
    }

    /**
     * How many {@code toUomId} make 1 {@code fromUomId} for the product (unrounded), or {@code null} when no conversion
     * exists. Same routing as {@link #convert}: {@code ProductUom} factors, the category factor, then the bridges. It
     * never throws for a missing path, so a caller's transaction stays usable.
     */
    @Transactional(readOnly = true)
    public BigDecimal conversionRate(String productId, String fromUomId, String toUomId) {
        if (fromUomId == null || toUomId == null) {
            return null;
        }
        if (fromUomId.equals(toUomId)) {
            return BigDecimal.ONE;
        }
        UnitOfMeasure from = findUom(fromUomId);
        UnitOfMeasure to = findUom(toUomId);
        if (from == null || to == null) {
            return null;
        }
        Product product = productId == null || productId.isEmpty() ? null : product(productId);
        if (product != null && product.getUomId() != null
                && ((!fromUomId.equals(product.getUomId()) && productUnit(productId, fromUomId) != null)
                        || (!toUomId.equals(product.getUomId()) && productUnit(productId, toUomId) != null))) {
            BigDecimal fromBase = baseRate(product, from);
            BigDecimal toBase = baseRate(product, to);
            return fromBase == null || toBase == null || toBase.signum() <= 0 ? null
                    : fromBase.divide(toBase, DECIMAL_JS);
        }
        return plainRate(product, from, to);
    }

    /** Base units per 1 {@code unit} of the product, or null. */
    private BigDecimal baseRate(Product product, UnitOfMeasure unit) {
        if (unit.getId().equals(product.getUomId())) {
            return BigDecimal.ONE;
        }
        ProductUom own = productUnit(product.getId(), unit.getId());
        if (own != null) {
            return own.getFactor();
        }
        UnitOfMeasure base = findUom(product.getUomId());
        return base == null ? null : plainRate(product, unit, base);
    }

    /** Category factor, else the product's bridges; ignores {@code ProductUom}. */
    private BigDecimal plainRate(Product product, UnitOfMeasure from, UnitOfMeasure to) {
        if (sameCategory(from, to)) {
            return to.getFactor().signum() <= 0 ? null : from.getFactor().divide(to.getFactor(), DECIMAL_JS);
        }
        return product == null ? null : bridgeRate(product.getId(), from, to);
    }

    /** One step of the bridge walk: {@code rate} of {@code unit} make 1 of the unit the walk started from. */
    private record Hop(UnitOfMeasure unit, BigDecimal rate) {}

    /**
     * Walk the product's bridges from {@code from}'s category to {@code to}'s category, each bridge used at most once.
     * Null when they are not connected.
     */
    private BigDecimal bridgeRate(String productId, UnitOfMeasure from, UnitOfMeasure to) {
        List<ProductUomConversion> bridges = em.createQuery("select c from ProductUomConversion c where c.productId = :p",
                ProductUomConversion.class).setParameter("p", productId).getResultList();
        Set<String> used = new HashSet<>();
        ArrayDeque<Hop> queue = new ArrayDeque<>();
        queue.add(new Hop(from, BigDecimal.ONE));
        while (!queue.isEmpty()) {
            Hop hop = queue.poll();
            if (sameCategory(hop.unit(), to)) {
                return to.getFactor().signum() <= 0 ? null : hop.rate().multiply(hop.unit().getFactor(), DECIMAL_JS)
                        .divide(to.getFactor(), DECIMAL_JS);
            }
            for (ProductUomConversion b : bridges) {
                if (used.contains(b.getId()) || b.getFactor().signum() <= 0) {
                    continue;
                }
                UnitOfMeasure start = findUom(b.getFromUomId());
                UnitOfMeasure end = findUom(b.getToUomId());
                if (start == null || end == null || start.getFactor().signum() <= 0 || end.getFactor().signum() <= 0) {
                    continue;
                }
                if (sameCategory(hop.unit(), start)) {
                    used.add(b.getId());
                    queue.add(new Hop(end, hop.rate().multiply(hop.unit().getFactor(), DECIMAL_JS)
                            .divide(start.getFactor(), DECIMAL_JS).multiply(b.getFactor(), DECIMAL_JS)));
                } else if (sameCategory(hop.unit(), end)) {
                    used.add(b.getId());
                    queue.add(new Hop(start, hop.rate().multiply(hop.unit().getFactor(), DECIMAL_JS)
                            .divide(end.getFactor(), DECIMAL_JS).divide(b.getFactor(), DECIMAL_JS)));
                }
            }
        }
        return null;
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

    /**
     * Base units of {@code productId} per 1 {@code uomId}, unrounded: 1 for the base unit (or no unit), the
     * product's own {@code ProductUom} factor when declared, else the category factor / density bridge.
     */
    @Transactional(readOnly = true)
    public BigDecimal factorToBase(String productId, String uomId) {
        Product product = product(productId);
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        return factorToBase(product, uomId);
    }

    private BigDecimal factorToBase(Product product, String uomId) {
        String base = product.getUomId();
        if (uomId == null || uomId.isEmpty() || base == null || base.isEmpty() || uomId.equals(base)) {
            return BigDecimal.ONE;
        }
        ProductUom own = productUnit(product.getId(), uomId);
        if (own != null) {
            return own.getFactor();
        }
        return convert(BigDecimal.ONE, uomId, base, product.getId(), false);
    }

    /**
     * Pick the unit of a transaction line and the factor to snapshot on it. With no {@code requestedUomId} the
     * product's purchase (or sales) unit is used, falling back to its base unit. A unit the product declares in
     * {@code ProductUom} must be active and enabled for the role; any other unit must convert to the base unit.
     * Lines without a product carry the requested unit and no factor.
     */
    @Transactional(readOnly = true)
    public LineUnit resolveLineUnit(String productId, String requestedUomId, UnitRole role) {
        String requested = requestedUomId == null || requestedUomId.isBlank() ? null : requestedUomId;
        Product product = productId == null || productId.isEmpty() ? null : product(productId);
        if (product == null) {
            return new LineUnit(requested, null);
        }
        String roleUnit = role == UnitRole.PURCHASE ? product.getPurchaseUomId() : product.getSalesUomId();
        String uomId = requested != null ? requested : roleUnit != null ? roleUnit : product.getUomId();
        if (uomId == null) {
            return new LineUnit(null, BigDecimal.ONE);
        }
        if (requested != null && !requested.equals(product.getUomId())) {
            ProductUom own = productUnit(product.getId(), requested);
            if (own != null) {
                String code = getUom(requested).getCode();
                if (!Boolean.TRUE.equals(own.getIsActive())) {
                    throw ApiException.badRequest("Unit " + code + " is not active for product " + product.getName());
                }
                boolean allowed = role == UnitRole.PURCHASE ? Boolean.TRUE.equals(own.getIsPurchase())
                        : Boolean.TRUE.equals(own.getIsSales());
                if (!allowed) {
                    throw ApiException.badRequest("Unit " + code + " is not enabled for "
                            + (role == UnitRole.PURCHASE ? "purchasing" : "sales") + " on product " + product.getName());
                }
            }
        }
        BigDecimal factor = factorToBase(product, uomId);
        if (factor.signum() <= 0) {
            throw ApiException.badRequest("Invalid product conversion factor");
        }
        return new LineUnit(uomId, factor);
    }

    /**
     * Default price of one {@code unit} when the line gives none. {@code defaultUnitPrice} is the product's price
     * for its default unit of {@code role} (sales: {@code salesUomId} or base). A different unit uses its own
     * {@code ProductUom} price when set, else the default price scaled by the units' conversion.
     */
    @Transactional(readOnly = true)
    public BigDecimal defaultUnitPrice(String productId, LineUnit unit, UnitRole role, BigDecimal defaultUnitPrice) {
        Product product = productId == null || productId.isEmpty() ? null : product(productId);
        if (product == null || unit == null || unit.uomId() == null || unit.factor() == null) {
            return defaultUnitPrice;
        }
        String roleUnit = role == UnitRole.PURCHASE ? product.getPurchaseUomId() : product.getSalesUomId();
        String defaultUom = roleUnit != null ? roleUnit : product.getUomId();
        if (unit.uomId().equals(defaultUom)) {
            return defaultUnitPrice;
        }
        ProductUom own = unit.uomId().equals(product.getUomId()) ? null : productUnit(product.getId(), unit.uomId());
        BigDecimal ownPrice = own == null ? null : role == UnitRole.PURCHASE ? own.getPurchasePrice() : own.getSalePrice();
        if (ownPrice != null) {
            return ownPrice;
        }
        if (defaultUnitPrice == null) {
            return null;
        }
        BigDecimal defaultFactor = factorToBase(product, defaultUom);
        return defaultUnitPrice.multiply(unit.factor(), DECIMAL_JS).divide(defaultFactor, DECIMAL_JS);
    }

    /** {@code qty} line units to base units through a snapshot factor, rounded to the base unit's step. */
    @Transactional(readOnly = true)
    public BigDecimal applyFactor(Object qty, BigDecimal factor, String baseUomId, boolean round) {
        BigDecimal result = Money.dec(qty).multiply(factor, DECIMAL_JS);
        if (!round || baseUomId == null || baseUomId.isEmpty()) {
            return result;
        }
        return roundToStep(result, getUom(baseUomId).getRoundingPrecision());
    }

    private Product product(String productId) {
        return com.inventoryerp.kernel.common.ReadMemo.get(() -> em.createQuery(
                "select p from Product p where p.id = :id", Product.class).setParameter("id", productId)
                .getResultList().stream().findFirst().orElse(null),
                "uom.product", com.inventoryerp.kernel.tenancy.TenantContext.organizationId(), productId);
    }

    private ProductUom productUnit(String productId, String uomId) {
        return em.createQuery("select u from ProductUom u where u.productId = :p and u.uomId = :u", ProductUom.class)
                .setParameter("p", productId).setParameter("u", uomId).getResultList().stream().findFirst()
                .orElse(null);
    }

    private ProductUomConversion bridge(String productId, String fromUomId, String toUomId) {
        return em.createQuery("select c from ProductUomConversion c where c.productId = :p and c.fromUomId = :f"
                        + " and c.toUomId = :t", ProductUomConversion.class)
                .setParameter("p", productId).setParameter("f", fromUomId).setParameter("t", toUomId)
                .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }
}
