package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.DispatchCommands.SiteInput;
import com.inventoryerp.fleet.application.DispatchCommands.SitePatch;
import com.inventoryerp.fleet.domain.FleetCustomerSite;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer construction sites / delivery addresses (fleet 1.3): per customer, GPS coordinates, contact, access notes,
 * the delivery zone and road distance used by transport charges. Code {@code SITE-0001} when blank; a site used by a
 * delivery order is deactivated instead of deleted.
 */
@Service
public class FleetCustomerSiteService {

    static final String AUDIT_ENTITY = "FleetCustomerSite";

    private static final String SELECT = "SELECT s.*, p.name AS \"partnerName\", p.code AS \"partnerCode\" FROM"
            + " \"FleetCustomerSite\" s LEFT JOIN \"Partner\" p ON p.id = s.\"partnerId\" AND p.\"organizationId\" ="
            + " s.\"organizationId\"";

    private final FleetDb db;
    private final SequenceService sequence;
    private final AuditService audit;

    public FleetCustomerSiteService(FleetDb db, SequenceService sequence, AuditService audit) {
        this.db = db;
        this.sequence = sequence;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(String search, String partnerId, String zone, boolean includeInactive,
            Integer page, Integer pageSize) {
        int p = page == null || page < 1 ? 1 : page;
        int size = pageSize == null ? 25 : Math.max(1, Math.min(200, pageSize));
        StringBuilder where = new StringBuilder(" WHERE s.\"organizationId\" = ? AND s.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (!includeInactive) {
            where.append(" AND s.\"isActive\"");
        }
        if (FleetVehicleService.notBlank(search)) {
            String like = "%" + search.trim().replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (s.code ILIKE ? OR s.name ILIKE ? OR s.address ILIKE ? OR s.city ILIKE ? OR p.name"
                    + " ILIKE ?)");
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }
        if (FleetVehicleService.notBlank(partnerId)) {
            where.append(" AND s.\"partnerId\" = ?");
            params.add(partnerId);
        }
        if (FleetVehicleService.notBlank(zone)) {
            where.append(" AND lower(s.zone) = lower(?)");
            params.add(zone.trim());
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetCustomerSite\" s LEFT JOIN \"Partner\" p ON p.id ="
                + " s.\"partnerId\"" + where).params(params).query(Long.class).single();
        List<Object> pp = new ArrayList<>(params);
        pp.add(size);
        pp.add((p - 1) * size);
        List<Map<String, Object>> rows = SqlRows.list(db.jdbc().sql(SELECT + where + " ORDER BY p.name, s.name, s.id"
                + " LIMIT ? OFFSET ?").params(pp));
        return PageResult.of(rows, p, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> s = SqlRows.one(db.jdbc().sql(SELECT + " WHERE s.id = ? AND s.\"organizationId\" = ? AND"
                + " s.\"deletedAt\" IS NULL").params(id, FleetDb.org()));
        if (s == null) {
            throw ApiException.notFound("Customer site not found");
        }
        s.put("deliveryOrders", db.jdbc().sql("SELECT count(*) FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = ?"
                + " AND \"siteId\" = ?").params(FleetDb.org(), id).query(Long.class).single());
        return s;
    }

    /** Site row (404 when unknown or deleted). */
    public Map<String, Object> require(String id) {
        Map<String, Object> s = db.byId(FleetCustomerSite.class, id, false);
        if (s == null || s.get("deletedAt") != null) {
            throw ApiException.notFound("Customer site not found");
        }
        return s;
    }

    /** Active site of {@code partnerId} (400 otherwise) — delivery orders call this. */
    public Map<String, Object> requireFor(String siteId, String partnerId) {
        Map<String, Object> s = require(siteId);
        if (!partnerId.equals(s.get("partnerId"))) {
            throw ApiException.badRequest("Site " + s.get("code") + " belongs to another customer");
        }
        if (!Boolean.TRUE.equals(s.get("isActive"))) {
            throw ApiException.badRequest("Site " + s.get("code") + " is inactive");
        }
        return s;
    }

    @Transactional
    public Map<String, Object> create(SiteInput in) {
        assertPartner(in.partnerId());
        String code = FleetVehicleService.trim(in.code());
        if (code == null) {
            code = sequence.next("fleet_customer_site", "SITE-", 4);
        } else {
            code = code.toUpperCase();
            assertCodeFree(code, null);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("partnerId", in.partnerId());
        data.put("name", in.name().trim());
        data.put("address", FleetVehicleService.trim(in.address()));
        data.put("city", FleetVehicleService.trim(in.city()));
        data.put("latitude", in.latitude());
        data.put("longitude", in.longitude());
        assertCoordinates(in.latitude(), in.longitude());
        data.put("contactName", FleetVehicleService.trim(in.contactName()));
        data.put("contactPhone", FleetVehicleService.trim(in.contactPhone()));
        data.put("accessNotes", FleetVehicleService.trim(in.accessNotes()));
        data.put("zone", FleetVehicleService.trim(in.zone()));
        data.put("distanceKm", in.distanceKm());
        data.put("notes", FleetVehicleService.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> row = db.insert(FleetCustomerSite.class, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) row.get("id"), "create", null, row));
        return get((String) row.get("id"));
    }

    @Transactional
    public Map<String, Object> update(String id, SitePatch in) {
        Map<String, Object> before = require(id);
        Map<String, Object> data = new LinkedHashMap<>();
        putIf(data, "name", FleetVehicleService.trim(in.name()));
        putIf(data, "address", FleetVehicleService.trim(in.address()));
        putIf(data, "city", FleetVehicleService.trim(in.city()));
        putIf(data, "latitude", in.latitude());
        putIf(data, "longitude", in.longitude());
        putIf(data, "contactName", FleetVehicleService.trim(in.contactName()));
        putIf(data, "contactPhone", FleetVehicleService.trim(in.contactPhone()));
        putIf(data, "accessNotes", FleetVehicleService.trim(in.accessNotes()));
        putIf(data, "zone", FleetVehicleService.trim(in.zone()));
        putIf(data, "distanceKm", in.distanceKm());
        putIf(data, "isActive", in.isActive());
        putIf(data, "notes", FleetVehicleService.trim(in.notes()));
        if (in.clear() != null) {
            for (String c : in.clear()) {
                data.put(c, null);
            }
        }
        Object lat = data.containsKey("latitude") ? data.get("latitude") : before.get("latitude");
        Object lon = data.containsKey("longitude") ? data.get("longitude") : before.get("longitude");
        assertCoordinates(lat, lon);
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetCustomerSite.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return get(id);
    }

    /** Soft delete when unused; a site with delivery orders is deactivated. */
    @Transactional
    public Map<String, Object> delete(String id) {
        Map<String, Object> before = require(id);
        long used = db.jdbc().sql("SELECT count(*) FROM \"FleetDeliveryOrder\" WHERE \"organizationId\" = ? AND"
                + " \"siteId\" = ?").params(FleetDb.org(), id).query(Long.class).single();
        if (used > 0) {
            db.update(FleetCustomerSite.class, id, Obj.of("isActive", false, "updatedBy", TenantContext.userId()));
            audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, Obj.of("isActive", false)));
            return Obj.of("id", id, "deleted", false, "deactivated", true);
        }
        db.update(FleetCustomerSite.class, id, Obj.of("deletedAt", Instant.now(), "isActive", false));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "delete", before, null));
        return Obj.of("id", id, "deleted", true, "deactivated", false);
    }

    private void assertPartner(String partnerId) {
        if (!db.exists("Partner", partnerId, true)) {
            throw ApiException.badRequest("Customer not found");
        }
    }

    private void assertCodeFree(String code, String exceptId) {
        long n = db.jdbc().sql("SELECT count(*) FROM \"FleetCustomerSite\" WHERE \"organizationId\" = ? AND code = ?"
                + " AND \"deletedAt\" IS NULL AND id <> ?").params(FleetDb.org(), code, exceptId == null ? "" : exceptId)
                .query(Long.class).single();
        if (n > 0) {
            throw ApiException.conflict("Site code " + code + " is already used");
        }
    }

    private static void assertCoordinates(Object lat, Object lon) {
        if ((lat == null) != (lon == null)) {
            throw ApiException.badRequest("Give both latitude and longitude, or neither");
        }
    }

    private static void putIf(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
