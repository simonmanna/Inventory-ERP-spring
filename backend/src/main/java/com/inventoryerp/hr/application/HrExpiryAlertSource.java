package com.inventoryerp.hr.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Employee documents and fixed-term contracts ending inside the lead days of the threshold, or already past them. */
@Component
public class HrExpiryAlertSource implements AlertSource {

    private final HrDocumentsService documents;
    private final HrContractService contracts;

    public HrExpiryAlertSource(HrDocumentsService documents, HrContractService contracts) {
        this.documents = documents;
        this.contracts = contracts;
    }

    @Override
    public String alertType() {
        return AlertTypes.EXPIRING_DOCUMENT;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static String employee(Map<String, Object> row) {
        Map<String, Object> e = (Map<String, Object>) row.get("employee");
        if (e == null) {
            return "an employee";
        }
        return e.get("employeeCode") + " " + e.get("firstName") + (e.get("lastName") == null ? ""
                : " " + e.get("lastName"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertItem> collect(AlertScan scan) {
        int days = scan.threshold() == null ? 30 : scan.threshold().intValue();
        List<AlertItem> out = new ArrayList<>();
        for (Map<String, Object> d : rows(documents.expiring(days))) {
            Instant at = HrDb.jsDate(d.get("expiresAt"));
            LocalDate expires = at.atZone(BusinessZone.get()).toLocalDate();
            boolean expired = expires.isBefore(scan.asOf());
            out.add(new AlertItem("document:" + d.get("id") + (expired ? ":expired" : ":expiring"), expired
                    ? AlertItem.CRITICAL : AlertItem.WARNING, d.get("title") + " of " + employee(d) + (expired
                            ? " expired on " : " expires on ") + expires, "Renew the document.", "/hr/documents",
                    Obj.of("documentType", String.valueOf(d.get("documentType")), "expiresOn", expires.toString())));
        }
        for (Map<String, Object> c : rows(contracts.expiring(Map.of("days", String.valueOf(days))))) {
            out.add(new AlertItem("contract:" + c.get("id"), AlertItem.WARNING, "Contract " + c.get("code") + " of "
                    + employee(c) + " ends on " + c.get("endDate"), "Renew or end the contract.", "/hr/contracts",
                    Obj.of("endsOn", String.valueOf(c.get("endDate")))));
        }
        return out;
    }
}
