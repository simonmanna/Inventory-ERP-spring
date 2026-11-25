package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Quality alerts open longer than the rule's threshold (days; ADR-S087 §8). Read-only, as the SPI requires. */
@Component
public class QcAlertAgingSource implements AlertSource {

    private final QcDb db;

    public QcAlertAgingSource(QcDb db) {
        this.db = db;
    }

    @Override
    public String alertType() {
        return AlertTypes.QUALITY_ALERT_OPEN;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertItem> collect(AlertScan scan) {
        int days = scan.threshold() == null ? 3 : Math.max(0, scan.threshold().intValue());
        List<AlertItem> out = new ArrayList<>();
        for (Map<String, Object> a : SqlRows.list(db.jdbc().sql("SELECT a.id, a.\"alertNumber\", a.title, a.priority,"
                + " s.name AS \"stageName\", (CURRENT_DATE - a.\"createdAt\"::date) AS age FROM \"QcAlert\" a JOIN"
                + " \"QcAlertStage\" s ON s.id = a.\"stageId\" WHERE a.\"organizationId\" = ? AND NOT s.\"isClosed\" AND"
                + " a.\"createdAt\" < now() - make_interval(days => ?) ORDER BY a.priority DESC, a.\"createdAt\" LIMIT 200")
                .params(scan.organizationId(), days))) {
            int priority = QcUtil.integer(a, "priority");
            out.add(new AlertItem("qc_alert:" + a.get("id"), priority >= 2 ? AlertItem.CRITICAL : AlertItem.WARNING,
                    "Quality alert " + a.get("alertNumber") + " open for " + a.get("age") + " day(s)",
                    a.get("title") + " (" + a.get("stageName") + ")", "/quality-control/alerts/" + a.get("id"),
                    Obj.of("priority", priority, "age", new BigDecimal(String.valueOf(a.get("age"))))));
        }
        return out;
    }
}
