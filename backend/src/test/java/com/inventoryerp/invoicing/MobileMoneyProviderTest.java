package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.invoicing.application.MobileMoneyProvider;
import com.inventoryerp.invoicing.application.MobileMoneyProvider.CollectRequest;
import com.inventoryerp.invoicing.application.MobileMoneyProviders;
import com.inventoryerp.invoicing.application.MockMobileMoneyProvider;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** INT-1: the mock's deterministic outcomes and the callback signature check. */
class MobileMoneyProviderTest {

    private final MockMobileMoneyProvider mock = new MockMobileMoneyProvider();

    private MobileMoneyProvider.CollectionState start(String msisdn) {
        return mock.collect(new CollectRequest("MM-1", msisdn, BigDecimal.TEN, "UGX", null));
    }

    @Test
    void lastDigitPicksTheOutcome() {
        assertThat(start("0772000129").status()).isEqualTo(MobileMoneyProvider.FAILED);
        assertThat(start("0772000128").status()).isEqualTo(MobileMoneyProvider.PENDING);
        assertThat(mock.status(start("0772000128").providerReference()).status())
                .isEqualTo(MobileMoneyProvider.PENDING);
        assertThat(mock.status(start("0772000120").providerReference()).status())
                .isEqualTo(MobileMoneyProvider.SUCCESSFUL);
    }

    @Test
    void parsesCallback() {
        var s = mock.parseCallback("{\"providerReference\":\"R\",\"status\":\"failed\",\"reason\":\"Timeout\"}");
        assertThat(s.providerReference()).isEqualTo("R");
        assertThat(s.status()).isEqualTo("failed");
        assertThat(s.failureReason()).isEqualTo("Timeout");
    }

    @Test
    void signatureNeedsASecret() {
        MobileMoneyProviders none = new MobileMoneyProviders(List.of(mock), "mock", "");
        assertThat(none.validSignature("{}", "anything")).isFalse();
        MobileMoneyProviders set = new MobileMoneyProviders(List.of(mock), " ", "s3cret");
        assertThat(set.configured()).isEqualTo("mock");
        assertThat(set.validSignature("{}", null)).isFalse();
        assertThat(set.validSignature("{}", "00")).isFalse();
    }
}
