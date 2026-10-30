package com.inventoryerp.fleet.application;

import com.inventoryerp.fleet.application.FleetReportCommands.CostQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.DeliveryQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.ProfitQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.RenewalQuery;
import com.inventoryerp.fleet.application.FleetReportCommands.UnitQuery;
import com.inventoryerp.fleet.application.FleetReportExporter.Column;
import com.inventoryerp.fleet.application.FleetReportExporter.Export;
import com.inventoryerp.fleet.application.FleetReportExporter.Table;
import com.inventoryerp.shared.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CSV / XLSX / PDF downloads of the fleet 1.9 reports: runs the report with the same filters as its JSON route and
 * renders its rows + totals with {@link FleetReportExporter} (English column headers; amounts in the organization's
 * currency, quantities in t / km / h / L).
 */
@Service
public class FleetReportExportService {

    /** Every filter any report takes (unused ones are ignored by the report). */
    public record ExportParams(LocalDate from, LocalDate to, String groupBy, String vehicleId, String categoryId,
            String branchId, String partnerId, String siteId, String driverId, String carrier,
            String transporterPartnerId, String deliveryOrderId, LocalDate asOf, Integer days, String kind) {}

    /** Reports that can be exported (path segment of {@code /fleet/reports/{report}/export}). */
    public static final List<String> REPORTS = List.of("vehicle-costs", "delivery-profitability", "deliveries",
            "tonnes", "transport-cost", "utilisation", "fuel", "renewals");

    private final FleetCostingService costing;
    private final FleetReportService reports;

    public FleetReportExportService(FleetCostingService costing, FleetReportService reports) {
        this.costing = costing;
        this.reports = reports;
    }

