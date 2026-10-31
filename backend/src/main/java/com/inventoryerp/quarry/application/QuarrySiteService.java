package com.inventoryerp.quarry.application;

import com.inventoryerp.inventory.application.LocationService;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quarry.application.QuarryCommands.MaterialInput;
import com.inventoryerp.quarry.application.QuarryCommands.SiteInput;
import com.inventoryerp.quarry.application.QuarryCommands.StockpileInput;
import com.inventoryerp.quarry.application.QuarryCommands.ZoneInput;
import com.inventoryerp.quarry.domain.QuarryMaterial;
import com.inventoryerp.quarry.domain.QuarrySite;
import com.inventoryerp.quarry.domain.QuarryStockpile;
import com.inventoryerp.quarry.domain.QuarryZone;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quarry master data (2.2): sites with their mining licence, the zone → bench → face hierarchy, material types / grades
 * (geology) mapped to the inventory product the stone is stocked as, and stockpiles (ROM) backed by inventory
 * locations (created on the fly when none is given). Every reference is validated against its owning table under the
 * organization scope.
 */
@Service
public class QuarrySiteService {

    private final QuarryDb db;
    private final SequenceService sequence;
    private final AuditService audit;
    private final LocationService locations;

    public QuarrySiteService(QuarryDb db, SequenceService sequence, AuditService audit, LocationService locations) {
        this.db = db;
        this.sequence = sequence;
        this.audit = audit;
        this.locations = locations;
    }

