package com.inventoryerp.hr.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Leave requests (ADR-S065). The manager is the employee's supervisor and their department manager; an employee
 * reaches a user through {@code "HrEmployee"."userId"}.
 */
@Component
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrNotificationRoutes implements RoutedEventSource {

    private static final String TABLE = "HrLeaveRequest";

    @Override
    public List<RoutedEvent> routedEvents() {
        Party employee = Party.employee("employee", "Employee", TABLE, "employeeId");
        Party manager = Party.employeeManager("manager", "Employee's manager", TABLE, "employeeId");
        return List.of(
                RoutedEvent.on("hr.leave.requested", "leaveRequestId")
                        .area("hr")
                        .label("Leave requested")
                        .record("""
                                SELECT r."requestCode", trim(e."firstName" || ' ' || coalesce(e."lastName", '')) AS employee,
                                  to_char(r."startDate", 'YYYY-MM-DD') AS "startDate",
                                  to_char(r."endDate", 'YYYY-MM-DD') AS "endDate"
                                FROM "HrLeaveRequest" r JOIN "HrEmployee" e ON e.id = r."employeeId"
                                WHERE r.id = :id AND r."organizationId" = :org AND r.status = 'PENDING'""")
                        .party(manager)
                        .party(Party.permission("hrAdmins", "HR officers (leave)", "hr:leave"))
                        .party(employee)
                        .defaults("manager")
                        .title("Leave request {requestCode} from {employee}")
                        .body("{startDate} to {endDate}")
                        .href("/hr/me/requests")
                        .build(),
                RoutedEvent.on("hr.leave.decided", "leaveRequestId")
                        .area("hr")
                        .label("Leave approved or rejected")
                        .record("""
                                SELECT "requestCode", lower(status::text) AS decision FROM "HrLeaveRequest"
                                WHERE id = :id AND "organizationId" = :org AND status IN ('APPROVED', 'REJECTED')""")
                        .party(employee)
                        .party(manager)
                        .defaults("employee")
                        .title("Your leave request {requestCode} was {decision}")
                        .body("{reason}")
                        .href("/hr/me")
                        .build());
    }
}
