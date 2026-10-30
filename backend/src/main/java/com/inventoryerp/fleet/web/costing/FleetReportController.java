package com.inventoryerp.fleet.web.costing;

import com.inventoryerp.fleet.application.FleetCostingService;
import com.inventoryerp.fleet.application.FleetReportCommands.CostQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.DeliveryQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.ProfitQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.RenewalQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.UnitQuery;
import com.inventoryerp.fleet.application.FleetReportExportService;
import com.inventoryerp.fleet.application.FleetReportExportService.ExportParams;
import com.inventoryerp.fleet.application.FleetReportExporter.Export;
import com.inventoryerp.fleet.application.FleetReportService;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fleet 1.9 reports (permission {@code fleet:report}): dashboard KPIs, vehicle cost / TCO, delivery profitability,
 * deliveries completed / on time, tonnes delivered, transport cost per tonne, utilisation / availability, fuel,
 * upcoming renewals and services — each as JSON and as a CSV / XLSX / PDF download
 * ({@code /fleet/reports/{report}/export?format=}). Periods are business days {@code from} .. {@code to}
 * (default: month to date, at most two years). The weighbridge discrepancy report stays at
 * {@code /fleet/weighbridge/reports/discrepancies} (1.4a).
 */
@RestController
@RequestMapping("/fleet")
@RequiresModule("fleet")
@ConditionalOnProperty(name = "app.modules.fleet", havingValue = "true")
@Validated
public class FleetReportController {

    private final FleetCostingService costing;
    private final FleetReportService reports;
    private final FleetReportExportService exports;

    public FleetReportController(FleetCostingService costing, FleetReportService reports,
            FleetReportExportService exports) {
        this.costing = costing;
        this.reports = reports;
        this.exports = exports;
    }

    @GetMapping("/reports/kpis")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> kpis(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return reports.kpis(from, to);
    }

    @GetMapping("/reports/vehicle-costs")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> vehicleCosts(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @OneOf({"vehicle", "category", "branch", "month"}) String groupBy,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String branchId) {
        return costing.report(new CostQuery(from, to, groupBy, vehicleId, categoryId, branchId));
    }

    /** One unit's cost of ownership over the period (vehicle detail tab). */
    @GetMapping("/vehicles/{id}/cost-summary")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> vehicleCostSummary(@PathVariable String id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @OneOf({"vehicle", "month"}) String groupBy) {
        return costing.vehicleSummary(id, from, to, groupBy);
    }

    @GetMapping("/reports/delivery-profitability")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> profitability(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @OneOf({"trip", "delivery_order", "customer", "site", "day", "month"})
            String groupBy,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) @OneOf({"own", "transporter"}) String carrier,
            @RequestParam(required = false) String transporterPartnerId,
            @RequestParam(required = false) String deliveryOrderId) {
        return reports.profitability(new ProfitQuery(from, to, groupBy, partnerId, siteId, vehicleId, carrier,
                transporterPartnerId, deliveryOrderId));
    }

    @GetMapping("/reports/deliveries")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> deliveries(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @OneOf({"day", "month", "customer", "site", "vehicle", "driver",
                    "transporter", "carrier"}) String groupBy,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) String transporterPartnerId,
            @RequestParam(required = false) @OneOf({"own", "transporter"}) String carrier) {
        return reports.deliveries(new DeliveryQuery(from, to, groupBy, partnerId, siteId, vehicleId, driverId,
                transporterPartnerId, carrier));
    }

    @GetMapping("/reports/tonnes")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> tonnes(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @OneOf({"vehicle", "driver", "customer", "site", "transporter", "product",
                    "month", "day"}) String groupBy,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) String transporterPartnerId,
            @RequestParam(required = false) @OneOf({"own", "transporter"}) String carrier) {
        return reports.tonnes(new DeliveryQuery(from, to, groupBy, partnerId, siteId, vehicleId, driverId,
                transporterPartnerId, carrier));
    }

    @GetMapping("/reports/transport-cost")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> transportCost(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @OneOf({"carrier", "vehicle", "transporter", "customer", "site", "month",
                    "driver"}) String groupBy,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) String transporterPartnerId,
            @RequestParam(required = false) @OneOf({"own", "transporter"}) String carrier) {
        return reports.transportCost(new DeliveryQuery(from, to, groupBy, partnerId, siteId, vehicleId, driverId,
                transporterPartnerId, carrier));
    }

    @GetMapping("/reports/utilisation")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> utilisation(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @OneOf({"vehicle", "category"}) String groupBy,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String branchId) {
        return reports.utilisation(new UnitQuery(from, to, groupBy, vehicleId, categoryId, branchId));
    }

    @GetMapping("/reports/fuel")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> fuel(@RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) @OneOf({"vehicle", "category", "month"}) String groupBy,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String branchId) {
        return reports.fuel(new UnitQuery(from, to, groupBy, vehicleId, categoryId, branchId));
    }

    @GetMapping("/reports/renewals")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public Map<String, Object> renewals(@RequestParam(required = false) LocalDate asOf,
            @RequestParam(required = false) @Min(0) @Max(365) Integer days,
            @RequestParam(required = false) @OneOf({"all", "compliance", "driver", "tare", "service"}) String kind) {
        return reports.renewals(new RenewalQuery(asOf, days, kind));
    }

    @GetMapping("/reports/{report}/export")
    @RequirePermissions(Permissions.FLEET__REPORT)
    public ResponseEntity<byte[]> export(@PathVariable String report,
            @RequestParam(required = false) @OneOf({"csv", "xlsx", "pdf"}) String format,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String vehicleId,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String siteId,
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) @OneOf({"own", "transporter"}) String carrier,
            @RequestParam(required = false) String transporterPartnerId,
            @RequestParam(required = false) String deliveryOrderId,
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(required = false) @Min(0) @Max(365) Integer days,
            @RequestParam(required = false) String kind) {
        Export e = exports.export(report, format, new ExportParams(from, to, groupBy, vehicleId, categoryId, branchId,
                partnerId, siteId, driverId, carrier, transporterPartnerId, deliveryOrderId, asOf, days, kind));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, e.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + e.filename().replace("\"", "")
                        + "\"")
                .body(e.content());
    }
}
