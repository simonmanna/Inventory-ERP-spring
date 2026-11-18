package com.inventoryerp.lossprevention.application;

import com.inventoryerp.alerts.spi.AlertItem;
import com.inventoryerp.alerts.spi.AlertScan;
import com.inventoryerp.alerts.spi.AlertSource;
import com.inventoryerp.alerts.spi.AlertTypes;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code lp_agent_health}: enrolled edge agents that have been offline or degraded for at least the threshold hours.
 * Offline is critical (no observations, so no vision finding is possible); degraded is a warning.
 */
@Component
@ConditionalOnProperty(name = "app.modules.lossprevention", havingValue = "true")
public class LpAgentHealthAlertSource implements AlertSource {

    private final JdbcClient jdbc;

    public LpAgentHealthAlertSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String alertType() {
        return AlertTypes.LP_AGENT_HEALTH;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AlertItem> collect(AlertScan scan) {
        long minutes = scan.threshold() == null ? 0 : scan.threshold().multiply(java.math.BigDecimal.valueOf(60))
                .longValue();
        Instant since = Instant.now().minus(Duration.ofMinutes(Math.max(0, minutes)));
        List<AlertItem> out = new ArrayList<>();
        for (Map<String, Object> a : SqlRows.list(jdbc.sql("SELECT a.id, a.name, a.health, a.\"healthReasons\","
                + " a.\"healthSince\", a.\"lastHeartbeatAt\", b.name AS \"branchName\" FROM \"LpAgent\" a LEFT JOIN"
                + " \"Branch\" b ON b.id = a.\"branchId\" WHERE a.\"organizationId\" = ? AND a.status = 'active' AND"
                + " a.health IN ('offline', 'degraded') AND coalesce(a.\"healthSince\", a.\"updatedAt\") <= ? ORDER BY"
                + " b.name, a.name").params(scan.organizationId(), SqlRows.ts(since)))) {
            boolean offline = "offline".equals(a.get("health"));
            List<Object> reasons = LpJson.list(a.get("healthReasons"));
            String where = a.get("branchName") == null ? "" : " at " + a.get("branchName");
            out.add(new AlertItem("agent:" + a.get("id") + ":" + a.get("health"),
                    offline ? AlertItem.CRITICAL : AlertItem.WARNING,
                    "Edge agent " + a.get("name") + where + (offline ? " is offline" : " is degraded")
                            + (reasons.isEmpty() ? "" : " (" + String.join(", ", reasons.stream().map(String::valueOf)
                                    .toList()) + ")"),
                    offline ? "No heartbeat: check the edge box's power and network. No camera observation is"
                            + " recorded while it is offline." : "Check the reasons on the agent screen: camera"
                                    + " streams, disk space, clock or the observation queue.",
                    "/loss-prevention",
                    Obj.of("agentId", a.get("id"), "health", a.get("health"), "reasons", reasons, "since",
                            a.get("healthSince") == null ? null : a.get("healthSince").toString(),
                            "lastHeartbeatAt", a.get("lastHeartbeatAt") == null ? null
                                    : a.get("lastHeartbeatAt").toString())));
        }
        return out;
    }
}
