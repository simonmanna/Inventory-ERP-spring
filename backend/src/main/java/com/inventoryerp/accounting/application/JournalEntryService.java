package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.Journal;
import com.inventoryerp.accounting.domain.JournalEntry;
import com.inventoryerp.accounting.domain.JournalLine;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manual journal entries (port of {@code journal-entry.service.ts}): list, detail, maker-checker, reverse. */
@Service
public class JournalEntryService {

    private final EntityManager em;
    private final PostingService posting;

    public JournalEntryService(EntityManager em, PostingService posting) {
        this.em = em;
        this.posting = posting;
    }

    /** Newest first, with {@code journal} and {@code _count.lines}; pageSize capped at 200. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(Integer page, Integer pageSize, String search) {
        int p = Math.max(1, page == null || page == 0 ? 1 : page);
        int ps = Math.min(200, Math.max(1, pageSize == null || pageSize == 0 ? 25 : pageSize));
        boolean filtered = search != null && !search.isEmpty();
        String where = filtered
                ? " where lower(e.entryNumber) like :q escape '\\' or lower(e.description) like :q escape '\\'" : "";
        var q = em.createQuery("select e from JournalEntry e" + where + " order by e.postingDate desc", JournalEntry.class);
        var c = em.createQuery("select count(e) from JournalEntry e" + where, Long.class);
        if (filtered) {
            String pattern = "%" + BaseCrudService.escapeLike(search.toLowerCase()) + "%";
            q.setParameter("q", pattern);
            c.setParameter("q", pattern);
        }
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
        JournalEntry e = em.createQuery("select e from JournalEntry e where e.id = :id", JournalEntry.class)
                .setParameter("id", id).getResultList().stream().findFirst().orElse(null);
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
