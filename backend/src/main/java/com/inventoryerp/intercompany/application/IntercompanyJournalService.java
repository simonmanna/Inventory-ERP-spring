package com.inventoryerp.intercompany.application;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.CurrencyService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.kernel.auth.UserAccounts.UserWithRoles;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.PageQuery;
import com.inventoryerp.shared.web.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Intercompany journals (ADR-S028 §5): one request, two balanced entries — one in the calling company, one in the
 * counterpart — in a single database transaction. The transaction is opened without a tenant and each half runs as
 * its company, so a refusal in either company (closed period, books lock, unknown account) rolls back both.
 */
@Service
public class IntercompanyJournalService {

    /** {@code POST /intercompany/journals} body. */
    public record JournalInput(String targetOrganizationId, String date, String description, BigDecimal amount,
            String sourceAccountId, String targetAccountId, String targetAccountCode) {}

    private final JdbcClient jdbc;
    private final CompanyGroupService groups;
    private final IntercompanyPartnerService partners;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final CurrencyService currencies;
    private final SequenceService sequence;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public IntercompanyJournalService(JdbcClient jdbc, CompanyGroupService groups, IntercompanyPartnerService partners,
            PostingService posting, AccountDeterminationService determination, CurrencyService currencies,
            SequenceService sequence, AuditService audit, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.partners = partners;
        this.posting = posting;
        this.determination = determination;
        this.currencies = currencies;
        this.sequence = sequence;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Map<String, Object> post(JournalInput in) {
        String source = TenantContext.organizationId();
        String userId = TenantContext.userId();
        String target = in.targetOrganizationId();
        groups.requireSameGroup(target);
        UserWithRoles twin = groups.twinUser(target).orElse(null);
        if (twin == null || !twin.permissions().contains(Permissions.INTERCOMPANY__POST)) {
            throw ApiException.forbidden("You need an active account with intercompany:post in the other company");
        }
        if (in.amount() == null || in.amount().signum() <= 0) {
            throw ApiException.badRequest("amount must be a positive number");
        }
        boolean byId = in.targetAccountId() != null && !in.targetAccountId().isBlank();
        boolean byCode = in.targetAccountCode() != null && !in.targetAccountCode().isBlank();
        if (byId == byCode) {
            throw ApiException.badRequest("Give exactly one of targetAccountId or targetAccountCode");
        }
        // The caller cannot list the other company's chart, so its account may be named by code.
        String targetAccount = byId ? in.targetAccountId() : TenantContext.unscoped(() -> tx.execute(s -> jdbc.sql(
                "SELECT id FROM \"Account\" WHERE \"organizationId\" = ? AND code = ? AND \"deletedAt\" IS NULL")
                .params(target, in.targetAccountCode().trim()).query(String.class).optional().orElseThrow(
                        () -> ApiException.notFound("Account " + in.targetAccountCode().trim()
                                + " not found in the other company"))));
        Instant date = LocalDate.parse(in.date()).atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal amount = in.amount().setScale(2, RoundingMode.HALF_UP);
        String sourceCcy = currencyOf(source);
        String targetCcy = TenantContext.unscoped(() -> tx.execute(s -> currencyOf(target)));
        BigDecimal rate = currencies.getRate(sourceCcy, targetCcy, date);
        BigDecimal targetAmount = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
        String id = UUID.randomUUID().toString();
        String description = in.description().trim();
        TenantStore sourceStore = TenantStore.user(source, userId);
        TenantStore targetStore = TenantStore.user(target, twin.id());

        Map<String, Object> result = TenantContext.unscoped(() -> tx.execute(s -> {
            // Partners standing for each other (created on first use).
            String sourcePartner = TenantContext.run(sourceStore, () -> partners.partnerFor(source, target)
                    .orElseGet(() -> (String) partners.link(target, null).get("partnerId")));
            String targetPartner = TenantContext.run(targetStore, () -> partners.partnerFor(target, source)
                    .orElseGet(() -> (String) partners.link(source, null).get("partnerId")));
            String number = TenantContext.run(sourceStore, () -> sequence.next("intercompany_journal", "ICJ-", 5));
            Map<String, Object> sourceEntry = TenantContext.run(sourceStore, () -> {
                String receivable = determination.receivableAccount(partnerAccount(source, sourcePartner,
                        "receivableAccountId"));
                return posting.post(new PostingRequest("GEN", date, number + " " + description, null, null,
                        "intercompany_journal", id, null, "icj:" + id + ":source", null, null, null, List.of(
                                new PostingRequest.Line(receivable, amount, null, sourcePartner, description, null, null,
                                        null),
                                new PostingRequest.Line(in.sourceAccountId(), null, amount, null, description, null,
                                        null, null))));
            });
            Map<String, Object> targetEntry = TenantContext.run(targetStore, () -> {
                String payable = determination.payableAccount(partnerAccount(target, targetPartner,
                        "payableAccountId"));
                return posting.post(new PostingRequest("GEN", date, number + " " + description, null, null,
                        "intercompany_journal", id, null, "icj:" + id + ":target", null, null, null, List.of(
                                new PostingRequest.Line(targetAccount, targetAmount, null, null, description,
                                        null, null, null),
                                new PostingRequest.Line(payable, null, targetAmount, targetPartner, description, null,
                                        null, null))));
            });
            jdbc.sql("INSERT INTO \"IntercompanyJournal\" (id, \"organizationId\", number, \"targetOrganizationId\","
                    + " \"postingDate\", description, amount, \"currencyCode\", \"targetAmount\", \"targetCurrencyCode\","
                    + " \"exchangeRate\", \"sourceAccountId\", \"targetAccountId\", \"sourceEntryId\", \"targetEntryId\","
                    + " \"createdBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(id, source, number, target, Timestamp.from(date), description, amount, sourceCcy,
                            targetAmount, targetCcy, rate, in.sourceAccountId(), targetAccount,
                            sourceEntry.get("id"), targetEntry.get("id"), userId).update();
            return Obj.of("id", id);
        }));
        audit.record(AuditInput.of("IntercompanyJournal", id, "post", null, Obj.of("targetOrganizationId", target,
                "amount", amount.toPlainString())));
        return one((String) result.get("id"));
    }

    private String partnerAccount(String org, String partnerId, String column) {
        return jdbc.sql("SELECT \"" + column + "\" FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(partnerId, org).query(String.class).optional().orElse(null);
    }

    private String currencyOf(String org) {
        return jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org).query(String.class)
                .single();
    }

    /** {@code GET /intercompany/journals}: journals this company sent or received. */
    public PageResult<Map<String, Object>> list(PageQuery q) {
        String org = TenantContext.organizationId();
        return TenantContext.unscoped(() -> tx.execute(s -> {
            long total = jdbc.sql("SELECT count(*) FROM \"IntercompanyJournal\" WHERE \"organizationId\" = ? OR"
                    + " \"targetOrganizationId\" = ?").params(org, org).query(Long.class).single();
            List<Map<String, Object>> rows = new ArrayList<>(SqlRows.list(jdbc.sql(select()
                    + " WHERE j.\"organizationId\" = ? OR j.\"targetOrganizationId\" = ? ORDER BY j.\"postingDate\" DESC,"
                    + " j.\"createdAt\" DESC LIMIT ? OFFSET ?").params(org, org, q.pageSizeOrDefault(), q.offset())));
            rows.forEach(r -> r.put("direction", org.equals(r.get("organizationId")) ? "outgoing" : "incoming"));
            return PageResult.of(rows, q, total);
        }));
    }

    private Map<String, Object> one(String id) {
        String org = TenantContext.organizationId();
        Map<String, Object> r = TenantContext.unscoped(() -> tx.execute(s -> SqlRows.one(jdbc.sql(select()
                + " WHERE j.id = ?").param(id))));
        if (r != null) {
            r.put("direction", org.equals(r.get("organizationId")) ? "outgoing" : "incoming");
            r.put("amount", Money.round(SqlRows.dec(r, "amount")).toPlainString());
        }
        return r;
    }

    private static String select() {
        return "SELECT j.*, so.code AS \"sourceCompanyCode\", so.name AS \"sourceCompanyName\", tg.code AS"
                + " \"targetCompanyCode\", tg.name AS \"targetCompanyName\", se.\"entryNumber\" AS \"sourceEntryNumber\","
                + " te.\"entryNumber\" AS \"targetEntryNumber\" FROM \"IntercompanyJournal\" j JOIN \"Organization\" so"
                + " ON so.id = j.\"organizationId\" JOIN \"Organization\" tg ON tg.id = j.\"targetOrganizationId\""
                + " JOIN \"JournalEntry\" se ON se.id = j.\"sourceEntryId\" JOIN \"JournalEntry\" te ON te.id ="
                + " j.\"targetEntryId\"";
    }
}
