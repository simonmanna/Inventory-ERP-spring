package com.inventoryerp.accounting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.domain.JournalLine;
import com.inventoryerp.accounting.application.PostingKeyReplayGuard.PairDifference;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Finance audit A6: what counts as "other lines" when a posting key is replayed. */
class PostingKeyReplayGuardTest {

    @Test
    @DisplayName("a true retry is no difference, in base and in a foreign currency at the stored rate")
    void retryIsNoDifference() {
        PostingRequest base = request(null, line("cash", "100", null, null), line("sales", null, "100", null));
        assertThat(PostingKeyReplayGuard.differences(base, List.of(stored("cash", "100", "0", null),
                stored("sales", "0", "100", null)), BigDecimal.ONE)).isEmpty();

        PostingRequest usd = request("USD", line("recv", "100", null, "p1"), line("sales", null, "100", null));
        assertThat(PostingKeyReplayGuard.differences(usd, List.of(stored("recv", "374000", "0", "p1"),
                stored("sales", "0", "374000", null)), new BigDecimal("3740"))).as("valued at the stored rate")
                .isEmpty();
    }

    @Test
    @DisplayName("the engine's rounding line (at most 0.01) is no difference")
    void roundingLineIsNoDifference() {
        PostingRequest r = request("USD", line("recv", "33.33", null, null), line("sales", null, "33.33", null));
        // 33.33 × 3.0001 = 99.993333; the stored entry carries a 0.004 rounding line on its own account.
        assertThat(PostingKeyReplayGuard.differences(r, List.of(stored("recv", "99.993333", "0", null),
                stored("sales", "0", "99.989333", null), stored("rounding", "0", "0.004", null)),
                new BigDecimal("3.0001"))).isEmpty();
    }

    @Test
    @DisplayName("another amount or another partner differs per account and partner")
    void otherLinesDiffer() {
        List<JournalLine> posted = List.of(stored("recv", "100", "0", "p1"), stored("sales", "0", "100", null));

        List<PairDifference> amount = PostingKeyReplayGuard.differences(request(null, line("recv", "120", null, "p1"),
                line("sales", null, "120", null)), posted, BigDecimal.ONE);
        assertThat(amount).extracting(PairDifference::accountId).containsExactly("recv", "sales");
        assertThat(amount.getFirst().stored()).isEqualByComparingTo("100");
        assertThat(amount.getFirst().requested()).isEqualByComparingTo("120");

        List<PairDifference> partner = PostingKeyReplayGuard.differences(request(null, line("recv", "100", null, "p2"),
                line("sales", null, "100", null)), posted, BigDecimal.ONE);
        assertThat(partner).extracting(PairDifference::partnerId).containsExactly("p1", "p2");
        assertThat(partner).extracting(d -> d.difference().abs()).allMatch(d -> d.compareTo(new BigDecimal("100")) == 0);
    }

    @Test
    @DisplayName("the base currency's code is the same currency as none")
    void baseCodeIsNoCurrency() {
        assertThat(PostingKeyReplayGuard.sameCurrency(null, " ", () -> "UGX")).isTrue();
        assertThat(PostingKeyReplayGuard.sameCurrency("ugx", null, () -> "UGX")).isTrue();
        assertThat(PostingKeyReplayGuard.sameCurrency("USD", "usd", () -> "UGX")).isTrue();
        assertThat(PostingKeyReplayGuard.sameCurrency("USD", null, () -> "UGX")).isFalse();
        assertThat(PostingKeyReplayGuard.sameCurrency("USD", "EUR", () -> null)).isFalse();
    }

    private static PostingRequest request(String currency, PostingRequest.Line... lines) {
        return new PostingRequest("GEN", Instant.EPOCH, "t", currency, null, "test", "s", null, "k", null, null, null,
                List.of(lines));
    }

    private static PostingRequest.Line line(String account, String debit, String credit, String partner) {
        return new PostingRequest.Line(account, debit, credit, partner, null, null, null, null);
    }

    private static JournalLine stored(String account, String baseDebit, String baseCredit, String partner) {
        JournalLine l = new JournalLine();
        l.setAccountId(account);
        l.setPartnerId(partner);
        l.setBaseDebit(new BigDecimal(baseDebit));
        l.setBaseCredit(new BigDecimal(baseCredit));
        return l;
    }
}
