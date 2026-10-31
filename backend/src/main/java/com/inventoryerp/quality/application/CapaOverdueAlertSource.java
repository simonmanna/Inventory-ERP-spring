package com.inventoryerp.quality.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.Obj;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** CAPAs and their actions past due, from {@link QualityCapaService#overdue}. */
@Component
public class CapaOverdueAlertSource implements AlertSource {

    private final QualityCapaService capas;

    public CapaOverdueAlertSource(QualityCapaService capas) {
        this.capas = capas;
    }

    @Override
    public String alertType() {
        return AlertTypes.CAPA_OVERDUE;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<AlertItem> collect(AlertScan scan) {
        Map<String, Object> overdue = capas.overdue();
        List<AlertItem> out = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) overdue.get("capas")) {
            out.add(new AlertItem("capa:" + c.get("id"), AlertItem.WARNING, "CAPA " + c.get("capaNumber") + " is "
                    + c.get("daysOverdue") + " day(s) overdue", String.valueOf(c.get("title")), "/quality/capas/"
                    + c.get("id"), Obj.of("dueDate", String.valueOf(c.get("dueDate")))));
        }
        for (Map<String, Object> a : (List<Map<String, Object>>) overdue.get("actions")) {
            out.add(new AlertItem("action:" + a.get("id"), AlertItem.WARNING, "An action of CAPA " + a.get("capaNumber")
                    + " is " + a.get("daysOverdue") + " day(s) overdue", a.get("description") + (a.get("ownerName")
                            == null ? "" : " (" + a.get("ownerName") + ")"), "/quality/capas/" + a.get("capaId"),
                    Obj.of("dueDate", String.valueOf(a.get("dueDate")))));
        }
        return out;
    }
}
