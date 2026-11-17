package com.inventoryerp.accounting.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.application.AccountResolverService.AccountMeta;
import com.inventoryerp.shared.web.ApiException;
import org.junit.jupiter.api.Test;

/** Control-account policy (V518): the postability gate on designated AR / AP control accounts. */
class ControlAccountGateTest {

    static AccountMeta meta(String code, String controlType, boolean allowManualPosting) {
        return new AccountMeta("id-" + code, code, "Account " + code, true, true, null, null, 0, controlType, false,
                "cat", "receivable", "Receivable", "asset", "debit", null, "operating", false, false, false,
                allowManualPosting, false);
    }

    @Test
    void userEntryOnArApIsRefusedWithWorkflowHint() {
        assertThatThrownBy(() -> AccountResolverService.assertPostable(meta("1300", "ar", true), false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("1300 Account 1300 is a designated accounts receivable control account")
                .hasMessageContaining("control-account adjustment");
        assertThatThrownBy(() -> AccountResolverService.assertPostable(meta("2100", "ap", true), false, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("designated accounts payable control account")
                .hasMessageContaining("supplier sub-ledger");
    }

    @Test
    void systemContextAndAdjustmentsPass() {
        assertThatCode(() -> AccountResolverService.assertPostable(meta("1300", "ar", false), true))
                .doesNotThrowAnyException();
        // An adjustment may post to a designated AR / AP account even when its category refuses manual posting.
        assertThatCode(() -> AccountResolverService.assertPostable(meta("1300", "ar", false), false, true))
                .doesNotThrowAnyException();
    }

    @Test
    void otherAccountsKeepTheirRules() {
        // Not designated: the name or category never makes an account a control account.
        assertThatCode(() -> AccountResolverService.assertPostable(meta("1310", null, true), false))
                .doesNotThrowAnyException();
        assertThatCode(() -> AccountResolverService.assertPostable(meta("1400", "inventory", true), false))
                .doesNotThrowAnyException();
        // The adjustment flag does not lift the manual-posting rule of a non-AR / AP account.
        assertThatThrownBy(() -> AccountResolverService.assertPostable(meta("1400", "inventory", false), false, true))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not allow manual posting");
    }
}
