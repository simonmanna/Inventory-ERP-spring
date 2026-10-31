package com.inventoryerp.fleet.web.costing;

import com.inventoryerp.fleet.application.FleetReminderService;
import com.inventoryerp.fleet.application.FleetReportCommands.ReminderQuery;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fleet reminders (fleet 1.9): the log of expiry / service-due reminders the daily {@code fleet-reminders} job sent,
 * and a manual run for the caller's organization (deduplicated per item per day like the job).
 */
@RestController
@RequestMapping("/fleet/reminders")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetReminderController {

    private final FleetReminderService reminders;

    public FleetReminderController(FleetReminderService reminders) {
        this.reminders = reminders;
    }

    /** Manual run body: {@code asOf} defaults to today. */
    public record RunReminders(LocalDate asOf) {}

    @GetMapping
    @RequirePermissions(Permissions.FLEET__REPORT)
    public PageResult<Map<String, Object>> list(
            @RequestParam(required = false) @OneOf({"compliance_document", "driver_document", "vehicle_tare",
                    "service_due"}) String itemType,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(200) Integer pageSize) {
        return reminders.log(new ReminderQuery(itemType, from, to, page, pageSize));
    }

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.FLEET__SETTINGS)
    public Map<String, Object> run(@RequestBody(required = false) RunReminders dto) {
        return reminders.run(dto == null ? null : dto.asOf());
    }
}
