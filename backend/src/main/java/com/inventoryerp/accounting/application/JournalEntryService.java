package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.Journal;
import com.inventoryerp.accounting.domain.JournalEntry;
import com.inventoryerp.accounting.domain.JournalLine;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import com.inventoryerp.accounting.domain.JournalEntryStatus;
import com.inventoryerp.kernel.security.Permissions;
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

/**
 * Manual journal entries (port of {@code journal-entry.service.ts}): list, detail, maker-checker, reverse, and the
 * control-account adjustment variant of a manual entry (V518).
 */
@Service
public class JournalEntryService {

    private final EntityManager em;
    private final PostingService posting;
    private final JdbcClient jdbc;
    private final SubledgerSources subledger;

    public JournalEntryService(EntityManager em, PostingService posting, JdbcClient jdbc, SubledgerSources subledger) {
        this.em = em;
        this.posting = posting;
        this.jdbc = jdbc;
        this.subledger = subledger;
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
        List<JournalLine> lines = em.createQuery("select l from JournalLine l where l.journalEntryId = :id"
                + " order by l.lineNumber asc", JournalLine.class).setParameter("id", id).getResultList();
        Map<String, Map<String, Object>> partners = partners(lines);
        m.put("lines", lines.stream().map(l -> {
            Map<String, Object> lm = EntityJson.of(l);
            lm.put("account", EntityJson.of(em.find(Account.class, l.getAccountId())));
            if (l.getPartnerId() != null) {
                // Additive (control-account policy): who a control-account line belongs to.
                lm.put("partner", partners.get(l.getPartnerId()));
            }
            return lm;
        }).toList());
        // Additive (document reversals, owner decision 2026-11-17): the sub-ledger document an entry belongs to, so the
        // web hides the generic Reverse and links the document-level action instead.
        SubledgerSources.SourceDocument source = subledger.resolve(id);
        if (source != null) {
            m.put("sourceDocument", source.toMap());
        }
        return m;
    }

    /** {@code id, code, name} of the partners the lines name, by id (this organization only). */
    private Map<String, Map<String, Object>> partners(List<JournalLine> lines) {
        List<String> ids = lines.stream().map(JournalLine::getPartnerId).filter(java.util.Objects::nonNull).distinct()
                .toList();
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, code, name FROM \"Partner\" WHERE \"organizationId\" = ? AND id = ANY(?::text[])")
                .params(TenantContext.organizationId(), ids.toArray(String[]::new))
                .query((rs, n) -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("id", rs.getString("id"));
                    p.put("code", rs.getString("code"));
                    p.put("name", rs.getString("name"));
                    out.put(rs.getString("id"), p);
                    return p;
                }).list();
        return out;
    }

    /** Maker: stage a manual entry as a draft. */
    public Map<String, Object> createManual(String journalCode, Instant date, String description, String branchId,
            String costCenterId, List<PostingRequest.Line> lines) {
        return createManual(journalCode, date, description, branchId, costCenterId, lines, null, null);
    }

    /**
     * Maker: stage a manual entry as a draft; {@code controlAdjustment = true} stages a control-account adjustment
     * (owner decision, control-account policy) with its mandatory {@code controlAdjustmentReason}, see
     * {@link PostingService#stageDraft(PostingRequest, String)}. A reason without the flag is refused.
     */
    public Map<String, Object> createManual(String journalCode, Instant date, String description, String branchId,
            String costCenterId, List<PostingRequest.Line> lines, Boolean controlAdjustment,
            String controlAdjustmentReason) {
        boolean adjustment = Boolean.TRUE.equals(controlAdjustment);
        String reason = controlAdjustmentReason == null ? null : controlAdjustmentReason.strip();
        if (adjustment && (reason == null || reason.isEmpty())) {
            throw ApiException.badRequest("A control-account adjustment requires a reason (controlAdjustmentReason)");
        }
        if (!adjustment && reason != null && !reason.isEmpty()) {
            throw ApiException.badRequest("controlAdjustmentReason is only allowed with controlAdjustment = true");
        }
        return posting.stageDraft(new PostingRequest(journalCode, date, description, null, null, null, null, null, null,
                branchId, costCenterId, null, lines), adjustment ? reason : null);
    }

    public Map<String, Object> post(String id) {
        return posting.postDraft(id);
    }

    public void discard(String id) {
        posting.discardDraft(id);
    }

    /**
     * The generic reversal ({@code POST /journal-entries/{id}/reverse}). An entry posted by a customer / supplier
     * sub-ledger document (invoice, bill, credit / debit note, payment, deposit, POS sale, …) — or the reversal of one —
     * is refused with the document-level action to use instead ({@link SubledgerSources}); the document workflows
     * reverse their own entries through {@link PostingService#reverse} directly. Manual entries, control-account
     * adjustments, recurring / deferral and other system entries stay reversible here.
     */
    @Transactional
    public Map<String, Object> reverse(String id, Instant date, String description) {
        subledger.assertGenericReversible(id);
        // FX-1: the automatic reversal of an FX revaluation belongs to the revaluation lifecycle; reversing it by hand
        // would turn an unrealised gain or loss into a permanent one. Re-run the revaluation instead.
        Boolean revaluationReversal = jdbc.sql("SELECT EXISTS (SELECT 1 FROM \"JournalEntry\" e JOIN \"JournalEntry\" o"
                        + " ON o.id = e.\"reversalOfId\" WHERE e.id = ? AND e.\"organizationId\" = ?"
                        + " AND o.\"sourceType\" = 'fx_revaluation')")
                .params(id, TenantContext.organizationId()).query(Boolean.class).single();
        if (Boolean.TRUE.equals(revaluationReversal)) {
            throw ApiException.badRequest("This entry is the automatic reversal of an FX revaluation and cannot be"
                    + " reversed by hand; run the FX revaluation again for the date instead");
        }
        // Control-account policy (V518): reversing a control-account adjustment posts to AR / AP again, so it needs
        // the same permission as staging or posting one (the reversal itself is audited by PostingService).
        Boolean adjustment = jdbc.sql("SELECT \"controlAdjustment\" FROM \"JournalEntry\" WHERE id = ? AND"
                        + " \"organizationId\" = ?")
                .params(id, TenantContext.organizationId()).query(Boolean.class).optional().orElse(false);
        if (Boolean.TRUE.equals(adjustment) && !TenantContext.has(Permissions.JOURNAL_ENTRY__CONTROL_ADJUST)) {
            throw ApiException.forbidden("Reversing a control-account adjustment requires "
                    + Permissions.JOURNAL_ENTRY__CONTROL_ADJUST);
        }
        return posting.reverse(id, date, description);
    }
}
