package com.inventoryerp.invoicing.application;

import com.inventoryerp.credit.application.CreditExposureContributor;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Invoicing share of credit exposure (credit.md §2): open residual of posted sales invoices and debit notes
 * (receivables, with the oldest overdue day count), draft invoices not raised from a sales order (other), and
 * unapplied credit notes and on-account payments (credits), and unapplied customer deposits (deposits, S6).
 */
@Component
public class InvoicingCreditContributor implements CreditExposureContributor {

    private final JdbcClient jdbc;

    public InvoicingCreditContributor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Contribution contribute(String partnerId) {
        return contribute(partnerId, null);
    }

    /** {@code excludeDocumentId}: the invoice being posted, which the check adds itself. */
    @Override
    public Contribution contribute(String partnerId, String excludeDocumentId) {
        String org = TenantContext.organizationId();
        Map<String, Object> r = jdbc.sql("""
                SELECT COALESCE(sum("amountResidual") FILTER (WHERE "documentType"::text IN ('sales_invoice', 'debit_note')
                           AND status::text IN ('posted', 'paid')), 0) AS receivables,
                       COALESCE(sum("amountResidual") FILTER (WHERE "documentType"::text IN ('sales_invoice', 'debit_note')
                           AND status::text IN ('posted', 'paid') AND "dueDate" < now()), 0) AS overdue,
                       COALESCE(max(EXTRACT(DAY FROM now() - "dueDate")) FILTER (WHERE "documentType"::text IN
                           ('sales_invoice', 'debit_note') AND status::text IN ('posted', 'paid') AND "amountResidual" > 0
                           AND "dueDate" < now()), 0) AS days,
                       COALESCE(sum("totalAmount") FILTER (WHERE "documentType"::text = 'sales_invoice'
                           AND status::text IN ('draft', 'submitted', 'approved')
                           AND COALESCE("sourceType", '') <> 'sales_order' AND id <> ?), 0) AS other,
                       COALESCE(sum("amountResidual") FILTER (WHERE "documentType"::text = 'credit_note'
                           AND status::text IN ('posted', 'paid')), 0) AS credits
                  FROM "Document" WHERE "organizationId" = ? AND "partnerId" = ?""")
                .params(excludeDocumentId == null ? "" : excludeDocumentId, org, partnerId).query().singleRow();
        BigDecimal onAccount = jdbc.sql("SELECT COALESCE(sum(\"unallocatedAmount\"), 0) FROM \"Payment\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ? AND direction::text = 'inbound' AND status::text ="
                + " 'posted'").params(org, partnerId).query(BigDecimal.class).single();
        BigDecimal deposits = jdbc.sql("SELECT COALESCE(sum(amount - \"appliedAmount\" - \"refundedAmount\"), 0) FROM"
                + " \"CustomerDeposit\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? AND status = 'open'")
                .params(org, partnerId).query(BigDecimal.class).single();
        return new Contribution(BigDecimal.ZERO, BigDecimal.ZERO, d(r.get("receivables")), d(r.get("other")),
                d(deposits), d(r.get("credits")).add(d(onAccount)), d(r.get("overdue")),
                d(r.get("days")).intValue());
    }

    private static BigDecimal d(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()).setScale(2, RoundingMode.HALF_UP);
    }
}
