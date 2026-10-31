package com.inventoryerp.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.crm.application.CrmCommissionService;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CrmCommissionTest {

    @Test
    @DisplayName("commission is the rate on the net base, half up to cents")
    void rounding() {
        assertThat(CrmCommissionService.commission(new BigDecimal("1000"), new BigDecimal("2.5")))
                .isEqualByComparingTo("25.00");
        assertThat(CrmCommissionService.commission(new BigDecimal("33.33"), new BigDecimal("1.5")))
                .isEqualByComparingTo("0.50");
    }
}
