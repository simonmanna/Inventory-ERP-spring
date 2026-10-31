package com.inventoryerp.fixedasset.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Open asset maintenance whose next date passed at least the threshold, in days, ago. */
@Component
public class AssetMaintenanceAlertSource implements AlertSource {

    private final JdbcClient jdbc;

    public AssetMaintenanceAlertSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String alertType() {
        return AlertTypes.OVERDUE_MAINTENANCE;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertItem> collect(AlertScan scan) {
        int grace = scan.threshold() == null ? 0 : scan.threshold().intValue();
        Instant cutoff = scan.asOf().minusDays(grace).atStartOfDay(BusinessZone.get()).toInstant();
        List<AlertItem> out = new ArrayList<>();
        for (Map<String, Object> m : SqlRows.list(jdbc.sql("SELECT m.id, m.\"assetId\", m.title,"
                + " m.\"nextMaintenanceDate\", a.\"assetCode\", a.name FROM \"AssetMaintenance\" m JOIN \"Asset\" a ON"
                + " a.id = m.\"assetId\" WHERE m.\"organizationId\" = ? AND m.status <> 'completed' AND"
                + " m.\"nextMaintenanceDate\" < ? ORDER BY m.\"nextMaintenanceDate\" LIMIT 200")
                .params(scan.organizationId(), SqlRows.ts(cutoff)))) {
            LocalDate due = ((Instant) m.get("nextMaintenanceDate")).atZone(BusinessZone.get()).toLocalDate();
            out.add(new AlertItem("asset:" + m.get("id"), AlertItem.WARNING, "Maintenance of " + m.get("assetCode")
                    + " " + m.get("name") + " was due on " + due, String.valueOf(m.get("title")), "/fixed-assets/"
                    + m.get("assetId"), Obj.of("dueOn", due.toString())));
        }
        return out;
    }
}