    @Transactional(readOnly = true)
    public Export export(String report, String format, ExportParams q) {
        if (!List.of("csv", "xlsx", "pdf").contains(format == null ? "csv" : format)) {
            throw ApiException.badRequest("format must be one of the following values: csv, xlsx, pdf");
        }
        Map<String, Object> data;
        String title;
        List<Column> cols;
        switch (report) {
            case "vehicle-costs" -> {
                data = costing.report(new CostQuery(q.from(), q.to(), q.groupBy(), q.vehicleId(), q.categoryId(),
                        q.branchId()));
                title = "Fleet cost of ownership";
                cols = List.of(Column.text("label", "Group"), Column.num("depreciation", "Depreciation"),
                        Column.num("fuel", "Fuel"), Column.num("maintenance", "Maintenance"),
                        Column.num("tyres", "Tyres"), Column.num("insurance", "Insurance"),
                        Column.num("compliance", "Licences & permits"), Column.num("incidents", "Incidents"),
                        Column.num("allowances", "Driver allowances"), Column.num("fines", "Fines"),
                        Column.num("total", "Total cost"), Column.num("km", "km"), Column.num("hours", "Engine h"),
                        Column.num("tonnes", "Tonnes"), Column.num("trips", "Trips"),
                        Column.num("perKm", "Cost / km"), Column.num("perHour", "Cost / h"),
                        Column.num("perTonne", "Cost / t"), Column.num("perTrip", "Cost / trip"));
            }
            case "delivery-profitability" -> {
                data = reports.profitability(new ProfitQuery(q.from(), q.to(), q.groupBy(), q.partnerId(),
                        q.siteId(), q.vehicleId(), q.carrier(), q.transporterPartnerId(), q.deliveryOrderId()));
                title = "Delivery profitability";
                cols = List.of(Column.text("label", "Group"), Column.num("trips", "Trips"),
                        Column.num("tonnes", "Tonnes"), Column.num("revenue", "Goods revenue"),
                        Column.num("transportCharge", "Transport charge"), Column.num("productCost", "Product cost"),
                        Column.num("tripCost", "Trip cost"), Column.num("margin", "Margin"),
                        Column.num("marginPct", "Margin %"));
            }
            case "deliveries" -> {
                data = reports.deliveries(delivery(q));
                title = "Deliveries completed and on time";
                cols = List.of(Column.text("label", "Group"), Column.num("trips", "Deliveries"),
                        Column.num("orders", "Delivery orders"), Column.num("onTime", "On time"),
                        Column.num("late", "Late"), Column.num("onTimePct", "On time %"),
                        Column.num("tonnes", "Tonnes"), Column.num("km", "km"));
            }
            case "tonnes" -> {
                data = reports.tonnes(delivery(q));
                title = "Tonnes delivered";
                cols = List.of(Column.text("label", "Group"), Column.num("trips", "Trips"),
                        Column.num("quantity", "Quantity"), Column.num("tonnes", "Tonnes"),
                        Column.num("tonnesPerTrip", "t / trip"));
            }
            case "transport-cost" -> {
                data = reports.transportCost(delivery(q));
                title = "Transport cost per tonne";
                cols = List.of(Column.text("label", "Group"), Column.num("trips", "Trips"),
                        Column.num("tonnes", "Tonnes"), Column.num("km", "km"),
                        Column.num("transportCost", "Transport cost"), Column.num("costPerTonne", "Cost / t"),
                        Column.num("costPerKm", "Cost / km"), Column.num("transportCharge", "Charged to customers"),
                        Column.num("recoveryPct", "Recovery %"));
            }
            case "utilisation" -> {
                data = reports.utilisation(new UnitQuery(q.from(), q.to(), q.groupBy(), q.vehicleId(), q.categoryId(),
                        q.branchId()));
                title = "Fleet utilisation and availability";
                cols = List.of(Column.text("label", "Group"), Column.num("units", "Units"),
                        Column.num("activeDays", "Active days"), Column.num("periodDays", "Days"),
                        Column.num("utilisationPct", "Utilisation %"), Column.num("availabilityPct", "Availability %"),
                        Column.num("downtimeHours", "Downtime h"), Column.num("trips", "Trips"),
                        Column.num("km", "km"), Column.num("hours", "Engine h"), Column.num("tonnes", "Tonnes"));
            }
            case "fuel" -> {
                data = reports.fuel(new UnitQuery(q.from(), q.to(), q.groupBy(), q.vehicleId(), q.categoryId(),
                        q.branchId()));
                title = "Fuel report";
                cols = List.of(Column.text("label", "Group"), Column.num("logs", "Logs"),
                        Column.num("litres", "Litres"), Column.num("stationLitres", "Station L"),
                        Column.num("tankLitres", "Tank L"), Column.num("cost", "Cost"), Column.num("km", "km"),
                        Column.num("lPer100Km", "L/100 km"), Column.num("lPerHour", "L/h"),
                        Column.num("costPerKm", "Cost / km"), Column.num("openAnomalies", "Open anomalies"));
            }
            case "renewals" -> {
                data = reports.renewals(new RenewalQuery(q.asOf(), q.days(), q.kind()));
                title = "Upcoming renewals and services";
                cols = List.of(Column.text("kind", "Kind"), Column.text("item", "Item"),
                        Column.text("label", "Vehicle / driver"), Column.text("reference", "Reference"),
                        Column.text("dueOn", "Due on"), Column.num("daysLeft", "Days left"),
                        Column.text("state", "State"));
            }
            default -> throw ApiException.notFound("Report not found");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) data.get("totals");
        String subtitle = data.containsKey("from") ? "Period " + data.get("from") + " to " + data.get("to")
                + (data.get("groupBy") != null ? " · by " + data.get("groupBy") : "")
                : "As of " + data.get("asOf") + " · next " + data.get("days") + " days";
        String stamp = data.containsKey("from") ? data.get("from") + "_" + data.get("to") : String.valueOf(
                data.get("asOf"));
        return FleetReportExporter.render(new Table(title, subtitle, cols, rows, totals), format == null ? "csv"
                : format, "fleet-" + report + "_" + stamp);
    }

    private static DeliveryQuery delivery(ExportParams q) {
        return new DeliveryQuery(q.from(), q.to(), q.groupBy(), q.partnerId(), q.siteId(), q.vehicleId(),
                q.driverId(), q.transporterPartnerId(), q.carrier());
    }
}
