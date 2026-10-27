package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Journal;
import com.inventoryerp.accounting.domain.JournalType;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Journals (port of {@code journal.service.ts}) + sales-journal resolution for invoicing. */
@Service
public class JournalService extends BaseCrudService<Journal> {

    private final JdbcClient jdbc;

    public JournalService(EntityManager em, JdbcClient jdbc) {
        super(em, Journal.class);
        this.jdbc = jdbc;
    }

    @Override
    protected String entityName() {
        return "Journal";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("code", "name");
    }

    @Override
    protected List<Sort> defaultOrderBy() {
        return List.of(Sort.asc("code"));
    }


    /**
     * The journal a sales document posts to: preferred id → org setting {@code accounting.defaultSalesJournalId}
     * → active {@code SALES} → first active sales-type journal.
     */
    @Transactional(readOnly = true)
    public Journal resolveSalesJournal(String preferredId) {
        if (preferredId != null) {
            Journal preferred = active("j.id = :v", preferredId);
            if (preferred != null) {
                return preferred;
            }
        }
        String value = jdbc.sql("SELECT value::text FROM \"Setting\" WHERE \"organizationId\" = ?"
                        + " AND \"scopeType\" = 'organization' AND \"scopeId\" = ''"
                        + " AND key = 'accounting.defaultSalesJournalId' LIMIT 1")
                .param(TenantContext.organizationId()).query(String.class).optional().orElse(null);
        if (value != null) {
            JsonNode node = EntityJson.tree(value);
            String id = node == null || node.isNull() ? null : node.isString() ? node.asString() : node.toString();
            if (id != null && !id.isEmpty()) {
                Journal bySetting = active("j.id = :v", id);
                if (bySetting != null) {
                    return bySetting;
                }
            }
        }
        Journal byCode = active("j.code = :v", "SALES");
        if (byCode != null) {
            return byCode;
        }
        List<Journal> sales = em.createQuery("select j from Journal j where j.journalType = :t and j.isActive = true"
                        + " order by j.code asc", Journal.class)
                .setParameter("t", JournalType.SALES).setMaxResults(1).getResultList();
        if (!sales.isEmpty()) {
            return sales.getFirst();
        }
        throw ApiException.badRequest("No active sales journal configured. Set the Default Sales Journal in Company"
                + " Settings or create a sales journal in Journals.");
    }

    private Journal active(String where, String value) {
        return em.createQuery("select j from Journal j where " + where + " and j.isActive = true", Journal.class)
                .setParameter("v", value).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }
}
