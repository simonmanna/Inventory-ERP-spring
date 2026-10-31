package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.BranchPerformanceService;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /reports/branches/comparison} (BR-2): branches side by side with a consolidated total. */
@RestController
public class BranchPerformanceController {

    private final BranchPerformanceService service;

    public BranchPerformanceController(BranchPerformanceService service) {
        this.service = service;
    }

    @GetMapping("/reports/branches/comparison")
    @RequirePermissions(Permissions.REPORT__ACCOUNTING)
    public Map<String, Object> comparison(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return service.comparison(from, to);
    }
}
