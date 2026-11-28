package com.inventoryerp.sales.application;

import static com.inventoryerp.sales.application.SalesDb.dec;
import static com.inventoryerp.sales.application.SalesDb.org;
import static com.inventoryerp.sales.application.SalesDb.str;

import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.common.Obj;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-S094 §4.4: the financial outcome of a sale, computed from its posted documents and nothing else.
 *
 * <ul>
 *   <li>invoiced: posted regular invoices, gross of the down-payment deductions they carry (so a zero-total final
 *       invoice after a full advance still counts what it sold);
 *   <li>credited: posted credit notes linked to the sale's lines or against its regular invoices (quantity and value
 *       kinds), except credit notes on its down-payment invoices (those reduce the advance) and bad-debt write-offs,
 *       which are counted apart as written off;
 *   <li>outstanding: open invoice balances less the sale's open credit not kept as customer credit; negative when the
 *       customer is owed money on the sale;
 *   <li>refunded: credit paid back (taken from the sale's credit notes); retained as credit: credit used on other
 *       documents plus credit kept on the customer's account by the explicit action;
 *   <li>received: what the sale's invoices were settled with (receipts, deposit applications, deductions), plus what was
 *       later refunded or kept as credit;
 *   <li>advance held: open deposits of the sale not yet applied or deducted.
 * </ul>
 */
@Service
public class SalesOutcomeService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private static final String SQL = """
            WITH inv AS (
              SELECT d.id, d."totalAmount" AS total, d."amountResidual" AS residual,
                     COALESCE((SELECT sum(j.amount) FROM "SalesInvoiceLink" j WHERE j."documentId" = d.id
                                  AND j."orderId" = ? AND j.kind = 'deduction'), 0) AS deducted
                FROM "Document" d
               WHERE d.id IN (SELECT k."documentId" FROM "SalesInvoiceLink" k WHERE k."orderId" = ? AND k.kind = 'regular')
                 AND d."organizationId" = ? AND d.status::text IN ('posted', 'paid')
            ), dp AS (
              SELECT k."documentId" AS id FROM "SalesInvoiceLink" k WHERE k."orderId" = ? AND k.kind = 'down_payment'
            ), cn AS (
              SELECT d.id, d."totalAmount" AS total, d."amountResidual" AS residual,
                     COALESCE(d.data->>'creditType', '') = 'write_off' AS "writeOff"
                FROM "Document" d
               WHERE d."organizationId" = ? AND d."documentType"::text = 'credit_note'
                 AND d.status::text IN ('posted', 'paid')
                 AND (d.id IN (SELECT k."documentId" FROM "SalesInvoiceLink" k WHERE k."orderId" = ? AND k.kind = 'credit_note')
                      OR d."reversedDocumentId" IN (SELECT id FROM inv))
                 AND (d."reversedDocumentId" IS NULL OR d."reversedDocumentId" NOT IN (SELECT id FROM dp))
            ), alloc AS (
              SELECT a."creditDocumentId" AS cn, sum(a.amount) AS total,
                     COALESCE(sum(a.amount) FILTER (WHERE a."documentId" NOT IN (SELECT id FROM inv)), 0) AS elsewhere
                FROM "CreditAllocation" a WHERE a."creditDocumentId" IN (SELECT id FROM cn) GROUP BY a."creditDocumentId"
            )
            SELECT COALESCE((SELECT sum(total + deducted) FROM inv), 0) AS invoiced,
                   COALESCE((SELECT sum(residual) FROM inv), 0) AS "invoiceOpen",
                   COALESCE((SELECT sum(total) FROM cn WHERE NOT "writeOff"), 0) AS credited,
                   COALESCE((SELECT sum(total) FROM cn WHERE "writeOff"), 0) AS "writtenOff",
                   COALESCE((SELECT sum(residual) FROM cn), 0) AS "creditOpen",
                   COALESCE((SELECT sum(c.total - COALESCE(a.total, 0) - c.residual) FROM cn c LEFT JOIN alloc a
                              ON a.cn = c.id), 0) AS refunded,
                   COALESCE((SELECT sum(elsewhere) FROM alloc), 0) AS elsewhere,
                   COALESCE((SELECT sum(p.amount - p."appliedAmount" - p."refundedAmount") FROM "CustomerDeposit" p
                              WHERE p."organizationId" = ? AND p."salesOrderId" = ? AND p.status <> 'void'), 0)
                       AS "advanceHeld\"""";

    /**
     * The outcome of one sale. {@code openCredit} is the sale's credit the customer may still use, be refunded or keep on
     * account (credit notes' open balance not yet kept).
     */
    public record Outcome(BigDecimal invoiced, BigDecimal credited, BigDecimal writtenOff, BigDecimal received,
            BigDecimal refunded, BigDecimal retained, BigDecimal outstanding, BigDecimal advanceHeld,
            BigDecimal openCredit, String paymentStatus, String creditStatus, String settlementStatus) {

        /** The stored columns of {@code SalesOrder}. */
        Map<String, Object> columns() {
            return Obj.of("amountInvoiced", invoiced, "amountCredited", credited, "amountWrittenOff", writtenOff,
                    "amountReceived", received, "amountRefunded", refunded, "amountRetainedAsCredit", retained,
                    "amountOutstanding", outstanding, "advanceHeld", advanceHeld, "paymentStatus", paymentStatus,
                    "creditStatus", creditStatus, "settlementStatus", settlementStatus, "outcomeAt", Instant.now());
        }

        /** The response fields (JS numbers), as stored plus {@code openCredit}. */
        Map<String, Object> json() {
            return Obj.of("amountInvoiced", JsNumbers.js(invoiced), "amountCredited", JsNumbers.js(credited),
                    "amountWrittenOff", JsNumbers.js(writtenOff), "amountReceived", JsNumbers.js(received),
                    "amountRefunded", JsNumbers.js(refunded), "amountRetainedAsCredit", JsNumbers.js(retained),
                    "amountOutstanding", JsNumbers.js(outstanding), "advanceHeld", JsNumbers.js(advanceHeld),
                    "openCredit", JsNumbers.js(openCredit), "paymentStatus", paymentStatus, "creditStatus",
                    creditStatus, "settlementStatus", settlementStatus);
        }

        boolean settled() {
            return "settled".equals(settlementStatus);
        }
    }

    private final SalesDb db;

    public SalesOutcomeService(SalesDb db) {
        this.db = db;
    }

    /** The outcome of a sale from its posted documents (reads only); null when the sale does not exist. */
    @Transactional(readOnly = true)
    public Outcome compute(String orderId) {
        Map<String, Object> o = db.row("SELECT total, status, \"creditKept\" FROM \"SalesOrder\" WHERE id = ?"
                + " AND \"organizationId\" = ?", orderId, org());
        if (o == null) {
            return null;
        }
        Map<String, Object> r = db.row(SQL, orderId, orderId, org(), orderId, org(), orderId, org(), orderId);
        return outcome(str(o.get("status")), money(o.get("total")), money(o.get("creditKept")), money(r.get("invoiced")),
                money(r.get("invoiceOpen")), money(r.get("credited")), money(r.get("writtenOff")),
                money(r.get("creditOpen")), money(r.get("refunded")), money(r.get("elsewhere")),
                money(r.get("advanceHeld")).max(ZERO));
    }

    /** Recompute and store the outcome of a sale (in the caller's transaction). */
    @Transactional
    public Outcome refresh(String orderId) {
        Outcome c = compute(orderId);
        if (c != null) {
            db.patch("SalesOrder", orderId, c.columns(), false);
        }
        return c;
    }

    /** The sales whose outcome a document (invoice, credit note) touches. */
    @Transactional(readOnly = true)
    public List<String> ordersOf(String documentId) {
        return db.rows("SELECT DISTINCT k.\"orderId\" FROM \"SalesInvoiceLink\" k WHERE k.\"organizationId\" = ? AND"
                + " (k.\"documentId\" = ? OR k.\"documentId\" = (SELECT d.\"reversedDocumentId\" FROM \"Document\" d"
                + " WHERE d.id = ? AND d.\"organizationId\" = ?))", org(), documentId, documentId, org()).stream()
                .map(x -> str(x.get("orderId"))).toList();
    }

    /** The documents a payment was allocated to (also after a void, from its recorded allocations). */
    @Transactional(readOnly = true)
    public List<String> documentsOfPayment(String paymentId) {
        return db.rows("SELECT DISTINCT COALESCE(a.\"documentId\", a.\"invoiceId\") AS doc FROM \"PaymentAllocation\" a"
                + " WHERE a.\"paymentId\" = ? AND a.\"organizationId\" = ? AND COALESCE(a.\"documentId\","
                + " a.\"invoiceId\") IS NOT NULL", paymentId, org()).stream().map(x -> str(x.get("doc"))).toList();
    }

    /** The §4.4 rules on computed amounts (pure). */
    static Outcome outcome(String status, BigDecimal total, BigDecimal creditKept, BigDecimal invoiced,
            BigDecimal invoiceOpen, BigDecimal credited, BigDecimal writtenOff, BigDecimal creditOpen,
            BigDecimal refunded, BigDecimal elsewhere, BigDecimal advanceHeld) {
        BigDecimal kept = creditKept.min(creditOpen).max(ZERO);
        BigDecimal openCredit = creditOpen.subtract(kept);
        BigDecimal outstanding = invoiceOpen.subtract(openCredit);
        BigDecimal retained = elsewhere.add(kept);
        BigDecimal net = invoiced.subtract(credited);
        BigDecimal netReceived = net.subtract(writtenOff).subtract(outstanding);
        BigDecimal received = netReceived.add(refunded).add(retained);
        String payment;
        if (invoiced.signum() == 0) {
            payment = total.signum() == 0 || "done".equals(status) || "cancelled".equals(status) ? "no_charge"
                    : "not_invoiced";
        } else if (outstanding.signum() < 0) {
            payment = "credit_balance";
        } else if (outstanding.signum() > 0) {
            payment = netReceived.signum() > 0 ? "partially_paid" : "unpaid";
        } else if (writtenOff.signum() > 0) {
            payment = "written_off";
        } else if (net.signum() == 0) {
            payment = refunded.signum() > 0 ? "refunded" : "credited";
        } else {
            payment = "paid";
        }
        String credit = credited.signum() == 0 ? "none" : credited.compareTo(invoiced) >= 0 ? "full" : "partial";
        boolean settled = outstanding.signum() == 0 && advanceHeld.signum() == 0 && !"not_invoiced".equals(payment)
                && !"draft".equals(status);
        return new Outcome(invoiced, credited, writtenOff, received, refunded, retained, outstanding, advanceHeld,
                openCredit, payment, credit, settled ? "settled" : "open");
    }

    private static BigDecimal money(Object v) {
        return dec(v).setScale(2, RoundingMode.HALF_UP);
    }
}
