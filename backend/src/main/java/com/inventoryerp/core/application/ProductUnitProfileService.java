package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.core.domain.ProductUom;
import com.inventoryerp.core.domain.ProductUomConversion;
import com.inventoryerp.core.domain.UnitOfMeasure;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A product's physical facts (UOM-1, extends ADR-S003): density, piece weight, bag weight and truckload size, entered
 * once and turned into the existing unit data, so sales, stock, weighbridge and invoicing all read the same numbers
 * through {@link UomConversionService}.
 *
 * <ul>
 *   <li>Density is the bridge {@code 1 M3 = d TON}; piece weight is the bridge {@code 1 UNIT = w KG} when the product
 *       counts in pieces, else a {@code ProductUom} row.
 *   <li>Bag and truckload are {@code ProductUom} rows, because their size differs per product.
 * </ul>
 *
 * A field left null is not touched; 0 removes the fact.
 */
@Service
public class ProductUnitProfileService {

    /** {@code PUT /products/:id/unit-profile}: every field optional. */
    public record ProfileInput(BigDecimal densityTonnesPerM3, BigDecimal kgPerPiece, BigDecimal kgPerBag,
            BigDecimal tonnesPerTruckload) {}

    private final EntityManager em;
    private final UomConversionService conversion;
    private final AuditService audit;

    public ProductUnitProfileService(EntityManager em, UomConversionService conversion, AuditService audit) {
        this.em = em;
        this.conversion = conversion;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String productId) {
        return profile(require(productId));
    }

    @Transactional
    public Map<String, Object> save(String productId, ProfileInput in) {
        Product p = require(productId);
        if (p.getUomId() == null) {
            throw ApiException.badRequest("Set the product's stock unit first");
        }
        UnitOfMeasure base = uom(p.getUomId());
        if (in.densityTonnesPerM3() != null) {
            saveDensity(p, base, in.densityTonnesPerM3());
        }
        if (in.kgPerPiece() != null) {
            UnitOfMeasure piece = byCode("UNIT");
            if (sameCategory(base, piece)) {
                saveBridge(p, piece, byCode("KG"), in.kgPerPiece());
            } else {
                savePack(p, base, piece, "KG", in.kgPerPiece());
            }
        }
        em.flush();
        if (in.kgPerBag() != null) {
            savePack(p, base, byCode("BAG"), "KG", in.kgPerBag());
        }
        if (in.tonnesPerTruckload() != null) {
            savePack(p, base, byCode("TRUCK"), "TON", in.tonnesPerTruckload());
        }
        em.flush();
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("densityTonnesPerM3", in.densityTonnesPerM3());
        changes.put("kgPerPiece", in.kgPerPiece());
        changes.put("kgPerBag", in.kgPerBag());
        changes.put("tonnesPerTruckload", in.tonnesPerTruckload());
        audit.record(AuditInput.of("Product", productId, "update_unit_profile", null, changes));
        return profile(p);
    }

    /** {@code quantity} of {@code uomId} (default: the base unit) in every unit the product converts to. */
    @Transactional(readOnly = true)
    public Map<String, Object> equivalents(String productId, BigDecimal quantity, String uomId) {
        Product p = require(productId);
        BigDecimal qty = quantity == null ? BigDecimal.ONE : quantity;
        String from = uomId == null || uomId.isBlank() ? p.getUomId() : uomId;
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(p.getUomId());
        candidates.add(p.getSalesUomId());
        candidates.add(p.getPurchaseUomId());
        for (ProductUom u : em.createQuery("select u from ProductUom u where u.productId = :p and u.isActive = true",
                ProductUom.class).setParameter("p", productId).getResultList()) {
            candidates.add(u.getUomId());
        }
        for (ProductUomConversion c : bridges(productId)) {
            candidates.add(c.getFromUomId());
            candidates.add(c.getToUomId());
        }
        for (String code : List.of("KG", "TON", "M3")) {
            UnitOfMeasure u = findByCode(code);
            if (u != null) {
                candidates.add(u.getId());
            }
        }
        candidates.remove(null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String id : candidates) {
            BigDecimal rate = conversion.conversionRate(productId, from, id);
            UnitOfMeasure u = rate == null ? null : uom(id);
            if (u != null) {
                rows.add(Obj.of("uomId", id, "code", u.getCode(), "name", u.getName(), "symbol", u.getSymbol(),
                        "quantity", JsNumbers.js(UomConversionService.roundToStep(qty.multiply(rate,
                                UomConversionService.DECIMAL_JS), u.getRoundingPrecision())),
                        "isBase", id.equals(p.getUomId())));
            }
        }
        return Obj.of("productId", productId, "quantity", JsNumbers.js(qty), "uomId", from, "units", rows);
    }

