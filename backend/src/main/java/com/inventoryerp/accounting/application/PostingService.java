package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.accounting.application.PostingMath.NormalizedLine;
import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.Journal;
import com.inventoryerp.accounting.domain.JournalEntry;
import com.inventoryerp.accounting.domain.JournalEntryStatus;
import com.inventoryerp.accounting.domain.JournalLine;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.ReadMemo;
import com.inventoryerp.kernel.crud.AfterCommit;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
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
 * posting idempotent (only a {@code posted} entry is replayed); reversal suffixes the original key so the
 * canonical slot can be re-posted. Manual entries go through draft → post (maker-checker).
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

    public PostingService(EntityManager em, JdbcClient jdbc, EventBus events, SequenceService sequence,
            FiscalPeriodControl fiscalPeriod, CurrencyService currency, AccountResolverService accounts) {
        this.em = em;
        this.jdbc = jdbc;
        this.events = events;
        this.sequence = sequence;
        this.fiscalPeriod = fiscalPeriod;
        this.currency = currency;
        this.accounts = accounts;
    }

    /** Post a balanced entry; returns the entry with {@code lines}. */
    @Transactional
    public Map<String, Object> post(PostingRequest request) {
        if (request.lines() == null || request.lines().size() < 2) {
            throw ApiException.badRequest("A journal entry requires at least two lines");
        }
        String organizationId = TenantContext.organizationId();
        if (request.postingKey() != null) {
            JournalEntry existing = em.createQuery("select e from JournalEntry e where e.organizationId = :org"
                            + " and e.postingKey = :key and e.status = :status", JournalEntry.class)
                    .setParameter("org", organizationId).setParameter("key", request.postingKey())
                    .setParameter("status", JournalEntryStatus.POSTED)
                    .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
            if (existing != null) {
                return render(existing);
            }
        }
        Journal journal = journal(request.journalCode());
        Instant date = request.date();
        fiscalPeriod.assertOpen(date);
        BigDecimal rate = rate(request, date);
        List<NormalizedLine> lines = new ArrayList<>(PostingMath.normalizeLines(request.lines(), rate));
        List<PostingMath.LineValidationError> errors = PostingMath.validateLines(lines);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(errors.getFirst().message());
        }
        assertAccounts(lines, true);
        applyRounding(lines);

        int year = date.atOffset(ZoneOffset.UTC).getYear();
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
        List<JournalLine> rows = persistLines(entry, lines, request, rate, entryDims);
        em.flush();
        em.refresh(entry);
        String entryId = entry.getId();
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
        JournalEntry original = em.createQuery("select e from JournalEntry e where e.id = :id and e.organizationId = :org",
                        JournalEntry.class)
                .setParameter("id", journalEntryId).setParameter("org", organizationId)
                .getResultList().stream().findFirst().orElse(null);
        if (original == null) {
            throw ApiException.notFound("Journal entry not found");
        }
        if (original.getStatus() != JournalEntryStatus.POSTED) {
            throw ApiException.badRequest("Only posted entries can be reversed");
        }
        Instant when = date == null ? Instant.now() : date;
        fiscalPeriod.assertOpen(when);
        Journal journal = em.find(Journal.class, original.getJournalId());
        int year = when.atOffset(ZoneOffset.UTC).getYear();
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
        AfterCommit.run(() -> events.publish("journal.reversed", Map.of("organizationId", organizationId,
                "journalEntryId", originalId, "reversalEntryId", reversalId)));
        return render(reversal, rows);
    }

    /** Stage a manual entry as a DRAFT (validated + balanced, not in the books). */
    @Transactional
    public Map<String, Object> stageDraft(PostingRequest request) {
        if (request.lines() == null || request.lines().size() < 2) {
            throw ApiException.badRequest("A journal entry requires at least two lines");
        }
        String organizationId = TenantContext.organizationId();
        Journal journal = journal(request.journalCode());
        Instant date = request.date();
        fiscalPeriod.assertOpen(date);
        BigDecimal rate = rate(request, date);
        List<NormalizedLine> lines = new ArrayList<>(PostingMath.normalizeLines(request.lines(), rate));
        List<PostingMath.LineValidationError> errors = PostingMath.validateLines(lines);
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(errors.getFirst().message());
        }
        assertAccounts(lines, false);
        applyRounding(lines);
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
        entry.setCreatedBy(TenantContext.userId());
        em.persist(entry);
        List<JournalLine> rows = persistLines(entry, lines, request, rate, entryDims);
        em.flush();
        em.refresh(entry);
        return render(entry, rows);
    }

    /** Approve + post a DRAFT (approver must differ from its creator). */
    @Transactional
    public Map<String, Object> postDraft(String entryId) {
        String organizationId = TenantContext.organizationId();
        JournalEntry draft = em.createQuery("select e from JournalEntry e where e.id = :id and e.organizationId = :org",
                        JournalEntry.class)
                .setParameter("id", entryId).setParameter("org", organizationId)
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
        fiscalPeriod.assertOpen(draft.getPostingDate());
        Journal journal = em.find(Journal.class, draft.getJournalId());
        int year = draft.getPostingDate().atOffset(ZoneOffset.UTC).getYear();
        String entryNumber = sequence.next("journal:" + journal.getCode() + ":" + year,
                journal.getCode() + "/" + year + "/", 5);
        draft.setEntryNumber(entryNumber);
        draft.setStatus(JournalEntryStatus.POSTED);
        draft.setPostedAt(Instant.now());
        draft.setPostedBy(userId);
        em.flush();
        Map<String, Object> payload = new HashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("journalEntryId", draft.getId());
        payload.put("entryNumber", entryNumber);
        payload.put("sourceType", draft.getSourceType());
        payload.put("sourceId", draft.getSourceId());
        AfterCommit.run(() -> events.publish("journal.posted", payload));
        return render(draft);
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

    private BigDecimal rate(PostingRequest request, Instant date) {
        String orgId = TenantContext.organizationId();
        String baseCode = Optional.ofNullable(ReadMemo.get(() -> jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\""
                + " WHERE id = ?").param(orgId).query(String.class).optional().orElse(null), "org.currencyCode", orgId))
                .orElse("USD");
        BigDecimal rate = Money.dec(request.exchangeRate() == null ? 1 : request.exchangeRate());
        if (request.currencyId() != null && !request.currencyId().equals(baseCode)) {
            try {
                rate = currency.getRate(request.currencyId(), baseCode, date);
            } catch (ApiException ignored) {
                // No rate configured — keep the caller-supplied rate (or 1).
            }
        }
        return rate;
    }

    private void assertAccounts(List<NormalizedLine> lines, boolean systemContext) {
        Set<String> ids = new LinkedHashSet<>();
        lines.forEach(l -> ids.add(l.accountId()));
        List<Account> found = em.createQuery("select a from Account a left join fetch a.category"
                        + " where a.organizationId = :org and a.id in :ids", Account.class)
                .setParameter("org", TenantContext.organizationId()).setParameter("ids", ids).getResultList();
        if (found.size() != ids.size()) {
            throw ApiException.badRequest("One or more accounts do not exist in this organization");
        }
        for (Account a : found) {
            AccountMeta meta = AccountResolverService.toMeta(a, a.getCategory());
            AccountResolverService.assertPostable(meta, systemContext);
        }
    }

    /** A residual ≤ 0.01 goes to the rounding account; larger imbalances are rejected. */
    private void applyRounding(List<NormalizedLine> lines) {
        PostingMath.Totals t = PostingMath.totals(lines);
        BigDecimal imbalance = t.debit().subtract(t.credit());
        if (imbalance.signum() == 0) {
            return;
        }
        if (imbalance.abs().compareTo(ROUNDING_EPSILON) > 0) {
            throw ApiException.badRequest("Unbalanced entry: total debit " + plain(t.debit()) + " != total credit "
                    + plain(t.credit()));
        }
        String roundingAccountId = accounts.ensureByCode("ROUNDING", "Rounding Differences", "other_expense", "rounding");
        BigDecimal abs = imbalance.abs();
        lines.add(imbalance.signum() > 0
                ? new NormalizedLine(roundingAccountId, null, null, null, null, null, BigDecimal.ZERO, abs, BigDecimal.ZERO, abs)
                : new NormalizedLine(roundingAccountId, null, null, null, null, null, abs, BigDecimal.ZERO, abs, BigDecimal.ZERO));
    }

    /** decimal.js {@code toString()} (no trailing zeros). */
    public static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }

    private List<JournalLine> persistLines(JournalEntry entry, List<NormalizedLine> lines, PostingRequest request,
            BigDecimal rate, Map<String, String> entryDims) {
        List<JournalLine> rows = new ArrayList<>();
        int i = 1;
        for (NormalizedLine l : lines) {
            JournalLine row = new JournalLine();
            row.setOrganizationId(entry.getOrganizationId());
            row.setJournalEntryId(entry.getId());
            row.setAccountId(l.accountId());
            row.setPartnerId(l.partnerId());
            row.setDescription(l.description());
            row.setDebit(l.debit());
            row.setCredit(l.credit());
            row.setCurrencyId(request.currencyId());
            row.setExchangeRate(rate);
            row.setBaseDebit(l.baseDebit());
            row.setBaseCredit(l.baseCredit());
            row.setBranchId(l.branchId() != null ? l.branchId() : request.branchId());
            row.setCostCenterId(l.costCenterId() != null ? l.costCenterId() : request.costCenterId());
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
