package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.Product;
import com.inventoryerp.core.domain.ProductCategory;
import com.inventoryerp.core.domain.ProductPackaging;
import com.inventoryerp.core.domain.ProductType;
import com.inventoryerp.core.domain.Tax;
import com.inventoryerp.core.domain.UnitOfMeasure;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BeverageMath;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Port of {@code product.service.ts}: include {@code category, uom, purchaseUom, salesUom, packagings,
 * tax}; beverage (digital-weight) config computed on write; inventory-tracking toggles normalized and
 * defaulted from org settings on create; packaging rows replaced on save; soft delete + restore;
 * barcode resolution and picker search.
 */
@Service
public class ProductService extends BaseCrudService<Product> {

    /** Fields that, when touched, require the beverage config to be (re)validated. */
    private static final List<String> BEVERAGE_FIELDS = List.of("measurementMethod", "containerVolumeMl",
            "emptyBottleWeightG", "actualEmptyWeightG", "fullBottleWeightG");

    /** Fields that, when touched, require the inventory-tracking config to be re-normalized. */
    private static final List<String> INVENTORY_CONFIG_FIELDS = List.of("batchTracking", "expiryTracking",
            "serialTracking", "pickingStrategy", "costingMethod");

    private static final Pattern UUID = Pattern.compile(
            "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})", Pattern.CASE_INSENSITIVE);

    /** One packaging row ({@code PackagingDto}). */
    public record Packaging(String id, String name, BigDecimal quantity, String barcode, Boolean isActive) {}

    private final AuditService audit;
    private final EventBus events;
    private final FilesService files;

    public ProductService(EntityManager em, AuditService audit, EventBus events, FilesService files) {
        super(em, Product.class);
        this.audit = audit;
        this.events = events;
        this.files = files;
    }

    @Override
    protected String entityName() {
        return "Product";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("code", "sku", "name");
    }

