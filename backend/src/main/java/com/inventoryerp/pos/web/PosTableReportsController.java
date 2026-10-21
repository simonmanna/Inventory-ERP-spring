package com.inventoryerp.pos.web;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosTableReportsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /pos/reports/tables} and {@code /pos/reports/reservations} (port of {@code pos-table-reports.controller.ts}:
 * {@code PosTableReportsController} + {@code PosReservationReportsController}).
 */
@RestController
@RequestMapping("/pos/reports")
public class PosTableReportsController {

    private final PosTableReportsService svc;

    public PosTableReportsController(PosTableReportsService svc) {
        this.svc = svc;
    }

    @GetMapping("/tables/utilization")
    @RequirePermissions(Permissions.TABLES__VIEW)
    public Map<String, Object> utilization(@RequestParam(required = false) String date) {
        return svc.utilization(date);
    }

    @GetMapping("/tables/revenue")
    @RequirePermissions(Permissions.TABLES__VIEW)
    public Map<String, Object> revenue(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return svc.revenue(fromDate, toDate);
    }

    @GetMapping("/reservations")
    @RequirePermissions(Permissions.TABLES__VIEW)
    public Map<String, Object> reservations(@RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        return svc.reservations(fromDate, toDate);
    }
}
