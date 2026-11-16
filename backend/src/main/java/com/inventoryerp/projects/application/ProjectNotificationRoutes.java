package com.inventoryerp.projects.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Project tasks (ADR-S065). Assignment, milestones and project updates are left out: {@code PmSupport.notifyUsers}
 * already tells the assignees, members and followers. The project manager is only told about a completed task when not
 * already following it.
 */
@Component
public class ProjectNotificationRoutes implements RoutedEventSource {

    private static final String AREA = "projects";

    private static final String RECORD = "SELECT number, title, \"projectId\" FROM \"ProjectTask\" WHERE id = :id AND"
            + " \"organizationId\" = :org";

    private static final String MANAGER = """
            SELECT p."managerId" FROM "ProjectTask" t JOIN "Project" p ON p.id = t."projectId"
            WHERE t.id = :id AND t."organizationId" = :org""";

    private static final String NOT_FOLLOWING = " AND NOT EXISTS (SELECT 1 FROM \"ProjectTaskFollower\" f WHERE"
            + " f.\"taskId\" = t.id AND f.\"userId\" = p.\"managerId\")";

    private static final String HREF = "/projects/{projectId}?task={id}";

    @Override
    public List<RoutedEvent> routedEvents() {
        Party assignees = Party.query("assignees", "Assignees", """
                SELECT a."userId" FROM "ProjectTaskAssignee" a JOIN "ProjectTask" t ON t.id = a."taskId"
                WHERE a."taskId" = :id AND t."organizationId" = :org""");
        Party followers = Party.query("followers", "Followers", """
                SELECT f."userId" FROM "ProjectTaskFollower" f JOIN "ProjectTask" t ON t.id = f."taskId"
                WHERE f."taskId" = :id AND t."organizationId" = :org""");
        return List.of(
                RoutedEvent.on("project.task.created", "taskId")
                        .area(AREA)
                        .label("Task added to a project")
                        .record(RECORD + " AND \"projectId\" IS NOT NULL")
                        .party(Party.query("projectManager", "Project manager", MANAGER))
                        .defaults("projectManager")
                        .title("New task {number}: {title}")
                        .href(HREF)
                        .build(),
                RoutedEvent.on("project.task.stage_changed", "taskId")
                        .area(AREA)
                        .label("Task moved to another stage")
                        .record(RECORD)
                        .party(assignees)
                        .party(Party.creator("ProjectTask"))
                        .party(followers)
                        .defaults("assignees")
                        .skipWhen("closed", "true")
                        .title("Task {number} moved to {stage}")
                        .body("{title}")
                        .href(HREF)
                        .build(),
                RoutedEvent.on("project.task.completed", "taskId")
                        .area(AREA)
                        .label("Task completed")
                        .record(RECORD)
                        .party(Party.query("projectManager", "Project manager (when not following the task)",
                                MANAGER + NOT_FOLLOWING))
                        .party(Party.creator("ProjectTask"))
                        .defaults("projectManager")
                        .title("Task {number} completed")
                        .body("{title}")
                        .href(HREF)
                        .build());
    }
}
