package com.inventoryerp.invoicing.application;

import com.inventoryerp.invoicing.application.MobileMoneyProvider.CollectRequest;
import com.inventoryerp.invoicing.application.MobileMoneyProvider.CollectionState;
import com.inventoryerp.invoicing.application.PaymentService.AllocationInput;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageResult;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mobile money collections from customers (INT-1). A collection is a row first, committed before the provider is called,
 * so a crash leaves something to reconcile. A successful collection becomes one customer receipt through
 * {@link PaymentService} (Dr mobile money, Cr receivable, via the posting engine); the row lock and {@code paymentId}
 * make that exactly once, however many polls and callbacks report it. The phone number is kept masked.
 */
@Service
public class MobileMoneyService {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyService.class);

    /** Start a collection; {@code invoiceId} (POS) or {@code documentId} (manual invoice) allocates the receipt. */
    public record CollectionInput(
            @NotBlank String partnerId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 14, fraction = 2) BigDecimal amount,
            @NotBlank @Pattern(regexp = "\\+?[0-9]{9,15}") String msisdn,
            String accountId,
            String invoiceId,
            String documentId,
            @Size(max = 200) String description) {}

    public record CollectionQuery(String status, String partnerId, Integer page, Integer pageSize) {}

    private final JdbcClient jdbc;
    private final MobileMoneyProviders providers;
    private final PaymentService payments;
    private final SequenceService sequence;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public MobileMoneyService(JdbcClient jdbc, MobileMoneyProviders providers, PaymentService payments,
            SequenceService sequence, AuditService audit, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.providers = providers;
        this.payments = payments;
        this.sequence = sequence;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Provider configuration as the screen shows it (never a key). */
    public Map<String, Object> settings() {
        MobileMoneyProvider p = providers.get(providers.configured()).orElse(null);
        return Obj.of("provider", providers.configured(), "available", providers.available(), "configured",
                p != null && p.configured(), "callbackSecretConfigured", providers.callbackSecretConfigured());
    }

    /** Collections, newest first. */
    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(CollectionQuery q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.pageSize() == null ? 25 : Math.max(1, Math.min(200, q.pageSize()));
        StringBuilder where = new StringBuilder(" WHERE c.\"organizationId\" = ?");
        List<Object> params = new ArrayList<>(List.of(org()));
        if (q.status() != null && !q.status().isBlank()) {
            where.append(" AND c.status = ?");
            params.add(q.status());
        }
        if (q.partnerId() != null && !q.partnerId().isBlank()) {
            where.append(" AND c.\"partnerId\" = ?");
            params.add(q.partnerId());
        }
        String from = " FROM \"MobileMoneyCollection\" c JOIN \"Partner\" p ON p.id = c.\"partnerId\""
                + " LEFT JOIN \"Payment\" y ON y.id = c.\"paymentId\"";
        long total = jdbc.sql("SELECT count(*)" + from + where).params(params).query(Long.class).single();
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((page - 1) * size);
        return PageResult.of(SqlRows.list(jdbc.sql("SELECT c.*, p.name AS \"partnerName\", y.\"paymentNumber\"" + from
                + where + " ORDER BY c.\"createdAt\" DESC, c.id LIMIT ? OFFSET ?").params(pageParams)), page, size,
                total);
    }

    /** One collection (404 when unknown). */
    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("""
                SELECT c.*, p.name AS "partnerName", y."paymentNumber"
                FROM "MobileMoneyCollection" c JOIN "Partner" p ON p.id = c."partnerId"
                LEFT JOIN "Payment" y ON y.id = c."paymentId"
                WHERE c.id = ? AND c."organizationId" = ?""").params(id, org()));
        if (c == null) {
            throw ApiException.notFound("Mobile money collection not found");
        }
        return c;
    }

    /**
     * Records the collection, commits, then asks the provider. A provider failure leaves it pending, with the error kept
     * on the row, for a retry by refresh, callback or the poll job. Not {@code @Transactional}: the steps commit apart.
     */
    public Map<String, Object> collect(CollectionInput in) {
        String code = providers.configured();
        MobileMoneyProvider provider = providers.get(code)
                .orElseThrow(() -> ApiException.badRequest("Mobile money provider " + code + " is not available"));
        if (!provider.configured()) {
            throw ApiException.badRequest("Mobile money provider " + code + " is not configured");
        }
        Map<String, Object> row = tx.execute(s -> insert(in, code));
        String id = (String) row.get("id");
        try {
            CollectionState state = provider.collect(new CollectRequest((String) row.get("reference"), in.msisdn(),
                    in.amount(), (String) row.get("currency"), in.description()));
            tx.executeWithoutResult(s -> apply(id, state));
        } catch (RuntimeException e) {
            log.warn("mobile money collection {} could not start: {}", row.get("reference"), e.toString());
            tx.executeWithoutResult(s -> note(id, e.getMessage()));
        }
        return tx.execute(s -> get(id));
    }

    /** Asks the provider for the current state of a pending collection. */
    public Map<String, Object> refresh(String id) {
        Map<String, Object> c = tx.execute(s -> get(id));
        if ("pending".equals(c.get("status")) && c.get("providerReference") != null) {
            MobileMoneyProvider provider = providers.get((String) c.get("provider"))
                    .orElseThrow(() -> ApiException.badRequest("Mobile money provider " + c.get("provider")
                            + " is not available"));
            CollectionState state = provider.status((String) c.get("providerReference"));
            tx.executeWithoutResult(s -> apply(id, state));
        }
        return tx.execute(s -> get(id));
    }

    /**
     * A signed provider callback: 401 for a bad signature, otherwise applied as the user who started the collection, so
     * the receipt carries a person. An unknown reference is ignored without saying so.
     */
    public void callback(String code, String organizationId, String body, String signature) {
        if (!providers.validSignature(body, signature)) {
            throw ApiException.unauthorized("Invalid signature");
        }
        MobileMoneyProvider provider = providers.get(code).orElseThrow(() -> ApiException.notFound("Not found"));
        CollectionState state = provider.parseCallback(body);
        Map<String, Object> row = TenantContext.run(TenantStore.organization(organizationId),
                () -> tx.execute(s -> SqlRows.one(jdbc.sql("SELECT id, \"createdBy\" FROM \"MobileMoneyCollection\""
                        + " WHERE \"organizationId\" = ? AND provider = ? AND \"providerReference\" = ?")
                        .params(organizationId, code, state.providerReference()))));
        if (row == null) {
            log.warn("mobile money callback for an unknown reference from {}", code);
            return;
        }
        TenantContext.run(TenantStore.user(organizationId, (String) row.get("createdBy")),
                () -> tx.executeWithoutResult(s -> apply((String) row.get("id"), state)));
    }

    /** Refreshes the pending collections of the current organization older than a minute; one failure stops none. */
    public int pollPending() {
        Object before = SqlRows.ts(Instant.now().minus(Duration.ofMinutes(1)));
        List<Map<String, Object>> pending = tx.execute(s -> SqlRows.list(jdbc.sql("SELECT id, \"createdBy\" FROM"
                + " \"MobileMoneyCollection\" WHERE \"organizationId\" = ? AND status = 'pending' AND"
                + " \"providerReference\" IS NOT NULL AND \"createdAt\" < ?::timestamp ORDER BY \"createdAt\" LIMIT 200")
                .params(org(), before)));
        String organizationId = org();
        int done = 0;
        for (Map<String, Object> p : pending) {
            try {
                TenantContext.run(TenantStore.user(organizationId, (String) p.get("createdBy")),
                        () -> refresh((String) p.get("id")));
                done++;
            } catch (RuntimeException e) {
                log.warn("mobile money poll failed for {}: {}", p.get("id"), e.toString());
            }
        }
        return done;
    }

    // ------------------------------------------------------------------ steps

    private Map<String, Object> insert(CollectionInput in, String code) {
        String org = org();
        long customer = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(in.partnerId(), org).query(Long.class).single();
        if (customer == 0) {
            throw ApiException.badRequest("Partner not found");
        }
        boolean toInvoice = in.invoiceId() != null && !in.invoiceId().isBlank();
        boolean toDocument = in.documentId() != null && !in.documentId().isBlank();
        if (toInvoice && toDocument) {
            throw ApiException.badRequest("Choose an invoice or a document, not both");
        }
        BigDecimal amount = in.amount().setScale(2, RoundingMode.HALF_UP);
        if (toInvoice || toDocument) {
            String table = toInvoice ? "Invoice" : "Document";
            BigDecimal residual = jdbc.sql("SELECT \"amountResidual\" FROM \"" + table + "\" WHERE id = ? AND"
                    + " \"organizationId\" = ? AND \"partnerId\" = ?")
                    .params(toInvoice ? in.invoiceId() : in.documentId(), org, in.partnerId())
                    .query(BigDecimal.class).optional().orElse(null);
            if (residual == null) {
                throw ApiException.badRequest("Invoice not found for this customer");
            }
            if (amount.compareTo(residual) > 0) {
                throw ApiException.badRequest("The amount is more than the balance of the invoice");
            }
        }
        String currency = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org)
                .query(String.class).optional().orElse(null);
        String id = UUID.randomUUID().toString();
        String reference = sequence.next("mobile_money", "MM-", 6);
        jdbc.sql("""
                INSERT INTO "MobileMoneyCollection" (id, "organizationId", reference, provider, "partnerId", "msisdnMasked",
                    amount, currency, "accountId", "invoiceId", "documentId", description, "createdBy", "updatedAt")
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())""")
                .params(id, org, reference, code, in.partnerId(), mask(in.msisdn()), amount, currency, blank(in.accountId()),
                        blank(in.invoiceId()), blank(in.documentId()), in.description(), TenantContext.userId())
                .update();
        audit.recordInTx(AuditInput.of("MobileMoneyCollection", id, "create", null, Obj.of("reference", reference,
                "partnerId", in.partnerId(), "amount", amount, "provider", code)));
        return get(id);
    }

    /** Applies a provider state under the row lock; a finished collection ignores it (exactly-once receipt). */
    private void apply(String id, CollectionState state) {
        Map<String, Object> c = SqlRows.one(jdbc.sql("SELECT * FROM \"MobileMoneyCollection\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (c == null || !"pending".equals(c.get("status"))) {
            return;
        }
        if (MobileMoneyProvider.SUCCESSFUL.equals(state.status())) {
            Map<String, Object> payment = payments.createReceipt(receipt(c), true, false);
            jdbc.sql("UPDATE \"MobileMoneyCollection\" SET status = 'successful', \"paymentId\" = ?,"
                    + " \"providerReference\" = COALESCE(\"providerReference\", ?), \"failureReason\" = NULL,"
                    + " \"completedAt\" = now(), \"updatedAt\" = now() WHERE id = ?")
                    .params(payment.get("id"), state.providerReference(), id).update();
        } else if (MobileMoneyProvider.FAILED.equals(state.status())) {
            jdbc.sql("UPDATE \"MobileMoneyCollection\" SET status = 'failed', \"failureReason\" = ?,"
                    + " \"providerReference\" = COALESCE(\"providerReference\", ?), \"completedAt\" = now(),"
                    + " \"updatedAt\" = now() WHERE id = ?")
                    .params(state.failureReason(), state.providerReference(), id).update();
        } else {
            jdbc.sql("UPDATE \"MobileMoneyCollection\" SET \"providerReference\" = COALESCE(\"providerReference\", ?),"
                    + " \"updatedAt\" = now() WHERE id = ?").params(state.providerReference(), id).update();
            return;
        }
        audit.recordInTx(AuditInput.of("MobileMoneyCollection", id, "update", Obj.of("status", "pending"),
                Obj.of("status", state.status())));
    }

    private void note(String id, String message) {
        jdbc.sql("UPDATE \"MobileMoneyCollection\" SET \"failureReason\" = ?, \"updatedAt\" = now() WHERE id = ? AND"
                + " \"organizationId\" = ?").params(message, id, org()).update();
    }

    private static PaymentInput receipt(Map<String, Object> c) {
        BigDecimal amount = SqlRows.dec(c, "amount");
        List<AllocationInput> allocations = null;
        if (c.get("invoiceId") != null || c.get("documentId") != null) {
            allocations = List.of(new AllocationInput((String) c.get("documentId"), (String) c.get("invoiceId"), amount));
        }
        return new PaymentInput((String) c.get("partnerId"), LocalDate.now(BusinessZone.get()).toString(), amount,
                "mobile_money", (String) c.get("accountId"), (String) c.get("reference"), null, allocations, null, null,
                null, null);
    }

    /** {@code +256772123456} → {@code ********3456}. */
    static String mask(String msisdn) {
        String digits = msisdn.replaceAll("[^0-9]", "");
        int keep = Math.min(4, digits.length());
        return "*".repeat(digits.length() - keep) + digits.substring(digits.length() - keep);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
