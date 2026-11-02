package com.inventoryerp.kernel.approvals;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separation of duties on the brief's sensitive pairs (CTRL-1): whoever made a document may not also clear it. Each
 * pair key is also the approval entity type of that pair, so {@code ApprovalsService.decide} applies the same rule to
 * inbox decisions and an approved request by someone else clears the pair. A pair is enforced only once a tenant
 * switches it on (see docs/DECISIONS_FOR_OWNER.md), except {@code alwaysOn} ones, which the owning service enforces in
 * code. Every check of an enforced pair lands in the audit log as entity {@value #AUDIT_ENTITY}: a refusal as
 * {@code reject}, a pass as {@code approve}. Refusals are written after the rollback, so they survive it.
 */
@Service
public class SegregationOfDutiesService {

    public static final String WEIGHBRIDGE_EXCEPTION = "weighbridge_exception";
    public static final String STOCK_ADJUSTMENT = "stock_adjustment";
    public static final String PRODUCTION_LOSS_WRITE_OFF = "production_loss_writeoff";
    public static final String CREDIT_OVERRIDE = "credit_override";
    public static final String SUPPLIER_BILL_APPROVAL = "supplier_bill_approval";
    /** EX-2 (ADR-S052): whoever raised, approved or claims an expense cannot also pay it. */
    public static final String EXPENSE_PAYMENT = "expense_payment";

    public static final String AUDIT_ENTITY = "SodCheck";

    /** One sensitive pair of the catalogue. */
    public record Pair(String key, String name, String maker, String checker, boolean alwaysOn) {}

    private static final Map<String, Pair> PAIRS = new LinkedHashMap<>();

    static {
        for (Pair p : List.of(
                new Pair(WEIGHBRIDGE_EXCEPTION, "Weighbridge exception approval", "Weighs the ticket",
                        "Approves the exception", true),
                new Pair(STOCK_ADJUSTMENT, "Stock adjustment", "Creates the adjustment", "Approves and posts it",
                        false),
                new Pair(PRODUCTION_LOSS_WRITE_OFF, "Production loss write-off", "Runs the production order",
                        "Writes off the loss", false),
                new Pair(CREDIT_OVERRIDE, "Credit override", "Raises the sale", "Overrides the credit hold", false),
                new Pair(SUPPLIER_BILL_APPROVAL, "Supplier bill approval", "Enters the bill", "Approves it", false),
                new Pair(EXPENSE_PAYMENT, "Expense payment", "Raises, approves or claims the expense", "Pays it",
                        false))) {
            PAIRS.put(p.key(), p);
        }
    }

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public SegregationOfDutiesService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Whether the pair is enforced in this organization; {@code false} for anything outside the catalogue. */
    public boolean enforced(String pairKey) {
        Pair pair = PAIRS.get(pairKey);
        if (pair == null) {
            return false;
        }
        return pair.alwaysOn() || jdbc.sql("SELECT count(*) FROM \"SodRule\" WHERE \"organizationId\" = ? AND"
                + " \"pairKey\" = ? AND \"isActive\" = TRUE").params(org(), pairKey).query(Long.class).single() > 0;
    }

    /**
     * Refuse the current user when they are one of the document's makers, unless someone already approved the
     * document through the engine. Does nothing while the pair is not enforced.
     *
     * @param auditEntity entity name of the document, for the audit trail
     * @param makers user ids of whoever made the document; nulls are ignored
     */
    public void assertSeparated(String pairKey, String auditEntity, String entityId, Collection<String> makers) {
        if (!enforced(pairKey) || approvedByRequest(pairKey, entityId)) {
            return;
        }
        assertNotMaker(pairKey, auditEntity, entityId, TenantContext.userId(), makers);
    }

    /** The core check, for callers that know the pair is enforced. */
    void assertNotMaker(String pairKey, String auditEntity, String entityId, String actorId,
            Collection<String> makers) {
        List<String> named = makers.stream().filter(Objects::nonNull).toList();
        if (named.contains(actorId)) {
            recordBlocked(pairKey, auditEntity, entityId, actorId, named);
            throw ApiException.forbidden("Separation of duties: the person who made this document cannot also clear it ("
                    + PAIRS.get(pairKey).name() + ")");
        }
        audit.record(AuditInput.of(AUDIT_ENTITY, entityId, "approve", null, Obj.of("pair", pairKey, "document",
                auditEntity, "actorId", actorId, "makers", named, "outcome", "passed")));
    }

    /** Audit a refusal made by a service that enforces its pair itself. */
    public void recordBlocked(String pairKey, String auditEntity, String entityId, String actorId,
            Collection<String> makers) {
        audit.record(AuditInput.of(AUDIT_ENTITY, entityId, "reject", null, Obj.of("pair", pairKey, "document",
                auditEntity, "actorId", actorId, "makers", new ArrayList<>(makers), "outcome", "blocked")));
    }

    private boolean approvedByRequest(String pairKey, String entityId) {
        return jdbc.sql("SELECT count(*) FROM \"ApprovalRequest\" WHERE \"organizationId\" = ? AND \"entityType\" = ?"
                        + " AND \"entityId\" = ? AND status = 'approved'")
                .params(org(), pairKey, entityId).query(Long.class).single() > 0;
    }

    /** The catalogue with this organization's switches. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT * FROM \"SodRule\" WHERE \"organizationId\" = ?")
                .param(org()))) {
            rows.put((String) r.get("pairKey"), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pair p : PAIRS.values()) {
            Map<String, Object> r = rows.get(p.key());
            out.add(Obj.of("pairKey", p.key(), "name", p.name(), "maker", p.maker(), "checker", p.checker(),
                    "alwaysOn", p.alwaysOn(),
                    "isActive", p.alwaysOn() || r != null && Boolean.TRUE.equals(r.get("isActive")),
                    "notes", r == null ? null : r.get("notes"),
                    "updatedBy", r == null ? null : r.get("updatedBy"),
                    "updatedAt", r == null ? null : r.get("updatedAt")));
        }
        return out;
    }

    /** Refuse a switch on an unknown or code-enforced pair, before anything is held for approval. */
    public void assertSwitchable(String pairKey, boolean isActive) {
        Pair pair = PAIRS.get(pairKey);
        if (pair == null) {
            throw ApiException.notFound("Unknown separation-of-duties pair");
        }
        if (pair.alwaysOn() && !isActive) {
            throw ApiException.badRequest("This pair is enforced in code and cannot be switched off");
        }
    }

    /** Switch a pair on or off. */
    @Transactional
    public List<Map<String, Object>> setRule(String pairKey, boolean isActive, String notes) {
        assertSwitchable(pairKey, isActive);
        jdbc.sql("INSERT INTO \"SodRule\" (id, \"organizationId\", \"pairKey\", \"isActive\", notes, \"updatedBy\","
                        + " \"createdAt\", \"updatedAt\") VALUES (?, ?, ?, ?, ?, ?, " + NOW + ", " + NOW + ")"
                        + " ON CONFLICT (\"organizationId\", \"pairKey\") DO UPDATE SET \"isActive\" ="
                        + " EXCLUDED.\"isActive\", notes = EXCLUDED.notes, \"updatedBy\" = EXCLUDED.\"updatedBy\","
                        + " \"updatedAt\" = EXCLUDED.\"updatedAt\"")
                .params(UUID.randomUUID().toString(), org(), pairKey, isActive, notes, TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("SodRule", pairKey, "update", null, Obj.of("pairKey", pairKey, "isActive",
                isActive, "notes", notes)));
        return list();
    }
}
