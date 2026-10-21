package com.inventoryerp.hr.application;

import com.inventoryerp.hr.domain.HrEmployee;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.events.DomainEvents;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * POS PIN sign-ins / log-offs → HR attendance (port of {@code hr-pos-attendance.subscriber.ts}). The event bus is the
 * only seam between POS and HR; attendance bookkeeping never fails or blocks a login.
 */
@Component
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrPosAttendanceSubscriber {

    private static final Logger log = LoggerFactory.getLogger(HrPosAttendanceSubscriber.class);

    private final EventBus events;
    private final HrDb hr;
    private final HrAttendanceService attendance;

    public HrPosAttendanceSubscriber(EventBus events, HrDb hr, HrAttendanceService attendance) {
        this.events = events;
        this.hr = hr;
        this.attendance = attendance;
    }

    @PostConstruct
    void subscribe() {
        events.subscribe(DomainEvents.POS_PIN_LOGIN, p -> handle(p, true));
        events.subscribe(DomainEvents.POS_PIN_LOGOFF, p -> handle(p, false));
        log.info("Subscribed to {} / {} for attendance", DomainEvents.POS_PIN_LOGIN, DomainEvents.POS_PIN_LOGOFF);
    }

    void handle(JsonNode p, boolean in) {
        String orgId = text(p, "organizationId");
        String userId = text(p, "userId");
        if (orgId == null) {
            return;
        }
        TenantContext.run(TenantStore.user(orgId, userId), () -> {
            try {
                Map<String, Object> employee = hr.where(HrEmployee.class).eq("userId", userId).first();
                if (employee == null || !HrLifecycleService.ACTIVE_STATUSES.contains(employee.get("employmentStatus"))) {
                    return;
                }
                String at = text(p, "at");
                Instant when = at != null && !at.isEmpty() ? HrDb.jsDate(at) : Instant.now();
                String employeeId = (String) employee.get("id");
                if (in) {
                    attendance.posSignIn(employeeId, when, text(p, "deviceId"));
                } else {
                    attendance.posSignOff(employeeId, when, text(p, "deviceId"));
                }
            } catch (RuntimeException err) {
                log.warn("Could not record POS clock-{} for user {}: {}", in ? "in" : "out", userId, err.getMessage());
            }
        });
    }

    private static String text(JsonNode p, String key) {
        JsonNode v = p == null ? null : p.get(key);
        return v == null || v.isNull() ? null : v.asString();
    }
}