    private Map<String, Object> profile(Product p) {
        String id = p.getId();
        return Obj.of("productId", id, "baseUomId", p.getUomId(),
                "densityTonnesPerM3", rate(id, "M3", "TON", false),
                "kgPerPiece", rate(id, "UNIT", "KG", false),
                "kgPerBag", rate(id, "BAG", "KG", true),
                "tonnesPerTruckload", rate(id, "TRUCK", "TON", true));
    }

    /** Declared rate, or null. {@code declaredOnly}: only a unit the product really declares has a size. */
    private Number rate(String productId, String fromCode, String toCode, boolean declaredOnly) {
        UnitOfMeasure from = findByCode(fromCode);
        UnitOfMeasure to = findByCode(toCode);
        if (from == null || to == null) {
            return null;
        }
        Product p = require(productId);
        if (declaredOnly && !from.getId().equals(p.getUomId()) && own(productId, from.getId()) == null) {
            return null;
        }
        BigDecimal r = conversion.conversionRate(productId, from.getId(), to.getId());
        return r == null ? null : JsNumbers.js(r.setScale(6, RoundingMode.HALF_UP));
    }

    private void saveDensity(Product p, UnitOfMeasure base, BigDecimal density) {
        UnitOfMeasure m3 = byCode("M3");
        UnitOfMeasure ton = byCode("TON");
        saveBridge(p, m3, ton, density);
        if (density.signum() <= 0) {
            return;
        }
        // A ProductUom row wins over the bridge in UomConversionService: keep it telling the same story.
        ProductUom ownM3 = own(p.getId(), m3.getId());
        if (ownM3 != null && sameCategory(base, ton)) {
            ownM3.setFactor(density.multiply(ton.getFactor(), UomConversionService.DECIMAL_JS)
                    .divide(base.getFactor(), UomConversionService.DECIMAL_JS));
        }
        ProductUom ownTon = own(p.getId(), ton.getId());
        if (ownTon != null && sameCategory(base, m3)) {
            ownTon.setFactor(m3.getFactor().divide(base.getFactor(), UomConversionService.DECIMAL_JS)
                    .divide(density, UomConversionService.DECIMAL_JS));
        }
    }

    private void saveBridge(Product p, UnitOfMeasure from, UnitOfMeasure to, BigDecimal factor) {
        String productId = p.getId();
        em.createQuery("delete from ProductUomConversion c where c.productId = :p and c.organizationId = :org"
                        + " and c.fromUomId = :f and c.toUomId = :t")
                .setParameter("p", productId).setParameter("org", TenantContext.organizationId())
                .setParameter("f", to.getId()).setParameter("t", from.getId()).executeUpdate();
        List<ProductUomConversion> rows = em.createQuery("select c from ProductUomConversion c where c.productId = :p"
                        + " and c.fromUomId = :f and c.toUomId = :t", ProductUomConversion.class)
                .setParameter("p", productId).setParameter("f", from.getId()).setParameter("t", to.getId())
                .getResultList();
        if (factor.signum() <= 0) {
            rows.forEach(em::remove);
            return;
        }
        if (!rows.isEmpty()) {
            rows.getFirst().setFactor(factor);
            return;
        }
        ProductUomConversion c = new ProductUomConversion();
        c.setOrganizationId(TenantContext.organizationId());
        c.setProductId(productId);
        c.setFromUomId(from.getId());
        c.setToUomId(to.getId());
        c.setFactor(factor);
        em.persist(c);
    }

