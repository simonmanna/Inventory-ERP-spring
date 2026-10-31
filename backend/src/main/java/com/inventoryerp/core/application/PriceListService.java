package com.inventoryerp.core.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Named price lists (SALES-3). It lives in {@code core} because invoicing and orders need it and {@code crm} sits above
 * both. A list is either the default for everyone or assigned to customers and customer categories, optionally limited
 * to one branch and a validity window. An item prices a product, a category or everything, optionally per unit, site,
 * delivery zone and minimum quantity, as a unit price, a discount off the catalogue price, or both.
 *
 * <p>Pricing itself belongs to the one sales pricing engine (ADR-S022): {@link #resolve} asks {@link PricingEngine},
 * where these lists are the {@code price_list} tier. {@link #namedListPrice} is that tier: customer list over category
 * list over default list; then list priority; then the branch list, site, product, exact unit and largest quantity
 * break that apply. Callers ask only for lines with no price of their own (a POS terminal price is kept).
 */
@Service
public class PriceListService {

    private static final Set<String> TIERS = Set.of("retail", "wholesale", "contract", "negotiated");

    /** One line to price; {@code uomId} null means the product's default sales unit. */
    public record PriceQuery(String partnerId, String productId, BigDecimal quantity, String uomId, String branchId,
            String siteId, LocalDate on) {}

    /** The match. {@code unitPrice} is per the requested unit; null means the catalogue price with this discount. */
    public record ListPrice(BigDecimal unitPrice, BigDecimal discountPercent, String priceListId, String priceListName) {}

    public record ItemInput(String productId, String productCategoryId, String uomId, String siteId, String zone,
            BigDecimal minQuantity, BigDecimal unitPrice, BigDecimal discountPercent) {}

    public record ListInput(String code, String name, String tier, Boolean isDefault, String branchId, LocalDate validFrom,
            LocalDate validTo, Integer priority, Boolean isActive, String notes, List<ItemInput> items,
            List<String> partnerIds, List<String> partnerCategoryIds) {}

    private final JdbcClient jdbc;
    private final UomConversionService units;
    private final AuditService audit;
    private final ObjectProvider<PricingEngine> engine;

    public PriceListService(JdbcClient jdbc, UomConversionService units, AuditService audit,
            ObjectProvider<PricingEngine> engine) {
        this.jdbc = jdbc;
        this.units = units;
        this.audit = audit;
        this.engine = engine;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ------------------------------------------------------------------ resolution

    /**
     * The price of one line from the central pricing engine, or {@code null} when only the catalogue price applies
     * (the caller keeps its own catalogue fallback, in its own unit).
     */
    @Transactional(readOnly = true)
    public ListPrice resolve(PriceQuery q) {
        PricingEngine e = engine.getIfAvailable();
        if (e == null) {
            return namedListPrice(q);
        }
        PricingEngine.Quote quote = e.quote(q);
        if (quote == null || "list".equals(quote.source())) {
            return null;
        }
        return new ListPrice(quote.unitPrice(), quote.discountPercent(), quote.ruleId(), quote.ruleName());
    }

    /** The named price list tier: the list price of one line, or {@code null} when no list applies. */
    @Transactional(readOnly = true)
    public ListPrice namedListPrice(PriceQuery q) {
        Map<String, Object> product = SqlRows.one(jdbc.sql("SELECT \"categoryId\", \"uomId\", \"salesUomId\" FROM \"Product\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(q.productId(), org()));
        if (product == null) {
            return null;
        }
        String defaultUom = product.get("salesUomId") != null ? (String) product.get("salesUomId")
                : (String) product.get("uomId");
        String requested = blank(q.uomId()) != null ? q.uomId() : defaultUom;
        String partnerCategory = blank(q.partnerId()) == null ? null : jdbc.sql("SELECT \"categoryId\" FROM \"Partner\""
                + " WHERE id = ? AND \"organizationId\" = ?").params(q.partnerId(), org()).query(String.class).optional()
                .orElse(null);
        String zone = blank(q.siteId()) == null ? null : jdbc.sql("SELECT zone FROM \"FleetCustomerSite\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(q.siteId(), org()).query(String.class).optional().orElse(null);
        java.sql.Date on = java.sql.Date.valueOf(q.on() == null ? LocalDate.now() : q.on());
        BigDecimal qty = q.quantity() == null ? BigDecimal.ONE : q.quantity();
        List<Map<String, Object>> candidates = SqlRows.list(jdbc.sql("""
                SELECT i."unitPrice", i."discountPercent", i."uomId", i."minQuantity", l.id AS "listId", l.name AS "listName"
                  FROM "PriceListItem" i JOIN "PriceList" l ON l.id = i."priceListId"
                  LEFT JOIN "PriceListAssignment" a ON a."priceListId" = l.id
                       AND (a."partnerId" = ? OR a."partnerCategoryId" = ?)
                 WHERE l."organizationId" = ? AND l."isActive" AND l."deletedAt" IS NULL
                   AND (l."validFrom" IS NULL OR l."validFrom" <= ?) AND (l."validTo" IS NULL OR l."validTo" >= ?)
                   AND (l."branchId" IS NULL OR l."branchId" = ?)
                   AND (l."isDefault" OR a.id IS NOT NULL)
                   AND (i."productId" = ? OR i."productCategoryId" = ?
                        OR (i."productId" IS NULL AND i."productCategoryId" IS NULL))
                   AND (i."siteId" IS NULL OR i."siteId" = ?) AND (i.zone IS NULL OR i.zone = ?)
                   AND (i."uomId" IS NULL OR i."uomId" = ?)
                 ORDER BY CASE WHEN a."partnerId" IS NOT NULL THEN 3 WHEN a."partnerCategoryId" IS NOT NULL THEN 2 ELSE 1 END DESC,
                          l.priority DESC,
                          CASE WHEN l."branchId" IS NOT NULL THEN 1 ELSE 0 END DESC,
                          CASE WHEN i."siteId" IS NOT NULL THEN 2 WHEN i.zone IS NOT NULL THEN 1 ELSE 0 END DESC,
                          CASE WHEN i."productId" IS NOT NULL THEN 2 WHEN i."productCategoryId" IS NOT NULL THEN 1 ELSE 0 END DESC,
                          CASE WHEN i."uomId" IS NOT NULL THEN 1 ELSE 0 END DESC,
                          i."minQuantity" DESC
                 LIMIT 100""").params(nz(q.partnerId()), nz(partnerCategory), org(), on, on, nz(q.branchId()), q.productId(),
                nz((String) product.get("categoryId")), nz(q.siteId()), nz(zone), nz(requested)));
        for (Map<String, Object> c : candidates) {
            String itemUom = c.get("uomId") != null ? (String) c.get("uomId") : defaultUom;
            // Item units per 1 requested unit: the quantity break and the price are stated in the item's unit.
            BigDecimal rate = requested == null || itemUom == null ? BigDecimal.ONE
                    : units.conversionRate(q.productId(), requested, itemUom);
            if (rate == null || qty.multiply(rate).compareTo(SqlRows.dec(c, "minQuantity")) < 0) {
                continue;
            }
            BigDecimal price = c.get("unitPrice") == null ? null : SqlRows.dec(c, "unitPrice").multiply(rate)
                    .setScale(6, RoundingMode.HALF_UP);
            return new ListPrice(price, c.get("discountPercent") == null ? BigDecimal.ZERO
                    : SqlRows.dec(c, "discountPercent"), (String) c.get("listId"), (String) c.get("listName"));
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ administration

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String search, String tier, Boolean active, int page, int pageSize) {
        int p = Math.max(1, page);
        int size = Math.min(200, Math.max(1, pageSize));
        StringBuilder where = new StringBuilder(" WHERE l.\"organizationId\" = ? AND l.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (blank(search) != null) {
            where.append(" AND (l.code ILIKE ? OR l.name ILIKE ?)");
            params.add("%" + search.trim() + "%");
            params.add("%" + search.trim() + "%");
        }
        if (blank(tier) != null) {
            where.append(" AND l.tier = ?");
            params.add(tier);
        }
        if (active != null) {
            where.append(" AND l.\"isActive\" = ?");
            params.add(active);
        }
        long total = jdbc.sql("SELECT count(*) FROM \"PriceList\" l" + where).params(params).query(Long.class).single();
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT l.*, b.name AS \"branchName\", (SELECT count(*) FROM"
                + " \"PriceListItem\" i WHERE i.\"priceListId\" = l.id) AS \"itemCount\" FROM \"PriceList\" l LEFT JOIN"
                + " \"Branch\" b ON b.id = l.\"branchId\"" + where + " ORDER BY l.priority DESC, l.name LIMIT " + size
                + " OFFSET " + (long) (p - 1) * size).params(params));
        return PageResult.of(rows, p, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> l = SqlRows.one(jdbc.sql("SELECT * FROM \"PriceList\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(id, org()));
        if (l == null) {
            throw ApiException.notFound("Price list not found");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> i : SqlRows.list(jdbc.sql("SELECT i.*, p.name AS \"productName\", u.code AS \"uomCode\""
                + " FROM \"PriceListItem\" i LEFT JOIN \"Product\" p ON p.id = i.\"productId\" LEFT JOIN \"UnitOfMeasure\" u"
                + " ON u.id = i.\"uomId\" WHERE i.\"priceListId\" = ? AND i.\"organizationId\" = ? ORDER BY p.name,"
                + " i.\"minQuantity\"").params(id, org()))) {
            for (String k : List.of("unitPrice", "discountPercent", "minQuantity")) {
                if (i.get(k) != null) {
                    i.put(k, JsNumbers.js(SqlRows.dec(i, k)));
                }
            }
            items.add(i);
        }
        l.put("items", items);
        List<String> partnerIds = new ArrayList<>();
        List<String> categoryIds = new ArrayList<>();
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT \"partnerId\", \"partnerCategoryId\" FROM"
                + " \"PriceListAssignment\" WHERE \"priceListId\" = ? AND \"organizationId\" = ?").params(id, org()))) {
            if (a.get("partnerId") != null) {
                partnerIds.add((String) a.get("partnerId"));
            } else {
                categoryIds.add((String) a.get("partnerCategoryId"));
            }
        }
        l.put("partnerIds", partnerIds);
        l.put("partnerCategoryIds", categoryIds);
        return l;
    }

    @Transactional
    public Map<String, Object> create(ListInput in) {
        String id = UUID.randomUUID().toString();
        validate(in, null);
        jdbc.sql("INSERT INTO \"PriceList\" (id, \"organizationId\", code, name, tier, \"isDefault\", \"branchId\","
                + " \"validFrom\", \"validTo\", priority, \"isActive\", notes, \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?,"
                + " ?, ?, ?, ?, ?)").params(id, org(), in.code().trim(), in.name().trim(), tier(in),
                Boolean.TRUE.equals(in.isDefault()), blank(in.branchId()), sqlDate(in.validFrom()), sqlDate(in.validTo()),
                in.priority() == null ? 0 : in.priority(), in.isActive() == null || in.isActive(), in.notes(),
                TenantContext.userId()).update();
        replaceChildren(id, in);
        audit.recordInTx(AuditInput.of("PriceList", id, "create", null, Obj.of("code", in.code(), "name", in.name())));
        return get(id);
    }

    /** Full replace of the header, items and assignments (the editor always saves the whole list). */
    @Transactional
    public Map<String, Object> update(String id, ListInput in) {
        Map<String, Object> before = get(id);
        validate(in, id);
        jdbc.sql("UPDATE \"PriceList\" SET code = ?, name = ?, tier = ?, \"isDefault\" = ?, \"branchId\" = ?, \"validFrom\""
                + " = ?, \"validTo\" = ?, priority = ?, \"isActive\" = ?, notes = ?, \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?").params(in.code().trim(), in.name().trim(), tier(in),
                Boolean.TRUE.equals(in.isDefault()), blank(in.branchId()), sqlDate(in.validFrom()), sqlDate(in.validTo()),
                in.priority() == null ? 0 : in.priority(), in.isActive() == null || in.isActive(), in.notes(), id, org())
                .update();
        jdbc.sql("DELETE FROM \"PriceListItem\" WHERE \"priceListId\" = ? AND \"organizationId\" = ?").params(id, org())
                .update();
        jdbc.sql("DELETE FROM \"PriceListAssignment\" WHERE \"priceListId\" = ? AND \"organizationId\" = ?")
                .params(id, org()).update();
        replaceChildren(id, in);
        audit.recordInTx(AuditInput.of("PriceList", id, "update", before, Obj.of("code", in.code(), "name", in.name())));
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        get(id);
        jdbc.sql("UPDATE \"PriceList\" SET \"deletedAt\" = now(), \"updatedAt\" = now() WHERE id = ? AND \"organizationId\""
                + " = ?").params(id, org()).update();
        audit.recordInTx(AuditInput.of("PriceList", id, "delete"));
    }

    private static String tier(ListInput in) {
        return blank(in.tier()) == null ? "retail" : in.tier();
    }

    private static java.sql.Date sqlDate(LocalDate d) {
        return d == null ? null : java.sql.Date.valueOf(d);
    }

    private void validate(ListInput in, String id) {
        if (blank(in.code()) == null || blank(in.name()) == null) {
            throw ApiException.badRequest("code and name should not be empty");
        }
        if (!TIERS.contains(tier(in))) {
            throw ApiException.badRequest("tier must be one of: retail, wholesale, contract, negotiated");
        }
        if (in.validFrom() != null && in.validTo() != null && in.validTo().isBefore(in.validFrom())) {
            throw ApiException.badRequest("validTo must be on or after validFrom");
        }
        long clash = jdbc.sql("SELECT count(*) FROM \"PriceList\" WHERE \"organizationId\" = ? AND code = ? AND \"deletedAt\""
                + " IS NULL AND id <> ?").params(org(), in.code().trim(), id == null ? "" : id).query(Long.class).single();
        if (clash > 0) {
            throw ApiException.conflict("Price list code already exists");
        }
        requireInOrg("Branch", blank(in.branchId()), "Branch not found");
        for (ItemInput i : in.items() == null ? List.<ItemInput>of() : in.items()) {
            if (i.unitPrice() == null && i.discountPercent() == null) {
                throw ApiException.badRequest("Every price list line needs a unit price or a discount");
            }
            if (i.unitPrice() != null && i.unitPrice().signum() < 0) {
                throw ApiException.badRequest("unitPrice must not be less than 0");
            }
            if (i.discountPercent() != null && (i.discountPercent().signum() < 0
                    || i.discountPercent().compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw ApiException.badRequest("A discount must be between 0 and 100");
            }
            if (blank(i.productId()) != null && blank(i.productCategoryId()) != null) {
                throw ApiException.badRequest("Choose a product or a product category, not both");
            }
            requireInOrg("Product", blank(i.productId()), "Product not found");
            requireInOrg("UnitOfMeasure", blank(i.uomId()), "Unit of measure not found");
        }
    }

    private void requireInOrg(String table, String id, String message) {
        if (id == null) {
            return;
        }
        long n = jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?").params(id, org())
                .query(Long.class).single();
        if (n == 0) {
            throw ApiException.badRequest(message);
        }
    }

    private void replaceChildren(String id, ListInput in) {
        for (ItemInput i : in.items() == null ? List.<ItemInput>of() : in.items()) {
            jdbc.sql("INSERT INTO \"PriceListItem\" (id, \"organizationId\", \"priceListId\", \"productId\","
                    + " \"productCategoryId\", \"uomId\", \"siteId\", zone, \"minQuantity\", \"unitPrice\", \"discountPercent\")"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), id,
                    blank(i.productId()), blank(i.productCategoryId()), blank(i.uomId()), blank(i.siteId()),
                    blank(i.zone()), i.minQuantity() == null ? BigDecimal.ZERO : i.minQuantity(), i.unitPrice(),
                    i.discountPercent()).update();
        }
        for (String p : in.partnerIds() == null ? List.<String>of() : in.partnerIds()) {
            requireInOrg("Partner", p, "Partner not found");
            jdbc.sql("INSERT INTO \"PriceListAssignment\" (id, \"organizationId\", \"priceListId\", \"partnerId\") VALUES"
                    + " (?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), id, p).update();
        }
        for (String c : in.partnerCategoryIds() == null ? List.<String>of() : in.partnerCategoryIds()) {
            requireInOrg("PartnerCategory", c, "Customer category not found");
            jdbc.sql("INSERT INTO \"PriceListAssignment\" (id, \"organizationId\", \"priceListId\", \"partnerCategoryId\")"
                    + " VALUES (?, ?, ?, ?)").params(UUID.randomUUID().toString(), org(), id, c).update();
        }
    }
}