    // ------------------------------------------------------------------ sites

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> listSites(String status, String search, Integer page, Integer pageSize) {
        int p = QuarryDb.page(page);
        int size = QuarryDb.size(pageSize);
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ? AND s.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(QuarryDb.org()));
        if (!QuarryDb.blank(status)) {
            where.append(" AND s.status = ?");
            params.add(status);
        }
        if (!QuarryDb.blank(search)) {
            where.append(" AND (s.code ILIKE ? OR s.name ILIKE ? OR s.\"licenceNumber\" ILIKE ?)");
            String like = QuarryDb.like(search);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"QuarrySite\" s" + where).params(params).query(Long.class)
                .single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql("""
                SELECT s.*,
                  (SELECT count(*) FROM "QuarryZone" z WHERE z."siteId" = s.id AND z."deletedAt" IS NULL) AS "zoneCount",
                  (SELECT count(*) FROM "QuarryStockpile" k WHERE k."siteId" = s.id AND k."deletedAt" IS NULL)
                      AS "stockpileCount",
                  (SELECT COALESCE(SUM(l."netTonnes"), 0) FROM "QuarryExtractionLog" l WHERE l."siteId" = s.id
                      AND l.status = 'posted' AND l."deletedAt" IS NULL
                      AND l."extractedOn" >= date_trunc('month', CURRENT_DATE)) AS "monthTonnes"
                FROM "QuarrySite" s""" + where + " ORDER BY s.code LIMIT ? OFFSET ?").params(pp));
        return PageResult.of(rows, p, size, total);
    }

    /** Detail: the site, its zone tree (flat, parent first), stockpiles with on-hand, magazines, active plans. */
    @Transactional(readOnly = true)
    public Map<String, Object> getSite(String id) {
        Map<String, Object> s = requireSite(id, false);
        String org = QuarryDb.org();
        Map<String, Object> out = new LinkedHashMap<>(s);
        out.put("branch", db.ref("SELECT id, code, name FROM \"Branch\" WHERE id = ? AND \"organizationId\" = ?",
                s.get("branchId")));
        out.put("costCenter", db.ref("SELECT id, code, name FROM \"CostCenter\" WHERE id = ? AND \"organizationId\" = ?",
                s.get("costCenterId")));
        out.put("zones", zones(id));
        out.put("stockpiles", stockpileRows(" AND k.\"siteId\" = ?", id));
        out.put("magazines", SqlRows.list(db.jdbc().sql("SELECT id, code, name, \"licenceNumber\", \"licenceExpiresOn\","
                + " \"isActive\" FROM \"QuarryMagazine\" WHERE \"organizationId\" = ? AND \"siteId\" = ? AND"
                + " \"deletedAt\" IS NULL ORDER BY code").params(org, id)));
        out.put("plans", SqlRows.list(db.jdbc().sql("SELECT id, code, name, \"periodStart\", \"periodEnd\","
                + " \"targetTonnes\", status FROM \"QuarryExtractionPlan\" WHERE \"organizationId\" = ? AND \"siteId\""
                + " = ? AND \"deletedAt\" IS NULL AND status <> 'closed' ORDER BY \"periodStart\" DESC LIMIT 20")
                .params(org, id)));
        return out;
    }

    public Map<String, Object> requireSite(String id, boolean lock) {
        return db.require(QuarrySite.class, id, lock, "Quarry site not found");
    }

    @Transactional
    public Map<String, Object> createSite(SiteInput in) {
        Map<String, Object> d = siteData(in);
        String code = QuarryDb.text(in.code());
        d.put("code", code == null ? sequence.next("quarry_site", "QS-", 3) : code.toUpperCase());
        assertFree("QuarrySite", "code", (String) d.get("code"), null, "A quarry site with code " + d.get("code")
                + " already exists");
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QuarrySite.class, d);
        audit.recordInTx(AuditInput.of("QuarrySite", (String) row.get("id"), "create", null, row));
        return getSite((String) row.get("id"));
    }

    @Transactional
    public Map<String, Object> updateSite(String id, SiteInput in) {
        Map<String, Object> before = requireSite(id, true);
        Map<String, Object> d = siteData(in);
        String code = QuarryDb.text(in.code());
        if (code != null && !code.equalsIgnoreCase((String) before.get("code"))) {
            assertFree("QuarrySite", "code", code.toUpperCase(), id, "A quarry site with code " + code.toUpperCase()
                    + " already exists");
            d.put("code", code.toUpperCase());
        }
        Map<String, Object> after = db.update(QuarrySite.class, id, d);
        audit.recordInTx(AuditInput.of("QuarrySite", id, "update", before, after));
        return getSite(id);
    }

    @Transactional
    public void deleteSite(String id) {
        Map<String, Object> before = requireSite(id, true);
        long logs = db.jdbc().sql("SELECT count(*) FROM \"QuarryExtractionLog\" WHERE \"organizationId\" = ? AND"
                + " \"siteId\" = ? AND \"deletedAt\" IS NULL").params(QuarryDb.org(), id).query(Long.class).single();
        if (logs > 0) {
            throw ApiException.badRequest("Site " + before.get("code") + " has extraction logs; close it instead");
        }
        db.update(QuarrySite.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("QuarrySite", id, "delete", before, null));
    }

    private Map<String, Object> siteData(SiteInput in) {
        String branch = QuarryDb.text(in.branchId());
        String cc = QuarryDb.text(in.costCenterId());
        db.requireRef("Branch", branch, true, "Branch not found");
        db.requireRef("CostCenter", cc, true, "Cost centre not found");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", in.name().trim());
        d.put("status", in.status() == null ? "active" : in.status());
        d.put("branchId", branch);
        d.put("costCenterId", cc);
        d.put("address", QuarryDb.text(in.address()));
        d.put("latitude", in.latitude());
        d.put("longitude", in.longitude());
        d.put("areaHectares", in.areaHectares());
        d.put("licenceNumber", QuarryDb.text(in.licenceNumber()));
        d.put("licenceAuthority", QuarryDb.text(in.licenceAuthority()));
        d.put("licenceExpiresOn", in.licenceExpiresOn());
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    // ------------------------------------------------------------------ zones / benches / faces

    /** Zones of a site (parent before child), with parent and material references. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> zones(String siteId) {
        requireSite(siteId, false);
        return SqlRows.list(db.jdbc().sql("""
                SELECT z.*, p.code AS "parentCode", p.name AS "parentName", m.code AS "materialCode",
                       m.name AS "materialName"
                FROM "QuarryZone" z
                LEFT JOIN "QuarryZone" p ON p.id = z."parentId"
                LEFT JOIN "QuarryMaterial" m ON m.id = z."materialId"
                WHERE z."organizationId" = ? AND z."siteId" = ? AND z."deletedAt" IS NULL
                ORDER BY COALESCE(p.code, z.code), (z."parentId" IS NOT NULL), z.code""")
                .params(QuarryDb.org(), siteId));
    }

    @Transactional
    public Map<String, Object> createZone(ZoneInput in) {
        Map<String, Object> site = requireSite(in.siteId(), false);
        Map<String, Object> d = zoneData(in, (String) site.get("id"), null);
        String code = QuarryDb.text(in.code());
        d.put("code", code == null ? sequence.next("quarry_zone", d.get("kind").toString().substring(0, 1).toUpperCase()
                + "-", 3) : code.toUpperCase());
        assertZoneCodeFree((String) site.get("id"), (String) d.get("code"), null);
        d.put("siteId", site.get("id"));
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QuarryZone.class, d);
        audit.recordInTx(AuditInput.of("QuarryZone", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateZone(String id, ZoneInput in) {
        Map<String, Object> before = requireZone(id, true);
        if (!Objects.equals(before.get("siteId"), in.siteId())) {
            throw ApiException.badRequest("A zone cannot move to another site");
        }
        Map<String, Object> d = zoneData(in, (String) before.get("siteId"), id);
        String code = QuarryDb.text(in.code());
        if (code != null && !code.equalsIgnoreCase((String) before.get("code"))) {
            assertZoneCodeFree((String) before.get("siteId"), code.toUpperCase(), id);
            d.put("code", code.toUpperCase());
        }
        Map<String, Object> after = db.update(QuarryZone.class, id, d);
        audit.recordInTx(AuditInput.of("QuarryZone", id, "update", before, after));
        return after;
    }

    @Transactional
    public void deleteZone(String id) {
        Map<String, Object> before = requireZone(id, true);
        String org = QuarryDb.org();
        long children = db.jdbc().sql("SELECT count(*) FROM \"QuarryZone\" WHERE \"organizationId\" = ? AND"
                + " \"parentId\" = ? AND \"deletedAt\" IS NULL").params(org, id).query(Long.class).single();
        if (children > 0) {
            throw ApiException.badRequest("Zone " + before.get("code") + " has benches or faces; remove them first");
        }
        long used = db.jdbc().sql("SELECT count(*) FROM \"QuarryExtractionLog\" WHERE \"organizationId\" = ? AND"
                + " \"zoneId\" = ? AND \"deletedAt\" IS NULL").params(org, id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Zone " + before.get("code") + " has extraction logs; mark it exhausted or"
                    + " inactive instead");
        }
        db.update(QuarryZone.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("QuarryZone", id, "delete", before, null));
    }

    public Map<String, Object> requireZone(String id, boolean lock) {
        return db.require(QuarryZone.class, id, lock, "Zone not found");
    }

    /** A zone of {@code siteId} (400 when it belongs to another site). */
    Map<String, Object> zoneOfSite(String zoneId, String siteId) {
        Map<String, Object> z = db.byId(QuarryZone.class, zoneId, false);
        if (z == null) {
            throw ApiException.badRequest("Zone not found");
        }
        if (!Objects.equals(z.get("siteId"), siteId)) {
            throw ApiException.badRequest("Zone " + z.get("code") + " belongs to another site");
        }
        return z;
    }

    private Map<String, Object> zoneData(ZoneInput in, String siteId, String selfId) {
        String kind = in.kind() == null ? "zone" : in.kind();
        String parentId = QuarryDb.text(in.parentId());
        if ("zone".equals(kind) && parentId != null) {
            throw ApiException.badRequest("A zone is the top level; only benches and faces have a parent");
        }
        if (parentId != null) {
            if (parentId.equals(selfId)) {
                throw ApiException.badRequest("A zone cannot be its own parent");
            }
            Map<String, Object> parent = zoneOfSite(parentId, siteId);
            String pk = (String) parent.get("kind");
            if ("bench".equals(kind) && !"zone".equals(pk)) {
                throw ApiException.badRequest("A bench sits in a zone");
            }
            if ("face".equals(kind) && "face".equals(pk)) {
                throw ApiException.badRequest("A face sits on a bench or in a zone");
            }
        }
        String material = QuarryDb.text(in.materialId());
        db.requireRef("QuarryMaterial", material, true, "Material not found");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("parentId", parentId);
        d.put("kind", kind);
        d.put("name", in.name().trim());
        d.put("materialId", material);
        d.put("geology", QuarryDb.text(in.geology()));
        d.put("elevationM", in.elevationM());
        d.put("benchHeightM", in.benchHeightM());
        d.put("status", in.status() == null ? "active" : in.status());
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    private void assertZoneCodeFree(String siteId, String code, String exceptId) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"QuarryZone\" WHERE \"organizationId\" = ? AND \"siteId\" = ?"
                + " AND code = ? AND \"deletedAt\" IS NULL AND id IS DISTINCT FROM ?").params(QuarryDb.org(), siteId,
                code, exceptId).query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("Zone code " + code + " is already used on this site");
        }
    }

    // ------------------------------------------------------------------ materials

    @Transactional(readOnly = true)
    public List<Map<String, Object>> materials(boolean includeInactive) {
        return SqlRows.list(db.jdbc().sql("""
                SELECT m.*, p.code AS "productCode", p.name AS "productName", p."costingMethod"::text AS "costingMethod"
                FROM "QuarryMaterial" m
                LEFT JOIN "Product" p ON p.id = m."productId" AND p."organizationId" = m."organizationId"
                WHERE m."organizationId" = ? AND m."deletedAt" IS NULL""" + (includeInactive ? "" : " AND m.\"isActive\"")
                + " ORDER BY m.code").params(QuarryDb.org()));
    }

    @Transactional
    public Map<String, Object> createMaterial(MaterialInput in) {
        Map<String, Object> d = materialData(in);
        String code = QuarryDb.text(in.code());
        d.put("code", code == null ? sequence.next("quarry_material", "RM-", 3) : code.toUpperCase());
        assertFree("QuarryMaterial", "code", (String) d.get("code"), null, "A material with code " + d.get("code")
                + " already exists");
        d.put("createdBy", TenantContext.userId());
        Map<String, Object> row = db.insert(QuarryMaterial.class, d);
        audit.recordInTx(AuditInput.of("QuarryMaterial", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateMaterial(String id, MaterialInput in) {
        Map<String, Object> before = requireMaterial(id, true);
        Map<String, Object> d = materialData(in);
        if (!Objects.equals(before.get("productId"), d.get("productId"))) {
            long posted = db.jdbc().sql("SELECT count(*) FROM \"QuarryExtractionLog\" WHERE \"organizationId\" = ? AND"
                    + " \"materialId\" = ? AND status <> 'draft'").params(QuarryDb.org(), id).query(Long.class).single();
            if (posted > 0) {
                throw ApiException.badRequest("Material " + before.get("code") + " already has posted extraction; its"
                        + " stock product cannot change");
            }
        }
        String code = QuarryDb.text(in.code());
        if (code != null && !code.equalsIgnoreCase((String) before.get("code"))) {
            assertFree("QuarryMaterial", "code", code.toUpperCase(), id, "A material with code " + code.toUpperCase()
                    + " already exists");
            d.put("code", code.toUpperCase());
        }
        Map<String, Object> after = db.update(QuarryMaterial.class, id, d);
        audit.recordInTx(AuditInput.of("QuarryMaterial", id, "update", before, after));
        return after;
    }

    @Transactional
    public void deleteMaterial(String id) {
        Map<String, Object> before = requireMaterial(id, true);
        long used = db.jdbc().sql("SELECT count(*) FROM \"QuarryExtractionLog\" WHERE \"organizationId\" = ? AND"
                + " \"materialId\" = ?").params(QuarryDb.org(), id).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("Material " + before.get("code") + " is used by extraction logs; deactivate it"
                    + " instead");
        }
        db.update(QuarryMaterial.class, id, Obj.of("deletedAt", Instant.now(), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("QuarryMaterial", id, "delete", before, null));
    }

    public Map<String, Object> requireMaterial(String id, boolean lock) {
        return db.require(QuarryMaterial.class, id, lock, "Material not found");
    }

    private Map<String, Object> materialData(MaterialInput in) {
        Map<String, Object> product = stockProduct(in.productId());
        if ("STANDARD".equals(product.get("costingMethod"))) {
            throw ApiException.badRequest("Product " + product.get("name") + " is standard-costed; raw stone enters ROM"
                    + " stock at its actual extraction cost, so use an AVCO or FIFO product");
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", in.name().trim());
        d.put("rockType", QuarryDb.text(in.rockType()));
        d.put("grade", QuarryDb.text(in.grade()));
        d.put("densityTPerM3", in.densityTPerM3());
        d.put("productId", product.get("id"));
        d.put("isActive", in.isActive() == null || in.isActive());
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    /** A stock-tracked inventory product of the organization (400 otherwise). */
    Map<String, Object> stockProduct(String productId) {
        Map<String, Object> p = SqlRows.one(db.jdbc().sql("SELECT id, code, name, \"costingMethod\"::text AS"
                + " \"costingMethod\", \"trackInventory\", \"batchTracking\", \"serialTracking\" FROM \"Product\" WHERE"
                + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(productId, QuarryDb.org()));
        if (p == null) {
            throw ApiException.badRequest("Product not found");
        }
        if (!Boolean.TRUE.equals(p.get("trackInventory"))) {
            throw ApiException.badRequest("Product " + p.get("name") + " does not track inventory");
        }
        if (Boolean.TRUE.equals(p.get("serialTracking"))) {
            throw ApiException.badRequest("Product " + p.get("name") + " is serial-tracked; bulk stone and explosives"
                    + " are stocked by quantity");
        }
        return p;
    }

    // ------------------------------------------------------------------ stockpiles

    private static final String STOCKPILE_SELECT = """
            SELECT k.*, s.code AS "siteCode", s.name AS "siteName", loc.code AS "locationCode", loc.name AS "locationName",
                   COALESCE((SELECT SUM(si.quantity) FROM "StockItem" si WHERE si."organizationId" = k."organizationId"
                       AND si."locationId" = k."locationId"), 0) AS "onHandTonnes",
                   COALESCE((SELECT SUM(si.quantity * si."runningAverageCost") FROM "StockItem" si
                       WHERE si."organizationId" = k."organizationId" AND si."locationId" = k."locationId"), 0)
                       AS "onHandValue"
            FROM "QuarryStockpile" k
            JOIN "QuarrySite" s ON s.id = k."siteId"
            LEFT JOIN "InventoryLocation" loc ON loc.id = k."locationId" AND loc."organizationId" = k."organizationId"
            WHERE k."organizationId" = ? AND k."deletedAt" IS NULL""";

    List<Map<String, Object>> stockpileRows(String extra, Object... params) {
        List<Object> all = new ArrayList<>(List.of(QuarryDb.org()));
        all.addAll(List.of(params));
        return SqlRows.list(db.jdbc().sql(STOCKPILE_SELECT + extra + " ORDER BY s.code, k.code").params(all));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> stockpiles(String siteId, String kind) {
        StringBuilder extra = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (!QuarryDb.blank(siteId)) {
            extra.append(" AND k.\"siteId\" = ?");
            params.add(siteId);
        }
        if (!QuarryDb.blank(kind)) {
            extra.append(" AND k.kind = ?");
            params.add(kind);
        }
        return stockpileRows(extra.toString(), params.toArray());
    }

    @Transactional
    public Map<String, Object> createStockpile(StockpileInput in) {
        Map<String, Object> site = requireSite(in.siteId(), false);
        String code = QuarryDb.text(in.code());
        code = code == null ? sequence.next("quarry_stockpile", "SP-", 3) : code.toUpperCase();
        assertFree("QuarryStockpile", "code", code, null, "A stockpile with code " + code + " already exists");
        String locationId = QuarryDb.text(in.locationId());
        if (locationId == null) {
            locationId = newLocation("SP-" + site.get("code") + "-" + code, in.name().trim() + " (" + site.get("code")
                    + ")");
        } else {
            requireFreeLocation(locationId, null);
        }
        Map<String, Object> d = stockpileData(in);
        d.put("siteId", site.get("id"));
        d.put("code", code);
        d.put("locationId", locationId);
        d.put("createdBy", TenantContext.userId());
        boolean first = db.jdbc().sql("SELECT count(*) FROM \"QuarryStockpile\" WHERE \"organizationId\" = ? AND"
                + " \"siteId\" = ? AND kind = 'rom' AND \"isDefault\" AND \"deletedAt\" IS NULL")
                .params(QuarryDb.org(), site.get("id")).query(Long.class).single() == 0;
        if (first && "rom".equals(d.get("kind"))) {
            d.put("isDefault", true);
        }
        if (Boolean.TRUE.equals(d.get("isDefault"))) {
            clearDefault((String) site.get("id"), null);
        }
        Map<String, Object> row = db.insert(QuarryStockpile.class, d);
        audit.recordInTx(AuditInput.of("QuarryStockpile", (String) row.get("id"), "create", null, row));
        return stockpileRows(" AND k.id = ?", row.get("id")).getFirst();
    }

    @Transactional
    public Map<String, Object> updateStockpile(String id, StockpileInput in) {
        Map<String, Object> before = requireStockpile(id, true);
        if (!Objects.equals(before.get("siteId"), in.siteId())) {
            throw ApiException.badRequest("A stockpile cannot move to another site");
        }
        String loc = QuarryDb.text(in.locationId());
        if (loc != null && !loc.equals(before.get("locationId"))) {
            throw ApiException.badRequest("The inventory location of a stockpile cannot change");
        }
        Map<String, Object> d = stockpileData(in);
        String code = QuarryDb.text(in.code());
        if (code != null && !code.equalsIgnoreCase((String) before.get("code"))) {
            assertFree("QuarryStockpile", "code", code.toUpperCase(), id, "A stockpile with code " + code.toUpperCase()
                    + " already exists");
            d.put("code", code.toUpperCase());
        }
        if (Boolean.TRUE.equals(d.get("isDefault"))) {
            clearDefault((String) before.get("siteId"), id);
        }
        Map<String, Object> after = db.update(QuarryStockpile.class, id, d);
        audit.recordInTx(AuditInput.of("QuarryStockpile", id, "update", before, after));
        return stockpileRows(" AND k.id = ?", id).getFirst();
    }

    public Map<String, Object> requireStockpile(String id, boolean lock) {
        return db.require(QuarryStockpile.class, id, lock, "Stockpile not found");
    }

    private Map<String, Object> stockpileData(StockpileInput in) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", in.name().trim());
        d.put("kind", in.kind() == null ? "rom" : in.kind());
        d.put("capacityT", in.capacityT());
        d.put("isDefault", Boolean.TRUE.equals(in.isDefault()));
        d.put("isActive", in.isActive() == null || in.isActive());
        d.put("notes", QuarryDb.text(in.notes()));
        d.put("updatedBy", TenantContext.userId());
        return d;
    }

    private void clearDefault(String siteId, String exceptId) {
        db.jdbc().sql("UPDATE \"QuarryStockpile\" SET \"isDefault\" = false, \"updatedAt\" = " + QuarryDb.NOW
                + " WHERE \"organizationId\" = ? AND \"siteId\" = ? AND \"isDefault\" AND id IS DISTINCT FROM ?")
                .params(QuarryDb.org(), siteId, exceptId).update();
    }

    /** The site's default active ROM stockpile, or 400. */
    Map<String, Object> defaultRom(String siteId) {
        Map<String, Object> k = SqlRows.one(QuarryStockpile.class, db.jdbc().sql("SELECT * FROM \"QuarryStockpile\""
                + " WHERE \"organizationId\" = ? AND \"siteId\" = ? AND kind = 'rom' AND \"isActive\" AND \"deletedAt\""
                + " IS NULL ORDER BY \"isDefault\" DESC, code LIMIT 1").params(QuarryDb.org(), siteId));
        if (k == null) {
            throw ApiException.badRequest("The site has no ROM stockpile; add one under the site first");
        }
        return k;
    }

    // ------------------------------------------------------------------ inventory locations (shared with magazines)

    /** Create an inventory location for a stockpile / magazine through the inventory module. */
    String newLocation(String code, String name) {
        String c = code.toUpperCase();
        long n = db.jdbc().sql("SELECT count(*) FROM \"InventoryLocation\" WHERE \"organizationId\" = ? AND code = ?")
                .params(QuarryDb.org(), c).query(Long.class).single();
        if (n > 0) {
            c = c + "-" + java.util.UUID.randomUUID().toString().substring(0, 4).toUpperCase();
        }
        Map<String, Object> loc = locations.create(new LocationInput(c, name, "warehouse", true));
        return (String) loc.get("id");
    }

    /** Body of {@link LocationService#create} (CreateLocationDto shape). */
    public record LocationInput(String code, String name, String type, Boolean isActive) {}

    /** An inventory location of the org not yet used by a stockpile or a magazine. */
    void requireFreeLocation(String locationId, String exceptId) {
        if (!db.exists("InventoryLocation", locationId, true)) {
            throw ApiException.badRequest("Inventory location not found");
        }
        long used = db.jdbc().sql("SELECT (SELECT count(*) FROM \"QuarryStockpile\" WHERE \"organizationId\" = ? AND"
                + " \"locationId\" = ? AND \"deletedAt\" IS NULL AND id IS DISTINCT FROM ?) + (SELECT count(*) FROM"
                + " \"QuarryMagazine\" WHERE \"organizationId\" = ? AND \"locationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND id IS DISTINCT FROM ?)").params(QuarryDb.org(), locationId, exceptId, QuarryDb.org(), locationId,
                exceptId).query(Long.class).single();
        if (used > 0) {
            throw ApiException.badRequest("This inventory location already backs a stockpile or a magazine");
        }
    }

    private void assertFree(String table, String col, String value, String exceptId, String message) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"" + table + "\" WHERE \"organizationId\" = ? AND " + col
                + " = ? AND \"deletedAt\" IS NULL AND id IS DISTINCT FROM ?").params(QuarryDb.org(), value, exceptId)
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict(message);
        }
    }
}
