package com.inventoryerp.fleet.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.expenses.application.ExpensesService;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ChargeInput;
import com.inventoryerp.fleet.application.FleetIncidentCommands.DeductionQuery;
import com.inventoryerp.fleet.application.FleetIncidentCommands.DeductionStatusChange;
import com.inventoryerp.fleet.application.FleetIncidentCommands.FineInput;
import com.inventoryerp.fleet.application.FleetIncidentCommands.FinePatch;
import com.inventoryerp.fleet.application.FleetIncidentCommands.FineQuery;
import com.inventoryerp.fleet.application.FleetIncidentCommands.FineStatusChange;
import com.inventoryerp.fleet.application.FleetIncidentCommands.ReverseChargeInput;
import com.inventoryerp.fleet.domain.FleetFine;
import com.inventoryerp.fleet.domain.FleetFineDeduction;
import com.inventoryerp.fleet.domain.FleetIncident;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.events.DomainEvents;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Traffic fines (fleet 1.7, ADR-S009 §6): record (numbered {@code FIN-00001}), dispute / cancel, and charge.
 *
 * <ul>
 *   <li><b>Company</b>: an expense through the expenses module's public API
 *       ({@link ExpensesService#createFromSource}, source {@code fleet_fine}) whose payment posts Dr traffic fines
 *       {@code fleet_fines_expense} (6450) on the vehicle's cost centre (dimensions vehicleId / fineId) / Cr the
 *       paying cash / bank account — at once for CASH, when Expenses pays it for CREDIT. Idempotent per charge;
 *       reversing the charge voids the expense (its payment entries are reversed) and reopens the fine.</li>
 *   <li><b>Driver</b>: no hr write — a {@code FleetFineDeduction} request (pending → exported → settled) and the
 *       event {@code fleet.fine.deduction_requested}, which hr payroll consumes once wired; reversing cancels the
 *       request ({@code fleet.fine.deduction_cancelled}) unless payroll already settled it.</li>
 * </ul>
 * A company charge settles the fine when its expense is paid ({@code expense.paid}, {@link FleetExpenseSubscriber});
 * a voided expense reopens it.
 */
@Service
public class FleetFineService {

    static final String AUDIT_ENTITY = "FleetFine";
    public static final String EXPENSE_SOURCE = "fleet_fine";

    private final FleetDb db;
    private final FleetVehicleService vehicles;
    private final FleetEmployeeLookup employees;
    private final ExpensesService expenses;
    private final AccountResolverService accounts;
    private final SequenceService sequence;
    private final AuditService audit;
    private final EventBus events;

    public FleetFineService(FleetDb db, FleetVehicleService vehicles, FleetEmployeeLookup employees,
            ExpensesService expenses, AccountResolverService accounts, SequenceService sequence, AuditService audit,
            EventBus events) {
        this.db = db;
        this.vehicles = vehicles;
        this.employees = employees;
        this.expenses = expenses;
        this.accounts = accounts;
        this.sequence = sequence;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ reads

    private static final String SELECT = """
            SELECT f.*, v.code AS "v_code", v."registrationPlate" AS "v_plate",
                   e."employeeCode" AS "e_code", e."firstName" AS "e_first", e."lastName" AS "e_last",
                   x."expenseCode" AS "x_code", x.status::text AS "x_status", x."paymentStatus"::text AS "x_payment",
                   d.status AS "d_status"
            FROM "FleetFine" f JOIN "FleetVehicle" v ON v.id = f."vehicleId"
            LEFT JOIN "HrEmployee" e ON e.id = f."driverEmployeeId" AND e."organizationId" = f."organizationId"
            LEFT JOIN "Expense" x ON x.id = f."expenseId" AND x."organizationId" = f."organizationId"
            LEFT JOIN "FleetFineDeduction" d ON d.id = f."deductionId" AND d."organizationId" = f."organizationId"
            """;

    /** Paged fines; {@code overdue} = not settled / cancelled and past the due date. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(FineQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE f.\"organizationId\" = ? AND f.\"deletedAt\" IS NULL");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        if (FleetVehicleService.notBlank(q.search())) {
            where.append(" AND (f.\"fineNumber\" ILIKE ? OR f.offence ILIKE ? OR f.\"noticeNumber\" ILIKE ?"
                    + " OR v.code ILIKE ? OR v.\"registrationPlate\" ILIKE ?)");
            String like = FleetIncidentService.like(q.search());
            for (int i = 0; i < 5; i++) {
                params.add(like);
            }
        }
        FleetIncidentService.eq(where, params, "f.status", q.status());
        FleetIncidentService.eq(where, params, "f.\"vehicleId\"", q.vehicleId());
        FleetIncidentService.eq(where, params, "f.\"driverEmployeeId\"", q.driverEmployeeId());
        FleetIncidentService.eq(where, params, "f.\"chargedTo\"", q.chargedTo());
        if (Boolean.TRUE.equals(q.overdue())) {
            where.append(" AND f.status IN ('open', 'disputed', 'charged') AND f.\"dueDate\" < ?");
            params.add(java.sql.Date.valueOf(LocalDate.now(BusinessZone.get())));
        }
        long total = db.jdbc().sql("SELECT count(*) FROM \"FleetFine\" f JOIN \"FleetVehicle\" v ON v.id ="
                + " f.\"vehicleId\"" + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql(SELECT + where
                + " ORDER BY f.\"offenceAt\" DESC, f.id LIMIT ? OFFSET ?").params(params))) {
            rows.add(shape(r));
        }
        return PageResult.of(rows, page, size, total);
    }

    /** Detail with the vehicle, driver, incident, expense and deduction history. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(db.jdbc().sql(SELECT + " WHERE f.id = ? AND f.\"organizationId\" = ? AND"
                + " f.\"deletedAt\" IS NULL").params(id, FleetDb.org()));
        if (r == null) {
            throw ApiException.notFound("Fine not found");
        }
        Map<String, Object> out = shape(r);
        out.put("incident", out.get("incidentId") == null ? null : SqlRows.one(db.jdbc().sql("SELECT id,"
                + " \"incidentNumber\", \"occurredAt\", status FROM \"FleetIncident\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(out.get("incidentId"), FleetDb.org())));
        out.put("deductions", SqlRows.list(FleetFineDeduction.class, db.jdbc().sql("SELECT * FROM"
                + " \"FleetFineDeduction\" WHERE \"organizationId\" = ? AND \"fineId\" = ? ORDER BY \"requestedAt\""
                + " DESC").params(FleetDb.org(), id)));
        return out;
    }

    private static Map<String, Object> shape(Map<String, Object> r) {
        Map<String, Object> f = SqlRows.as(FleetFine.class, r);
        f.put("vehicle", Obj.of("id", r.get("vehicleId"), "code", r.get("v_code"), "registrationPlate",
                r.get("v_plate")));
        f.put("driver", r.get("driverEmployeeId") == null ? null : FleetEmployeeLookup.shape(Obj.of("id",
                r.get("driverEmployeeId"), "employeeCode", r.get("e_code"), "firstName", r.get("e_first"),
                "lastName", r.get("e_last"))));
        f.put("expense", r.get("expenseId") == null ? null : Obj.of("id", r.get("expenseId"), "expenseCode",
                r.get("x_code"), "status", r.get("x_status"), "paymentStatus", r.get("x_payment")));
        f.put("deductionStatus", r.get("d_status"));
        LocalDate due = (LocalDate) f.get("dueDate");
        f.put("overdue", due != null && List.of("open", "disputed", "charged").contains(f.get("status"))
                && due.isBefore(LocalDate.now(BusinessZone.get())));
        return f;
    }

    // ------------------------------------------------------------------ writes

    /** Record a fine (status {@code open}). */
    @Transactional
    public Map<String, Object> create(FineInput in) {
        vehicles.require(in.vehicleId(), false);
        employees.assertEmployee(FleetIncidentService.blank(in.driverEmployeeId()), "Driver (employee) not found");
        assertIncident(FleetIncidentService.blank(in.incidentId()), in.vehicleId());
        FleetIncidentService.notFuture(in.offenceAt());
        String notice = FleetVehicleService.trim(in.noticeNumber());
        assertNotice(notice, null);
        String number = nextNumber();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fineNumber", number);
        data.put("vehicleId", in.vehicleId());
        data.put("driverEmployeeId", FleetIncidentService.blank(in.driverEmployeeId()));
        data.put("incidentId", FleetIncidentService.blank(in.incidentId()));
        data.put("offenceAt", in.offenceAt());
        data.put("location", FleetVehicleService.trim(in.location()));
        data.put("offence", in.offence().trim());
        data.put("offenceCode", FleetVehicleService.trim(in.offenceCode()));
        data.put("authority", FleetVehicleService.trim(in.authority()));
        data.put("noticeNumber", notice);
        data.put("amount", in.amount().setScale(2, RoundingMode.HALF_UP));
        data.put("dueDate", in.dueDate());
        data.put("status", "open");
        data.put("notes", FleetVehicleService.trim(in.notes()));
        data.put("createdBy", TenantContext.userId());
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> row = db.insert(FleetFine.class, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, (String) row.get("id"), "create", null, row));
        return get((String) row.get("id"));
    }

    /** Edit an open or disputed fine. */
    @Transactional
    public Map<String, Object> update(String id, FinePatch in) {
        Map<String, Object> before = fine(id, true);
        String status = (String) before.get("status");
        if (!List.of("open", "disputed").contains(status)) {
            throw ApiException.badRequest("Fine " + before.get("fineNumber") + " is " + status
                    + "; only open or disputed fines can be edited");
        }
        List<String> clear = in.clear() == null ? List.of() : in.clear();
        for (String f : clear) {
            if (!FleetIncidentCommands.FINE_CLEARABLE.contains(f)) {
                throw ApiException.badRequest("Field " + f + " cannot be cleared");
            }
        }
        employees.assertEmployee(FleetIncidentService.blank(in.driverEmployeeId()), "Driver (employee) not found");
        assertIncident(FleetIncidentService.blank(in.incidentId()), (String) before.get("vehicleId"));
        if (in.offenceAt() != null) {
            FleetIncidentService.notFuture(in.offenceAt());
        }
        String notice = FleetVehicleService.trim(in.noticeNumber());
        assertNotice(notice, id);
        Map<String, Object> data = new LinkedHashMap<>();
        FleetIncidentService.put(data, "driverEmployeeId", FleetIncidentService.blank(in.driverEmployeeId()));
        FleetIncidentService.put(data, "incidentId", FleetIncidentService.blank(in.incidentId()));
        FleetIncidentService.put(data, "offenceAt", in.offenceAt());
        FleetIncidentService.put(data, "location", FleetVehicleService.trim(in.location()));
        FleetIncidentService.put(data, "offence", FleetVehicleService.trim(in.offence()));
        FleetIncidentService.put(data, "offenceCode", FleetVehicleService.trim(in.offenceCode()));
        FleetIncidentService.put(data, "authority", FleetVehicleService.trim(in.authority()));
        FleetIncidentService.put(data, "noticeNumber", notice);
        FleetIncidentService.put(data, "amount", in.amount() == null ? null
                : in.amount().setScale(2, RoundingMode.HALF_UP));
        FleetIncidentService.put(data, "dueDate", in.dueDate());
        FleetIncidentService.put(data, "notes", FleetVehicleService.trim(in.notes()));
        for (String f : clear) {
            data.put(f, null);
        }
        if (data.isEmpty()) {
            return get(id);
        }
        data.put("updatedBy", TenantContext.userId());
        Map<String, Object> after = db.update(FleetFine.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "update", before, after));
        return get(id);
    }

    /** Dispute, resolve (back to open) or cancel a fine that is not charged. */
    @Transactional
    public Map<String, Object> changeStatus(String id, FineStatusChange in) {
        Map<String, Object> f = fine(id, true);
        String from = (String) f.get("status");
        IncidentPolicy.assertFine((String) f.get("fineNumber"), from, in.status(), in.reason());
        db.update(FleetFine.class, id, Obj.of("status", in.status(), "statusReason",
                FleetVehicleService.trim(in.reason()), "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "cancelled".equals(in.status()) ? "cancel" : "update",
                Obj.of("status", from), Obj.of("status", in.status(), "reason", FleetVehicleService.trim(in.reason()))));
        return get(id);
    }

    /** Charge an open fine to the company (expense) or the driver (deduction request). See the class comment. */
    @Transactional
    public Map<String, Object> charge(String id, ChargeInput in) {
        Map<String, Object> f = fine(id, true);
        String number = (String) f.get("fineNumber");
        String status = (String) f.get("status");
        if ("charged".equals(status) || "settled".equals(status)) {
            throw ApiException.badRequest("Fine " + number + " is already charged to the " + f.get("chargedTo"));
        }
        if ("disputed".equals(status)) {
            throw ApiException.badRequest("Fine " + number + " is disputed; resolve the dispute before charging it");
        }
        if (!"open".equals(status)) {
            throw ApiException.badRequest("Fine " + number + " is " + status + " and cannot be charged");
        }
        Map<String, Object> v = vehicles.require((String) f.get("vehicleId"), false);
        BigDecimal amount = SqlRows.dec(f, "amount");
        int chargeNo = ((Number) f.get("chargeCount")).intValue() + 1;
        Instant now = Instant.now();
        Map<String, Object> data = Obj.of("status", "charged", "chargedTo", in.chargeTo(), "chargedAt", now,
                "chargedBy", TenantContext.userId(), "chargeCount", chargeNo, "settledAt", null,
                "statusReason", FleetVehicleService.trim(in.notes()), "updatedBy", TenantContext.userId());
        String expenseId = null;
        String deductionId = null;
        if ("company".equals(in.chargeTo())) {
            String paymentType = in.paymentType() == null ? "CREDIT" : in.paymentType();
            String debit = accounts.ensureByCode("6450", "Traffic fines", "operating_expense", "fleet_fines_expense");
            String title = "Traffic fine " + number + " · " + (v.get("registrationPlate") != null
                    ? v.get("registrationPlate") : v.get("code")) + " · " + f.get("offence");
            Map<String, Object> expense = expenses.createFromSource(new ExpensesService.CreateInput(
                            title.length() > 200 ? title.substring(0, 200) : title,
                            f.get("noticeNumber") == null ? null : "Notice " + f.get("noticeNumber"),
                            amount, null, LocalDate.now(BusinessZone.get()).toString(),
                            FleetVehicleService.trim(in.notes()), null, paymentType,
                            "CASH".equals(paymentType) ? in.paymentMethod() : null,
                            "CASH".equals(paymentType) ? in.accountId() : null,
                            "CASH".equals(paymentType) ? FleetVehicleService.trim(in.paymentReference()) : null),
                    new ExpensesService.SourceTags(EXPENSE_SOURCE, id + "#" + chargeNo, debit,
                            (String) v.get("costCenterId"), Obj.of("vehicleId", v.get("id"), "fineId", id)));
            expenseId = (String) expense.get("id");
            data.put("expenseId", expenseId);
            data.put("deductionId", null);
            if ("PAID".equals(String.valueOf(expense.get("paymentStatus")))) {
                data.put("status", "settled");
                data.put("settledAt", now);
            }
        } else {
            String employeeId = FleetIncidentService.blank(in.employeeId()) != null ? in.employeeId()
                    : (String) f.get("driverEmployeeId");
            if (employeeId == null) {
                throw ApiException.badRequest("Choose the driver (employee) to charge fine " + number + " to");
            }
            employees.assertEmployee(employeeId, "Driver (employee) not found");
            Map<String, Object> d = db.insert(FleetFineDeduction.class, Obj.of("fineId", id, "employeeId", employeeId,
                    "amount", amount, "status", "pending", "requestedAt", now, "requestedBy", TenantContext.userId()));
            deductionId = (String) d.get("id");
            data.put("deductionId", deductionId);
            data.put("expenseId", null);
            if (f.get("driverEmployeeId") == null) {
                data.put("driverEmployeeId", employeeId);
            }
            events.publish(DomainEvents.FLEET_FINE_DEDUCTION_REQUESTED, deductionPayload(f, d));
        }
        db.update(FleetFine.class, id, data);
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "post", Obj.of("status", status),
                Obj.of("status", data.get("status"), "chargedTo", in.chargeTo(), "expenseId", expenseId,
                        "deductionId", deductionId, "amount", amount)));
        events.publish(DomainEvents.FLEET_FINE_CHARGED, Obj.of("organizationId", FleetDb.org(), "fineId", id,
                "fineNumber", number, "vehicleId", f.get("vehicleId"), "chargedTo", in.chargeTo(),
                "amount", amount.toPlainString(), "expenseId", expenseId, "deductionId", deductionId));
        return get(id);
    }

    /**
     * Undo a charge: the company expense is voided (its payment entries reversed by the expenses module), a driver
     * deduction request is cancelled unless payroll settled it. The fine is open again.
     */
    @Transactional
    public Map<String, Object> reverseCharge(String id, ReverseChargeInput in) {
        Map<String, Object> f = fine(id, true);
        String number = (String) f.get("fineNumber");
        String status = (String) f.get("status");
        if (!List.of("charged", "settled").contains(status)) {
            throw ApiException.badRequest("Fine " + number + " is not charged");
        }
        String reason = in.reason().trim();
        if ("company".equals(f.get("chargedTo"))) {
            String expenseId = (String) f.get("expenseId");
            String expenseStatus = expenseId == null ? null : db.jdbc().sql("SELECT status::text FROM \"Expense\""
                    + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(expenseId,
                    FleetDb.org()).query(String.class).optional().orElse(null);
            if (expenseStatus != null && !List.of("VOID", "CANCELLED").contains(expenseStatus)) {
                expenses.voidExpense(expenseId, "Fine " + number + " charge reversed: " + reason);
            }
        } else {
            Map<String, Object> d = db.byId(FleetFineDeduction.class, (String) f.get("deductionId"), true);
            if (d != null && "settled".equals(d.get("status"))) {
                throw ApiException.badRequest("The deduction of fine " + number
                        + " was already settled through payroll; refund it in payroll instead");
            }
            if (d != null && !"cancelled".equals(d.get("status"))) {
                Map<String, Object> cancelled = db.update(FleetFineDeduction.class, (String) d.get("id"),
                        Obj.of("status", "cancelled", "cancelledAt", Instant.now(), "cancelReason", reason));
                Map<String, Object> payload = deductionPayload(f, cancelled);
                payload.put("reason", reason);
                events.publish(DomainEvents.FLEET_FINE_DEDUCTION_CANCELLED, payload);
            }
        }
        db.update(FleetFine.class, id, Obj.of("status", "open", "chargedTo", null, "chargedAt", null, "chargedBy",
                null, "expenseId", null, "deductionId", null, "settledAt", null, "statusReason", reason,
                "updatedBy", TenantContext.userId()));
        audit.recordInTx(AuditInput.of(AUDIT_ENTITY, id, "cancel", Obj.of("status", status, "chargedTo",
                f.get("chargedTo"), "expenseId", f.get("expenseId"), "deductionId", f.get("deductionId")),
                Obj.of("status", "open", "reason", reason)));
        return get(id);
    }

    // ------------------------------------------------------------------ deductions

    /** Deduction requests (the payroll export list): {@code status}, {@code employeeId} filters. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> deductions(DeductionQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 20 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(FleetDb.org()));
        FleetIncidentService.eq(where, params, "d.status", q.status());
        FleetIncidentService.eq(where, params, "d.\"employeeId\"", q.employeeId());
        String from = " FROM \"FleetFineDeduction\" d JOIN \"FleetFine\" f ON f.id = d.\"fineId\""
                + " JOIN \"FleetVehicle\" v ON v.id = f.\"vehicleId\""
                + " LEFT JOIN \"HrEmployee\" e ON e.id = d.\"employeeId\" AND e.\"organizationId\" = d.\"organizationId\"";
        long total = db.jdbc().sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        params.add(size);
        params.add((page - 1) * size);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT d.*, f.\"fineNumber\" AS \"f_number\","
                + " f.offence AS \"f_offence\", f.\"offenceAt\" AS \"f_at\", v.code AS \"v_code\","
                + " v.\"registrationPlate\" AS \"v_plate\", e.\"employeeCode\" AS \"e_code\", e.\"firstName\" AS"
                + " \"e_first\", e.\"lastName\" AS \"e_last\"" + from + where
                + " ORDER BY d.\"requestedAt\" DESC, d.id LIMIT ? OFFSET ?").params(params))) {
            Map<String, Object> m = SqlRows.as(FleetFineDeduction.class, r);
            m.put("fine", Obj.of("id", r.get("fineId"), "fineNumber", r.get("f_number"), "offence", r.get("f_offence"),
                    "offenceAt", r.get("f_at")));
            m.put("vehicle", Obj.of("code", r.get("v_code"), "registrationPlate", r.get("v_plate")));
            m.put("employee", FleetEmployeeLookup.shape(Obj.of("id", r.get("employeeId"), "employeeCode",
                    r.get("e_code"), "firstName", r.get("e_first"), "lastName", r.get("e_last"))));
            rows.add(m);
        }
        return PageResult.of(rows, page, size, total);
    }

    /** Mark a deduction request exported to payroll or settled (settled also settles its fine). */
    @Transactional
    public Map<String, Object> deductionStatus(String deductionId, DeductionStatusChange in) {
        Map<String, Object> d = db.require(FleetFineDeduction.class, deductionId, true, "Deduction request not found");
        IncidentPolicy.assertDeduction((String) d.get("status"), in.status());
        Instant now = Instant.now();
        String ref = FleetVehicleService.trim(in.reference());
        Map<String, Object> data = "exported".equals(in.status())
                ? Obj.of("status", "exported", "exportedAt", now, "exportReference", ref)
                : Obj.of("status", "settled", "settledAt", now, "settledReference", ref);
        Map<String, Object> after = db.update(FleetFineDeduction.class, deductionId, data);
        if ("settled".equals(in.status())) {
            db.jdbc().sql("UPDATE \"FleetFine\" SET status = 'settled', \"settledAt\" = ?, \"updatedAt\" = "
                    + FleetDb.NOW + " WHERE id = ? AND \"organizationId\" = ? AND \"deductionId\" = ? AND status ="
                    + " 'charged'").params(SqlRows.ts(now), d.get("fineId"), FleetDb.org(), deductionId).update();
        }
        audit.recordInTx(AuditInput.of("FleetFineDeduction", deductionId, "update", Obj.of("status", d.get("status")),
                Obj.of("status", in.status(), "reference", ref)));
        return after;
    }

    // ------------------------------------------------------------------ expense outcome (subscriber)

    /** The linked expense was paid (Expenses screen): the company-charged fine is settled. Idempotent. */
    @Transactional
    public void expensePaid(String expenseId) {
        db.jdbc().sql("UPDATE \"FleetFine\" SET status = 'settled', \"settledAt\" = " + FleetDb.NOW + ", \"updatedAt\" = "
                + FleetDb.NOW + " WHERE \"organizationId\" = ? AND \"expenseId\" = ? AND status = 'charged'")
                .params(FleetDb.org(), expenseId).update();
    }

    /** The linked expense was voided outside fleet: the fine is open again. Idempotent. */
    @Transactional
    public void expenseVoided(String expenseId) {
        db.jdbc().sql("UPDATE \"FleetFine\" SET status = 'open', \"chargedTo\" = NULL, \"chargedAt\" = NULL,"
                + " \"chargedBy\" = NULL, \"expenseId\" = NULL, \"settledAt\" = NULL, \"statusReason\" ="
                + " 'Expense voided', \"updatedAt\" = " + FleetDb.NOW + " WHERE \"organizationId\" = ? AND"
                + " \"expenseId\" = ? AND status IN ('charged', 'settled')").params(FleetDb.org(), expenseId).update();
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> deductionPayload(Map<String, Object> f, Map<String, Object> d) {
        return Obj.of("organizationId", FleetDb.org(), "deductionId", d.get("id"), "fineId", f.get("id"),
                "fineNumber", f.get("fineNumber"), "employeeId", d.get("employeeId"),
                "amount", SqlRows.dec(d, "amount").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                "vehicleId", f.get("vehicleId"), "offence", f.get("offence"),
                "offenceAt", String.valueOf(f.get("offenceAt")));
    }

    private Map<String, Object> fine(String id, boolean lock) {
        return db.require(FleetFine.class, id, lock, "Fine not found");
    }

    private void assertIncident(String incidentId, String vehicleId) {
        if (incidentId == null) {
            return;
        }
        Map<String, Object> i = db.byId(FleetIncident.class, incidentId, false);
        if (i == null) {
            throw ApiException.badRequest("Incident not found");
        }
        if (!vehicleId.equals(i.get("vehicleId"))) {
            throw ApiException.badRequest("Incident " + i.get("incidentNumber") + " is of another vehicle");
        }
    }

    private void assertNotice(String notice, String exceptId) {
        if (notice == null) {
            return;
        }
        List<Object> params = new ArrayList<>(List.of(FleetDb.org(), notice));
        if (exceptId != null) {
            params.add(exceptId);
        }
        String other = db.jdbc().sql("SELECT \"fineNumber\" FROM \"FleetFine\" WHERE \"organizationId\" = ? AND"
                        + " \"noticeNumber\" = ? AND \"deletedAt\" IS NULL" + (exceptId == null ? "" : " AND id <> ?")
                        + " LIMIT 1").params(params).query(String.class).optional().orElse(null);
        if (other != null) {
            throw ApiException.conflict("Notice " + notice + " is already recorded as fine " + other);
        }
    }

    private String nextNumber() {
        for (int n = 0; n < 20; n++) {
            String code = sequence.next("fleet_fine", "FIN-", 5);
            long used = db.jdbc().sql("SELECT count(*) FROM \"FleetFine\" WHERE \"organizationId\" = ? AND"
                    + " \"fineNumber\" = ?").params(FleetDb.org(), code).query(Long.class).single();
            if (used == 0) {
                return code;
            }
        }
        throw ApiException.conflict("Could not allocate a fine number");
    }
}
