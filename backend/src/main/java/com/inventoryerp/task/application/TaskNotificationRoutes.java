package com.inventoryerp.task.application;

import com.inventoryerp.notificationrouting.spi.Party;
import com.inventoryerp.notificationrouting.spi.RoutedEvent;
import com.inventoryerp.notificationrouting.spi.RoutedEventSource;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Operational tasks ({@code /tasks}, ADR-S065). */
@Component
@ConditionalOnProperty(name = "app.modules.tasks", havingValue = "true")
public class TaskNotificationRoutes implements RoutedEventSource {

    @Override
    public List<RoutedEvent> routedEvents() {
        return List.of(RoutedEvent.on("task.assigned", "taskId")
                .area("projects")
                .label("Operational task assigned")
                .record("SELECT title FROM \"Task\" WHERE id = :id AND \"organizationId\" = :org AND \"assignedToId\" ="
                        + " :assignedToId")
                .party(Party.payload("assignee", "Assignee", "assignedToId"))
                .party(Party.column("supervisor", "Supervisor", "Task", "supervisorId"))
                .defaults("assignee")
                .title("Task assigned to you: {title}")
                .href("/tasks/{id}/edit")
                .build());
    }
}
