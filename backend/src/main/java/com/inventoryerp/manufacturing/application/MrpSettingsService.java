package com.inventoryerp.manufacturing.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.manufacturing.application.MfgCommands.MrpRunInput;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-organization MRP settings (PR-5, ADR-S027): an optional nightly run with a horizon and an optional location.
 * Without a row the defaults apply (no nightly run, 30 days, every location).
 */
@Service
public class MrpSettingsService {

    /** {@code PUT /manufacturing/mrp/settings} body; absent fields keep their value. */
    public record SettingsInput(Boolean autoRun, Integer horizonDays, String locationId) {}

    private final MfgDb db;
    private final MrpPlanningService mrp;
    private final ProductionService production;
    private final AuditService audit;

    public MrpSettingsService(MfgDb db, MrpPlanningService mrp, ProductionService production, AuditService audit) {
        this.db = db;
        this.mrp = mrp;
        this.production = production;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        Map<String, Object> row = SqlRows.one(db.jdbc().sql("SELECT * FROM \"MrpSettings\" WHERE \"organizationId\" = ?")
                .param(MfgDb.org()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("autoRun", row != null && Boolean.TRUE.equals(row.get("autoRun")));
        out.put("horizonDays", row == null ? 30 : ((Number) row.get("horizonDays")).intValue());
        out.put("locationId", row == null ? null : row.get("locationId"));
        out.put("lastAutoRunAt", row == null ? null : row.get("lastAutoRunAt"));
        out.put("lastAutoRunId", row == null ? null : row.get("lastAutoRunId"));
        return out;
    }

    @Transactional
    public Map<String, Object> update(SettingsInput in) {
        Map<String, Object> cur = get();
        boolean autoRun = in.autoRun() != null ? in.autoRun() : (Boolean) cur.get("autoRun");
        int horizon = in.horizonDays() != null ? in.horizonDays() : (Integer) cur.get("horizonDays");
        if (horizon < 1 || horizon > 730) {
            throw ApiException.badRequest("horizonDays must be between 1 and 730");
        }
        String location = in.locationId() != null ? (in.locationId().isBlank() ? null : in.locationId())
                : (String) cur.get("locationId");
        if (location != null) {
            production.requireLocation(location);
        }
        db.jdbc().sql("INSERT INTO \"MrpSettings\" (\"organizationId\", \"autoRun\", \"horizonDays\", \"locationId\","
                + " \"updatedBy\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, " + MfgDb.NOW + ") ON CONFLICT"
                + " (\"organizationId\") DO UPDATE SET \"autoRun\" = EXCLUDED.\"autoRun\", \"horizonDays\" ="
                + " EXCLUDED.\"horizonDays\", \"locationId\" = EXCLUDED.\"locationId\", \"updatedBy\" ="
                + " EXCLUDED.\"updatedBy\", \"updatedAt\" = EXCLUDED.\"updatedAt\"")
                .params(MfgDb.org(), autoRun, horizon, location, TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("MrpSettings", MfgDb.org(), "update", cur, Obj.of("autoRun", autoRun,
                "horizonDays", horizon, "locationId", location)));
        return get();
    }

    /** Organizations whose nightly run is on (no tenant needed). */
    public List<String> autoRunOrganizations() {
        return db.jdbc().sql("SELECT \"organizationId\" FROM \"MrpSettings\" WHERE \"autoRun\" ORDER BY 1")
                .query(String.class).list();
    }

    /** The nightly run of the current organization; remembered on the settings row. */
    @Transactional
    public Map<String, Object> autoRun() {
        Map<String, Object> s = get();
        Map<String, Object> run = mrp.run(new MrpRunInput((Integer) s.get("horizonDays"),
                (String) s.get("locationId")));
        db.jdbc().sql("UPDATE \"MrpSettings\" SET \"lastAutoRunAt\" = ?, \"lastAutoRunId\" = ? WHERE"
                + " \"organizationId\" = ?").params(SqlRows.ts(Instant.now()), run.get("id"), MfgDb.org()).update();
        return run;
    }
}
