package com.inventoryerp.invoicing.application;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supplier payment schedule (PROC-4): every open vendor bill by the date AP intends to pay it, which is the planned
 * date when one is set and the due date otherwise. A plan moves no money; payment stays on {@code /supplier-payments}.
 */
@Service
public class SupplierPaymentScheduleService {

    /** Plan of one bill; a null date or amount clears it, so the bill falls back to its due date and residual. */
    public record PlanInput(String plannedDate, BigDecimal plannedAmount, Boolean onHold, String notes) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public SupplierPaymentScheduleService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Open bills by payment date, with totals for overdue, the next 7 and 30 days, later and on hold. */
    @Transactional(readOnly = true)
    public Map<String, Object> schedule(String partnerId, String from, String to) {
        LocalDate fromDate = parseDate(from);
        LocalDate toDate = parseDate(to);
        LocalDate today = LocalDate.now(BusinessZone.get());
        List<Object> params = new ArrayList<>(List.of(TenantContext.organizationId()));
        StringBuilder where = new StringBuilder(" WHERE d.\"organizationId\" = ? AND d.\"documentType\" = 'vendor_bill'"
                + " AND d.status IN ('posted', 'paid') AND d.\"amountResidual\" > 0");
        if (partnerId != null && !partnerId.isEmpty()) {
            where.append(" AND d.\"partnerId\" = ?");
            params.add(partnerId);
        }
        List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT d.id AS \"documentId\", d.\"documentNumber\","
                + " d.\"partnerId\", p.name AS \"partnerName\", d.reference, d.\"issueDate\", d.\"dueDate\","
                + " d.\"amountResidual\", s.\"plannedDate\"::text AS \"plannedDate\", s.\"plannedAmount\","
                + " COALESCE(s.\"onHold\", false) AS \"onHold\", s.notes FROM \"Document\" d"
                + " LEFT JOIN \"Partner\" p ON p.id = d.\"partnerId\""
                + " LEFT JOIN \"SupplierPaymentSchedule\" s ON s.\"organizationId\" = d.\"organizationId\""
                + " AND s.\"documentId\" = d.id" + where).params(params));

        BigDecimal overdue = Money.ZERO;
        BigDecimal next7 = Money.ZERO;
        BigDecimal next30 = Money.ZERO;
        BigDecimal later = Money.ZERO;
        BigDecimal held = Money.ZERO;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            LocalDate payOn = paymentDate(r);
            if ((fromDate != null && payOn.isBefore(fromDate)) || (toDate != null && payOn.isAfter(toDate))) {
                continue;
            }
            BigDecimal residual = Money.dec(r.get("amountResidual"));
            BigDecimal amount = r.get("plannedAmount") == null ? residual
                    : Money.min(Money.dec(r.get("plannedAmount")), residual);
            long days = ChronoUnit.DAYS.between(payOn, today);
            boolean onHold = Boolean.TRUE.equals(r.get("onHold"));
            String bucket = onHold ? "on_hold" : days > 0 ? "overdue" : days >= -7 ? "next_7_days"
                    : days >= -30 ? "next_30_days" : "later";
            switch (bucket) {
                case "on_hold" -> held = held.add(amount);
                case "overdue" -> overdue = overdue.add(amount);
                case "next_7_days" -> next7 = next7.add(amount);
                case "next_30_days" -> next30 = next30.add(amount);
                default -> later = later.add(amount);
            }
            r.put("paymentDate", payOn.toString());
            r.put("scheduledAmount", JsNumbers.dec(amount));
            r.put("amountResidual", JsNumbers.dec(residual));
            r.put("plannedAmount", r.get("plannedAmount") == null ? null
                    : JsNumbers.dec(Money.dec(r.get("plannedAmount"))));
            r.put("daysOverdue", Math.max(0, days));
            r.put("bucket", bucket);
            out.add(r);
        }
        out.sort(Comparator.comparing((Map<String, Object> r) -> (String) r.get("paymentDate"))
                .thenComparing(r -> (String) r.get("documentNumber")));
        return Obj.of("asOf", today.toString(), "totals", Obj.of("overdue", JsNumbers.dec(overdue), "next7Days",
                JsNumbers.dec(next7), "next30Days", JsNumbers.dec(next30), "later", JsNumbers.dec(later), "onHold",
                JsNumbers.dec(held), "total", JsNumbers.dec(overdue.add(next7).add(next30).add(later))), "rows", out);
    }

    /** Plan the payment of an open bill, or hold it. */
    @Transactional
    public Map<String, Object> plan(String documentId, PlanInput input) {
        String org = TenantContext.organizationId();
        Map<String, Object> bill = SqlRows.one(jdbc.sql("SELECT id, \"amountResidual\" FROM \"Document\" WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"documentType\" = 'vendor_bill' AND status IN ('posted', 'paid')")
                .params(documentId, org));
        if (bill == null) {
            throw ApiException.notFound("Vendor bill not found");
        }
        BigDecimal residual = Money.dec(bill.get("amountResidual"));
        if (residual.signum() <= 0) {
            throw ApiException.badRequest("This bill has no outstanding balance");
        }
        if (input.plannedAmount() != null && input.plannedAmount().compareTo(residual) > 0) {
            throw ApiException.badRequest("Planned amount cannot exceed the outstanding balance");
        }
        LocalDate plannedDate = parseDate(input.plannedDate());
        boolean onHold = Boolean.TRUE.equals(input.onHold());
        jdbc.sql("INSERT INTO \"SupplierPaymentSchedule\" (id, \"organizationId\", \"documentId\", \"plannedDate\","
                        + " \"plannedAmount\", \"onHold\", notes, \"createdBy\", \"updatedBy\") VALUES (?, ?, ?, ?, "
                        + "?, ?, ?, ?, ?)"
                        + " ON CONFLICT (\"organizationId\", \"documentId\") DO UPDATE SET \"plannedDate\" ="
                        + " EXCLUDED.\"plannedDate\", \"plannedAmount\" = EXCLUDED.\"plannedAmount\", \"onHold\" ="
                        + " EXCLUDED.\"onHold\", notes = EXCLUDED.notes, \"updatedBy\" = EXCLUDED.\"updatedBy\","
                        + " \"updatedAt\" = CURRENT_TIMESTAMP")
                .params(UUID.randomUUID().toString(), org, documentId,
                        plannedDate == null ? null : java.sql.Date.valueOf(plannedDate), input.plannedAmount(), onHold,
                        input.notes(), TenantContext.userId(), TenantContext.userId())
                .update();
        audit.record(AuditInput.of("VendorBill", documentId, "update", null, Obj.of("paymentPlan", plannedDate == null
                ? null : plannedDate.toString(), "plannedAmount", input.plannedAmount(), "onHold", onHold)));
        return Obj.of("documentId", documentId, "plannedDate", plannedDate == null ? null : plannedDate.toString(),
                "plannedAmount", input.plannedAmount() == null ? null : JsNumbers.dec(input.plannedAmount()), "onHold",
                onHold, "notes", input.notes());
    }

    private static LocalDate paymentDate(Map<String, Object> row) {
        if (row.get("plannedDate") != null) {
            return LocalDate.parse((String) row.get("plannedDate"));
        }
        Object due = row.get("dueDate") != null ? row.get("dueDate") : row.get("issueDate");
        return ((Instant) due).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.length() > 10 ? raw.substring(0, 10) : raw);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid date: " + raw);
        }
    }
}
