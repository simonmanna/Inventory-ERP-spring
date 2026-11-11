package com.inventoryerp.fleet.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fleet maintenance, defects, incidents, fines, trips and driver claims (ADR-S065). A driver reaches a user through
 * {@code "FleetDriver"."employeeId"} → {@code "HrEmployee"."userId"}. Nothing names a fleet manager, so the managers are
 * the holders of {@code fleet_maintenance:update} / {@code fleet_incident:update}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
public class FleetNotificationRoutes implements RoutedEventSource {

    private static final String MAINTENANCE = "maintenance";

    private static final String WO_RECORD = "SELECT code, title FROM \"FleetWorkOrder\" WHERE id = :id AND"
            + " \"organizationId\" = :org";

    /** The FleetDriver in {@code table.column} as a user. */
    private static Party driver(String table, String column) {
        return Party.query("driver", "Driver", "SELECT e.\"userId\" FROM \"" + table + "\" t JOIN \"FleetDriver\" d ON"
                + " d.id = t.\"" + column + "\" JOIN \"HrEmployee\" e ON e.id = d.\"employeeId\" WHERE t.id = :id AND"
                + " t.\"organizationId\" = :org");
    }

    @Override
    public List<RoutedEvent> routedEvents() {
        Party maintenanceManagers = Party.permission("fleetManagers", "Fleet maintenance managers",
                "fleet_maintenance:update");
        Party incidentManagers = Party.permission("fleetManagers", "Fleet incident managers", "fleet_incident:update");
        return List.of(
                RoutedEvent.on("fleet.workorder.created", "workOrderId")
                        .area(MAINTENANCE)
                        .label("Vehicle work order opened")
                        .record(WO_RECORD)
                        .party(Party.employee("technician", "Assigned technician", "FleetWorkOrder",
                                "assignedEmployeeId"))
                        .party(Party.column(Party.CREATOR, "Opened it", "FleetWorkOrder", "createdBy"))
                        .party(maintenanceManagers)
                        .defaults("technician")
                        .title("Work order {code}: {title}")
                        .href("/fleet/maintenance/work-orders/{id}")
                        .build(),
                RoutedEvent.on("fleet.workorder.completed", "workOrderId")
                        .area(MAINTENANCE)
                        .label("Vehicle work order completed")
                        .record(WO_RECORD)
                        .party(Party.column(Party.CREATOR, "Opened it", "FleetWorkOrder", "createdBy"))
                        .party(Party.query("defectReporters", "Reported a defect it fixes", """
                                SELECT d."reportedBy" FROM "FleetDefect" d
                                WHERE d."workOrderId" = :id AND d."organizationId" = :org"""))
                        .party(Party.query("vehicleDriver", "Vehicle's regular driver", """
                                SELECT e."userId" FROM "FleetWorkOrder" w
                                JOIN "FleetAssignment" a ON a."vehicleId" = w."vehicleId" AND a.kind = 'primary'
                                  AND a.status = 'active'
                                JOIN "FleetDriver" d ON d.id = a."driverId"
                                JOIN "HrEmployee" e ON e.id = d."employeeId"
                                WHERE w.id = :id AND w."organizationId" = :org"""))
                        .party(maintenanceManagers)
                        .defaults(Party.CREATOR, "defectReporters", "vehicleDriver")
                        .title("Work order {code} completed")
                        .body("{title}")
                        .href("/fleet/maintenance/work-orders/{id}")
                        .build(),
                RoutedEvent.on("fleet.defect.reported", "defectId")
                        .area("fleet")
                        .label("Vehicle defect reported")
                        .record("SELECT code, title FROM \"FleetDefect\" WHERE id = :id AND \"organizationId\" = :org")
                        .party(maintenanceManagers)
                        .defaults("fleetManagers")
                        .title("Defect {code} ({severity}): {title}")
                        .href("/fleet/vehicles/{vehicleId}")
                        .build(),
                RoutedEvent.on("fleet.incident.reported", "incidentId")
                        .area("fleet")
                        .label("Vehicle incident reported")
                        .record("SELECT \"incidentNumber\" FROM \"FleetIncident\" WHERE id = :id AND \"organizationId\""
                                + " = :org")
                        .party(incidentManagers)
                        .party(Party.employeeManager("driverManager", "Driver's manager", "FleetIncident",
                                "driverEmployeeId"))
                        .party(Party.employee("driver", "Driver", "FleetIncident", "driverEmployeeId"))
                        .defaults("fleetManagers", "driverManager")
                        .title("Incident {incidentNumber}: {incidentType} ({severity})")
                        .href("/fleet/incidents/{id}")
                        .build(),
                RoutedEvent.on("fleet.fine.charged", "fineId")
                        .area("fleet")
                        .label("Traffic fine charged to the driver")
                        .record("SELECT \"fineNumber\" FROM \"FleetFine\" WHERE id = :id AND \"organizationId\" = :org")
                        .party(Party.employee("driver", "Driver", "FleetFine", "driverEmployeeId"))
                        .party(Party.employeeManager("driverManager", "Driver's manager", "FleetFine",
                                "driverEmployeeId"))
                        .defaults("driver")
                        .skipWhen("chargedTo", "company")
                        .title("Fine {fineNumber} charged to you ({amount})")
                        .href("/fleet/fines/{id}")
                        .build(),
                RoutedEvent.on("fleet.trip.dispatched", "loadId")
                        .area("fleet")
                        .label("Trip dispatched to the driver")
                        .record("SELECT code FROM \"FleetLoad\" WHERE id = :id AND \"organizationId\" = :org")
                        .party(Party.query("driver", "Driver", """
                                SELECT e."userId" FROM "FleetDriver" d JOIN "HrEmployee" e ON e.id = d."employeeId"
                                WHERE d.id = :driverId AND d."organizationId" = :org"""))
                        .defaults("driver")
                        .skipWhen("carrier", "transporter")
                        .title("Trip {code} dispatched to you")
                        .href("/fleet/my-trips/{id}")
                        .build(),
                RoutedEvent.on("fleet.claim.settled", "claimId")
                        .area("fleet")
                        .label("Driver claim settled")
                        .record("SELECT code FROM \"FleetDriverClaim\" WHERE id = :id AND \"organizationId\" = :org")
                        .party(driver("FleetDriverClaim", "driverId"))
                        .defaults("driver")
                        .title("Your claim {code} was settled ({amount})")
                        .href("/fleet/claims/{id}")
                        .build());
    }
}
