package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.notifications.NotificationsService;
import com.inventoryerp.kernel.notifications.NotificationsService.SendInput;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** In-app alerts to the people responsible for a sales document. A failed alert never fails the document. */
@Component
public class SalesAlerts {

    private static final Logger log = LoggerFactory.getLogger(SalesAlerts.class);

    private final NotificationsService notifications;

    public SalesAlerts(NotificationsService notifications) {
        this.notifications = notifications;
    }

    /** Sends to {@code userId}; nothing happens without a recipient. */
    public void send(String userId, String category, String title, String body, Map<String, Object> payload) {
        if (userId == null) {
            return;
        }
        try {
            notifications.send(new SendInput(org(), userId, "in_app", category, title, body, payload));
        } catch (RuntimeException e) {
            log.warn("Sales alert {} not sent: {}", category, e.getMessage());
        }
    }

    /** Alert the order's owner, falling back to its creator. */
    void order(Map<String, Object> order, String category, String title, String body) {
        String to = order.get("ownerId") != null ? str(order.get("ownerId")) : str(order.get("createdById"));
        send(to, category, title, body, Obj.of("entityType", "sales_order", "entityId", order.get("id")));
    }
}
