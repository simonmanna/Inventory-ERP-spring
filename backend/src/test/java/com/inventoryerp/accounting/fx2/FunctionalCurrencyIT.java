package com.inventoryerp.accounting.fx2;

import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.cr;
import static com.inventoryerp.accounting.support.AccountingFixtures.JeLine.dr;
import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.accounting.support.AccountingFixtures;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FX-2 A: the functional currency is mandatory, changeable only before the first posting, never cleared. */
class FunctionalCurrencyIT extends AccountingFixtures {

    @Test
    @DisplayName("the functional currency cannot be cleared")
    void cannotBeCleared() {
        Books b = freshBooks();
        Map<String, Object> body = new HashMap<>();
        body.put("currencyCode", " ");
        Res r = as(b, "PATCH", "/organizations/me/settings", body);
        assertThat(r.status()).as(r.raw().body()).isEqualTo(400);
        assertThat(r.raw().body()).contains("The functional currency is required");
        assertThat(baseCurrency(b.org())).isNotBlank();
    }

    @Test
    @DisplayName("changeable before the first posting, locked after it")
    void lockedAfterFirstPosting() {
        Books b = freshBooks();
        String base = baseCurrency(b.org());
        String other = "USD".equals(base) ? "EUR" : "USD";
        assertThat(as(b, "PATCH", "/organizations/me/settings", Map.of("currencyCode", other)).status()).isEqualTo(200);
        assertThat(as(b, "PATCH", "/organizations/me/settings", Map.of("currencyCode", base)).status()).isEqualTo(200);
        String cash = accountCodeOfCategory(b.org(), "cash");
        String equity = accountCodeOfCategory(b.org(), "equity");
        postJe(b, today(), List.of(dr(cash, 10), cr(equity, 10)));
        Res r = as(b, "PATCH", "/organizations/me/settings", Map.of("currencyCode", other));
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.raw().body()).contains("The functional currency cannot change once entries are posted");
    }

    @Test
    @DisplayName("the database refuses a blank functional currency")
    void blankRefusedByDatabase() {
        Books b = freshBooks();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("UPDATE \"Organization\" SET \"currencyCode\""
                + " = '' WHERE id = ?").param(b.org()).update()).hasMessageContaining("Organization_currencyCode_not_blank");
    }
}