    @Override
    protected List<Map<String, Object>> render(List<Product> rows) {
        List<Object> ids = ids(rows);
        Map<Object, List<Map<String, Object>>> packs = ids.isEmpty() ? Map.of() : groupBy(em.createQuery(
                        "select p from ProductPackaging p where p.productId in :ids", ProductPackaging.class)
                .setParameter("ids", ids).getResultList(), "productId");
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Product p : rows) {
            Map<String, Object> m = EntityJson.of(p);
            m.put("category", one(em, ProductCategory.class, p.getCategoryId()));
            m.put("uom", one(em, UnitOfMeasure.class, p.getUomId()));
            m.put("purchaseUom", one(em, UnitOfMeasure.class, p.getPurchaseUomId()));
            m.put("salesUom", one(em, UnitOfMeasure.class, p.getSalesUomId()));
            m.put("packagings", packs.getOrDefault(p.getId(), List.of()));
            m.put("tax", one(em, Tax.class, p.getTaxId()));
            out.add(m);
        }
        return out;
    }

    /** Resolve a stored image value to a fresh signed download URL (bare UUID, signed path or absolute URL). */
    private String resolveImage(String image) {
        if (image == null || image.isEmpty()) {
            return null;
        }
        if (image.startsWith("http")) {
            return image;
        }
        Matcher m = UUID.matcher(image);
        if (!m.find()) {
            return image;
        }
        try {
            return (String) files.signDownload(m.group(1)).get("url");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code GET /products}: active products only, optional category/type filters, signed images. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(PageQuery query, String categoryId, String productType) {
        PageResult<Map<String, Object>> page = list(query, (cb, root, p) -> {
            p.add(cb.isTrue(root.get("isActive")));
            if (categoryId != null && !categoryId.isEmpty()) {
                p.add(cb.equal(root.get("categoryId"), categoryId));
            }
            if (productType != null && !productType.isEmpty()) {
                p.add(cb.equal(root.get("productType"), DbEnum.fromValue(ProductType.class, productType)));
            }
        });
        page.data().forEach(m -> m.put("image", resolveImage((String) m.get("image"))));
        return page;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        Map<String, Object> out = render(require(id));
        out.put("image", resolveImage((String) out.get("image")));
        return out;
    }

    /** Create; {@code packagings} rows are written after the insert (so the response lists none). */
    @Transactional
    public Map<String, Object> create(Record dto, List<Packaging> packagings) {
        Map<String, Object> data = applyBeverageComputation(EntityPatch.data(dto, "packagings"), null);
        applyOrgInventoryDefaults(data);
        applyInventoryConfig(data, null);
        Product row = EntityPatch.apply(newInstance(), data);
        Map<String, Object> product = render(persist(row));
        if (packagings != null) {
            syncPackagings(row.getId(), packagings);
        }
        String orgId = TenantContext.organizationId();
        AfterCommit.run(() -> {
            events.publish(DomainEvents.PRODUCT_CREATED, Map.of("id", row.getId(), "organizationId", orgId));
            audit.record(AuditInput.of("Product", row.getId(), "create", null, product));
        });
        return product;
    }

    @Transactional
    public Map<String, Object> update(String id, Record dto, List<Packaging> packagings) {
        Map<String, Object> data = EntityPatch.data(dto, "packagings");
        boolean beverageTouched = BEVERAGE_FIELDS.stream().anyMatch(data::containsKey);
        boolean inventoryTouched = INVENTORY_CONFIG_FIELDS.stream().anyMatch(data::containsKey);
        if (beverageTouched || inventoryTouched) {
            Product existing = find(id);
            if (beverageTouched) {
                applyBeverageComputation(data, existing);
            }
            if (inventoryTouched) {
                applyInventoryConfig(data, existing);
            }
        }
        Product row = require(id);
        EntityPatch.apply(row, data);
        em.flush();
        Map<String, Object> product = findOne(id);
        if (packagings != null) {
            syncPackagings(id, packagings);
        }
        String orgId = TenantContext.organizationId();
        AfterCommit.run(() -> {
            events.publish(DomainEvents.PRODUCT_UPDATED, Map.of("id", id, "organizationId", orgId));
            audit.record(AuditInput.of("Product", id, "update", null, product));
        });
        return product;
    }

    @Override
    @Transactional
    public void remove(String id) {
        Product row = require(id);
        row.setDeletedAt(Instant.now());
        row.setIsActive(false);
        em.flush();
        String orgId = TenantContext.organizationId();
        AfterCommit.run(() -> {
            events.publish(DomainEvents.PRODUCT_DELETED, Map.of("id", id, "organizationId", orgId));
            audit.record(AuditInput.of("Product", id, "delete"));
        });
    }

    @Transactional
    public Map<String, Object> restore(String id) {
        Product row = withDeleted(em, () -> em.createQuery(
                        "select p from Product p where p.id = :id and p.deletedAt is not null", Product.class)
                .setParameter("id", id).getResultList().stream().findFirst().orElse(null));
        if (row == null) {
            throw ApiException.notFound("Deleted Product " + id + " not found");
        }
        row.setDeletedAt(null);
        row.setIsActive(true);
        em.flush();
        Map<String, Object> out = EntityJson.of(row);
        String orgId = TenantContext.organizationId();
        AfterCommit.run(() -> {
            events.publish("product.restored", Map.of("id", id, "organizationId", orgId));
            audit.record(AuditInput.of("Product", id, "restore"));
        });
        return out;
    }

    /**
     * Resolve a scanned barcode: the product's own barcode (qty 1) first, then an active packaging barcode
     * (qty = pack size). {@code null} when not found.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resolveBarcode(String code) {
        if (code == null || code.isEmpty()) {
            return null;
        }
        Product product = em.createQuery("select p from Product p where p.barcode = :code and p.isActive = true",
                        Product.class)
                .setParameter("code", code).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
        if (product != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("productId", product.getId());
            out.put("product", withUom(product));
            out.put("quantity", 1);
            out.put("packaging", null);
            return out;
        }
        ProductPackaging pack = em.createQuery(
                        "select p from ProductPackaging p where p.barcode = :code and p.isActive = true",
                        ProductPackaging.class)
                .setParameter("code", code).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
        if (pack != null) {
            Number qty = jsNumber(pack.getQuantity());
            Map<String, Object> packaging = new LinkedHashMap<>();
            packaging.put("id", pack.getId());
            packaging.put("name", pack.getName());
            packaging.put("quantity", qty);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("productId", pack.getProductId());
            out.put("product", withUom(em.find(Product.class, pack.getProductId())));
            out.put("quantity", qty);
            out.put("packaging", packaging);
            return out;
        }
        return null;
    }

    /** Picker search: active products by name/code/sku, name order, narrow select. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String q, int pageSize) {
        StringBuilder jpql = new StringBuilder("select p from Product p where p.organizationId = :org and p.isActive = true");
        if (q != null && !q.isEmpty()) {
            jpql.append(" and (lower(p.name) like :q escape '\\' or lower(p.code) like :q escape '\\'"
                    + " or lower(p.sku) like :q escape '\\')");
        }
        jpql.append(" order by p.name asc");
        var query = em.createQuery(jpql.toString(), Product.class).setParameter("org", TenantContext.organizationId());
        if (q != null && !q.isEmpty()) {
            query.setParameter("q", "%" + escapeLike(q.toLowerCase()) + "%");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Product p : query.setMaxResults(pageSize).getResultList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("salesPrice", p.getSalesPrice());
            m.put("productType", p.getProductType());
            m.put("station", p.getStation());
            m.put("categoryId", p.getCategoryId());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ rules

    private Map<String, Object> withUom(Product p) {
        Map<String, Object> m = EntityJson.of(p);
        m.put("uom", one(em, UnitOfMeasure.class, p.getUomId()));
        return m;
    }

    /**
     * Compute + validate the digital-weight config (liquidWeightG, conversionFactorMlPerG); clear them when a
     * product is switched away from digital_weight. Partial edits merge with the existing row.
     */
    private static Map<String, Object> applyBeverageComputation(Map<String, Object> data, Product existing) {
        Object methodValue = data.get("measurementMethod") != null ? data.get("measurementMethod")
                : existing != null && existing.getMeasurementMethod() != null ? existing.getMeasurementMethod().value()
                : "count";
        String method = methodValue instanceof DbEnum e ? e.value() : String.valueOf(methodValue);
        if (!"digital_weight".equals(method)) {
            Object m = data.get("measurementMethod");
            if (m != null && !"digital_weight".equals(m instanceof DbEnum e ? e.value() : String.valueOf(m))) {
                data.put("liquidWeightG", null);
                data.put("conversionFactorMlPerG", null);
            }
            return data;
        }
        BeverageMath.BottleConfig cfg = BeverageMath.computeBottleConfig(new BeverageMath.BottleInput(
                pick(data, "containerVolumeMl", existing == null ? null : existing.getContainerVolumeMl()),
                pick(data, "emptyBottleWeightG", existing == null ? null : existing.getEmptyBottleWeightG()),
                pick(data, "actualEmptyWeightG", existing == null ? null : existing.getActualEmptyWeightG()),
                pick(data, "fullBottleWeightG", existing == null ? null : existing.getFullBottleWeightG())));
        data.put("liquidWeightG", cfg.liquidWeightG());
        data.put("conversionFactorMlPerG", cfg.conversionFactorMlPerG());
        return data;
    }

    /** {@code pick(a, b) = a !== undefined ? a : b} (an explicit null wins). */
    private static Object pick(Map<String, Object> data, String key, Object fallback) {
        return data.containsKey(key) ? data.get(key) : fallback;
    }

    /**
     * Normalize the inventory-tracking toggles: expiry ⇒ batch; FIFO ⇒ batch; SPECIFIC ⇒ serial unless
     * batch/serial already on; SERIAL picking ⇒ serial. Partial edits merge with the existing row.
     */
    private static void applyInventoryConfig(Map<String, Object> data, Product existing) {
        Boolean batch = (Boolean) eff(data, "batchTracking", existing == null ? null : existing.getBatchTracking());
        Boolean expiry = (Boolean) eff(data, "expiryTracking", existing == null ? null : existing.getExpiryTracking());
        Boolean serial = (Boolean) eff(data, "serialTracking", existing == null ? null : existing.getSerialTracking());
        String costing = enumValue(eff(data, "costingMethod", existing == null ? null : existing.getCostingMethod()));
        String picking = enumValue(eff(data, "pickingStrategy", existing == null ? null : existing.getPickingStrategy()));

        if (Boolean.TRUE.equals(expiry)) {
            batch = true;
        }
        if ("FIFO".equals(costing)) {
            batch = true;
        }
        if ("SPECIFIC".equals(costing) && !Boolean.TRUE.equals(serial) && !Boolean.TRUE.equals(batch)) {
            serial = true;
        }
        if ("SERIAL".equals(picking)) {
            serial = true;
        }
        if (batch != null) {
            data.put("batchTracking", batch);
        }
        if (serial != null) {
            data.put("serialTracking", serial);
        }
    }

    /** {@code data[k] !== undefined ? data[k] : existing?.[k]}. */
    private static Object eff(Map<String, Object> data, String key, Object fallback) {
        return data.containsKey(key) ? data.get(key) : fallback;
    }

    private static String enumValue(Object v) {
        return v == null ? null : v instanceof DbEnum e ? e.value() : String.valueOf(v);
    }

    /** Fill omitted inventory-config fields from org settings ({@code inventory.default*}); create only. */
    private void applyOrgInventoryDefaults(Map<String, Object> data) {
        String[][] fieldKeys = {
            {"costingMethod", "inventory.defaultCostingMethod"},
            {"pickingStrategy", "inventory.defaultPickingStrategy"},
            {"batchTracking", "inventory.defaultBatchTracking"},
            {"expiryTracking", "inventory.defaultExpiryTracking"},
            {"serialTracking", "inventory.defaultSerialTracking"},
        };
        List<String[]> missing = new ArrayList<>();
        for (String[] fk : fieldKeys) {
            if (!data.containsKey(fk[0])) {
                missing.add(fk);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT key, value::text FROM \"Setting\" WHERE \"organizationId\" = ?1"
                        + " AND scope = 'organization' AND key IN (?2)")
                .setParameter(1, TenantContext.organizationId())
                .setParameter(2, missing.stream().map(fk -> fk[1]).toList())
                .getResultList();
        Map<String, Object> byKey = new HashMap<>();
        for (Object[] r : rows) {
            JsonNode node = EntityJson.tree((String) r[1]);
            byKey.put((String) r[0], node == null || node.isNull() ? null
                    : node.isBoolean() ? node.booleanValue() : node.isNumber() ? node.numberValue() : node.asString());
        }
        for (String[] fk : missing) {
            Object v = byKey.get(fk[1]);
            if (v != null) {
                data.put(fk[0], v);
            }
        }
    }

    /** Replace a product's packaging rows with the supplied set (delete-all + create). */
    private void syncPackagings(String productId, List<Packaging> rows) {
        String orgId = TenantContext.organizationId();
        em.createQuery("delete from ProductPackaging p where p.productId = :pid and p.organizationId = :org")
                .setParameter("pid", productId).setParameter("org", orgId).executeUpdate();
        for (Packaging r : rows) {
            ProductPackaging p = new ProductPackaging();
            p.setOrganizationId(orgId);
            p.setProductId(productId);
            p.setName(r.name());
            p.setQuantity(r.quantity());
            p.setBarcode(r.barcode() == null || r.barcode().isEmpty() ? null : r.barcode());
            p.setIsActive(r.isActive() == null || r.isActive());
            em.persist(p);
        }
        em.flush();
    }

    /** JS {@code Number(decimal)}: integral values as long, others as double. */
    private static Number jsNumber(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() <= 0 ? (Number) s.longValueExact() : (Number) v.doubleValue();
    }
}
