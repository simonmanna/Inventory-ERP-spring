package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.accounting.application.PostingMath.NormalizedLine;
import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.Journal;
import com.inventoryerp.accounting.domain.JournalEntry;
import com.inventoryerp.accounting.domain.JournalEntryStatus;
import com.inventoryerp.accounting.domain.JournalLine;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one writer of JournalEntry/JournalLine (port of {@code posting.service.ts}, ADR-009): validates
 * double-entry rules, enforces period control, numbers atomically per journal/year and joins the caller's
 * transaction so a document's state change and its posting commit together. {@code postingKey} makes a
 * posting idempotent (only a {@code posted} entry is replayed; a replay with other lines is reported, finance audit A6,
 * {@link PostingKeyReplayGuard}); reversal suffixes the original key so the canonical slot can be re-posted. Manual
 * entries go through draft → post (maker-checker).
 *
 * <p>Control-account policy (owner decision, V518): a user's entry (staged draft, approved draft) and what recurring
 * templates / deferral schedules check for themselves ({@link #assertManualPostingAllowed}) never reach a designated
 * AR / AP control account ({@link AccountResolverService#isReceivablePayableControl}); sub-ledger services post there
 * in system context ({@link #post}), and {@link #reverse} mirrors any posted entry, partners included. The one manual
 * way in is a control-account adjustment ({@link #stageDraft(PostingRequest, String)}): a reason, a partner on every
 * control-account line, the {@code journal_entry:control_adjust} permission for maker and checker, a second user to
 * post it, and an audit row on stage, post and reversal.
 *
 * <p>Document reversals (owner decision 2026-11-17, widened by finance readiness audit A2): {@link #reverse} itself has
 * no source-type restriction — it is the GL step of every document- and process-level reversal workflow (invoice cancel,
 * bill void, credit / debit note reversal, payment void, period reopen, payroll-run reversal, …), which call it in
 * their own transaction. The user-facing generic reversal routes check {@link SubledgerSources#assertGenericReversible}
 * first, so only manual entries and allow-listed sources ({@code recurring_entry}) are reversed there; every other entry
 * only by the workflow of the document or process that posted it. {@code reverse} refuses an entry that is no longer
 * {@code posted} (400), so a workflow replay can never mirror an entry twice; the document's status check makes the
 * replay a no-op first.
 */
@Service
public class PostingService {

    private static final BigDecimal ROUNDING_EPSILON = new BigDecimal("0.01");

    private final EntityManager em;
    private final JdbcClient jdbc;
    private final EventBus events;
    private final SequenceService sequence;
    private final FiscalPeriodControl fiscalPeriod;
    private final CurrencyService currency;
    private final AccountResolverService accounts;
    private final CostCenterRulesService costCenters;
    private final AnalyticDimensionService analyticDimensions;
    private final AuditService audit;
    private CostingDimensionGuard costingDimensions;
    private PostingKeyReplayGuard replayGuard;

    public PostingService(EntityManager em, JdbcClient jdbc, EventBus events, SequenceService sequence,
            FiscalPeriodControl fiscalPeriod, CurrencyService currency, AccountResolverService accounts,
            CostCenterRulesService costCenters, AnalyticDimensionService analyticDimensions, AuditService audit) {
        this.audit = audit;
        this.costCenters = costCenters;
        this.analyticDimensions = analyticDimensions;
        this.em = em;
        this.jdbc = jdbc;
        this.events = events;
        this.sequence = sequence;
        this.fiscalPeriod = fiscalPeriod;
        this.currency = currency;
        this.accounts = accounts;
    }

    /** CM-2 (ADR-S032): branch dimension on costing postings; optional so hand-built engines in tests still work. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCostingDimensions(CostingDimensionGuard costingDimensions) {
        this.costingDimensions = costingDimensions;
    }

    /** Finance audit A6: compares a replayed posting key's request with its entry; optional like the costing guard. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setReplayGuard(PostingKeyReplayGuard replayGuard) {
        this.replayGuard = replayGuard;
    }

    /** Post a balanced entry; returns the entry with {@code lines}. */
    @Transactional
    public Map<String, Object> post(PostingRequest request) {
        return post(request, List.of());
    }

    /**
     * As {@link #post(PostingRequest)}, also snapshotting the rate of each foreign leg whose line states its own currency
     * (R-8, ADR-S076: a treasury operation on a foreign account, a cross-currency transfer). One snapshot row per
     * currency pair; the entry's own rate first.
     */
    @Transactional
    public Map<String, Object> post(PostingRequest request, List<CurrencyService.Resolution> legRates) {
        if (request.lines() == null || request.lines().size() < 2) {
            throw ApiException.badRequest("A journal entry requires at least two lines");
        }
        String organizationId = TenantContext.organizationId();
        if (request.postingKey() != null) {
            // ACC-3 (P1-a): concurrent posts of one key queue here until the first commits, then replay its entry
            // instead of hitting the unique index (which aborted the loser's transaction with a 500).
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(? || '|' || ?, 0))")
                    .params(organizationId, request.postingKey()).query().listOfRows();
            JournalEntry existing = em.createQuery("select e from JournalEntry e where e.organizationId = :org"
                            + " and e.postingKey = :key and e.status = :status", JournalEntry.class)
                    .setParameter("org", organizationId).setParameter("key", request.postingKey())
                    .setParameter("status", JournalEntryStatus.POSTED)
                    .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
            if (existing != null) {
                // Finance audit A6 (P2-12): the same key with other lines is a second posting the replay would drop.
                List<JournalLine> stored = lines(existing.getId());
                if (replayGuard != null) {
                    replayGuard.check(existing, request, stored);
                }
                return render(existing, stored);
            }
        }
        Journal journal = journal(request.journalCode());
        ZoneId zone = AccountingDay.zone(jdbc, organizationId);
        Instant date = AccountingDay.normalize(request.date(), zone);
        fiscalPeriod.assertOpen(date);
        CurrencyService.Resolution fx = resolution(request, date);
        BigDecimal rate = fx.rate();
        CheckedLines checked = checkLines(organizationId, date, PostingMath.normalizeLines(request.lines(), rate),
                request.currencyId(), request.costCenterId(), request.branchId(), request.dimensions(),
                request.sourceType(), true, false, true, true);
        List<NormalizedLine> lines = checked.lines();
        boolean centersResolved = checked.centersResolved();

        int year = AccountingDay.year(date, zone);
        String entryNumber = sequence.next("journal:" + journal.getCode() + ":" + year,
                journal.getCode() + "/" + year + "/", 5);
        Map<String, String> entryDims = normalizeDimensions(request.dimensions());
        JournalEntry entry = new JournalEntry();
        entry.setOrganizationId(organizationId);
        entry.setJournalId(journal.getId());
        entry.setEntryNumber(entryNumber);
        entry.setPostingDate(date);
        entry.setDescription(request.description());
        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setCurrencyId(request.currencyId());
        entry.setSourceType(request.sourceType());
        entry.setSourceId(request.sourceId());
        entry.setPostingType(request.postingType() == null ? "primary" : request.postingType());
        entry.setPostingKey(request.postingKey());
        entry.setBranchId(request.branchId());
        entry.setCostCenterId(request.costCenterId());
        entry.setDimensions(entryDims == null ? null : EntityJson.toJson(entryDims));
        entry.setPostedAt(Instant.now());
        entry.setPostedBy(TenantContext.userId());
        em.persist(entry);
        List<JournalLine> rows = persistLines(entry, lines, request, rate, entryDims, centersResolved);
        em.flush();
        em.refresh(entry);
        String entryId = entry.getId();
        snapshotRate(organizationId, entryId, fx);
        Set<String> pairs = new LinkedHashSet<>();
        pairs.add(fx.fromCode() + ">" + fx.toCode());
        for (CurrencyService.Resolution leg : legRates == null ? List.<CurrencyService.Resolution>of() : legRates) {
            if (leg != null && !leg.fromCode().equals(leg.toCode()) && pairs.add(leg.fromCode() + ">" + leg.toCode())) {
                snapshotRate(organizationId, entryId, leg);
            }
        }
        AfterCommit.run(() -> {
            Map<String, Object> payload = new HashMap<>();
            payload.put("organizationId", organizationId);
            payload.put("journalEntryId", entryId);
            payload.put("entryNumber", entryNumber);
            payload.put("sourceType", request.sourceType());
            payload.put("sourceId", request.sourceId());
            events.publish("journal.posted", payload);
        });
        return render(entry, rows);
    }

    /** Reverse a posted entry with a mirrored entry; flips the original to {@code reversed}. */
    @Transactional
    public Map<String, Object> reverse(String journalEntryId, Instant date, String description) {
        String organizationId = TenantContext.organizationId();
        // row lock: two concurrent reversals must not both mirror the entry (G-7)
        JournalEntry original = em.createQuery("select e from JournalEntry e where e.id = :id and e.organizationId = :org",
                        JournalEntry.class)
                .setParameter("id", journalEntryId).setParameter("org", organizationId)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst().orElse(null);
        if (original == null) {
            throw ApiException.notFound("Journal entry not found");
        }
        if (original.getStatus() != JournalEntryStatus.POSTED) {
            throw ApiException.badRequest("Only posted entries can be reversed");
        }
        ZoneId zone = AccountingDay.zone(jdbc, organizationId);
        Set<String> originalAccounts = new LinkedHashSet<>();
        lines(original.getId()).forEach(l -> originalAccounts.add(l.getAccountId()));
        // a settlement entry's reversal is exempt, as the settlement itself is
        String lockSource = "tax_settlement".equals(original.getSourceType()) ? "tax_settlement" : "reversal";
        Instant when = date != null ? AccountingDay.normalize(date, zone) : reversalDate(original.getPostingDate());
        if (date != null && AccountingDay.of(when, zone).isBefore(AccountingDay.of(original.getPostingDate(), zone))) {
            // B5: a reversal dated before its original would undo it in a period where it did not yet exist
            throw ApiException.badRequest("A reversal cannot be dated before the entry it reverses ("
                    + original.getEntryNumber() + " is dated " + AccountingDay.of(original.getPostingDate(), zone) + ")");
        }
        if (date == null && (taxLockedThrough(organizationId, when, originalAccounts, lockSource) != null
                || bankReconciledThrough(organizationId, when, originalAccounts) != null)) {
            // the original's date is inside a settled VAT period or a reconciled bank statement: reverse today, never
            // into filed VAT (G-2) or a locked reconciliation (B5)
            when = Instant.now();
        }
        fiscalPeriod.assertOpen(when);
        assertTaxPeriodOpenFor(organizationId, when, originalAccounts, lockSource);
        Journal journal = em.find(Journal.class, original.getJournalId());
        int year = AccountingDay.year(when, zone);
        String entryNumber = sequence.next("journal:" + journal.getCode() + ":" + year,
                journal.getCode() + "/" + year + "/", 5);

        JournalEntry reversal = new JournalEntry();
        reversal.setOrganizationId(organizationId);
        reversal.setJournalId(original.getJournalId());
        reversal.setEntryNumber(entryNumber);
        reversal.setPostingDate(when);
        reversal.setDescription(description != null ? description : "Reversal of " + original.getEntryNumber());
        reversal.setStatus(JournalEntryStatus.POSTED);
        reversal.setCurrencyId(original.getCurrencyId());
        reversal.setSourceType("reversal");
        reversal.setSourceId(original.getId());
        reversal.setPostingType("reversal");
        reversal.setReversalOfId(original.getId());
        reversal.setBranchId(original.getBranchId());
        reversal.setCostCenterId(original.getCostCenterId());
        reversal.setDimensions(original.getDimensions());
        reversal.setPostedAt(Instant.now());
        reversal.setPostedBy(TenantContext.userId());
        em.persist(reversal);
        List<JournalLine> rows = new ArrayList<>();
        int i = 1;
        for (JournalLine l : lines(original.getId())) {
            JournalLine r = new JournalLine();
            r.setOrganizationId(organizationId);
            r.setJournalEntryId(reversal.getId());
            r.setAccountId(l.getAccountId());
            r.setPartnerId(l.getPartnerId());
            r.setDescription("Reversal: " + (l.getDescription() == null ? "" : l.getDescription()));
            r.setDebit(l.getCredit());
            r.setCredit(l.getDebit());
            r.setCurrencyId(l.getCurrencyId());
            r.setExchangeRate(l.getExchangeRate());
            r.setBaseDebit(l.getBaseCredit());
            r.setBaseCredit(l.getBaseDebit());
            r.setBranchId(l.getBranchId());
            r.setCostCenterId(l.getCostCenterId());
            r.setDimensions(l.getDimensions());
            r.setLineNumber(i++);
            em.persist(r);
            rows.add(r);
        }
        em.flush();
        original.setStatus(JournalEntryStatus.REVERSED);
        original.setReversedEntryId(reversal.getId());
        if (original.getPostingKey() != null) {
            original.setPostingKey(original.getPostingKey() + ":rev:" + reversal.getId());
        }
        em.flush();
        em.refresh(reversal);
        String originalId = original.getId();
        String reversalId = reversal.getId();
        if (Boolean.TRUE.equals(original.getControlAdjustment())) {
            // Allowed like any reversal (the mirror keeps every partner, so the sub-ledger stays reconciled); recorded
            // because it undoes an approved control-account adjustment.
            Map<String, Object> trail = adjustmentTrail(reversal, rows);
            trail.put("reversalOfId", originalId);
            trail.put("reversedAdjustmentReason", original.getControlAdjustmentReason());
            audit.recordInTx(AuditInput.of("JournalEntry", reversalId, "post", null, trail));
        }
        AfterCommit.run(() -> events.publish("journal.reversed", Map.of("organizationId", organizationId,
                "journalEntryId", originalId, "reversalEntryId", reversalId)));
        return render(reversal, rows);
    }

    /**
     * Reversal-date policy when the caller states no date (ACC-3, B6; owner decision D-B6): the original's own date while
     * its period is still open, so the period nets to zero; otherwise today (the first permissible open date — a closed
     * or locked period is never posted into). Both entries keep their own dates and the permanent link.
     */
    public Instant reversalDate(Instant originalDate) {
        return openDateOrToday(originalDate);
    }

    /** {@code date} when it may still be posted to (books lock, open period), else today. */
    public Instant openDateOrToday(Instant date) {
        return date != null && fiscalPeriod.isOpen(date) ? date : Instant.now();
    }

    /**
     * ACC-7 tax-period lock: once a VAT period is settled ({@code TaxSettlement}), nothing more may post to a tax account
     * (output / input / withholding mappings and every tax's own account) on or before the settled period end. The
     * settlement entry itself is exempt.
     */
    private void assertTaxPeriodOpen(String org, Instant date, List<NormalizedLine> lines, String sourceType) {
        assertTaxPeriodOpenFor(org, date, lines.stream().map(NormalizedLine::accountId).toList(), sourceType);
    }

    /**
     * As {@link #assertTaxPeriodOpen} for account ids. Every path into the books checks it: {@link #post},
     * {@link #stageDraft} / {@link #postDraft} and {@link #reverse} (review 2026-11-15, G-2).
     */
    private void assertTaxPeriodOpenFor(String org, Instant date, java.util.Collection<String> accountIds,
            String sourceType) {
        Instant settled = taxLockedThrough(org, date, accountIds, sourceType);
        if (settled != null) {
            ZoneId zone = AccountingDay.zone(jdbc, org);
            throw ApiException.badRequest("VAT is settled through " + FiscalPeriodControl.day(settled)
                    + "; tax cannot be posted on " + FiscalPeriodControl.day(date, zone));
        }
        // an FX revaluation moves only the base value of a foreign bank account, not what the bank statement shows
        String[] bank = "fx_revaluation".equals(sourceType) ? null : bankReconciledThrough(org, date, accountIds);
        if (bank != null) {
            throw ApiException.badRequest("Bank account " + bank[0] + " is reconciled through " + bank[1]
                    + "; nothing can be posted to it on " + AccountingDay.of(date, AccountingDay.zone(jdbc, org))
                    + ". Date it after " + bank[1] + ".");
        }
    }

    /**
     * Finance readiness audit B5: a locked bank reconciliation ({@code BankReconciliationStatement}) freezes its bank
     * account's ledger through the statement day, as it freezes the statement lines. Returns the account code and that
     * day when posting {@code accountIds} on {@code date} would land on or before it, else {@code null}.
     */
    private String[] bankReconciledThrough(String org, Instant date, java.util.Collection<String> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return null;
        }
        java.time.LocalDate day = AccountingDay.of(date, AccountingDay.zone(jdbc, org));
        return jdbc.sql("SELECT a.code, to_char(max(s.\"statementDate\"), 'YYYY-MM-DD') AS through"
                        + " FROM \"BankReconciliationStatement\" s JOIN \"Account\" a ON a.id = s.\"bankAccountId\""
                        + " WHERE s.\"organizationId\" = ? AND s.\"bankAccountId\" = ANY(?::text[]) GROUP BY a.code"
                        + " HAVING max(s.\"statementDate\")::date >= ?::date ORDER BY a.code LIMIT 1")
                .params(org, new java.util.LinkedHashSet<>(accountIds).toArray(String[]::new), java.sql.Date.valueOf(day))
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).optional().orElse(null);
    }

    /** The settled VAT period end when posting {@code accountIds} on {@code date} would hit a tax account in it. */
    private Instant taxLockedThrough(String org, Instant date, java.util.Collection<String> accountIds,
            String sourceType) {
        if ("tax_settlement".equals(sourceType)) {
            return null;
        }
        // a reversed settlement locks nothing (finance audit A3)
        Instant settled = jdbc.sql("SELECT max(\"periodTo\") FROM \"TaxSettlement\" WHERE \"organizationId\" = ?"
                        + " AND \"reversedAt\" IS NULL")
                .param(org).query((rs, n) -> rs.getTimestamp(1) == null ? null
                        : rs.getTimestamp(1).toLocalDateTime().toInstant(ZoneOffset.UTC))
                .optional().orElse(null);
        ZoneId zone = AccountingDay.zone(jdbc, org);
        if (settled == null || date.isAfter(AccountingDay.fromWallClock(settled, zone))) {
            return null;
        }
        java.util.Set<String> taxAccounts = new java.util.HashSet<>(jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\""
                        + " WHERE \"organizationId\" = ? AND key IN ('tax_payable', 'tax_receivable', 'withholding_payable',"
                        + " 'withholding_receivable')"
                        + " AND \"deletedAt\" IS NULL UNION SELECT \"accountId\" FROM \"Tax\" WHERE \"organizationId\" = ?"
                        + " AND \"accountId\" IS NOT NULL").params(org, org).query(String.class).list());
        for (String id : accountIds) {
            if (taxAccounts.contains(id)) {
                return settled;
            }
        }
        return null;
    }

    /** Stage a manual entry as a DRAFT (validated + balanced, not in the books). */
    @Transactional
    public Map<String, Object> stageDraft(PostingRequest request) {
        return stageDraft(request, null);
    }

    /**
     * As {@link #stageDraft(PostingRequest)}; a non-null {@code controlAdjustmentReason} stages a control-account
     * adjustment: the maker holds {@code journal_entry:control_adjust}, the entry is a plain manual one (no source, so
     * never a recurring or deferral posting), at least one line is on a designated AR / AP control account, every such
     * line names a partner of this organization, and an audit row records the reason, accounts, partners and amounts.
     */
    @Transactional
    public Map<String, Object> stageDraft(PostingRequest request, String controlAdjustmentReason) {
        return stageDraft(request, controlAdjustmentReason, null);
    }

    /**
     * As {@link #stageDraft(PostingRequest, String)}, with the draft's maker: {@code makerId} (else the current user)
     * is the creator who may not approve it. A {@code postingKey} is kept on the draft, and a second stage of the same
     * key returns the draft (or entry) already there: finance audit B5 moved this out of the recurring service's raw
     * SQL.
     */
    @Transactional
    public Map<String, Object> stageDraft(PostingRequest request, String controlAdjustmentReason, String makerId) {
        if (request.postingKey() != null) {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(? || '|' || ?, 0))")
                    .params(TenantContext.organizationId(), request.postingKey()).query().listOfRows();
            JournalEntry existing = em.createQuery("select e from JournalEntry e where e.organizationId = :org"
                            + " and e.postingKey = :key", JournalEntry.class)
                    .setParameter("org", TenantContext.organizationId()).setParameter("key", request.postingKey())
                    .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
            if (existing != null) {
                return render(existing, lines(existing.getId()));
            }
        }
        if (request.lines() == null || request.lines().size() < 2) {
            throw ApiException.badRequest("A journal entry requires at least two lines");
        }
        boolean adjustment = controlAdjustmentReason != null;
        if (adjustment) {
            if (controlAdjustmentReason.isBlank()) {
                throw ApiException.badRequest("A control-account adjustment requires a reason");
            }
            if (request.sourceType() != null || request.sourceId() != null || request.postingKey() != null) {
                throw ApiException.badRequest("Only a manual journal entry can be a control-account adjustment");
            }
            assertControlAdjustPermission();
        }
        String organizationId = TenantContext.organizationId();
        Journal journal = journal(request.journalCode());
        Instant date = AccountingDay.normalize(request.date(), AccountingDay.zone(jdbc, organizationId));
        fiscalPeriod.assertOpen(date);
        BigDecimal rate = resolution(request, date).rate();
        CheckedLines checked = checkLines(organizationId, date, PostingMath.normalizeLines(request.lines(), rate),
                request.currencyId(), request.costCenterId(), request.branchId(), request.dimensions(),
                request.sourceType(), false, adjustment, true, false);
        List<NormalizedLine> lines = checked.lines();
        boolean centersResolved = checked.centersResolved();
        Map<String, String> entryDims = normalizeDimensions(request.dimensions());
        JournalEntry entry = new JournalEntry();
        entry.setOrganizationId(organizationId);
        entry.setJournalId(journal.getId());
        entry.setEntryNumber("DRAFT-" + UUID.randomUUID());
        entry.setPostingDate(date);
        entry.setDescription(request.description());
        entry.setStatus(JournalEntryStatus.DRAFT);
        entry.setCurrencyId(request.currencyId());
        entry.setSourceType(request.sourceType());
        entry.setSourceId(request.sourceId());
        entry.setBranchId(request.branchId());
        entry.setCostCenterId(request.costCenterId());
        entry.setDimensions(entryDims == null ? null : EntityJson.toJson(entryDims));
        entry.setCreatedBy(makerId != null ? makerId : TenantContext.userId());
        entry.setPostingKey(request.postingKey());
        if (adjustment) {
            entry.setControlAdjustment(true);
            entry.setControlAdjustmentReason(controlAdjustmentReason.strip());
        }
        em.persist(entry);
        List<JournalLine> rows = persistLines(entry, lines, request, rate, entryDims, centersResolved);
        em.flush();
        em.refresh(entry);
        if (adjustment) {
            audit.recordInTx(AuditInput.of("JournalEntry", entry.getId(), "create", null, adjustmentTrail(entry, rows)));
        }
        return render(entry, rows);
    }

    /** Approve + post a DRAFT (approver must differ from its creator). */
    @Transactional
    public Map<String, Object> postDraft(String entryId) {
        String organizationId = TenantContext.organizationId();
        // row lock: two concurrent approvals must not both number and post the draft (G-7)
        JournalEntry draft = em.createQuery("select e from JournalEntry e where e.id = :id and e.organizationId = :org",
                        JournalEntry.class)
                .setParameter("id", entryId).setParameter("org", organizationId)
                .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream().findFirst().orElse(null);
        if (draft == null) {
            throw ApiException.notFound("Journal entry not found");
        }
        if (draft.getStatus() != JournalEntryStatus.DRAFT) {
            throw ApiException.badRequest("Only draft entries can be posted");
        }
        String userId = TenantContext.userId();
        if (draft.getCreatedBy() != null && userId != null && draft.getCreatedBy().equals(userId)) {
            throw ApiException.forbidden("The user who created a journal entry cannot post it (maker-checker).");
        }
        boolean adjustment = Boolean.TRUE.equals(draft.getControlAdjustment());
        if (adjustment) {
            assertControlAdjustPermission();
            if (draft.getCreatedBy() == null || userId == null) {
                throw ApiException.forbidden("A control-account adjustment must be posted by a known user other than"
                        + " its maker (maker-checker).");
            }
        }
        fiscalPeriod.assertOpen(draft.getPostingDate());
        assertDraftLines(organizationId, draft, adjustment);
        Journal journal = em.find(Journal.class, draft.getJournalId());
        int year = AccountingDay.year(draft.getPostingDate(), AccountingDay.zone(jdbc, organizationId));
        String entryNumber = sequence.next("journal:" + journal.getCode() + ":" + year,
                journal.getCode() + "/" + year + "/", 5);
        draft.setEntryNumber(entryNumber);
        draft.setStatus(JournalEntryStatus.POSTED);
        draft.setPostedAt(Instant.now());
        draft.setPostedBy(userId);
        em.flush();
        if (adjustment) {
            audit.recordInTx(AuditInput.of("JournalEntry", draft.getId(), "post", null,
                    adjustmentTrail(draft, lines(draft.getId()))));
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("journalEntryId", draft.getId());
        payload.put("entryNumber", entryNumber);
        payload.put("sourceType", draft.getSourceType());
        payload.put("sourceId", draft.getSourceId());
        AfterCommit.run(() -> events.publish("journal.posted", payload));
        return render(draft);
    }

    /**
     * R8: a draft's lines can change after it was staged (accounts deactivated, VAT settled, cost centres or dimension
     * values retired, rows edited in the database), so approving it re-runs on the stored lines the checks
     * {@link #post} runs ({@link #checkLines}): one side per line, accounts postable for a user entry, VAT period
     * open, balanced in transaction and base currency (exactly in base: staging already added the rounding line),
     * foreign cash denominations, cost-centre rules, analytic dimensions and the costing guard. A cost centre the
     * rules now default onto a line without one is written to the line, as {@link #post} would.
     */
    private void assertDraftLines(String organizationId, JournalEntry draft, boolean controlAdjustment) {
        List<JournalLine> rows = lines(draft.getId());
        if (rows.size() < 2) {
            throw ApiException.badRequest("A journal entry requires at least two lines");
        }
        Map<String, Object> entryDims = storedDimensions(draft.getDimensions());
        List<NormalizedLine> stored = new ArrayList<>(rows.size());
        for (JournalLine row : rows) {
            stored.add(normalized(row, draft.getCurrencyId(), entryDims));
        }
        // Stored line centres are already effective (the header's was inherited at staging), so no header centre.
        CheckedLines checked = checkLines(organizationId, draft.getPostingDate(), stored, draft.getCurrencyId(), null,
                draft.getBranchId(), entryDims, draft.getSourceType(), false, controlAdjustment, false, true);
        if (checked.lines().size() != rows.size()) {
            throw ApiException.badRequest("The draft's lines no longer match its cost center split rules; discard it"
                    + " and enter it again");
        }
        boolean changed = false;
        for (int i = 0; i < rows.size(); i++) {
            String costCenterId = checked.lines().get(i).costCenterId();
            if (!java.util.Objects.equals(costCenterId, rows.get(i).getCostCenterId())) {
                rows.get(i).setCostCenterId(costCenterId);
                changed = true;
            }
        }
        if (changed) {
            // Write the line updates while the entry is still a draft: once its status flips to posted, the
            // journal_line_immutable_after_post trigger refuses any line UPDATE, and Hibernate's ordered flush would
            // otherwise send the entry's UPDATE first.
            em.flush();
        }
    }

    /**
     * A stored line as {@link #checkLines} sees it: the entry's currency (no override) when the row carries it, the
     * row's own currency when it differs, and {@link PostingMath#BASE_ONLY} for a base-only row (the rounding line)
     * of a foreign-currency entry. Dimensions equal to the entry's were inherited at staging, so they count as the
     * entry's, not the line's own.
     */
    private static NormalizedLine normalized(JournalLine row, String entryCurrency, Map<String, Object> entryDims) {
        BigDecimal debit = row.getDebit() == null ? BigDecimal.ZERO : row.getDebit();
        BigDecimal credit = row.getCredit() == null ? BigDecimal.ZERO : row.getCredit();
        BigDecimal baseDebit = row.getBaseDebit() == null ? BigDecimal.ZERO : row.getBaseDebit();
        BigDecimal baseCredit = row.getBaseCredit() == null ? BigDecimal.ZERO : row.getBaseCredit();
        String currency = row.getCurrencyId();
        String own = currency == null ? (entryCurrency == null ? null : PostingMath.BASE_ONLY)
                : currency.equals(entryCurrency) ? null : currency;
        BigDecimal foreign = own == null || own.isEmpty() ? null : debit.signum() > 0 ? debit : credit;
        Map<String, Object> dims = storedDimensions(row.getDimensions());
        if (dims != null && dims.equals(entryDims)) {
            dims = null;
        }
        return new NormalizedLine(row.getAccountId(), row.getPartnerId(), row.getDescription(), row.getBranchId(),
                row.getCostCenterId(), dims, debit, credit, baseDebit, baseCredit, own, foreign);
    }

    /** A stored (flat, string-valued) dimensions column as a map; {@code null} when empty. */
    private static Map<String, Object> storedDimensions(String json) {
        tools.jackson.databind.JsonNode node = EntityJson.tree(json);
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        node.properties().forEach(e -> {
            if (!e.getValue().isNull()) {
                out.put(e.getKey(), e.getValue().asString());
            }
        });
        return out.isEmpty() ? null : out;
    }

    /** Discard a DRAFT entry and its lines; posted/reversed entries are immutable. */
    @Transactional
    public void discardDraft(String entryId) {
        JournalEntry draft = em.createQuery("select e from JournalEntry e where e.id = :id and e.organizationId = :org",
                        JournalEntry.class)
                .setParameter("id", entryId).setParameter("org", TenantContext.organizationId())
                .getResultList().stream().findFirst().orElse(null);
        if (draft == null) {
            throw ApiException.notFound("Journal entry not found");
        }
        if (draft.getStatus() != JournalEntryStatus.DRAFT) {
            throw ApiException.badRequest("Only draft entries can be discarded");
        }
        em.createQuery("delete from JournalLine l where l.journalEntryId = :id").setParameter("id", entryId).executeUpdate();
        em.createQuery("delete from JournalEntry e where e.id = :id").setParameter("id", entryId).executeUpdate();
    }

    // ------------------------------------------------------------------ rendering

    /** Entry row with its {@code lines} (Prisma {@code include: {lines: true}}). */
    public Map<String, Object> render(JournalEntry entry) {
        return render(entry, lines(entry.getId()));
    }

    private Map<String, Object> render(JournalEntry entry, List<JournalLine> lines) {
        Map<String, Object> out = EntityJson.of(entry);
        out.put("lines", EntityJson.list(lines));
        return out;
    }

    private List<JournalLine> lines(String entryId) {
        return em.createQuery("select l from JournalLine l where l.journalEntryId = :id order by l.lineNumber asc",
                JournalLine.class).setParameter("id", entryId).getResultList();
    }

    // ------------------------------------------------------------------ internals

    private Journal journal(String code) {
        Journal journal = em.createQuery("select j from Journal j where j.organizationId = :org and j.code = :code",
                        Journal.class)
                .setParameter("org", TenantContext.organizationId()).setParameter("code", code)
                .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
        if (journal == null) {
            throw ApiException.badRequest("Journal '" + code + "' not found");
        }
        return journal;
    }

    /** The organisation's base (functional) currency code. */
    public String baseCurrency() {
        String orgId = TenantContext.organizationId();
        return Optional.ofNullable(ReadMemo.get(() -> jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\""
                + " WHERE id = ?").param(orgId).query(String.class).optional().orElse(null), "org.currencyCode", orgId))
                // FX-2 A: no silent "USD" — a company posts only once its functional currency is set (V584 NOT NULL).
                .orElseThrow(() -> ApiException.badRequest("Set the company's functional currency before posting"));
    }

    /**
     * Rate of a posting, resolved before anything is written (owner decision D-PC1, ADR-S054): base currency keeps the
     * caller's rate or 1 (unchanged); a foreign currency takes the explicit document rate, else the rate table, else
     * the posting is refused and nothing is written.
     */
    private CurrencyService.Resolution resolution(PostingRequest request, Instant date) {
        String baseCode = baseCurrency();
        if (request.currencyId() == null || request.currencyId().equals(baseCode)) {
            return new CurrencyService.Resolution(baseCode, baseCode,
                    Money.dec(request.exchangeRate() == null ? 1 : request.exchangeRate()), "base", date);
        }
        return currency.resolve(request.currencyId(), baseCode, request.exchangeRate(), date,
                fxContext(request.sourceType()));
    }

    /**
     * FX-2 B: the rate-policy context of a posting, from its source. Sources a module resolves itself (payments,
     * treasury moves, deposits) carry their context on the leg rates they pass; unknown sources use the defaults.
     */
    static String fxContext(String sourceType) {
        if (sourceType == null) {
            return null;
        }
        String s = sourceType.toLowerCase();
        if (s.startsWith("vendor_bill") || s.startsWith("debit_note") || s.startsWith("purchase")
                || s.startsWith("landed_cost") || s.startsWith("supplier_claim")) {
            return "purchase";
        }
        if (s.equals("invoice") || s.startsWith("sales_invoice") || s.startsWith("credit_note") || s.startsWith("pos_")
                || s.startsWith("cash_sale")) {
            return "sales";
        }
        if (s.startsWith("goods_receipt") || s.startsWith("receipt_voucher") || s.startsWith("direct_stock_in")) {
            return "goods_receipt";
        }
        if (s.startsWith("expense")) {
            return "expense";
        }
        if (s.startsWith("customer_deposit") || s.startsWith("deposit")) {
            return "deposit";
        }
        if (s.startsWith("cash_flow") || s.startsWith("treasury") || s.startsWith("bank_")) {
            return "treasury";
        }
        if (s.equals("fx_revaluation")) {
            return "revaluation";
        }
        if (s.startsWith("opening_balance")) {
            return "opening_balance";
        }
        return null;
    }

    /** Resolved lines of an entry about to be written, and whether their cost centres are final. */
    private record CheckedLines(List<NormalizedLine> lines, boolean centersResolved) {}

    /**
     * The line validation every write into the books shares ({@link #post}, {@link #stageDraft}, {@link #postDraft};
     * R8): one side per line, accounts postable ({@code systemContext} lets engine postings into control accounts;
     * outside it {@code controlAdjustment} lets a flagged adjustment into designated AR / AP control accounts, each such
     * line with a partner of this organization),
     * VAT period open, balanced in transaction and base currency (a base residual of at most 0.01 goes to the rounding
     * account when {@code allowRounding}, else any base residual is refused), foreign cash denominations, cost-centre
     * rules and analytic dimensions; the costing guard when {@code costingGuard}. Returns the lines to persist.
     */
    private CheckedLines checkLines(String organizationId, Instant date, List<NormalizedLine> normalized,
            String currencyId, String costCenterId, String branchId, Map<String, Object> dimensions,
            String sourceType, boolean systemContext, boolean controlAdjustment, boolean allowRounding,
            boolean costingGuard) {
        List<NormalizedLine> lines = new ArrayList<>(normalized);
        List<PostingMath.LineValidationError> errors = PostingMath.validateLines(lines);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(errors.getFirst().message());
        }
        Map<String, AccountMeta> metas = assertAccounts(lines, systemContext, controlAdjustment);
        if (controlAdjustment) {
            assertControlAdjustmentLines(organizationId, lines, metas);
        }
        assertTaxPeriodOpen(organizationId, date, lines, sourceType);
        applyRounding(lines, currencyId, allowRounding);
        assertDenominations(organizationId, lines, currencyId, sourceType);
        List<NormalizedLine> ruled = costCenters.apply(lines, costCenterId, branchId, date);
        boolean centersResolved = ruled != lines;
        analyticDimensions.validate(ruled, dimensions, date, sourceType);
        if (costingGuard && costingDimensions != null) {
            costingDimensions.check(ruled, branchId, sourceType, date);
        }
        return new CheckedLines(ruled, centersResolved);
    }

    /**
     * The postability rule a user's own entry meets ({@link AccountResolverService#assertPostable} outside system
     * context): every account exists in this organization, is postable, active, not deprecated, allows manual
     * posting and is not a designated AR / AP control account (never with the adjustment exception: an automation
     * cannot be a control-account adjustment). Automations a user sets up (recurring templates, deferral schedules)
     * post through {@link #post} in system context, so they check it themselves when saved, approved and before each
     * run (R3).
     */
    public void assertManualPostingAllowed(java.util.Collection<String> accountIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : accountIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (!ids.isEmpty()) {
            assertAccountIds(ids, false, false);
        }
    }

    /**
     * Control-account policy (V518) for a posting a user directs without a sub-ledger document (a treasury deposit /
     * withdrawal counterpart, a bank rule's counter account): none of {@code accountIds} may be a designated AR / AP
     * control account. Unlike {@link #assertManualPostingAllowed} it checks nothing else, so those flows keep their
     * other rules unchanged.
     */
    public void assertNotReceivablePayableControl(java.util.Collection<String> accountIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : accountIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        for (Account a : em.createQuery("select a from Account a where a.organizationId = :org and a.id in :ids",
                        Account.class)
                .setParameter("org", TenantContext.organizationId()).setParameter("ids", ids).getResultList()) {
            AccountMeta meta = AccountResolverService.toMeta(a, null);
            if (AccountResolverService.isReceivablePayableControl(meta)) {
                throw ApiException.badRequest(AccountResolverService.controlAccountRefusal(meta));
            }
        }
    }

    /** Lines on a designated foreign cash account must be in its currency (R-8, {@link ForeignCash}). */
    private void assertDenominations(String organizationId, List<NormalizedLine> lines, String currencyId,
            String sourceType) {
        String entryCurrency = currencyId == null || currencyId.isBlank()
                || currencyId.equalsIgnoreCase(baseCurrency()) ? null : currencyId;
        List<String[]> perLine = new ArrayList<>();
        for (NormalizedLine l : lines) {
            String c = PostingMath.BASE_ONLY.equals(l.currencyId()) ? null
                    : l.overridesCurrency() ? l.currencyId() : entryCurrency;
            perLine.add(new String[] {l.accountId(), c, l.partnerId()});
        }
        ForeignCash.assertDenominated(jdbc, organizationId, perLine, sourceType);
        ForeignCash.assertForeignLines(jdbc, organizationId, perLine, sourceType);
    }

    /** Snapshot of the rate a foreign-currency entry was posted at: pair, rate, date and source. */
    private void snapshotRate(String organizationId, String entryId, CurrencyService.Resolution fx) {
        if ("base".equals(fx.source())) {
            return;
        }
        jdbc.sql("INSERT INTO \"JournalEntryFxRate\" (id, \"organizationId\", \"journalEntryId\", \"fromCode\","
                        + " \"toCode\", rate, \"rateDate\", source, \"rateType\", context, \"policyRate\","
                        + " \"overrideReason\", \"approvedBy\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID().toString(), organizationId, entryId, fx.fromCode(), fx.toCode(), fx.rate(),
                        FiscalPeriodControl.ts(fx.rateDate()), fx.source(), fx.rateType(), fx.context(),
                        fx.policyRate(), fx.overrideReason(), fx.overrideReason() == null ? null : TenantContext.userId())
                .update();
    }

    private Map<String, AccountMeta> assertAccounts(List<NormalizedLine> lines, boolean systemContext,
            boolean controlAdjustment) {
        Set<String> ids = new LinkedHashSet<>();
        lines.forEach(l -> ids.add(l.accountId()));
        return assertAccountIds(ids, systemContext, controlAdjustment);
    }

    /** Every account exists in this organization and passes {@link AccountResolverService#assertPostable}; by id. */
    private Map<String, AccountMeta> assertAccountIds(Set<String> ids, boolean systemContext,
            boolean controlAdjustment) {
        List<Account> found = em.createQuery("select a from Account a left join fetch a.category"
                        + " where a.organizationId = :org and a.id in :ids", Account.class)
                .setParameter("org", TenantContext.organizationId()).setParameter("ids", ids).getResultList();
        if (found.size() != ids.size()) {
            throw ApiException.badRequest("One or more accounts do not exist in this organization");
        }
        Map<String, AccountMeta> out = new HashMap<>();
        for (Account a : found) {
            AccountMeta meta = AccountResolverService.toMeta(a, a.getCategory());
            AccountResolverService.assertPostable(meta, systemContext, controlAdjustment);
            out.put(meta.id(), meta);
        }
        return out;
    }

    /**
     * A control-account adjustment touches at least one designated AR / AP control account, and every line on one
     * names a live partner of this organization, so the customer / supplier sub-ledger (statements, ageing, the
     * per-partner control reconciliation) carries the same amount as the general ledger.
     */
    private void assertControlAdjustmentLines(String organizationId, List<NormalizedLine> lines,
            Map<String, AccountMeta> metas) {
        Set<String> partners = new LinkedHashSet<>();
        boolean any = false;
        for (NormalizedLine l : lines) {
            AccountMeta meta = metas.get(l.accountId());
            if (meta == null || !AccountResolverService.isReceivablePayableControl(meta)) {
                continue;
            }
            any = true;
            if (l.partnerId() == null || l.partnerId().isBlank()) {
                throw ApiException.badRequest("Account " + meta.code() + " " + meta.name() + " is a designated "
                        + ("ar".equals(meta.controlAccountType()) ? "receivable" : "payable") + " control account:"
                        + " every control-account line of an adjustment needs a partner (partnerId), so the "
                        + ("ar".equals(meta.controlAccountType()) ? "customer" : "supplier")
                        + " sub-ledger stays reconciled with the general ledger");
            }
            partners.add(l.partnerId());
        }
        if (!any) {
            throw ApiException.badRequest("A control-account adjustment must post to at least one designated accounts"
                    + " receivable / payable control account; post an ordinary journal entry instead");
        }
        long found = jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE \"organizationId\" = ? AND id = ANY(?::text[])"
                + " AND \"deletedAt\" IS NULL").params(organizationId, partners.toArray(String[]::new))
                .query(Long.class).single();
        if (found != partners.size()) {
            throw ApiException.badRequest("One or more partners of the control-account lines do not exist in this"
                    + " organization");
        }
    }

    /** Maker and checker of a control-account adjustment both hold {@code journal_entry:control_adjust}. */
    private static void assertControlAdjustPermission() {
        if (!TenantContext.has(Permissions.JOURNAL_ENTRY__CONTROL_ADJUST)) {
            throw ApiException.forbidden("Missing required permission(s): " + Permissions.JOURNAL_ENTRY__CONTROL_ADJUST);
        }
    }

    /**
     * Audit payload of a control-account adjustment (or of its reversal): entry, reason, maker / checker, totals and
     * every line with its account, control designation, partner and amounts.
     */
    private Map<String, Object> adjustmentTrail(JournalEntry entry, List<JournalLine> rows) {
        Set<String> ids = new LinkedHashSet<>();
        rows.forEach(r -> ids.add(r.getAccountId()));
        Map<String, Account> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            em.createQuery("select a from Account a where a.organizationId = :org and a.id in :ids", Account.class)
                    .setParameter("org", entry.getOrganizationId()).setParameter("ids", ids).getResultList()
                    .forEach(a -> byId.put(a.getId(), a));
        }
        List<Map<String, Object>> lineTrail = new ArrayList<>();
        Set<String> controlAccounts = new LinkedHashSet<>();
        Set<String> partners = new LinkedHashSet<>();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (JournalLine r : rows) {
            Account a = byId.get(r.getAccountId());
            String type = a == null || a.getControlAccountType() == null ? null : a.getControlAccountType().value();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineNumber", r.getLineNumber());
            m.put("accountId", r.getAccountId());
            m.put("accountCode", a == null ? null : a.getCode());
            m.put("accountName", a == null ? null : a.getName());
            m.put("controlAccountType", type);
            m.put("partnerId", r.getPartnerId());
            m.put("debit", plain(nz(r.getDebit())));
            m.put("credit", plain(nz(r.getCredit())));
            m.put("baseDebit", plain(nz(r.getBaseDebit())));
            m.put("baseCredit", plain(nz(r.getBaseCredit())));
            m.put("currencyId", r.getCurrencyId());
            lineTrail.add(m);
            if ("ar".equals(type) || "ap".equals(type)) {
                controlAccounts.add(a.getCode());
                if (r.getPartnerId() != null) {
                    partners.add(r.getPartnerId());
                }
            }
            debit = debit.add(nz(r.getBaseDebit()));
            credit = credit.add(nz(r.getBaseCredit()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("controlAdjustment", Boolean.TRUE.equals(entry.getControlAdjustment()));
        out.put("reason", entry.getControlAdjustmentReason());
        out.put("entryNumber", entry.getEntryNumber());
        out.put("status", entry.getStatus() == null ? null : entry.getStatus().value());
        out.put("postingDate", entry.getPostingDate());
        out.put("description", entry.getDescription());
        out.put("createdBy", entry.getCreatedBy());
        out.put("postedBy", entry.getPostedBy());
        out.put("controlAccounts", List.copyOf(controlAccounts));
        out.put("partners", List.copyOf(partners));
        out.put("totalBaseDebit", plain(debit));
        out.put("totalBaseCredit", plain(credit));
        out.put("lines", lineTrail);
        return out;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * A residual ≤ 0.01 goes to the rounding account; larger imbalances are rejected. A foreign-currency entry must also
     * balance in its own currency (a base-only check let 1 JPY through at a 0.0067 rate). The rounding line is stored in
     * base with no currency: it has no transaction-currency amount (review 2026-11-15, G-6). Without
     * {@code allowRounding} (a staged draft, already rounded) any base residual is rejected.
     */
    private void applyRounding(List<NormalizedLine> lines, String currencyId, boolean allowRounding) {
        if (currencyId != null && !currencyId.isBlank()) {
            PostingMath.Totals tx = PostingMath.transactionTotals(lines);
            if (tx.debit().subtract(tx.credit()).abs().compareTo(ROUNDING_EPSILON) > 0) {
                throw ApiException.badRequest("Unbalanced entry: total debit " + plain(tx.debit()) + " != total credit "
                        + plain(tx.credit()) + " " + currencyId);
            }
        }
        PostingMath.Totals t = PostingMath.totals(lines);
        BigDecimal imbalance = t.debit().subtract(t.credit());
        if (imbalance.signum() == 0) {
            return;
        }
        if (!allowRounding || imbalance.abs().compareTo(ROUNDING_EPSILON) > 0) {
            throw ApiException.badRequest("Unbalanced entry: total debit " + plain(t.debit()) + " != total credit "
                    + plain(t.credit()));
        }
        String roundingAccountId = accounts.ensureByCode("ROUNDING", "Rounding Differences", "other_expense", "rounding");
        BigDecimal abs = imbalance.abs();
        lines.add(imbalance.signum() > 0
                ? new NormalizedLine(roundingAccountId, null, null, null, null, null, BigDecimal.ZERO, abs, BigDecimal.ZERO,
                        abs, PostingMath.BASE_ONLY, null)
                : new NormalizedLine(roundingAccountId, null, null, null, null, null, abs, BigDecimal.ZERO, abs,
                        BigDecimal.ZERO, PostingMath.BASE_ONLY, null));
    }

    /** decimal.js {@code toString()} (no trailing zeros). */
    public static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }

    private List<JournalLine> persistLines(JournalEntry entry, List<NormalizedLine> lines, PostingRequest request,
            BigDecimal rate, Map<String, String> entryDims, boolean centersResolved) {
        List<JournalLine> rows = new ArrayList<>();
        int i = 1;
        for (NormalizedLine l : lines) {
            JournalLine row = new JournalLine();
            row.setOrganizationId(entry.getOrganizationId());
            row.setJournalEntryId(entry.getId());
            row.setAccountId(l.accountId());
            row.setPartnerId(l.partnerId());
            row.setDescription(l.description());
            if (PostingMath.BASE_ONLY.equals(l.currencyId())) {
                row.setDebit(l.baseDebit());
                row.setCredit(l.baseCredit());
                row.setCurrencyId(null);
                row.setExchangeRate(BigDecimal.ONE);
            } else if (l.overridesCurrency()) {
                boolean debit = l.baseDebit().signum() > 0;
                BigDecimal base = debit ? l.baseDebit() : l.baseCredit();
                row.setDebit(debit ? l.foreignAmount() : BigDecimal.ZERO);
                row.setCredit(debit ? BigDecimal.ZERO : l.foreignAmount());
                row.setCurrencyId(l.currencyId());
                row.setExchangeRate(l.foreignAmount().signum() == 0 ? BigDecimal.ONE
                        : base.divide(l.foreignAmount(), 8, java.math.RoundingMode.HALF_UP));
            } else {
                row.setDebit(l.debit());
                row.setCredit(l.credit());
                row.setCurrencyId(request.currencyId());
                row.setExchangeRate(rate);
            }
            row.setBaseDebit(l.baseDebit());
            row.setBaseCredit(l.baseCredit());
            row.setBranchId(l.branchId() != null ? l.branchId() : request.branchId());
            row.setCostCenterId(centersResolved || l.costCenterId() != null ? l.costCenterId() : request.costCenterId());
            Map<String, String> dims = normalizeDimensions(l.dimensions());
            Map<String, String> effective = dims != null ? dims : entryDims;
            row.setDimensions(effective == null ? null : EntityJson.toJson(effective));
            row.setLineNumber(i++);
            em.persist(row);
            rows.add(row);
        }
        return rows;
    }

    /** Flat map of primitive values as strings; {@code null} when empty. */
    static Map<String, String> normalizeDimensions(Map<String, Object> d) {
        if (d == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : d.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                out.put(e.getKey(), v instanceof Number n ? com.inventoryerp.kernel.common.JsNumbers.toJsString(n.doubleValue())
                        : String.valueOf(v));
            }
        }
        return out.isEmpty() ? null : out;
    }
}
