package com.inventoryerp.hr.application;

import com.inventoryerp.hr.domain.HrPayrollInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payroll inputs: one-off earnings other modules queue for an employee's next payroll run (ADR-S009 §2 — fleet driver
 * allowance claims; 1.7 fines will add deductions). This class is hr's only public application API (named interface
 * {@code payroll-inputs}); the rest of {@code hr.application} stays internal.
 *
 * <p>The payroll engine ({@link HrPayrollService}) adds every pending input of an active employee earned up to the
 * period end as an allowance line of the run it calculates (non-taxable unless flagged), and when the input names a
 * {@code debitAccountId} (the source's clearing liability) the approved run's journal debits that account instead of
 * salaries expense — so the source module books the expense on its own cost centre and payroll only settles it.
 * Approving the run marks its inputs {@code included}; recalculating, deleting or reversing it releases them again.
 * Without inputs the payroll engine behaves exactly as before.
 */
@Service
@NamedInterface("payroll-inputs")
public class HrPayrollInputService {

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    private final JdbcClient jdbc;
    private final AuditService audit;

    public HrPayrollInputService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /**
     * Queue an earning for {@code employeeId}. Idempotent per ({@code source}, {@code sourceId}): while an earlier
     * input of the same source document is not cancelled, that input is returned unchanged.
     */
    @Transactional
    public Map<String, Object> addEarning(String employeeId, String name, BigDecimal amount, LocalDate earnedOn,
            boolean taxable, String debitAccountId, String source, String sourceId) {
        Map<String, Object> existing = find(source, sourceId);
        if (existing != null) {
            return existing;
        }
        Map<String, Object> emp = SqlRows.one(jdbc.sql("SELECT id, \"employeeCode\", \"isActive\" FROM \"HrEmployee\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(employeeId, org()));
        if (emp == null) {
            throw ApiException.notFound("Employee not found");
        }
        if (!Boolean.TRUE.equals(emp.get("isActive"))) {
            throw ApiException.badRequest("Employee " + emp.get("employeeCode") + " is not active; payroll inputs need"
                    + " an active employee");
        }
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("A payroll input needs a positive amount");
        }
        String id = UUID.randomUUID().toString();
        Map<String, Object> row = SqlRows.one(HrPayrollInput.class, jdbc.sql("INSERT INTO \"HrPayrollInput\" (id,"
                        + " \"organizationId\", \"employeeId\", kind, name, amount, \"earnedOn\", \"isTaxable\","
                        + " \"debitAccountId\", source, \"sourceId\", status, \"createdBy\", \"createdAt\", \"updatedAt\")"
                        + " VALUES (?, ?, ?, 'earning', ?, ?, ?, ?, ?, ?, ?, 'pending', ?, " + NOW + ", " + NOW + ")"
                        + " RETURNING *")
                .params(id, org(), employeeId, name, amount, java.sql.Date.valueOf(earnedOn), taxable, debitAccountId,
                        source, sourceId, TenantContext.userId()));
        audit.recordInTx(AuditInput.of("HrPayrollInput", id, "create", null, row));
        return row;
    }

    /** The live (not cancelled) input of a source document, or null. */
    @Transactional(readOnly = true)
    public Map<String, Object> find(String source, String sourceId) {
        return SqlRows.one(HrPayrollInput.class, jdbc.sql("SELECT * FROM \"HrPayrollInput\" WHERE \"organizationId\" = ?"
                + " AND source = ? AND \"sourceId\" = ? AND status <> 'cancelled' ORDER BY \"createdAt\" DESC LIMIT 1")
                .params(org(), source, sourceId));
    }

    /**
     * Cancel the pending input of a source document (the source reverses its export). Refused once a payroll run has
     * picked it up: that run must be recalculated, deleted or reversed first.
     */
    @Transactional
    public Map<String, Object> cancel(String source, String sourceId, String reason) {
        Map<String, Object> in = SqlRows.one(HrPayrollInput.class, jdbc.sql("SELECT * FROM \"HrPayrollInput\" WHERE"
                + " \"organizationId\" = ? AND source = ? AND \"sourceId\" = ? AND status <> 'cancelled' LIMIT 1"
                + " FOR UPDATE").params(org(), source, sourceId));
        if (in == null) {
            throw ApiException.notFound("Payroll input not found");
        }
        if (in.get("runId") != null) {
            String run = jdbc.sql("SELECT \"runNumber\" FROM \"HrPayrollRun\" WHERE id = ? AND \"organizationId\" = ?")
                    .params(in.get("runId"), org()).query(String.class).optional().orElse((String) in.get("runId"));
            throw ApiException.badRequest("The payroll input is already in payroll run " + run
                    + "; reverse or recalculate that run first");
        }
        Map<String, Object> out = SqlRows.one(HrPayrollInput.class, jdbc.sql("UPDATE \"HrPayrollInput\" SET status ="
                + " 'cancelled', \"cancelledAt\" = " + NOW + ", \"cancelReason\" = ?, \"updatedAt\" = " + NOW
                + " WHERE id = ? AND \"organizationId\" = ? RETURNING *").params(reason, in.get("id"), org()));
        audit.recordInTx(AuditInput.of("HrPayrollInput", (String) in.get("id"), "cancel", in, out));
        return out;
    }

    // ------------------------------------------------------------------ payroll engine hooks (package-private)

    /** Pending inputs not yet in a run, earned on or before the period end (any earlier backlog included). */
    List<Map<String, Object>> pendingUntil(Instant periodEnd) {
        LocalDate until = periodEnd == null ? LocalDate.now(ZoneOffset.UTC)
                : periodEnd.atOffset(ZoneOffset.UTC).toLocalDate();
        return SqlRows.list(HrPayrollInput.class, jdbc.sql("SELECT * FROM \"HrPayrollInput\" WHERE \"organizationId\" ="
                + " ? AND status = 'pending' AND \"runId\" IS NULL AND \"earnedOn\" <= ? ORDER BY \"earnedOn\", id")
                .params(org(), java.sql.Date.valueOf(until)));
    }

    /** Link calculated inputs to the run item that carries them. */
    void attach(List<String> inputIds, String runId, String itemId) {
        for (String inputId : inputIds) {
            jdbc.sql("UPDATE \"HrPayrollInput\" SET \"runId\" = ?, \"itemId\" = ?, \"updatedAt\" = " + NOW
                    + " WHERE id = ? AND \"organizationId\" = ?").params(runId, itemId, inputId, org()).update();
        }
    }

    /** Release the pending inputs of a run being recalculated, deleted or reversed before approval. */
    void releaseRun(String runId) {
        jdbc.sql("UPDATE \"HrPayrollInput\" SET \"runId\" = NULL, \"itemId\" = NULL, \"updatedAt\" = " + NOW
                + " WHERE \"runId\" = ? AND \"organizationId\" = ? AND status = 'pending'").params(runId, org()).update();
    }

    /** Debit accounts and amounts of the run's inputs that the payroll journal debits instead of salaries expense. */
    List<Map<String, Object>> redirectedDebits(String runId) {
        return SqlRows.list(jdbc.sql("SELECT \"debitAccountId\", sum(amount) AS amount FROM \"HrPayrollInput\" WHERE"
                + " \"runId\" = ? AND \"organizationId\" = ? AND status = 'pending' AND \"debitAccountId\" IS NOT NULL"
                + " GROUP BY \"debitAccountId\" ORDER BY \"debitAccountId\"").params(runId, org()));
    }

    /** The run was approved: its inputs are paid through payroll. */
    void markIncluded(String runId) {
        jdbc.sql("UPDATE \"HrPayrollInput\" SET status = 'included', \"includedAt\" = " + NOW + ", \"updatedAt\" = "
                + NOW + " WHERE \"runId\" = ? AND \"organizationId\" = ? AND status = 'pending'").params(runId, org())
                .update();
    }

    /** An approved run was reversed: its inputs wait for the next run again. */
    void reopenRun(String runId) {
        jdbc.sql("UPDATE \"HrPayrollInput\" SET status = 'pending', \"runId\" = NULL, \"itemId\" = NULL,"
                + " \"includedAt\" = NULL, \"updatedAt\" = " + NOW + " WHERE \"runId\" = ? AND \"organizationId\" = ?"
                + " AND status IN ('pending', 'included')").params(runId, org()).update();
    }
}
