package com.inventoryerp.intercompany.application;

import com.inventoryerp.intercompany.application.CompanyGroupService.Group;
import com.inventoryerp.intercompany.application.IntercompanyPartnerService.Settings;
import com.inventoryerp.invoicing.application.DocumentBuilderService.LineInput;
import com.inventoryerp.invoicing.application.VendorBillService;
import com.inventoryerp.invoicing.application.VendorBillService.BillInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mirrored documents (ADR-S028 §4): a sales invoice posted by X to the partner standing for member Y becomes a vendor
 * bill in Y, to the partner standing for X. One {@code IntercompanyTransaction} row per source document records the
 * outcome; business errors are recorded, never thrown, so the outbox does not retry them forever.
 */
@Service
public class IntercompanyMirrorService {

    private static final Logger log = LoggerFactory.getLogger(IntercompanyMirrorService.class);

    private final JdbcClient jdbc;
    private final CompanyGroupService groups;
    private final IntercompanyPartnerService partners;
    private final VendorBillService bills;
    private final TransactionTemplate newTx;

    public IntercompanyMirrorService(JdbcClient jdbc, CompanyGroupService groups, IntercompanyPartnerService partners,
            VendorBillService bills, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.partners = partners;
        this.bills = bills;
        this.newTx = new TransactionTemplate(txm);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Source document header (company X). */
    private record Source(String id, String number, String partnerId, String currencyId, String currencyCode,
            Timestamp issueDate, Timestamp dueDate, BigDecimal total, String status, List<Map<String, Object>> lines) {}

    /** Called for {@code invoice.posted} in {@code sourceOrg}; no-op for invoices to non-intercompany partners. */
    public void onInvoicePosted(String sourceOrg, String documentId) {
        Source src = TenantContext.run(TenantStore.organization(sourceOrg), () -> newTx.execute(s -> source(sourceOrg,
                documentId)));
        if (src == null || !"posted".equals(src.status())) {
            return;
        }
        Optional<String> target = partners.counterpartOf(sourceOrg, src.partnerId());
        if (target.isEmpty()) {
            return;
        }
        Optional<Group> group = groups.groupOf(sourceOrg);
        if (group.isEmpty() || !group.get().contains(target.get())) {
            return;
        }
        String rowId = UUID.randomUUID().toString();
        int inserted = jdbc.sql("INSERT INTO \"IntercompanyTransaction\" (id, \"organizationId\", \"sourceDocumentId\","
                + " \"sourceDocumentType\", \"sourceDocumentNumber\", \"targetOrganizationId\", amount, \"currencyCode\","
                + " status) VALUES (?, ?, ?, 'sales_invoice', ?, ?, ?, ?, 'failed') ON CONFLICT (\"organizationId\","
                + " \"sourceDocumentId\") DO NOTHING").params(rowId, sourceOrg, documentId, src.number(), target.get(),
                src.total(), src.currencyCode()).update();
        if (inserted == 0) {
            return; // at-least-once delivery: already handled (a failed row is retried by hand)
        }
        mirror(rowId, sourceOrg, src, target.get());
    }

    /** {@code POST /intercompany/transactions/:id/retry} (source company). */
    public Map<String, Object> retry(String id) {
        String org = TenantContext.organizationId();
        Map<String, Object> row = SqlRows.one(jdbc.sql("SELECT * FROM \"IntercompanyTransaction\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org));
        if (row == null) {
            throw ApiException.notFound("Intercompany transaction not found");
        }
        if ("mirrored".equals(row.get("status"))) {
            throw ApiException.badRequest("The document is already mirrored");
        }
        Source src = newTx.execute(s -> source(org, (String) row.get("sourceDocumentId")));
        if (src == null || !"posted".equals(src.status())) {
            throw ApiException.badRequest("The source document is no longer posted");
        }
        String target = (String) row.get("targetOrganizationId");
        groups.requireSameGroup(target);
        TenantContext.unscoped(() -> {
            mirror(id, org, src, target);
            return null;
        });
        return one(id);
    }

    private void mirror(String rowId, String sourceOrg, Source src, String targetOrg) {
        String status;
        String error = null;
        String billId = null;
        String billNumber = null;
        try {
            Settings settings = partners.settings(targetOrg);
            if ("off".equals(settings.mirrorMode())) {
                status = "skipped";
                error = "Mirroring is off in the receiving company";
            } else {
                Map<String, Object> bill = TenantContext.run(TenantStore.organization(targetOrg),
                        () -> newTx.execute(s -> createBill(sourceOrg, targetOrg, src, settings)));
                billId = (String) bill.get("id");
                billNumber = (String) bill.get("documentNumber");
                status = "mirrored";
                if ("post".equals(settings.mirrorMode())) {
                    String id = billId;
                    try {
                        TenantContext.run(TenantStore.organization(targetOrg),
                                () -> newTx.execute(s -> bills.post(id, false, false)));
                    } catch (ApiException e) {
                        error = "Bill left in draft: " + e.getMessage();
                    }
                }
            }
        } catch (ApiException e) {
            status = "failed";
            error = e.getMessage();
        } catch (RuntimeException e) {
            log.warn("intercompany mirror of {} failed", src.number(), e);
            status = "failed";
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        jdbc.sql("UPDATE \"IntercompanyTransaction\" SET status = ?, error = ?, \"targetDocumentId\" = ?,"
                + " \"targetDocumentType\" = CASE WHEN ?::text IS NULL THEN NULL ELSE 'vendor_bill' END,"
                + " \"targetDocumentNumber\" = ?, attempts = attempts + 1, \"updatedAt\" = now() WHERE id = ?")
                .params(status, error, billId, billId, billNumber, rowId).update();
    }

    /** Runs in the target company. */
    private Map<String, Object> createBill(String sourceOrg, String targetOrg, Source src, Settings settings) {
        // sourceOrg: the company the partner in the target stands for.
        String partnerId = partners.partnerFor(targetOrg, sourceOrg).orElse(null);
        if (partnerId == null) {
            partnerId = (String) partners.link(sourceOrg, null).get("partnerId");
        }
        String account = partners.billExpenseAccount(settings);
        if (account == null) {
            throw ApiException.badRequest("Set the expense account for intercompany bills in the receiving company");
        }
        List<LineInput> lines = new ArrayList<>();
        for (Map<String, Object> l : src.lines()) {
            String taxId = l.get("taxRate") == null ? null : mapTax(targetOrg, (String) l.get("taxCode"),
                    (String) l.get("taxType"), SqlRows.dec(l, "taxRate"), Boolean.TRUE.equals(l.get("taxIsInclusive")));
            lines.add(new LineInput(null, null, null, null, account, (String) l.get("description"),
                    SqlRows.dec(l, "quantity"), SqlRows.dec(l, "unitPrice"), SqlRows.dec(l, "discountPercent"), null,
                    null, null, null, taxId, null, Boolean.TRUE.equals(l.get("taxInclusive")), null));
        }
        if (lines.isEmpty()) {
            throw ApiException.badRequest("The invoice has no lines");
        }
        BillInput input = new BillInput(partnerId, day(src.issueDate()), src.dueDate() == null ? null
                : day(src.dueDate()), src.currencyId(), src.number(), "Intercompany: mirrored from " + src.number(),
                null, List.of(), lines);
        Map<String, Object> bill = bills.create(input);
        jdbc.sql("UPDATE \"Document\" SET \"sourceType\" = 'intercompany', \"sourceId\" = ? WHERE id = ? AND"
                + " \"organizationId\" = ?").params(src.id(), bill.get("id"), targetOrg).update();
        return bill;
    }

    /** Tax of the target company: same code, else same type + rate + inclusive flag; none when nothing matches. */
    private String mapTax(String org, String code, String type, BigDecimal rate, boolean inclusive) {
        if (code != null) {
            Optional<String> byCode = jdbc.sql("SELECT id FROM \"Tax\" WHERE \"organizationId\" = ? AND code = ? AND"
                    + " \"isActive\" AND \"deletedAt\" IS NULL LIMIT 1").params(org, code).query(String.class).optional();
            if (byCode.isPresent()) {
                return byCode.get();
            }
        }
        return jdbc.sql("SELECT id FROM \"Tax\" WHERE \"organizationId\" = ? AND type::text = ? AND rate = ? AND"
                + " \"isInclusive\" = ? AND \"isActive\" AND \"deletedAt\" IS NULL ORDER BY \"createdAt\" LIMIT 1")
                .params(org, type, rate, inclusive).query(String.class).optional().orElse(null);
    }

    private Source source(String org, String documentId) {
        Map<String, Object> d = SqlRows.one(jdbc.sql("SELECT d.id, d.\"documentNumber\", d.\"partnerId\","
                + " d.\"currencyId\", c.code AS \"currencyCode\", d.\"issueDate\", d.\"dueDate\", d.\"totalAmount\","
                + " d.status::text AS status FROM \"Document\" d LEFT JOIN \"Currency\" c ON c.id = d.\"currencyId\""
                + " WHERE d.id = ? AND d.\"organizationId\" = ? AND d.\"documentType\"::text = 'sales_invoice'")
                .params(documentId, org));
        if (d == null) {
            return null;
        }
        String ccy = d.get("currencyCode") != null ? (String) d.get("currencyCode")
                : jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org).query(String.class)
                        .single();
        // Read here, as the source company: the bill is written as the target, whose tenant cannot see these rows.
        List<Map<String, Object>> lines = SqlRows.list(jdbc.sql("SELECT l.description, l.quantity, l.\"unitPrice\","
                + " l.\"discountPercent\", l.\"taxInclusive\", t.code AS \"taxCode\", t.type::text AS \"taxType\","
                + " t.rate AS \"taxRate\", t.\"isInclusive\" AS \"taxIsInclusive\" FROM \"DocumentLine\" l LEFT JOIN"
                + " \"Tax\" t ON t.id = l.\"taxId\" WHERE l.\"documentId\" = ? AND l.\"organizationId\" = ? ORDER BY"
                + " l.\"lineNumber\"").params(documentId, org));
        return new Source((String) d.get("id"), (String) d.get("documentNumber"), (String) d.get("partnerId"),
                (String) d.get("currencyId"), ccy, ts(d.get("issueDate")), ts(d.get("dueDate")),
                SqlRows.dec(d, "totalAmount"), (String) d.get("status"), lines);
    }

    private static Timestamp ts(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Timestamp t) {
            return t;
        }
        if (v instanceof java.time.Instant i) {
            return Timestamp.from(i);
        }
        if (v instanceof java.time.LocalDateTime l) {
            return Timestamp.valueOf(l);
        }
        return Timestamp.valueOf(String.valueOf(v).replace('T', ' ').replace("Z", ""));
    }

    private static String day(Timestamp t) {
        return t.toLocalDateTime().toLocalDate().toString();
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    /** {@code GET /intercompany/transactions}: documents this company sent or received. */
    public PageResult<Map<String, Object>> list(PageQuery q, String status) {
        String org = TenantContext.organizationId();
        // Rows received from another company live in that company's tenant: read without the tenant filter, by id.
        return TenantContext.unscoped(() -> newTx.execute(s -> {
            StringBuilder where = new StringBuilder(" WHERE (t.\"organizationId\" = ? OR t.\"targetOrganizationId\" = ?)");
            List<Object> params = new ArrayList<>(List.of(org, org));
            if (status != null && !status.isBlank()) {
                where.append(" AND t.status = ?");
                params.add(status);
            }
            long total = jdbc.sql("SELECT count(*) FROM \"IntercompanyTransaction\" t" + where).params(params)
                    .query(Long.class).single();
            List<Object> page = new ArrayList<>(params);
            page.add(q.pageSizeOrDefault());
            page.add(q.offset());
            List<Map<String, Object>> rows = SqlRows.list(jdbc.sql("SELECT t.*, so.code AS \"sourceCompanyCode\","
                    + " so.name AS \"sourceCompanyName\", tg.code AS \"targetCompanyCode\", tg.name AS"
                    + " \"targetCompanyName\" FROM \"IntercompanyTransaction\" t JOIN \"Organization\" so ON so.id ="
                    + " t.\"organizationId\" JOIN \"Organization\" tg ON tg.id = t.\"targetOrganizationId\"" + where
                    + " ORDER BY t.\"createdAt\" DESC LIMIT ? OFFSET ?").params(page));
            rows.forEach(r -> r.put("direction", org.equals(r.get("organizationId")) ? "outgoing" : "incoming"));
            return PageResult.of(rows, q, total);
        }));
    }

    private Map<String, Object> one(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"IntercompanyTransaction\" WHERE id = ?").param(id));
        return r == null ? Obj.of("id", id) : r;
    }
}
