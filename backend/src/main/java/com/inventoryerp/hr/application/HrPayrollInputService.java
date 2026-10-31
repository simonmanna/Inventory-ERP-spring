package com.inventoryerp.hr.application;

import com.inventoryerp.hr.domain.HrPayrollInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventOutboxService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *
 * <p><b>Deductions</b> (fleet 1.9 — traffic fines charged to a driver): {@link #addDeduction} queues an amount the next
 * run subtracts from the employee's net pay (after statutory deductions, loans and advances; a deduction that does
 * not fit the remaining net pay waits for a later run). The approved run's journal credits the input's
 * {@code creditAccountId} (the source's recovery / payable account) for it. Including or re-opening inputs publishes
 * {@code hr.payroll.inputs_included} / {@code hr.payroll.inputs_reopened} with their source documents, so the source
 * module can follow the settlement.
 */
@Service
@NamedInterface("payroll-inputs")
public class HrPayrollInputService {

    private static final String NOW = "date_trunc('milliseconds', clock_timestamp())::timestamp(3)";

    /** Published when an approved payroll run includes inputs (payload: run + the inputs' source documents). */
    public static final String EVENT_INCLUDED = DomainEvents.HR_PAYROLL_INPUTS_INCLUDED;
    /** Published when included inputs wait for a run again (the approved run was reversed). */
    public static final String EVENT_REOPENED = DomainEvents.HR_PAYROLL_INPUTS_REOPENED;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final EventOutboxService outbox;

    public HrPayrollInputService(JdbcClient jdbc, AuditService audit, EventOutboxService outbox) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
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

    /**
     * Queue a deduction from {@code employeeId}'s net pay (e.g. a traffic fine charged to the driver). Idempotent per
     * ({@code source}, {@code sourceId}) like {@link #addEarning}. The approved payroll journal credits
     * {@code creditAccountId} (null: the "other deductions" contra of salaries expense).
     */
    @Transactional
    public Map<String, Object> addDeduction(String employeeId, String name, BigDecimal amount, LocalDate effectiveOn,
            String creditAccountId, String source, String sourceId) {
        Map<String, Object> existing = find(source, sourceId);
        if (existing != null) {
            return existing;
        }
        assertActiveEmployee(employeeId);
        if (amount == null || amount.signum() <= 0) {
            throw ApiException.badRequest("A payroll input needs a positive amount");
        }
        String id = UUID.randomUUID().toString();
        Map<String, Object> row = SqlRows.one(HrPayrollInput.class, jdbc.sql("INSERT INTO \"HrPayrollInput\" (id,"
                        + " \"organizationId\", \"employeeId\", kind, name, amount, \"earnedOn\", \"isTaxable\","
                        + " \"creditAccountId\", source, \"sourceId\", status, \"createdBy\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?, 'deduction', ?, ?, ?, false, ?, ?, ?, 'pending', ?, " + NOW
                        + ", " + NOW + ") RETURNING *")
                .params(id, org(), employeeId, name, amount.setScale(2, RoundingMode.HALF_UP),
                        java.sql.Date.valueOf(effectiveOn), creditAccountId, source, sourceId, TenantContext.userId()));
        audit.recordInTx(AuditInput.of("HrPayrollInput", id, "create", null, row));
        return row;
    }

    private void assertActiveEmployee(String employeeId) {
        Map<String, Object> emp = SqlRows.one(jdbc.sql("SELECT id, \"employeeCode\", \"isActive\" FROM \"HrEmployee\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(employeeId, org()));
        if (emp == null) {
            throw ApiException.notFound("Employee not found");
        }
        if (!Boolean.TRUE.equals(emp.get("isActive"))) {
            throw ApiException.badRequest("Employee " + emp.get("employeeCode") + " is not active; payroll inputs need"
                    + " an active employee");
        }
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
                + " AND kind = 'earning' GROUP BY \"debitAccountId\" ORDER BY \"debitAccountId\"").params(runId, org()));
    }

    /** What the run's redirected earnings take from each employee's salary debit (HR-5 cost allocation). */
    Map<String, BigDecimal> redirectedByEmployee(String runId) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"employeeId\", sum(amount) AS amount FROM"
                + " \"HrPayrollInput\" WHERE \"runId\" = ? AND \"organizationId\" = ? AND status = 'pending' AND"
                + " \"debitAccountId\" IS NOT NULL AND kind = 'earning' GROUP BY \"employeeId\"")
                .params(runId, org()))) {
            out.put((String) r.get("employeeId"), SqlRows.dec(r, "amount"));
        }
        return out;
    }

    /**
     * Credit accounts and amounts of the run's deduction inputs (credited by the payroll journal; a null account is
     * the "other deductions" contra of salaries expense). Empty without deductions.
     */
    List<Map<String, Object>> deductionCredits(String runId) {
        return SqlRows.list(jdbc.sql("SELECT \"creditAccountId\", sum(amount) AS amount FROM \"HrPayrollInput\" WHERE"
                + " \"runId\" = ? AND \"organizationId\" = ? AND status = 'pending' AND kind = 'deduction'"
                + " GROUP BY \"creditAccountId\" ORDER BY \"creditAccountId\" NULLS LAST").params(runId, org()));
    }

    /** The run was approved: its inputs are paid through payroll. */
    void markIncluded(String runId) {
        List<Map<String, Object>> rows = SqlRows.list(HrPayrollInput.class, jdbc.sql("UPDATE \"HrPayrollInput\" SET"
                + " status = 'included', \"includedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + " WHERE \"runId\" = ?"
                + " AND \"organizationId\" = ? AND status = 'pending' RETURNING *").params(runId, org()));
        announce(EVENT_INCLUDED, runId, rows);
    }

    /** An approved run was reversed: its inputs wait for the next run again. */
    void reopenRun(String runId) {
        List<Map<String, Object>> rows = SqlRows.list(HrPayrollInput.class, jdbc.sql("UPDATE \"HrPayrollInput\" SET"
                + " status = 'pending', \"runId\" = NULL, \"itemId\" = NULL, \"includedAt\" = NULL, \"updatedAt\" = "
                + NOW + " WHERE \"runId\" = ? AND \"organizationId\" = ? AND status IN ('pending', 'included')"
                + " RETURNING *").params(runId, org()));
        announce(EVENT_REOPENED, runId, rows);
    }

    /** Outbox event (in the run's transaction) naming the inputs' source documents; nothing without inputs. */
    private void announce(String event, String runId, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String runNumber = jdbc.sql("SELECT \"runNumber\" FROM \"HrPayrollRun\" WHERE id = ? AND \"organizationId\" = ?")
                .params(runId, org()).query(String.class).optional().orElse(null);
        List<Map<String, Object>> inputs = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("inputId", r.get("id"));
            in.put("kind", r.get("kind"));
            in.put("employeeId", r.get("employeeId"));
            in.put("amount", SqlRows.dec(r, "amount").toPlainString());
            in.put("source", r.get("source"));
            in.put("sourceId", r.get("sourceId"));
            inputs.add(in);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("organizationId", org());
        payload.put("runId", runId);
        payload.put("runNumber", runNumber);
        payload.put("inputs", inputs);
        outbox.publish(event, payload);
    }
}
