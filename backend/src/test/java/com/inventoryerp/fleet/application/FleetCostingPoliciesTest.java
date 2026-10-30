package com.inventoryerp.fleet.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.fleet.application.FleetCostCalculator.Allocation;
import com.inventoryerp.fleet.application.FleetCostCalculator.CostBuckets;
import com.inventoryerp.fleet.application.FleetCostCalculator.Profit;
import com.inventoryerp.fleet.application.FleetCostCalculator.Rates;
import com.inventoryerp.fleet.application.FleetCostCalculator.Usage;
import com.inventoryerp.fleet.application.FleetReportExporter.Column;
import com.inventoryerp.fleet.application.FleetReportExporter.Table;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Fleet 1.9 — cost, allocation, profitability, on-time and tolerance formulas; report exporters. */
class FleetCostingPoliciesTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    static CostBuckets buckets(String dep, String fuel, String mnt, String tyres, String ins, String comp, String inc,
            String allow, String fines) {
        return new CostBuckets(d(dep), d(fuel), d(mnt), d(tyres), d(ins), d(comp), d(inc), d(allow), d(fines));
    }

    @Test
    @DisplayName("TCO = sum of every bucket; running cost leaves out depreciation; buckets add up")
    void tco() {
        CostBuckets b = buckets("100000", "500000", "100000", "800000", "10000", "2500", "-5000", "20000", "50000");
        assertThat(b.total()).isEqualByComparingTo("1577500");
        assertThat(b.running()).isEqualByComparingTo("1477500");
        CostBuckets twice = b.plus(b);
        assertThat(twice.total()).isEqualByComparingTo("3155000");
        assertThat(twice.toMap().get("fuel")).isEqualTo(d("1000000.00"));
        assertThat(CostBuckets.ZERO.total()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("unit rates: cost / km, hour, tonne, trip to 4 decimals; unknown or zero use gives no rate")
    void rates() {
        Rates r = Rates.of(d("1580000"), new Usage(d("84"), null, d("20"), 1));
        assertThat(r.perKm()).isEqualByComparingTo("18809.5238");
        assertThat(r.perHour()).isNull();
        assertThat(r.perTonne()).isEqualByComparingTo("79000");
        assertThat(r.perTrip()).isEqualByComparingTo("1580000");
        assertThat(Rates.of(d("10"), Usage.NONE).perTrip()).isNull();
        assertThat(FleetCostCalculator.rate(d("10"), BigDecimal.ZERO)).isNull();
        assertThat(FleetCostCalculator.rate(null, d("1"))).isNull();
        Usage sum = new Usage(d("10"), null, null, 1).plus(new Usage(null, d("3"), d("5"), 2));
        assertThat(sum.km()).isEqualByComparingTo("10");
        assertThat(sum.hours()).isEqualByComparingTo("3");
        assertThat(sum.tonnes()).isEqualByComparingTo("5");
        assertThat(sum.trips()).isEqualTo(3);
    }

    @Test
    @DisplayName("amortisation of a premium over its validity; depreciation pro rata by days of the month")
    void periods() {
        LocalDate issued = LocalDate.of(2026, 1, 1);
        LocalDate expires = LocalDate.of(2026, 12, 31);
        assertThat(FleetCostCalculator.amortised(d("3650000"), issued, expires, null, LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 1))).isEqualByComparingTo("10000");
        assertThat(FleetCostCalculator.amortised(d("3650000"), issued, expires, null, LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31))).isEqualByComparingTo("310000");
        // Period larger than the validity: the whole amount, once.
        assertThat(FleetCostCalculator.amortised(d("3650000"), issued, expires, null, LocalDate.of(2025, 1, 1),
                LocalDate.of(2027, 1, 1))).isEqualByComparingTo("3650000");
        assertThat(FleetCostCalculator.amortised(d("3650000"), issued, expires, null, LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 2, 1))).isEqualByComparingTo("0");
        // Without an expiry: the whole amount on its start day (or the recorded day).
        assertThat(FleetCostCalculator.amortised(d("500"), null, null, LocalDate.of(2026, 5, 5),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31))).isEqualByComparingTo("500");
        assertThat(FleetCostCalculator.amortised(d("500"), LocalDate.of(2026, 4, 30), null, null,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31))).isEqualByComparingTo("0");
        assertThat(FleetCostCalculator.amortised(null, issued, expires, null, issued, expires)).isZero();
        assertThat(FleetCostCalculator.depreciationShare("2026-02", d("2800000"), LocalDate.of(2026, 2, 10),
                LocalDate.of(2026, 3, 31))).isEqualByComparingTo("1900000");
        assertThat(FleetCostCalculator.depreciationShare("2026-02", d("2800000"), LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31))).isEqualByComparingTo("2800000");
        assertThat(FleetCostCalculator.depreciationShare("2026-02", d("2800000"), LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31))).isZero();
        assertThat(FleetCostCalculator.depreciationShare("bad", d("1"), LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31))).isZero();
        assertThat(FleetCostCalculator.overlapDays(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 10),
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 1))).isEqualTo(6);
    }

    @Test
    @DisplayName("meter use: last in period − last before (else first in period), never negative, unknown without readings")
    void meterUse() {
        assertThat(FleetCostCalculator.meterUse(d("1000"), d("1010"), d("1084"))).isEqualByComparingTo("84");
        assertThat(FleetCostCalculator.meterUse(null, d("1000"), d("1084"))).isEqualByComparingTo("84");
        assertThat(FleetCostCalculator.meterUse(d("1100"), d("1000"), d("1084"))).isEqualByComparingTo("0");
        assertThat(FleetCostCalculator.meterUse(d("1000"), null, null)).isNull();
    }

    @Test
    @DisplayName("own-fleet allocation: rate × trip quantity on the basis, with km → tonne → trip fallbacks")
    void allocation() {
        Rates r = new Rates(d("18571.4286"), null, d("78000"), d("1560000"));
        Allocation km = FleetCostCalculator.allocate("km", r, d("84"), d("20"));
        assertThat(km.amount()).isEqualByComparingTo("1560000.00");
        assertThat(km.basis()).isEqualTo("km");
        assertThat(FleetCostCalculator.allocate("tonne", r, d("84"), d("10")).amount()).isEqualByComparingTo("780000");
        assertThat(FleetCostCalculator.allocate("trip", r, d("84"), d("10")).amount()).isEqualByComparingTo("1560000");
        // No trip distance: the tonne rate; no tonnes either: per trip.
        Allocation fallback = FleetCostCalculator.allocate("km", r, null, d("5"));
        assertThat(fallback.basis()).isEqualTo("tonne");
        assertThat(fallback.amount()).isEqualByComparingTo("390000");
        assertThat(FleetCostCalculator.allocate("km", r, null, null).basis()).isEqualTo("trip");
        Allocation none = FleetCostCalculator.allocate("km", new Rates(null, null, null, null), d("1"), d("1"));
        assertThat(none.basis()).isEqualTo("none");
        assertThat(none.amount()).isZero();
        assertThat(FleetCostCalculator.allocate(null, r, d("1"), null).basis()).isEqualTo("km");
    }

    @Test
    @DisplayName("delivery margin = revenue + charge − product − trip; shares, line revenue, delivered cost")
    void profitability() {
        Profit p = FleetCostCalculator.profit(d("450000"), null, d("300000"), d("40000"));
        assertThat(p.margin()).isEqualByComparingTo("110000");
        assertThat(p.marginPct()).isEqualByComparingTo("24.44");
        assertThat(FleetCostCalculator.profit(null, null, d("10"), null).marginPct()).isNull();
        assertThat(p.toMap()).containsKeys("revenue", "transportCharge", "productCost", "tripCost", "margin",
                "marginPct");
        assertThat(FleetCostCalculator.share(d("100000"), d("20"), d("30"), 2)).isEqualByComparingTo("66666.67");
        assertThat(FleetCostCalculator.share(d("100000"), null, null, 4)).isEqualByComparingTo("25000");
        assertThat(FleetCostCalculator.share(d("100000"), d("1"), BigDecimal.ZERO, 0)).isEqualByComparingTo("100000");
        assertThat(FleetCostCalculator.share(null, d("1"), d("1"), 1)).isZero();
        assertThat(FleetCostCalculator.lineRevenue(d("20"), d("45000"), d("10"))).isEqualByComparingTo("810000");
        assertThat(FleetCostCalculator.lineRevenue(d("20"), d("45000"), null)).isEqualByComparingTo("900000");
        assertThat(FleetCostCalculator.lineRevenue(d("20"), null, null)).isZero();
        assertThat(FleetCostCalculator.deliveredCost(d("600000"), d("20"), d("5"))).isEqualByComparingTo("450000");
        assertThat(FleetCostCalculator.deliveredCost(d("600000"), d("20"), d("20"))).isZero();
        assertThat(FleetCostCalculator.deliveredCost(null, d("20"), null)).isZero();
        assertThat(FleetCostCalculator.pct(d("1"), d("3"), 1)).isEqualByComparingTo("33.3");
    }

    @Test
    @DisplayName("on time: by the requested day, on that day by the window end")
    void onTime() {
        LocalDate req = LocalDate.of(2026, 10, 29);
        assertThat(FleetCostCalculator.onTime(req, null, LocalDateTime.of(2026, 10, 28, 18, 0))).isTrue();
        assertThat(FleetCostCalculator.onTime(req, null, LocalDateTime.of(2026, 10, 29, 23, 0))).isTrue();
        assertThat(FleetCostCalculator.onTime(req, "16:00", LocalDateTime.of(2026, 10, 29, 15, 59))).isTrue();
        assertThat(FleetCostCalculator.onTime(req, "16:00", LocalDateTime.of(2026, 10, 29, 16, 30))).isFalse();
        assertThat(FleetCostCalculator.onTime(req, null, LocalDateTime.of(2026, 10, 30, 7, 0))).isFalse();
        assertThat(FleetCostCalculator.onTime(req, "xx", LocalDateTime.of(2026, 10, 29, 20, 0))).isTrue();
        assertThat(FleetCostCalculator.onTime(null, null, LocalDateTime.of(2026, 10, 29, 20, 0))).isFalse();
    }

    @Test
    @DisplayName("freight match tolerance: max(absolute, total × pct)")
    void tolerance() {
        assertThat(FleetCostCalculator.allowedVariance(d("40000"), d("1"), d("0"))).isEqualByComparingTo("400");
        assertThat(FleetCostCalculator.allowedVariance(d("40000"), d("1"), d("1000"))).isEqualByComparingTo("1000");
        assertThat(FleetCostCalculator.allowedVariance(d("40000"), null, null)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("exports: CSV guards formulas, XLSX is a valid workbook with numbers as numbers, PDF renders")
    void exporters() throws Exception {
        Table t = new Table("Fleet <costs>", "Period", List.of(Column.text("label", "Group"), Column.num("total",
                "Total")), List.of(Map.of("label", "=HYPERLINK(\"x\")", "total", d("12.50")), Map.of("label", "B")),
                Map.of("label", "Total", "total", d("12.50")));
        String csv = new String(FleetReportExporter.render(t, "csv", "r").content(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿\"Group\",\"Total\"").contains("\"'=HYPERLINK(\"\"x\"\")\",\"12.50\"")
                .contains("\"B\",\"\"").contains("\"Total\",\"12.50\"");
        assertThat(FleetReportExporter.csvCell("-12.5")).isEqualTo("\"-12.5\"");
        assertThat(FleetReportExporter.csvCell("-x")).isEqualTo("\"'-x\"");
        FleetReportExporter.Export x = FleetReportExporter.render(t, "xlsx", "r");
        assertThat(x.filename()).isEqualTo("r.xlsx");
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(x.content()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                parts.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                "xl/worksheets/sheet1.xml", "xl/styles.xml", "xl/_rels/workbook.xml.rels");
        String sheet = parts.get("xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("<v>12.50</v>").contains("Fleet &lt;costs&gt;").contains("r=\"B5\"");
        assertThat(parts.get("xl/workbook.xml")).contains("name=\"Fleet &lt;costs&gt;\"");
        FleetReportExporter.Export pdf = FleetReportExporter.render(t, "pdf", "r");
        assertThat(new String(pdf.content(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(FleetReportExporter.ref(0, 1)).isEqualTo("A1");
        assertThat(FleetReportExporter.ref(26, 3)).isEqualTo("AA3");
        assertThat(FleetReportExporter.cell(true)).isEqualTo("yes");
        assertThat(FleetReportExporter.xml("a\u0001&")).isEqualTo("a&amp;");
    }
}
