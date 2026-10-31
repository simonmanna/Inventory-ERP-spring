package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API for dispatch (1.3), assignment and check-out: may this unit be sent out now? A unit is <b>eligible</b>
 * when its status is operational ({@link VehicleStatusPolicy#isOperational}: active) and no <b>critical</b> defect is
 * open or in repair ({@link MaintenancePolicies#blocksDispatch}). Due / overdue services and open major defects are
 * returned as warnings only (they do not block).
 *
 * <p>Call {@link #check(String)} to show the reasons, {@link #assertEligible(String)} inside a dispatch transaction.
 */
@Service
public class FleetDispatchEligibilityService {

    private final FleetDb db;
    private final FleetVehicleService vehicles;
    private final FleetServiceScheduleService schedules;

    public FleetDispatchEligibilityService(FleetDb db, FleetVehicleService vehicles,
            FleetServiceScheduleService schedules) {
        this.db = db;
        this.vehicles = vehicles;
        this.schedules = schedules;
    }

    /** Result: {@code reasons} block dispatch; {@code warnings} do not. */
    public record Eligibility(String vehicleId, String code, String status, boolean eligible, List<String> reasons,
            List<Map<String, Object>> blockingDefects, List<String> warnings) {

        public Map<String, Object> toMap() {
            return Obj.of("vehicleId", vehicleId, "code", code, "status", status, "eligible", eligible, "reasons",
                    reasons, "blockingDefects", blockingDefects, "warnings", warnings);
        }
    }

    @Transactional(readOnly = true)
    public Eligibility check(String vehicleId) {
        Map<String, Object> v = vehicles.require(vehicleId, false);
        String code = (String) v.get("code");
        String status = (String) v.get("status");
        List<String> reasons = new ArrayList<>();
        if (!VehicleStatusPolicy.isOperational(status)) {
            reasons.add("Vehicle " + code + " is " + VehicleStatusPolicy.label(status));
        }
        Map<String, Object> category = db.byId(com.inventoryerp.fleet.domain.FleetVehicleCategory.class,
                (String) v.get("categoryId"), false);
        if (category != null && FleetPlantService.KIND.equals(category.get("kind"))) {
            reasons.add("Vehicle " + code + " is fixed plant and cannot carry a load");
        }
        List<Map<String, Object>> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> d : SqlRows.list(db.jdbc().sql("SELECT id, code, title, severity, status,"
                + " \"workOrderId\", \"reportedAt\" FROM \"FleetDefect\" WHERE \"organizationId\" = ? AND"
                + " \"vehicleId\" = ? AND status IN ('open', 'in_repair') ORDER BY \"reportedAt\"")
                .params(FleetDb.org(), vehicleId))) {
            if (MaintenancePolicies.blocksDispatch((String) d.get("severity"), (String) d.get("status"))) {
                blocking.add(d);
                reasons.add("Critical defect " + d.get("code") + " (" + d.get("title") + ") is "
                        + MaintenancePolicies.label((String) d.get("status")));
            } else if ("major".equals(d.get("severity"))) {
                warnings.add("Major defect " + d.get("code") + " (" + d.get("title") + ") is open");
            }
        }
        for (Map<String, Object> s : schedules.attentionForVehicle(vehicleId)) {
            warnings.add("Service " + s.get("name") + " is " + s.get("dueState"));
        }
        return new Eligibility(vehicleId, code, status, reasons.isEmpty(), reasons, blocking, warnings);
    }

    /** 400 {@code "Vehicle X cannot be dispatched: …"} when the unit is not eligible. */
    public void assertEligible(String vehicleId) {
        Eligibility e = check(vehicleId);
        if (!e.eligible()) {
            throw ApiException.badRequest("Vehicle " + e.code() + " cannot be dispatched: "
                    + String.join("; ", e.reasons()));
        }
    }
}
