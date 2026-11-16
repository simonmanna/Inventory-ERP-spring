package com.inventoryerp.projects.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expiring bonds and guarantees and subcontractor compliance documents (ADR-S074), as {@code expiring_document}
 * alerts. The rule's threshold is the outer lead time (default 30 days); inside it a bond or document alerts again
 * at 14 and 7 days and once expired — the tier is part of the subject key, so each tier raises one alert. Bonds expire
 * when live (active, extended, reduced); a compliance document counts when it is the latest verified one of its type
 * for the supplier (or subcontract).
 */
@Component
public class ConstructionAlertSource implements AlertSource {

    private final JdbcClient jdbc;

    public ConstructionAlertSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String alertType() {
        return AlertTypes.EXPIRING_DOCUMENT;
    }

    /** {@code 30}, {@code 14}, {@code 7} or {@code expired}: the tier a remaining number of days falls in. */
    static String tier(long days) {
        return days < 0 ? "expired" : days <= 7 ? "7" : days <= 14 ? "14" : "30";
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertItem> collect(AlertScan scan) {
        int lead = scan.threshold() == null ? 30 : scan.threshold().intValue();
        LocalDate horizon = scan.asOf().plusDays(lead);
        List<AlertItem> out = new ArrayList<>();
        for (Map<String, Object> b : SqlRows.list(jdbc.sql("SELECT b.id, b.number, b.\"bondType\", b.direction,"
                + " b.issuer, b.amount, b.\"currencyCode\", b.\"expiryDate\", p.name AS counterparty, pr.code AS"
                + " \"projectCode\" FROM \"ProjectBond\" b JOIN \"Partner\" p ON p.id = b.\"counterpartyId\" LEFT JOIN"
                + " \"Project\" pr ON pr.id = b.\"projectId\" WHERE b.\"organizationId\" = ? AND b.status IN ('active',"
                + " 'extended', 'reduced') AND b.\"expiryDate\" <= ?::date ORDER BY b.\"expiryDate\" LIMIT 500")
                .params(scan.organizationId(), horizon.toString()))) {
            LocalDate expiry = (LocalDate) b.get("expiryDate");
            long days = ChronoUnit.DAYS.between(scan.asOf(), expiry);
            String tier = tier(days);
            String what = String.valueOf(b.get("bondType")).replace('_', ' ') + " bond " + b.get("number") + " ("
                    + b.get("issuer") + ", " + b.get("counterparty") + ")";
            out.add(new AlertItem("bond:" + b.get("id") + ":" + tier, days <= 7 ? AlertItem.CRITICAL
                    : AlertItem.WARNING, (days < 0 ? "Expired on " : "Expires on ") + expiry + ": " + what,
                    "issued".equals(b.get("direction")) ? "Extend or release the bond with the bank before it lapses."
                            : "Ask the subcontractor for an extension, or call the bond before it lapses.",
                    "/projects/bonds?bond=" + b.get("id"), Obj.of("expiresOn", expiry.toString(), "tier", tier,
                            "amount", String.valueOf(b.get("amount")), "currency", b.get("currencyCode"), "project",
                            b.get("projectCode"))));
        }
        for (Map<String, Object> d : SqlRows.list(jdbc.sql("SELECT d.id, d.\"documentType\", d.\"documentNumber\","
                + " d.\"validTo\", d.\"partnerId\", p.name AS supplier FROM \"ComplianceDocument\" d JOIN \"Partner\" p ON"
                + " p.id = d.\"partnerId\" WHERE d.\"organizationId\" = ? AND d.status = 'verified' AND"
                + " d.\"documentType\" <> 'lien_waiver' AND d.\"validTo\" <= ?::date AND NOT EXISTS (SELECT 1 FROM"
                + " \"ComplianceDocument\" n WHERE n.\"organizationId\" = d.\"organizationId\" AND n.\"partnerId\" ="
                + " d.\"partnerId\" AND n.\"documentType\" = d.\"documentType\" AND n.status = 'verified' AND"
                + " n.\"subcontractId\" IS NOT DISTINCT FROM d.\"subcontractId\" AND n.\"validTo\" > d.\"validTo\")"
                + " ORDER BY d.\"validTo\" LIMIT 500").params(scan.organizationId(), horizon.toString()))) {
            LocalDate to = (LocalDate) d.get("validTo");
            long days = ChronoUnit.DAYS.between(scan.asOf(), to);
            String tier = tier(days);
            String label = SubcontractComplianceService.LABELS.getOrDefault((String) d.get("documentType"),
                    String.valueOf(d.get("documentType")));
            out.add(new AlertItem("compliance:" + d.get("id") + ":" + tier, days <= 7 ? AlertItem.CRITICAL
                    : AlertItem.WARNING, label + " of " + d.get("supplier") + (days < 0 ? " expired on " : " expires on ")
                    + to, "Ask the supplier for a renewed document; gated valuations and payments stop when it lapses.",
                    "/suppliers/" + d.get("partnerId") + "?tab=compliance", Obj.of("expiresOn", to.toString(), "tier",
                            tier, "documentNumber", d.get("documentNumber"))));
        }
        return out;
    }
}