    /** A pack unit of {@code size} {@code sizeCode} units, as a {@code ProductUom} row (or a bridge on the base unit). */
    private void savePack(Product p, UnitOfMeasure base, UnitOfMeasure pack, String sizeCode, BigDecimal size) {
        UnitOfMeasure sizeUnit = byCode(sizeCode);
        if (pack.getId().equals(base.getId())) {
            saveBridge(p, pack, sizeUnit, size);
            return;
        }
        ProductUom row = own(p.getId(), pack.getId());
        if (size.signum() <= 0) {
            if (row != null) {
                em.remove(row);
            }
            return;
        }
        em.flush();
        BigDecimal baseRate = conversion.conversionRate(p.getId(), sizeUnit.getId(), base.getId());
        if (baseRate == null || baseRate.signum() <= 0) {
            throw ApiException.badRequest("Cannot size " + pack.getCode() + " in " + sizeUnit.getCode() + ": product "
                    + p.getName() + " in " + base.getCode() + " has no weight. Set its density or piece weight first.");
        }
        BigDecimal factor = size.multiply(baseRate, UomConversionService.DECIMAL_JS);
        if (row != null) {
            row.setFactor(factor);
            return;
        }
        ProductUom u = new ProductUom();
        u.setOrganizationId(TenantContext.organizationId());
        u.setProductId(p.getId());
        u.setUomId(pack.getId());
        u.setFactor(factor);
        u.setIsPurchase(true);
        u.setIsSales(true);
        u.setIsActive(true);
        em.persist(u);
    }

    private List<ProductUomConversion> bridges(String productId) {
        return em.createQuery("select c from ProductUomConversion c where c.productId = :p", ProductUomConversion.class)
                .setParameter("p", productId).getResultList();
    }

    private ProductUom own(String productId, String uomId) {
        return em.createQuery("select u from ProductUom u where u.productId = :p and u.uomId = :u", ProductUom.class)
                .setParameter("p", productId).setParameter("u", uomId).getResultList().stream().findFirst()
                .orElse(null);
    }

    private UnitOfMeasure uom(String id) {
        return em.createQuery("select u from UnitOfMeasure u where u.id = :id", UnitOfMeasure.class)
                .setParameter("id", id).getResultList().stream().findFirst().orElse(null);
    }

    private UnitOfMeasure findByCode(String code) {
        return em.createQuery("select u from UnitOfMeasure u where u.organizationId = :org and upper(u.code) = :code"
                        + " and u.deletedAt is null", UnitOfMeasure.class)
                .setParameter("org", TenantContext.organizationId()).setParameter("code", code).getResultList().stream()
                .findFirst().orElse(null);
    }

    private UnitOfMeasure byCode(String code) {
        UnitOfMeasure u = findByCode(code);
        if (u == null) {
            throw ApiException.badRequest("Unit " + code + " is missing in Units of Measure");
        }
        return u;
    }

    private Product require(String productId) {
        return em.createQuery("select p from Product p where p.id = :id", Product.class).setParameter("id", productId)
                .getResultList().stream().findFirst().orElseThrow(() -> ApiException.notFound("Product not found"));
    }

    private static boolean sameCategory(UnitOfMeasure a, UnitOfMeasure b) {
        if (a.getCategoryId() != null && b.getCategoryId() != null) {
            return a.getCategoryId().equals(b.getCategoryId());
        }
        return Objects.equals(a.getCategory(), b.getCategory());
    }
}
