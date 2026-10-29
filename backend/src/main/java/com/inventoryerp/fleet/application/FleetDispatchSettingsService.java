package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.DispatchCommands.ChargeRuleInput;
import com.inventoryerp.fleet.application.DispatchCommands.ChargeRulePatch;
import com.inventoryerp.fleet.application.DispatchCommands.LoadFactorInput;
import com.inventoryerp.fleet.application.DispatchCommands.TransporterRateInput;
import com.inventoryerp.fleet.application.DispatchCommands.TransporterRatePatch;
import com.inventoryerp.fleet.domain.FleetDeliveryChargeRule;
import com.inventoryerp.fleet.domain.FleetProductLoadFactor;
import com.inventoryerp.fleet.domain.FleetTransporterRate;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dispatch settings (fleet 1.3): third-party transporter freight rates, customer delivery-charge rules (per customer /
 * zone / distance band) and the per-product load factors (t / m³ per base unit) the load planner uses.
 */
@Service
public class FleetDispatchSettingsService {

    private final FleetDb db;
    private final FleetLoadFactors factors;
    private final AuditService audit;

    public FleetDispatchSettingsService(FleetDb db, FleetLoadFactors factors, AuditService audit) {
        this.db = db;
        this.factors = factors;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ transporter rates

    @Transactional(readOnly = true)
    public List<Map<String, Object>> rates(String partnerId, boolean includeInactive) {
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        String where = " WHERE r.\"organizationId\" = ?";
        if (!includeInactive) {
            where += " AND r.\"isActive\"";
        }
        if (FleetVehicleService.notBlank(partnerId)) {
            where += " AND r.\"partnerId\" = ?";
            params.add(partnerId);
        }
        return SqlRows.list(db.jdbc().sql("SELECT r.*, p.name AS \"partnerName\", (SELECT count(*) FROM \"FleetLoad\" l"
                + " WHERE l.\"transporterRateId\" = r.id) AS usage FROM \"FleetTransporterRate\" r LEFT JOIN \"Partner\" p"
                + " ON p.id = r.\"partnerId\"" + where + " ORDER BY p.name, r.name, r.id").params(params));
    }

    /** Rate row (404). */
    public Map<String, Object> rate(String id) {
        return db.require(FleetTransporterRate.class, id, false, "Transporter rate not found");
    }

    @Transactional
    public Map<String, Object> createRate(TransporterRateInput in) {
        assertSupplier(in.partnerId());
        assertDates(in.effectiveFrom(), in.effectiveTo());
        Map<String, Object> row = db.insert(FleetTransporterRate.class, Obj.of("partnerId", in.partnerId(), "name",
                in.name().trim(), "basis", in.basis(), "rate", in.rate(), "zone", FleetVehicleService.trim(in.zone()),
                "effectiveFrom", in.effectiveFrom(), "effectiveTo", in.effectiveTo(), "notes",
                FleetVehicleService.trim(in.notes()), "createdBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetTransporterRate", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateRate(String id, TransporterRatePatch in) {
        Map<String, Object> before = rate(id);
        Map<String, Object> data = new LinkedHashMap<>();
        putIf(data, "name", FleetVehicleService.trim(in.name()));
        putIf(data, "rate", in.rate());
        putIf(data, "zone", FleetVehicleService.trim(in.zone()));
        putIf(data, "effectiveFrom", in.effectiveFrom());
        putIf(data, "effectiveTo", in.effectiveTo());
        putIf(data, "isActive", in.isActive());
        putIf(data, "notes", FleetVehicleService.trim(in.notes()));
        clear(data, in.clear());
        assertDates((LocalDate) pick(data, before, "effectiveFrom"), (LocalDate) pick(data, before, "effectiveTo"));
        Map<String, Object> after = db.update(FleetTransporterRate.class, id, data);
        audit.recordInTx(AuditInput.of("FleetTransporterRate", id, "update", before, after));
        return after;
    }

    /** Deletes an unused rate, otherwise deactivates it. */
    @Transactional
    public Map<String, Object> deleteRate(String id) {
        Map<String, Object> before = rate(id);
        long used = db.jdbc().sql("SELECT count(*) FROM \"FleetLoad\" WHERE \"organizationId\" = ? AND"
                + " \"transporterRateId\" = ?").params(FleetDb.org(), id).query(Long.class).single();
        if (used > 0) {
            db.update(FleetTransporterRate.class, id, Obj.of("isActive", false));
            return Obj.of("id", id, "deleted", false, "deactivated", true);
        }
        db.jdbc().sql("DELETE FROM \"FleetTransporterRate\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, FleetDb.org()).update();
        audit.recordInTx(AuditInput.of("FleetTransporterRate", id, "delete", before, null));
        return Obj.of("id", id, "deleted", true, "deactivated", false);
    }

    // ------------------------------------------------------------------ delivery charge rules

    @Transactional(readOnly = true)
    public List<Map<String, Object>> chargeRules(boolean includeInactive) {
        return SqlRows.list(db.jdbc().sql("SELECT r.*, p.name AS \"partnerName\", t.name AS \"taxName\" FROM"
                + " \"FleetDeliveryChargeRule\" r LEFT JOIN \"Partner\" p ON p.id = r.\"partnerId\" LEFT JOIN \"Tax\" t ON"
                + " t.id = r.\"taxId\" WHERE r.\"organizationId\" = ?" + (includeInactive ? "" : " AND r.\"isActive\"")
                + " ORDER BY r.\"createdAt\" DESC, r.id").params(FleetDb.org()));
    }

    /** Active rules, newest first, for the charge calculator. */
    public List<DeliveryChargeCalculator.Rule> activeRules() {
        List<DeliveryChargeCalculator.Rule> out = new ArrayList<>();
        for (Map<String, Object> r : db.list(FleetDeliveryChargeRule.class, " AND \"isActive\" ORDER BY \"createdAt\""
                + " DESC, id")) {
            out.add(new DeliveryChargeCalculator.Rule((String) r.get("id"), (String) r.get("name"),
                    (String) r.get("partnerId"), (String) r.get("zone"), (BigDecimal) r.get("minDistanceKm"),
                    (BigDecimal) r.get("maxDistanceKm"), (String) r.get("basis"), (BigDecimal) r.get("rate"),
                    (BigDecimal) r.get("minAmount"), (String) r.get("taxId")));
        }
        return out;
    }

    public Map<String, Object> chargeRule(String id) {
        return db.require(FleetDeliveryChargeRule.class, id, false, "Delivery charge rule not found");
    }

    @Transactional
    public Map<String, Object> createChargeRule(ChargeRuleInput in) {
        if (FleetVehicleService.notBlank(in.partnerId()) && !db.exists("Partner", in.partnerId(), true)) {
            throw ApiException.badRequest("Customer not found");
        }
        assertTax(in.taxId());
        assertBand(in.minDistanceKm(), in.maxDistanceKm());
        Map<String, Object> row = db.insert(FleetDeliveryChargeRule.class, Obj.of("name", in.name().trim(),
                "partnerId", blank(in.partnerId()), "zone", FleetVehicleService.trim(in.zone()), "minDistanceKm",
                in.minDistanceKm(), "maxDistanceKm", in.maxDistanceKm(), "basis", in.basis(), "rate", in.rate(),
                "minAmount", in.minAmount(), "taxId", blank(in.taxId()), "notes", FleetVehicleService.trim(in.notes()),
                "createdBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of("FleetDeliveryChargeRule", (String) row.get("id"), "create", null, row));
        return row;
    }

    @Transactional
    public Map<String, Object> updateChargeRule(String id, ChargeRulePatch in) {
        Map<String, Object> before = chargeRule(id);
        if (FleetVehicleService.notBlank(in.partnerId()) && !db.exists("Partner", in.partnerId(), true)) {
            throw ApiException.badRequest("Customer not found");
        }
        assertTax(in.taxId());
        Map<String, Object> data = new LinkedHashMap<>();
        putIf(data, "name", FleetVehicleService.trim(in.name()));
        putIf(data, "partnerId", blank(in.partnerId()));
        putIf(data, "zone", FleetVehicleService.trim(in.zone()));
        putIf(data, "minDistanceKm", in.minDistanceKm());
        putIf(data, "maxDistanceKm", in.maxDistanceKm());
        putIf(data, "basis", in.basis());
        putIf(data, "rate", in.rate());
        putIf(data, "minAmount", in.minAmount());
        putIf(data, "taxId", blank(in.taxId()));
        putIf(data, "isActive", in.isActive());
        putIf(data, "notes", FleetVehicleService.trim(in.notes()));
        clear(data, in.clear());
        assertBand((BigDecimal) pick(data, before, "minDistanceKm"), (BigDecimal) pick(data, before, "maxDistanceKm"));
        Map<String, Object> after = db.update(FleetDeliveryChargeRule.class, id, data);
        audit.recordInTx(AuditInput.of("FleetDeliveryChargeRule", id, "update", before, after));
        return after;
    }

    @Transactional
    public Map<String, Object> deleteChargeRule(String id) {
        Map<String, Object> before = chargeRule(id);
        long used = db.jdbc().sql("SELECT count(*) FROM \"FleetDeliveryCharge\" WHERE \"organizationId\" = ? AND"
                + " \"ruleId\" = ?").params(FleetDb.org(), id).query(Long.class).single();
        if (used > 0) {
            db.update(FleetDeliveryChargeRule.class, id, Obj.of("isActive", false));
            return Obj.of("id", id, "deleted", false, "deactivated", true);
        }
        db.jdbc().sql("DELETE FROM \"FleetDeliveryChargeRule\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, FleetDb.org()).update();
        audit.recordInTx(AuditInput.of("FleetDeliveryChargeRule", id, "delete", before, null));
        return Obj.of("id", id, "deleted", true, "deactivated", false);
    }

    // ------------------------------------------------------------------ product load factors

    /** Products with a fleet factor, plus the resolved factor (fleet factor / product master / unit). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> loadFactors() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT f.*, p.code AS \"productCode\", p.name AS"
                + " \"productName\", u.code AS \"uomCode\" FROM \"FleetProductLoadFactor\" f JOIN \"Product\" p ON p.id ="
                + " f.\"productId\" LEFT JOIN \"UnitOfMeasure\" u ON u.id = p.\"uomId\" WHERE f.\"organizationId\" = ?"
                + " ORDER BY p.name").params(FleetDb.org()))) {
            out.add(r);
        }
        return out;
    }

    /** Resolved planning factor of a product (any source). */
    @Transactional(readOnly = true)
    public Map<String, Object> resolvedFactor(String productId) {
        requireProduct(productId);
        FleetLoadFactors.Factor f = factors.forProduct(productId);
        return Obj.of("productId", productId, "tonnesPerUnit", f.tonnesPerUnit(), "cubicMetresPerUnit", f.m3PerUnit(),
                "source", f.source());
    }

    @Transactional
    public Map<String, Object> putLoadFactor(String productId, LoadFactorInput in) {
        requireProduct(productId);
        if (in.tonnesPerUnit() == null && in.cubicMetresPerUnit() == null) {
            throw ApiException.badRequest("Give tonnes and / or cubic metres per unit");
        }
        db.jdbc().sql("INSERT INTO \"FleetProductLoadFactor\" (id, \"organizationId\", \"productId\", \"tonnesPerUnit\","
                + " \"cubicMetresPerUnit\", notes, \"updatedBy\", \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, ?, "
                + FleetDb.NOW + ", " + FleetDb.NOW + ") ON CONFLICT (\"organizationId\", \"productId\") DO UPDATE SET"
                + " \"tonnesPerUnit\" = EXCLUDED.\"tonnesPerUnit\", \"cubicMetresPerUnit\" = EXCLUDED.\"cubicMetresPerUnit\","
                + " notes = EXCLUDED.notes, \"updatedBy\" = EXCLUDED.\"updatedBy\", \"updatedAt\" = EXCLUDED.\"updatedAt\"")
                .params(java.util.UUID.randomUUID().toString(), FleetDb.org(), productId, in.tonnesPerUnit(),
                        in.cubicMetresPerUnit(), FleetVehicleService.trim(in.notes()), TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("FleetProductLoadFactor", productId, "update", null, Obj.of("tonnesPerUnit",
                in.tonnesPerUnit(), "cubicMetresPerUnit", in.cubicMetresPerUnit())));
        return SqlRows.one(FleetProductLoadFactor.class, db.jdbc().sql("SELECT * FROM \"FleetProductLoadFactor\" WHERE"
                + " \"organizationId\" = ? AND \"productId\" = ?").params(FleetDb.org(), productId));
    }

    @Transactional
    public Map<String, Object> deleteLoadFactor(String productId) {
        int n = db.jdbc().sql("DELETE FROM \"FleetProductLoadFactor\" WHERE \"organizationId\" = ? AND \"productId\" = ?")
                .params(FleetDb.org(), productId).update();
        if (n == 0) {
            throw ApiException.notFound("Load factor not found");
        }
        return Obj.of("productId", productId, "deleted", true);
    }

    // ------------------------------------------------------------------ helpers

    private void requireProduct(String productId) {
        if (!db.exists("Product", productId, true)) {
            throw ApiException.notFound("Product not found");
        }
    }

    private void assertSupplier(String partnerId) {
        Boolean supplier = db.jdbc().sql("SELECT \"isSupplier\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL").params(partnerId, FleetDb.org()).query(Boolean.class).optional()
                .orElse(null);
        if (supplier == null) {
            throw ApiException.badRequest("Transporter not found");
        }
        if (!supplier) {
            throw ApiException.badRequest("The transporter must be a supplier partner");
        }
    }

    private void assertTax(String taxId) {
        if (FleetVehicleService.notBlank(taxId) && !db.exists("Tax", taxId, true)) {
            throw ApiException.badRequest("Tax not found");
        }
    }

    private static void assertDates(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from)) {
            throw ApiException.badRequest("effectiveTo must be on or after effectiveFrom");
        }
    }

    private static void assertBand(BigDecimal min, BigDecimal max) {
        if (min != null && max != null && max.compareTo(min) < 0) {
            throw ApiException.badRequest("maxDistanceKm must be at least minDistanceKm");
        }
    }

    private static Object pick(Map<String, Object> data, Map<String, Object> before, String key) {
        return data.containsKey(key) ? data.get(key) : before.get(key);
    }

    private static void clear(Map<String, Object> data, List<String> clear) {
        if (clear != null) {
            for (String c : clear) {
                data.put(c, null);
            }
        }
    }

    private static void putIf(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
