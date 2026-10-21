package com.inventoryerp.pos.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosKdsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/kds/reports} (port of {@code pos-kds-reports.controller.ts}). */
@RestController
@RequestMapping("/pos/kds/reports")
public class PosKdsReportsController {

    private final PosKdsService svc;

    public PosKdsReportsController(PosKdsService svc) {
        this.svc = svc;
    }

    @GetMapping("/summary")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> summary(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return svc.summary(from, to);
    }

    @GetMapping("/live")
    @RequirePermissions(Permissions.POS__REPORTS)
    public Map<String, Object> live() {
        return svc.live();
    }
}
