package com.inventoryerp.kernel.notifications;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.domain.Notification;
import com.inventoryerp.kernel.domain.NotificationChannel;
import com.inventoryerp.kernel.domain.NotificationPreference;
import com.inventoryerp.kernel.domain.NotificationStatus;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The current user's in-app inbox and channel preferences ({@code notifications.controller.ts}). */
@Service
public class NotificationInboxService {

    private static final List<NotificationStatus> UNREAD = List.of(NotificationStatus.PENDING, NotificationStatus.SENT);

    private final EntityManager em;

    public NotificationInboxService(EntityManager em) {
        this.em = em;
    }

    /** {@code {data, meta, unread}}: page (default 1), pageSize (1..100, default 25), filter all|unread. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(AuthUser user, String page, String pageSize, String filter) {
        int p = (int) Math.max(1, page == null ? 1 : JsNumbers.number(page));
        int ps = (int) Math.min(100, Math.max(1, pageSize == null ? 25 : JsNumbers.number(pageSize)));
        boolean unreadOnly = "unread".equals(filter);
        String where = " where n.userId = :user and n.channel = :channel" + (unreadOnly ? " and n.status in :unread" : "");
        var q = em.createQuery("select n from Notification n" + where + " order by n.createdAt desc", Notification.class)
                .setParameter("user", user.sub())
                .setParameter("channel", NotificationChannel.IN_APP);
        var c = em.createQuery("select count(n) from Notification n" + where, Long.class)
                .setParameter("user", user.sub())
                .setParameter("channel", NotificationChannel.IN_APP);
        if (unreadOnly) {
            q.setParameter("unread", UNREAD);
            c.setParameter("unread", UNREAD);
        }
        List<Notification> rows = q.setFirstResult((p - 1) * ps).setMaxResults(ps).getResultList();
        long total = c.getSingleResult();
        long unread = em.createQuery("select count(n) from Notification n where n.userId = :user"
                        + " and n.channel = :channel and n.status in :unread", Long.class)
                .setParameter("user", user.sub())
                .setParameter("channel", NotificationChannel.IN_APP)
                .setParameter("unread", UNREAD)
                .getSingleResult();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", p);
        meta.put("pageSize", ps);
        meta.put("total", total);
        meta.put("totalPages", Math.max(1, (int) Math.ceil(total / (double) ps)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", EntityJson.list(rows));
        out.put("meta", meta);
        out.put("unread", unread);
        return out;
    }

    @Transactional
    public void markRead(AuthUser user, String id) {
        em.createQuery("update Notification n set n.status = :read, n.readAt = :now where n.id = :id and n.userId = :user"
                        + " and n.organizationId = :org")
                .setParameter("read", NotificationStatus.READ)
                .setParameter("now", Instant.now())
                .setParameter("id", id)
                .setParameter("user", user.sub())
                .setParameter("org", user.organizationId())
                .executeUpdate();
    }

    @Transactional
    public int markAllRead(AuthUser user) {
        return em.createQuery("update Notification n set n.status = :read, n.readAt = :now where n.userId = :user"
                        + " and n.organizationId = :org and n.status in :unread")
                .setParameter("read", NotificationStatus.READ)
                .setParameter("now", Instant.now())
                .setParameter("user", user.sub())
                .setParameter("org", user.organizationId())
                .setParameter("unread", UNREAD)
                .executeUpdate();
    }

    @Transactional
    public void remove(AuthUser user, String id) {
        em.createQuery("delete from Notification n where n.id = :id and n.userId = :user and n.organizationId = :org")
                .setParameter("id", id)
                .setParameter("user", user.sub())
                .setParameter("org", user.organizationId())
                .executeUpdate();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> preferences(AuthUser user) {
        return EntityJson.list(em.createQuery("select p from NotificationPreference p where p.userId = :user"
                        + " and p.organizationId = :org order by p.channel asc, p.category asc", NotificationPreference.class)
                .setParameter("user", user.sub())
                .setParameter("org", user.organizationId())
                .getResultList());
    }

    @Transactional
    public Map<String, Object> setPreference(AuthUser user, String channel, String category, boolean enabled) {
        NotificationChannel ch = DbEnum.fromValue(NotificationChannel.class, channel);
        String cat = category == null ? "general" : category;
        List<NotificationPreference> rows = em.createQuery("select p from NotificationPreference p where"
                        + " p.organizationId = :org and p.userId = :user and p.channel = :ch and p.category = :cat",
                        NotificationPreference.class)
                .setParameter("org", user.organizationId())
                .setParameter("user", user.sub())
                .setParameter("ch", ch)
                .setParameter("cat", cat)
                .getResultList();
        NotificationPreference row;
        if (rows.isEmpty()) {
            row = new NotificationPreference();
            row.setOrganizationId(user.organizationId());
            row.setUserId(user.sub());
            row.setChannel(ch);
            row.setCategory(cat);
            row.setEnabled(enabled);
            em.persist(row);
        } else {
            row = rows.getFirst();
            row.setEnabled(enabled);
        }
        em.flush();
        return EntityJson.of(row);
    }
}
