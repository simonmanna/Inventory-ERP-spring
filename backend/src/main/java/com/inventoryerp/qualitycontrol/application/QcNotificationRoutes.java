package com.inventoryerp.qualitycontrol.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.stereotype.Component;

/** Quality alerts (ADR-S087 §8): the responsible person and the alert's team hear about new and reassigned alerts. */
@Component
public class QcNotificationRoutes implements RoutedEventSource {

    private static final String AREA = "quality";

    private static final String TEAM_MEMBERS = """
            SELECT m."userId" FROM "QcAlert" a JOIN "QcTeamMember" m ON m."teamId" = a."teamId"
            WHERE a.id = :id AND a."organizationId" = :org""";

    private static RoutedEvent.Builder alert(String event, String label) {
        return RoutedEvent.on(event, "alertId")
                .area(AREA)
                .label(label)
                .record("SELECT a.\"alertNumber\", a.title, s.name AS \"stageName\" FROM \"QcAlert\" a JOIN"
                        + " \"QcAlertStage\" s ON s.id = a.\"stageId\" WHERE a.id = :id AND a.\"organizationId\" = :org")
                .party(Party.column("responsible", "Responsible for the alert", "QcAlert", "responsibleUserId"))
                .party(Party.query("team", "Members of the alert's quality team", TEAM_MEMBERS))
                .party(Party.column(Party.CREATOR, "Raised the alert", "QcAlert", "createdBy"))
                .href("/quality-control/alerts/{id}");
    }

    @Override
    public List<RoutedEvent> routedEvents() {
        return List.of(
                alert("quality_control.alert.created", "Quality alert raised")
                        .defaults("responsible", "team")
                        .title("Quality alert {alertNumber}: {title}")
                        .build(),
                alert("quality_control.alert.assigned", "Quality alert reassigned")
                        .defaults("responsible")
                        .title("Quality alert {alertNumber} assigned to you: {title}")
                        .build(),
                alert("quality_control.alert.stage_changed", "Quality alert resolved")
                        .defaults(Party.CREATOR)
                        .skipWhen("closed", "false")
                        .title("Quality alert {alertNumber} resolved: {title}")
                        .body("Moved to {stageName}.")
                        .build());
    }
}
