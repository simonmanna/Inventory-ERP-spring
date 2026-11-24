package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.JournalEntry;
import com.inventoryerp.accounting.domain.JournalLine;
import com.inventoryerp.kernel.common.Money;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Finance readiness audit A6 (P2-12): a posting key replayed with other lines. {@link PostingService#post} replays the
 * posted entry of a key it has seen (ACC-3), so a caller that reuses a key for a different posting — a second gratuity
 * run on the same date, say — silently gets the first entry back and the ledger misses the second.
 *
 * <p>On every replay the request is compared with the stored entry: net base amount per (account, partner), the request
 * valued at the stored entry's own rate, so a replay after a rate-table change is no difference; at most 0.01 apart
 * per pair, the most the engine's rounding line moves. On a difference: a WARN, a {@code PostingKeyConflict} row for
 * integrity check I13, and the stored entry is returned as before; with {@value #SETTING} = {@code refuse} the replay is
 * refused with 409 instead. The plan switches the default to {@code refuse} after one clean release.
 */
@Component
public class PostingKeyReplayGuard {

    /** Organisation setting: {@code warn} (default) or {@code refuse}. */
    public static final String SETTING = "accounting.postingKeyReuse";

    /** Pairs kept on a conflict row, largest difference first. */
    static final int KEPT_PAIRS = 50;

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final Logger log = LoggerFactory.getLogger(PostingKeyReplayGuard.class);

    /** One (account, partner) pair whose net base amount differs: debit positive. */
    record PairDifference(String accountId, String partnerId, BigDecimal stored, BigDecimal requested) {

        BigDecimal difference() {
            return requested.subtract(stored);
        }
    }

    private final JdbcClient jdbc;
    private final SettingResolverService settings;

    public PostingKeyReplayGuard(JdbcClient jdbc, SettingResolverService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /**
     * Compare a replayed request with the posted entry of its key. Returns quietly when they agree; on a difference
     * refuses (409) under {@code refuse}, else logs, records the conflict in the caller's transaction and returns.
     */
    void check(JournalEntry existing, PostingRequest request, List<JournalLine> stored) {
        List<PairDifference> differences = differences(request, stored, entryRate(existing, stored));
        boolean sameCurrency = sameCurrency(request.currencyId(), existing.getCurrencyId(), this::baseCurrency);
        if (differences.isEmpty() && sameCurrency) {
            return;
        }
        String org = TenantContext.organizationId();
        if ("refuse".equals(mode())) {
            throw ApiException.conflict("Posting key " + request.postingKey() + " was already used by journal entry "
                    + existing.getEntryNumber() + " with different lines");
        }
        log.warn("[posting-key] {} replayed journal entry {} ({} {}) but the request differs: {} account/partner"
                        + " pair(s){}", request.postingKey(), existing.getEntryNumber(), existing.getSourceType(),
                existing.getSourceId(), differences.size(), sameCurrency ? "" : ", another currency");
        List<Map<String, Object>> pairs = new ArrayList<>();
        differences.stream().sorted((a, b) -> b.difference().abs().compareTo(a.difference().abs())).limit(KEPT_PAIRS)
                .forEach(d -> pairs.add(Obj.of("accountId", d.accountId(), "partnerId", d.partnerId(), "stored",
                        PostingService.plain(d.stored()), "requested", PostingService.plain(d.requested()))));
        jdbc.sql("INSERT INTO \"PostingKeyConflict\" (id, \"organizationId\", \"postingKey\", \"journalEntryId\","
                        + " \"sourceType\", \"sourceId\", \"requestedCurrency\", \"pairCount\", pairs, \"createdBy\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)")
                .params(UUID.randomUUID().toString(), org, request.postingKey(), existing.getId(),
                        request.sourceType(), request.sourceId(), request.currencyId(), differences.size(),
                        EntityJson.toJson(pairs), TenantContext.userId())
                .update();
    }

    private String mode() {
        try {
            return settings.resolveString(SETTING, SettingContext.EMPTY);
        } catch (RuntimeException e) {
            return "warn";
        }
    }

    /** The rate of the stored entry's own-currency lines: its rate snapshot, else Σ base ÷ Σ amount; 1 in base. */
    private BigDecimal entryRate(JournalEntry existing, List<JournalLine> stored) {
        String currency = existing.getCurrencyId();
        if (currency == null || currency.isBlank()) {
            return BigDecimal.ONE;
        }
        BigDecimal snapshot = jdbc.sql("SELECT rate FROM \"JournalEntryFxRate\" WHERE \"journalEntryId\" = ? ORDER BY"
                        + " (upper(\"fromCode\") = upper(?)) DESC, \"createdAt\" LIMIT 1")
                .params(existing.getId(), currency).query(BigDecimal.class).optional().orElse(null);
        if (snapshot != null) {
            return snapshot;
        }
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal base = BigDecimal.ZERO;
        for (JournalLine l : stored) {
            if (l.getCurrencyId() != null && l.getCurrencyId().equalsIgnoreCase(currency)) {
                amount = amount.add(nz(l.getDebit())).add(nz(l.getCredit()));
                base = base.add(nz(l.getBaseDebit())).add(nz(l.getBaseCredit()));
            }
        }
        return amount.signum() == 0 ? BigDecimal.ONE : base.divide(amount, MC);
    }

    /** Pairs whose net base amounts differ by more than the tolerance. */
    static List<PairDifference> differences(PostingRequest request, List<JournalLine> stored, BigDecimal rate) {
        Map<String, BigDecimal[]> net = new TreeMap<>();
        for (JournalLine l : stored) {
            BigDecimal[] v = net.computeIfAbsent(key(l.getAccountId(), l.getPartnerId()), k -> zeros());
            v[0] = v[0].add(nz(l.getBaseDebit()).subtract(nz(l.getBaseCredit())));
        }
        for (PostingRequest.Line l : request.lines()) {
            BigDecimal amount = Money.dec(l.debit() == null ? 0 : l.debit())
                    .subtract(Money.dec(l.credit() == null ? 0 : l.credit()));
            BigDecimal[] v = net.computeIfAbsent(key(l.accountId(), l.partnerId()), k -> zeros());
            v[1] = v[1].add(amount.multiply(rate, MC));
        }
        List<PairDifference> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal[]> e : net.entrySet()) {
            BigDecimal storedNet = e.getValue()[0].setScale(6, RoundingMode.HALF_UP);
            BigDecimal requested = e.getValue()[1].setScale(6, RoundingMode.HALF_UP);
            if (requested.subtract(storedNet).abs().compareTo(TOLERANCE) > 0) {
                String[] k = e.getKey().split("\u0000", -1);
                out.add(new PairDifference(k[0], k[1].isEmpty() ? null : k[1], storedNet, requested));
            }
        }
        return out;
    }

    /** The same currency, the base currency's code counting as none (an entry stores the code it was given). */
    static boolean sameCurrency(String requested, String stored, Supplier<String> base) {
        String a = norm(requested);
        String b = norm(stored);
        if (Objects.equals(a, b)) {
            return true;
        }
        String functional = norm(base.get());
        return Objects.equals(functional != null && functional.equals(a) ? null : a,
                functional != null && functional.equals(b) ? null : b);
    }

    private String baseCurrency() {
        return jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(TenantContext.organizationId())
                .query(String.class).optional().orElse(null);
    }

    private static String norm(String currency) {
        return currency == null || currency.isBlank() ? null : currency.trim().toUpperCase();
    }

    private static String key(String accountId, String partnerId) {
        return accountId + "\u0000" + (partnerId == null ? "" : partnerId);
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
