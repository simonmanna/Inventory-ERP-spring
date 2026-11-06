package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.NOW;
import static com.inventoryerp.sales.application.SalesDb.blank;
import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Milestone billing (SG-9). Each milestone bills through the existing down-payment invoice, so its deposit, tax point and
 * final settlement against the regular invoice stay on the one accounting path.
 */
@Service
public class SalesMilestoneService {

    private final SalesDb db;
    private final SalesOrderService orders;
    private final SalesBillingService billing;
    private final AuditService audit;

    public SalesMilestoneService(SalesDb db, SalesOrderService orders, SalesBillingService billing, AuditService audit) {
        this.db = db;
        this.orders = orders;
        this.billing = billing;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String orderId) {
        orders.require(orderId);
        return db.rows("SELECT m.*, d.\"documentNumber\" FROM \"SalesOrderMilestone\" m LEFT JOIN \"Document\" d ON d.id ="
                + " m.\"documentId\" WHERE m.\"orderId\" = ? AND m.\"organizationId\" = ? ORDER BY m.\"sortOrder\"", orderId, org());
    }

    /** Replace the pending milestones. Each has a name and a percent or an amount; the plan may not exceed the order total. */
    @Transactional
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> save(String orderId, Map<String, Object> in) {
        Map<String, Object> o = orders.lock(orderId);
        if (java.util.Set.of("cancelled", "done").contains(o.get("status"))) {
            throw ApiException.badRequest("This order is closed");
        }
        List<Map<String, Object>> raw = in.get("milestones") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        BigDecimal total = dec(o.get("total"));
        BigDecimal planned = dec(db.row("SELECT COALESCE(sum(amount), 0) AS a FROM \"SalesOrderMilestone\" WHERE \"orderId\" = ? AND"
                + " status = 'invoiced'", orderId).get("a"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            if (blank(str(m.get("name")))) {
                throw ApiException.badRequest("name should not be empty");
            }
            BigDecimal percent = m.get("percent") == null ? null : dec(m.get("percent"));
            BigDecimal amount = percent != null ? total.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                    : dec(m.get("amount"));
            if (amount.signum() <= 0) {
                throw ApiException.badRequest("Give a milestone percent or amount");
            }
            planned = planned.add(amount);
            rows.add(Obj.of("orderId", orderId, "name", str(m.get("name")), "percent", percent, "amount", amount, "dueDate",
                    SalesDb.date(str(m.get("dueDate"))), "sortOrder", rows.size()));
        }
        if (planned.compareTo(total.add(new BigDecimal("0.01"))) > 0) {
            throw ApiException.badRequest("The milestones add up to more than the order total");
        }
        db.update("DELETE FROM \"SalesOrderMilestone\" WHERE \"orderId\" = ? AND \"organizationId\" = ? AND status = 'pending'", orderId, org());
        rows.forEach(r -> db.insert("SalesOrderMilestone", r));
        audit.record(AuditInput.of("SalesOrder", orderId, "update", null, Obj.of("milestones", rows.size())));
        return list(orderId);
    }

    /** Bill one milestone. */
    @Transactional
    public Map<String, Object> invoice(String orderId, String milestoneId) {
        orders.lock(orderId);
        Map<String, Object> m = db.row("SELECT * FROM \"SalesOrderMilestone\" WHERE id = ? AND \"orderId\" = ? AND \"organizationId\" = ? FOR UPDATE",
                milestoneId, orderId, org());
        if (m == null) {
            throw ApiException.notFound("Milestone not found");
        }
        if (!"pending".equals(m.get("status"))) {
            throw ApiException.badRequest("This milestone is already invoiced");
        }
        Map<String, Object> doc = billing.createInvoice(orderId, Obj.of("mode", "down_payment", "amount", m.get("amount")));
        db.update("UPDATE \"SalesOrderMilestone\" SET status = 'invoiced', \"documentId\" = ?, \"updatedAt\" = " + NOW + " WHERE id = ?",
                doc.get("id"), milestoneId);
        return doc;
    }
}
