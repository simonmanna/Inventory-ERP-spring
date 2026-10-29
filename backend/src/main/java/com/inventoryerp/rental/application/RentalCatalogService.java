package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalPackage;
import com.inventoryerp.rental.domain.RentalPackageItem;
import com.inventoryerp.rental.domain.RentalRate;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rate cards and rental packages (port of {@code rental-catalog.service.ts}). A rate card is a set of tiered bands
 * per product + period; {@link #resolveRate} picks the highest-priority band containing the unit count. Bands and
 * packages are soft-deleted masters.
 */
@Service
public class RentalCatalogService {

    /** A resolved band. */
    public record ResolvedRate(String rateId, double unitRate) {}

    private final RentalRows rows;
    private final PosDb db;

    public RentalCatalogService(RentalRows rows) {
        this.rows = rows;
        this.db = rows.db();
    }

    /** {@code GET /rental/catalog}: {@code {rates, packages}}. */
    @Transactional(readOnly = true)
    public Map<String, Object> catalog(String productId, boolean includeInactive) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rates", listRates(productId));
        out.put("packages", listPackages(includeInactive));
        return out;
    }

    // ------------------------------------------------------------------ rates

    List<Map<String, Object>> listRates(String productId) {
        String orgId = RentalRows.org();
        String where = "\"organizationId\" = ? AND \"deletedAt\" IS NULL";
        if (productId != null && !productId.isEmpty()) {
            return db.list(RentalRate.class, where + " AND \"productId\" = ? ORDER BY \"productId\" ASC, period ASC,"
                    + " \"minUnits\" ASC", orgId, productId);
        }
        return db.list(RentalRate.class, where + " ORDER BY \"productId\" ASC, period ASC, \"minUnits\" ASC", orgId);
    }

    /** Create or update the band keyed by (product, period, minUnits). */
    @Transactional
    public Map<String, Object> upsertRate(Map<String, Object> dto) {
        String orgId = RentalRows.org();
        if (rows.product(dto.get("productId")) == null) {
            throw ApiException.notFound("Product not found");
        }
        double price = RentalRows.num(dto, "price");
        if (!(price > 0)) {
            throw ApiException.badRequest("Rate price must be positive");
        }
        return upsert(orgId, (String) dto.get("productId"), RentalRows.str(dto, "period"), dto.get("minUnits"),
                dto.get("maxUnits"), price, dto.get("priority"), dto.get("isActive"));
    }

    private Map<String, Object> upsert(String orgId, String productId, String period, Object minUnits,
            Object maxUnits, double price, Object priority, Object isActive) {
        if (period == null || minUnits == null) {
            throw new IllegalStateException("Argument `period`/`minUnits` is missing.");
        }
        int min = intArg(minUnits, "minUnits");
        Integer max = maxUnits == null ? null : intArg(maxUnits, "maxUnits");
        int prio = priority == null ? 100 : intArg(priority, "priority");
        boolean active = isActive == null || Boolean.TRUE.equals(isActive);
        if (isActive != null && !(isActive instanceof Boolean)) {
            throw new IllegalStateException("Argument `isActive`: Invalid value provided. Expected Boolean.");
        }
        return SqlRows.one(RentalRate.class, db.jdbc().sql("""
                        INSERT INTO "RentalRate" (id, "organizationId", "productId", period, "minUnits", "maxUnits", price,
                          priority, "isActive", "createdBy", "createdAt", "updatedAt")
                        VALUES (gen_random_uuid()::text, ?, ?, ?::"RentalRatePeriod", ?, ?, ?, ?, ?, ?, %s, %s)
                        ON CONFLICT ("organizationId", "productId", period, "minUnits") DO UPDATE SET
                          "maxUnits" = EXCLUDED."maxUnits", price = EXCLUDED.price, priority = EXCLUDED.priority,
                          "isActive" = EXCLUDED."isActive", "updatedBy" = ?, "updatedAt" = %s
                        RETURNING *""".formatted(RentalRows.NOW, RentalRows.NOW, RentalRows.NOW))
                .params(orgId, productId, period, min, max, RentalRows.dec(price), prio, active, RentalRows.user(),
                        RentalRows.user()));
    }

    private static int intArg(Object v, String name) {
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        throw new IllegalStateException("Argument `" + name + "`: Invalid value provided. Expected Int.");
    }

    @Transactional
    public Map<String, Object> updateRate(String id, Map<String, Object> dto) {
        String orgId = RentalRows.org();
        Map<String, Object> rate = db.first(RentalRate.class, "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL",
                id, orgId);
        if (rate == null) {
            throw ApiException.notFound("Rate not found");
        }
        if (rows.product(rate.get("productId")) == null) {
            throw ApiException.notFound("Product not found");
        }
        double price = dto.get("price") != null ? RentalRows.num(dto, "price")
                : SqlRows.dec(rate, "price").doubleValue();
        if (!(price > 0)) {
            throw ApiException.badRequest("Rate price must be positive");
        }
        return upsert(orgId, (String) rate.get("productId"), (String) rate.get("period"),
                dto.get("minUnits") != null ? dto.get("minUnits") : rate.get("minUnits"),
                dto.get("maxUnits") != null ? dto.get("maxUnits") : rate.get("maxUnits"), price,
                dto.get("priority") != null ? dto.get("priority") : rate.get("priority"),
                dto.get("isActive") != null ? dto.get("isActive") : rate.get("isActive"));
    }

    @Transactional
    public Map<String, Object> deleteRate(String id) {
        String orgId = RentalRows.org();
        if (db.first(RentalRate.class, "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", id, orgId) == null) {
            throw ApiException.notFound("Rate not found");
        }
        return db.update(RentalRate.class, Obj.of("deletedAt", PosDb.raw(RentalRows.NOW), "updatedBy", RentalRows.user()),
                "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL", id, orgId);
    }

    /**
     * Applicable unit rate for a product / period / unit count: bands with {@code minUnits <= units <= maxUnits}
     * (open-ended when null), highest priority (lowest number) first, ties by the largest minUnits.
     */
    @Transactional(readOnly = true)
    public ResolvedRate resolveRate(String productId, String period, double units) {
        List<Map<String, Object>> bands = db.list(RentalRate.class, "\"organizationId\" = ? AND \"productId\" = ? AND"
                + " period::text = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL ORDER BY priority ASC,"
                + " \"minUnits\" DESC", RentalRows.org(), productId, period);
        Map<String, Object> b = RentalMath.pickBand(bands, units);
        return b == null ? null : new ResolvedRate((String) b.get("id"), SqlRows.dec(b, "price").doubleValue());
    }

    // ------------------------------------------------------------------ packages

    List<Map<String, Object>> listPackages(boolean includeInactive) {
        List<Map<String, Object>> pkgs = db.list(RentalPackage.class, "\"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + (includeInactive ? "" : " AND \"isActive\" = true") + " ORDER BY name ASC", RentalRows.org());
        for (Map<String, Object> p : pkgs) {
            p.put("items", RentalRows.with(items(p.get("id")), "product", it -> rows.productBrief(it.get("productId"))));
        }
        return pkgs;
    }

    private List<Map<String, Object>> items(Object packageId) {
        return db.list(RentalPackageItem.class, "\"packageId\" = ?", packageId);
    }

    @Transactional
    public Map<String, Object> createPackage(Map<String, Object> dto) {
        String orgId = RentalRows.org();
        if (rows.product(dto.get("productId")) == null) {
            throw ApiException.notFound("Package product not found");
        }
        List<Map<String, Object>> items = RentalRows.lines(dto, "items");
        Map<String, Object> pkg = db.insert(RentalPackage.class, Obj.of("organizationId", orgId, "code", dto.get("code"),
                "name", dto.get("name"), "description", dto.get("description"), "productId", dto.get("productId"),
                "createdBy", RentalRows.user()));
        insertItems(orgId, (String) pkg.get("id"), items);
        pkg.put("items", items(pkg.get("id")));
        return pkg;
    }

    private void insertItems(String orgId, String packageId, List<Map<String, Object>> items) {
        for (Map<String, Object> it : items) {
            db.insert(RentalPackageItem.class, Obj.of("organizationId", orgId, "packageId", packageId,
                    "productId", it.get("productId"),
                    "quantity", RentalRows.dec(RentalRows.numOr(it, "quantity", 1)),
                    "role", it.get("role") != null ? it.get("role") : "accessory",
                    "isRequired", it.get("isRequired") != null ? it.get("isRequired") : true,
                    "priceMode", it.get("priceMode") != null ? it.get("priceMode") : "included"));
        }
    }

    @Transactional
    public Map<String, Object> updatePackage(String id, Map<String, Object> dto) {
        String orgId = RentalRows.org();
        String where = "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL";
        if (db.first(RentalPackage.class, where, id, orgId) == null) {
            throw ApiException.notFound("Package not found");
        }
        List<Map<String, Object>> items = dto.get("items") != null ? RentalRows.lines(dto, "items") : null;
        if (items != null) {
            db.jdbc().sql("DELETE FROM \"RentalPackageItem\" WHERE \"packageId\" = ? AND \"organizationId\" = ?")
                    .params(id, orgId).update();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : List.of("name", "description", "isActive")) {
            if (dto.containsKey(k)) {
                data.put(k, dto.get(k));
            }
        }
        data.put("updatedBy", RentalRows.user());
        Map<String, Object> pkg = db.update(RentalPackage.class, data, where, id, orgId);
        if (items != null) {
            insertItems(orgId, id, items);
        }
        pkg.put("items", items(id));
        return pkg;
    }

    @Transactional
    public Map<String, Object> deletePackage(String id) {
        String orgId = RentalRows.org();
        String where = "id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL";
        if (db.first(RentalPackage.class, where, id, orgId) == null) {
            throw ApiException.notFound("Package not found");
        }
        return db.update(RentalPackage.class, Obj.of("deletedAt", PosDb.raw(RentalRows.NOW), "isActive", false,
                "updatedBy", RentalRows.user()), where, id, orgId);
    }
}
