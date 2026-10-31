package com.inventoryerp.quarry.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Mining licences of open sites and magazine licences expired or inside the lead days of the threshold. */
@Component
public class QuarryLicenceAlertSource implements AlertSource {

    private final QuarryDb db;

    public QuarryLicenceAlertSource(QuarryDb db) {
        this.db = db;
    }

    @Override
    public String alertType() {
        return AlertTypes.EXPIRING_DOCUMENT;
    }

    private static AlertItem item(String kind, Map<String, Object> row, LocalDate asOf, String link) {
        LocalDate expires = (LocalDate) row.get("licenceExpiresOn");
        boolean expired = expires.isBefore(asOf);
        String number = row.get("licenceNumber") == null ? "" : " " + row.get("licenceNumber");
        return new AlertItem(kind + ":" + row.get("id") + (expired ? ":expired" : ":expiring"), expired
                ? AlertItem.CRITICAL : AlertItem.WARNING, kind.substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + kind.substring(1) + " " + row.get("code") + " licence" + number + (expired ? " expired on "
                        : " expires on ") + expires, "Renew the licence of " + row.get("name") + ".", link,
                Obj.of("licenceNumber", row.get("licenceNumber"), "expiresOn", expires.toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertItem> collect(AlertScan scan) {
        int days = scan.threshold() == null ? 30 : scan.threshold().intValue();
        java.sql.Date until = java.sql.Date.valueOf(scan.asOf().plusDays(days));
        List<AlertItem> out = new ArrayList<>();
        for (Map<String, Object> s : SqlRows.list(db.jdbc().sql("SELECT id, code, name, \"licenceNumber\","
                + " \"licenceExpiresOn\" FROM \"QuarrySite\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL AND"
                + " status = 'active' AND \"licenceExpiresOn\" <= ? ORDER BY \"licenceExpiresOn\"")
                .params(scan.organizationId(), until))) {
            out.add(item("site", s, scan.asOf(), "/quarry/sites/" + s.get("id")));
        }
        for (Map<String, Object> m : SqlRows.list(db.jdbc().sql("SELECT id, \"siteId\", code, name, \"licenceNumber\","
                + " \"licenceExpiresOn\" FROM \"QuarryMagazine\" WHERE \"organizationId\" = ? AND \"deletedAt\" IS NULL"
                + " AND \"isActive\" AND \"licenceExpiresOn\" <= ? ORDER BY \"licenceExpiresOn\"")
                .params(scan.organizationId(), until))) {
            out.add(item("magazine", m, scan.asOf(), "/quarry/sites/" + m.get("siteId")));
        }
        return out;
    }
}
