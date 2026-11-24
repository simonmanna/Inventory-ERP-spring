package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Row helpers shared by the crushing services (ADR-S016): tenancy-scoped lookups, locks, labels and GL entries. */
@Component
class CrushingSupport {

    private final MfgDb db;

    CrushingSupport(MfgDb db) {
        this.db = db;
    }

    MfgDb db() {
        return db;
    }

    /** The live (not soft-deleted) row, optionally locked {@code FOR UPDATE}; 404 with {@code message} otherwise. */
    Map<String, Object> require(Class<?> entity, String id, boolean lock, String message) {
        if (id == null || id.isBlank()) {
            throw ApiException.notFound(message);
        }
        boolean soft = com.inventoryerp.shared.web.EntityJson.columnNames(entity).contains("deletedAt");
        Map<String, Object> row = SqlRows.one(entity, db.jdbc().sql("SELECT * FROM \"" + MfgDb.table(entity)
                + "\" WHERE id = ? AND \"organizationId\" = ?" + (soft ? " AND \"deletedAt\" IS NULL" : "")
                + (lock ? " FOR UPDATE" : "")).params(id, MfgDb.org()));
        if (row == null) {
            throw ApiException.notFound(message);
        }
        return row;
    }

    /** The product row (id, code, name, costing method, cost price) or 404. */
    Map<String, Object> product(String id) {
        Map<String, Object> p = id == null ? null : SqlRows.one(db.jdbc().sql("SELECT id, code, name,"
                + " \"costingMethod\"::text AS \"costingMethod\", \"costPrice\", \"salesPrice\" FROM \"Product\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(id, MfgDb.org()));
        if (p == null) {
            throw ApiException.notFound("Product " + id + " not found");
        }
        return p;
    }

    /** A stockpile (quarry module table, read-only) with its site code; 404 when unknown. */
    Map<String, Object> stockpile(String id) {
        Map<String, Object> k = id == null ? null : SqlRows.one(db.jdbc().sql("SELECT k.id, k.code, k.name, k.kind,"
                + " k.\"siteId\", k.\"locationId\", k.\"isActive\", s.code AS \"siteCode\" FROM \"QuarryStockpile\" k"
                + " JOIN \"QuarrySite\" s ON s.id = k.\"siteId\" WHERE k.id = ? AND k.\"organizationId\" = ?"
                + " AND k.\"deletedAt\" IS NULL").params(id, MfgDb.org()));
        if (k == null) {
            throw ApiException.notFound("Stockpile not found");
        }
        return k;
    }

    /** Average cost of a product at a location (running average), else the product cost price. */
    BigDecimal averageCost(String productId, String locationId) {
        BigDecimal avg = db.jdbc().sql("SELECT CASE WHEN SUM(quantity) > 0 THEN SUM(quantity * \"runningAverageCost\")"
                + " / SUM(quantity) END FROM \"StockItem\" WHERE \"organizationId\" = ? AND \"productId\" = ?"
                + " AND \"locationId\" = ?").params(MfgDb.org(), productId, locationId).query(BigDecimal.class)
                .optional().orElse(null);
        if (avg != null && avg.signum() > 0) {
            return avg;
        }
        return SqlRows.dec(product(productId), "costPrice");
    }

    /** On-hand quantity of a product at a location. */
    BigDecimal onHand(String productId, String locationId) {
        return db.jdbc().sql("SELECT COALESCE(SUM(quantity), 0) FROM \"StockItem\" WHERE \"organizationId\" = ?"
                + " AND \"productId\" = ? AND \"locationId\" = ?").params(MfgDb.org(), productId, locationId)
                .query(BigDecimal.class).single();
    }

    /**
     * Integrity fix 2026-11-24 (MV-7): crushing feed, fractions and surveys are counted in tonnes and posted to stock
     * as base units, so a product stocked in kilograms (or litres, metres ...) would be off by the unit factor. The
     * stock unit must be the tonne (a weight unit of 1000 kg, or coded TON / T / TONNE / MT). A product without a unit,
     * or in the generic count unit (UNIT), is accepted as before (read as tonnes).
     */
    void requireTonnes(String productId) {
        Map<String, Object> u = SqlRows.one(db.jdbc().sql("SELECT p.name AS \"productName\", u.code, u.name,"
                + " u.symbol, u.category, u.factor, (SELECT k.factor FROM \"UnitOfMeasure\" k WHERE"
                + " k.\"organizationId\" = u.\"organizationId\" AND k.category = u.category AND upper(k.code) = 'KG'"
                + " AND k.\"deletedAt\" IS NULL LIMIT 1) AS \"kgFactor\" FROM \"Product\" p JOIN \"UnitOfMeasure\" u"
                + " ON u.id = p.\"uomId\" AND u.\"organizationId\" = p.\"organizationId\" WHERE p.id = ?"
                + " AND p.\"organizationId\" = ?").params(productId, MfgDb.org()));
        if (u == null || isTonne(u)) {
            return;
        }
        throw ApiException.badRequest("Product " + u.get("productName") + " is stocked in " + u.get("name") + " ("
                + u.get("code") + "); crushing quantities are tonnes, so its stock unit must be the tonne");
    }

    static boolean isTonne(Map<String, Object> u) {
        String code = String.valueOf(u.get("code")).trim().toUpperCase(java.util.Locale.ROOT);
        if ("UNIT".equals(code)) {
            return true;
        }
        if (!"weight".equalsIgnoreCase(String.valueOf(u.get("category")))) {
            return false;
        }
        if (java.util.Set.of("TON", "T", "TONNE", "MT").contains(code)) {
            return true;
        }
        BigDecimal factor = u.get("factor") == null ? null : SqlRows.dec(u, "factor");
        BigDecimal kg = u.get("kgFactor") == null ? null : SqlRows.dec(u, "kgFactor");
        return factor != null && kg != null && kg.signum() > 0
                && factor.compareTo(kg.multiply(BigDecimal.valueOf(1000))) == 0;
    }

    /** Journal entries (with lines) of a GL source, plus their reversals. */
    List<Map<String, Object>> journals(String sourceType, List<String> sourceIds) {
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        List<String> ids = db.jdbc().sql("SELECT id FROM \"JournalEntry\" WHERE \"organizationId\" = :org AND"
                + " \"sourceType\" = :type AND \"sourceId\" IN (:ids)").param("org", MfgDb.org())
                .param("type", sourceType).param("ids", sourceIds).query(String.class).list();
        LinkedHashSet<String> all = new LinkedHashSet<>(ids);
        if (all.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> je : SqlRows.list(db.jdbc().sql("SELECT id, \"entryNumber\", status::text AS status,"
                + " \"postingDate\", description, \"reversalOfId\" FROM \"JournalEntry\" WHERE \"organizationId\" = :org"
                + " AND (id IN (:ids) OR \"reversalOfId\" IN (:ids)) ORDER BY \"postedAt\", \"entryNumber\"")
                .param("org", MfgDb.org()).param("ids", List.copyOf(all)))) {
            Map<String, Object> entry = new LinkedHashMap<>(je);
            entry.put("lines", SqlRows.list(db.jdbc().sql("SELECT l.id, l.debit, l.credit, l.description,"
                    + " a.code AS \"accountCode\", a.name AS \"accountName\" FROM \"JournalLine\" l JOIN \"Account\" a"
                    + " ON a.id = l.\"accountId\" WHERE l.\"journalEntryId\" = ? ORDER BY l.\"lineNumber\"")
                    .param(je.get("id"))));
            entries.add(entry);
        }
        return entries;
    }

    static String text(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static LocalDate date(String iso) {
        return iso == null || iso.isBlank() ? null : LocalDate.parse(iso.substring(0, 10));
    }

    static int page(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    static int size(Integer size) {
        return size == null || size < 1 ? 25 : Math.min(size, 200);
    }
}
