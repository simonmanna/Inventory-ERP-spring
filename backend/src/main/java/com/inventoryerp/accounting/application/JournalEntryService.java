package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.Journal;
import com.inventoryerp.accounting.domain.JournalEntry;
import com.inventoryerp.accounting.domain.JournalLine;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import com.inventoryerp.accounting.domain.JournalEntryStatus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manual journal entries (port of {@code journal-entry.service.ts}): list, detail, maker-checker, reverse. */
@Service
public class JournalEntryService {

    private final EntityManager em;
    private final PostingService posting;
    private final JdbcClient jdbc;

    public JournalEntryService(EntityManager em, PostingService posting, JdbcClient jdbc) {
        this.em = em;
        this.posting = posting;
        this.jdbc = jdbc;
    }

    /** Newest first, with {@code journal} and {@code _count.lines}; pageSize capped at 200. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(Integer page, Integer pageSize, String search) {
        return list(page, pageSize, search, null, null, null, null);
    }

    /**
     * As {@link #list(Integer, Integer, String)}, filtered in the query by journal, status and an inclusive org-local
     * date range, so paging and the total cover every match (review 2026-11-15: the web filtered one page).
     */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(Integer page, Integer pageSize, String search, String journalId,
            String status, String from, String to) {
        int p = Math.max(1, page == null || page == 0 ? 1 : page);
        int ps = Math.min(200, Math.max(1, pageSize == null || pageSize == 0 ? 25 : pageSize));
        List<String> conditions = new ArrayList<>(List.of("e.organizationId = :org"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("org", TenantContext.organizationId());
        if (search != null && !search.isEmpty()) {
            conditions.add("(lower(e.entryNumber) like :q escape '\\' or lower(e.description) like :q escape '\\')");
            params.put("q", "%" + BaseCrudService.escapeLike(search.toLowerCase()) + "%");
        }
        if (journalId != null && !journalId.isBlank()) {
            conditions.add("e.journalId = :journal");
            params.put("journal", journalId);
        }
        if (status != null && !status.isBlank()) {
            JournalEntryStatus s;
            try {
                s = JournalEntryStatus.fromValue(status);
            } catch (RuntimeException ex) {
                s = null;
            }
            if (s == null) {
                throw ApiException.badRequest("status must be one of the following values: draft, posted, reversed");
            }
            conditions.add("e.status = :status");
            params.put("status", s);
        }
        ZoneId zone = (from != null && !from.isBlank()) || (to != null && !to.isBlank()) ? AccountingDay.zone(jdbc) : null;
        try {
            Instant f = LedgerQueries.date(from, zone);
            Instant t = LedgerQueries.dateEnd(to, zone);
            if (f != null) {
                conditions.add("e.postingDate >= :from");
                params.put("from", f);
            }
            if (t != null) {
                conditions.add("e.postingDate <= :to");
                params.put("to", t);
            }
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("from and to must be ISO 8601 dates");
        }
        String where = " where " + String.join(" and ", conditions);
        var q = em.createQuery("select e from JournalEntry e" + where + " order by e.postingDate desc", JournalEntry.class);
        var c = em.createQuery("select count(e) from JournalEntry e" + where, Long.class);
        params.forEach((k, v) -> {
            q.setParameter(k, v);
            c.setParameter(k, v);
        });
        List<JournalEntry> rows = q.setFirstResult((p - 1) * ps).setMaxResults(ps).getResultList();
        Map<Object, Long> counts = BaseCrudService.countBy(em, "JournalLine", "journalEntryId", BaseCrudService.ids(rows));
        List<Map<String, Object>> data = rows.stream().map(e -> {
            Map<String, Object> m = EntityJson.of(e);
            m.put("journal", EntityJson.of(em.find(Journal.class, e.getJournalId())));
            m.put("_count", Map.of("lines", counts.getOrDefault(e.getId(), 0L)));
            return m;
        }).toList();
        return PageResult.of(data, p, ps, c.getSingleResult());
    }

    /** Entry with {@code journal} and {@code lines[].account}; {@code null} when not found. */
    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        JournalEntry e = em.createQuery("select e from JournalEntry e where e.id = :id and e.organizationId = :org",
                        JournalEntry.class)
                .setParameter("id", id).setParameter("org", TenantContext.organizationId())
                .getResultList().stream().findFirst().orElse(null);
        if (e == null) {
            return null;
        }
        Map<String, Object> m = EntityJson.of(e);
        m.put("journal", EntityJson.of(em.find(Journal.class, e.getJournalId())));
        m.put("lines", em.createQuery("select l from JournalLine l where l.journalEntryId = :id order by l.lineNumber asc",
                        JournalLine.class).setParameter("id", id).getResultList().stream().map(l -> {
                    Map<String, Object> lm = EntityJson.of(l);
                    lm.put("account", EntityJson.of(em.find(Account.class, l.getAccountId())));
                    return lm;
                }).toList());
        return m;
    }

    /** Maker: stage a manual entry as a draft. */
    public Map<String, Object> createManual(String journalCode, Instant date, String description, String branchId,
            String costCenterId, List<PostingRequest.Line> lines) {
        return posting.stageDraft(new PostingRequest(journalCode, date, description, null, null, null, null, null, null,
                branchId, costCenterId, null, lines));
    }

    public Map<String, Object> post(String id) {
        return posting.postDraft(id);
    }

    public void discard(String id) {
        posting.discardDraft(id);
    }

    public Map<String, Object> reverse(String id, Instant date, String description) {
        return posting.reverse(id, date, description);
    }
}
