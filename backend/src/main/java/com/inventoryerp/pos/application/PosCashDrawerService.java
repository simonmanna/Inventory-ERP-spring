package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.common.OccurredAt;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cash-drawer openings recorded by the POS (LP2b, owner decision 2026-11-19). Each opening is one append-only
 * {@code CashDrawerOpening} row; loss prevention projects it as a {@code cash_drawer.opened} ledger event, which C11
 * counts (no-sale openings per cashier) and V-C1 takes as the reason a drawer was seen open.
 */
@Service
public class PosCashDrawerService {

    /** Why a drawer opened; the same list as loss prevention's {@code cash_drawer.opened} {@code payload.reason}. */
    public static final List<String> REASONS = List.of("sale", "refund", "cash_movement", "no_sale", "float", "other");

    /** What an opening may belong to, and the table each lives in. */
    private static final Map<String, String> REFERENCE_TABLES = Map.of("invoice", "Invoice", "refund", "PosRefund",
            "cash_movement", "CashMovement");

    /** One opening to record. {@code sessionId} and {@code cashRegisterId} are optional (see {@link #record}). */
    public record OpeningInput(String sessionId, String cashRegisterId, String reason, String note,
            String referenceType, String referenceId, String occurredAt) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventBus events;
    private final TransactionTemplate tx;

    public PosCashDrawerService(PosDb db, AuditService audit, EventBus events, PlatformTransactionManager txm) {
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.events = events;
        this.tx = new TransactionTemplate(txm);
    }

    /**
     * Records a drawer opening by the caller. The till is the session's register, or {@code cashRegisterId}; without
     * either, the caller's one open shift. A {@code no_sale} opening needs the caller's own open shift on that till;
     * any other reason links the shift open on the till when there is one.
     */
    public Map<String, Object> record(OpeningInput in) {
        String organizationId = PosSql.org();
        String userId = TenantContext.userId();
        if (in.reason() == null || !REASONS.contains(in.reason())) {
            throw ApiException.badRequest("Reason must be sale, refund, cash_movement, no_sale, float or other");
        }
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (note != null && note.length() > 500) {
            throw ApiException.badRequest("The note must be 500 characters or fewer");
        }
        if ((in.referenceType() == null) != (in.referenceId() == null)) {
            throw ApiException.badRequest("referenceType and referenceId are given together");
        }
        if (in.referenceType() != null && !REFERENCE_TABLES.containsKey(in.referenceType())) {
            throw ApiException.badRequest("referenceType must be invoice, refund or cash_movement");
        }
        Instant resolved = OccurredAt.resolve(in.occurredAt());
        Instant occurredAt = resolved != null ? resolved : Instant.now();
        boolean noSale = "no_sale".equals(in.reason());
        return tx.execute(st -> {
            Map<String, Object> session = session(organizationId, userId, in, noSale);
            String registerId = session != null ? (String) session.get("cashRegisterId") : in.cashRegisterId();
            Map<String, Object> register = SqlRows.one(jdbc.sql("SELECT id, \"branchId\" FROM \"CashRegister\" WHERE"
                    + " id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(registerId, organizationId));
            if (register == null) {
                throw ApiException.notFound("Cash register not found");
            }
            if (in.referenceType() != null) {
                String table = REFERENCE_TABLES.get(in.referenceType());
                boolean found = jdbc.sql("SELECT count(*) FROM \"" + table + "\" WHERE id = ? AND \"organizationId\" = ?")
                        .params(in.referenceId(), organizationId).query(Long.class).single() > 0;
                if (!found) {
                    throw ApiException.notFound("The referenced " + in.referenceType().replace('_', ' ')
                            + " was not found");
                }
            }
            String branchId = session != null && session.get("branchId") != null ? (String) session.get("branchId")
                    : (String) register.get("branchId");
            String sessionId = session == null ? null : (String) session.get("id");
            String id = UUID.randomUUID().toString();
            jdbc.sql("INSERT INTO \"CashDrawerOpening\" (id, \"organizationId\", \"branchId\", \"cashSessionId\","
                    + " \"cashRegisterId\", \"userId\", reason, note, \"referenceType\", \"referenceId\", \"occurredAt\")"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").params(id, organizationId, branchId, sessionId,
                            registerId, userId, in.reason(), note, in.referenceType(), in.referenceId(),
                            SqlRows.ts(occurredAt)).update();
            audit.recordInTx(AuditInput.of("CashDrawerOpening", id, "create", null, Obj.of("cashSessionId", sessionId,
                    "cashRegisterId", registerId, "reason", in.reason(), "referenceType", in.referenceType(),
                    "referenceId", in.referenceId())));
            events.publishOnCommit(DomainEvents.POS_CASH_DRAWER_OPENED, Obj.of("organizationId", organizationId,
                    "openingId", id, "cashRegisterId", registerId, "cashSessionId", sessionId, "userId", userId,
                    "reason", in.reason()));
            Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"CashDrawerOpening\" WHERE id = ?").param(id));
            BusinessOutcome.record(jdbc, row, true);
            return row;
        });
    }

    /** The shift the opening belongs to; null only for a reason other than {@code no_sale} on a till with none open. */
    private Map<String, Object> session(String organizationId, String userId, OpeningInput in, boolean noSale) {
        if (in.sessionId() != null) {
            Map<String, Object> s = SqlRows.one(jdbc.sql("SELECT id, \"cashRegisterId\", \"branchId\", \"userId\","
                    + " status::text AS status FROM \"CashSession\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(in.sessionId(), organizationId));
            if (s == null) {
                throw ApiException.notFound("No open cash session");
            }
            if (!"open".equals(s.get("status"))) {
                throw ApiException.badRequest("Session is not open");
            }
            if (noSale && !Objects.equals(s.get("userId"), userId)) {
                throw ApiException.forbidden("This cash session belongs to a different cashier");
            }
            if (in.cashRegisterId() != null && !in.cashRegisterId().equals(s.get("cashRegisterId"))) {
                throw ApiException.badRequest("This cash session is on a different register");
            }
            return s;
        }
        String base = "SELECT id, \"cashRegisterId\", \"branchId\", \"userId\", status::text AS status FROM"
                + " \"CashSession\" WHERE \"organizationId\" = ? AND status = 'open'";
        if (in.cashRegisterId() != null) {
            List<Map<String, Object>> open = SqlRows.list(jdbc.sql(base + " AND \"cashRegisterId\" = ?"
                    + " ORDER BY \"openedAt\" LIMIT 1").params(organizationId, in.cashRegisterId()));
            Map<String, Object> s = open.isEmpty() ? null : open.getFirst();
            if (noSale && (s == null || !Objects.equals(s.get("userId"), userId))) {
                throw ApiException.notFound("No open session on this register");
            }
            return s;
        }
        List<Map<String, Object>> mine = SqlRows.list(jdbc.sql(base + " AND \"userId\" = ? ORDER BY \"openedAt\""
                + " LIMIT 2").params(organizationId, userId));
        if (mine.isEmpty()) {
            throw ApiException.notFound("No open cash session");
        }
        if (mine.size() > 1) {
            throw ApiException.badRequest("You have shifts open on more than one register; choose the shift");
        }
        return mine.getFirst();
    }
}
