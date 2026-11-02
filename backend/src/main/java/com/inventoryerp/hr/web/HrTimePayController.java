package com.inventoryerp.hr.web;

import com.inventoryerp.hr.application.HrTimePayService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /hr/time} (HP-5): pre-payroll period review and overtime approval. */
@RestController
@RequestMapping("/hr/time")
@RequiresModule("hr")
@ConditionalOnProperty(name = "app.modules.hr", havingValue = "true")
public class HrTimePayController {

    private final HrTimePayService time;

    public HrTimePayController(HrTimePayService time) {
        this.time = time;
    }

    @GetMapping("/periods/{periodId}/summary")
    @RequirePermissions(Permissions.HR__ATTENDANCE)
    public Map<String, Object> periodSummary(@PathVariable String periodId) {
        return time.periodSummary(periodId);
    }

    @GetMapping("/overtime")
    @RequirePermissions(Permissions.HR__ATTENDANCE)
    public Map<String, Object> overtime(@RequestParam Map<String, String> query) {
        return time.overtime(query);
    }

    @PostMapping("/overtime/review")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.HR__ATTENDANCE)
    public Map<String, Object> review(@RequestBody Map<String, Object> dto) {
        return time.reviewOvertime(dto);
    }
}
