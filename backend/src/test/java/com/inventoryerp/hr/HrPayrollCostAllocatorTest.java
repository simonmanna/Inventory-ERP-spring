package com.inventoryerp.hr;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.hr.application.HrPayrollCostAllocator;
import com.inventoryerp.hr.application.HrPayrollCostAllocator.Cost;
import com.inventoryerp.hr.application.HrPayrollCostAllocator.Debit;
import com.inventoryerp.hr.application.HrPayrollCostAllocator.Rule;
import com.inventoryerp.hr.application.HrPayrollCostAllocator.Share;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Payroll cost split (HR-5): rule precedence, exact totals, journal grouping. */
class HrPayrollCostAllocatorTest {

    final Rule half1 = new Rule("CC1", new BigDecimal("50"));
    final Rule half2 = new Rule("CC2", new BigDecimal("50"));
    final Rule all3 = new Rule("CC3", new BigDecimal("100"));

    BigDecimal sum(List<Share> shares) {
        return shares.stream().map(Share::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("an employee's rules beat the department's; nobody's rule leaves the cost unallocated")
    void precedence() {
        List<Cost> costs = List.of(new Cost("E1", "D1", "B1", new BigDecimal("1000")),
                new Cost("E2", "D1", "B1", new BigDecimal("1000")), new Cost("E3", null, null, new BigDecimal("1000")));
        List<Share> shares = HrPayrollCostAllocator.split(costs, Map.of("E1", List.of(all3)),
                Map.of("D1", List.of(half1, half2)), new BigDecimal("3000"));
        assertThat(shares).hasSize(4);
        assertThat(shares.get(0).costCenterId()).isEqualTo("CC3");
        assertThat(shares.get(1).costCenterId()).isEqualTo("CC1");
        assertThat(shares.get(1).amount()).isEqualByComparingTo("500");
        assertThat(shares.get(3).costCenterId()).isNull();
    }

    @Test
    @DisplayName("thirds still sum exactly to the employee and to the journal total")
    void exactTotals() {
        Rule a = new Rule("A", new BigDecimal("33.3333"));
        Rule b = new Rule("B", new BigDecimal("33.3333"));
        Rule c = new Rule("C", new BigDecimal("33.3334"));
        List<Share> shares = HrPayrollCostAllocator.split(List.of(new Cost("E", null, null, new BigDecimal("100000.01"))),
                Map.of("E", List.of(a, b, c)), Map.of(), new BigDecimal("100000.01"));
        assertThat(sum(shares)).isEqualByComparingTo("100000.01");
    }

    @Test
    @DisplayName("a difference against the journal total lands on the largest share")
    void settled() {
        List<Share> shares = HrPayrollCostAllocator.split(List.of(new Cost("E1", null, null, new BigDecimal("10")),
                new Cost("E2", null, null, new BigDecimal("20"))), Map.of(), Map.of(), new BigDecimal("30.01"));
        assertThat(sum(shares)).isEqualByComparingTo("30.01");
        assertThat(shares.get(1).amount()).isEqualByComparingTo("20.01");
    }

    @Test
    @DisplayName("journal debits group by cost centre and branch and drop zero lines")
    void debits() {
        List<Debit> debits = HrPayrollCostAllocator.debits(List.of(new Share("E1", "D", "B1", "CC1", new BigDecimal("10")),
                new Share("E2", "D", "B1", "CC1", new BigDecimal("5")), new Share("E3", "D", "B2", "CC1", new BigDecimal("7")),
                new Share("E4", null, null, null, BigDecimal.ZERO)));
        assertThat(debits).hasSize(2);
        assertThat(debits.get(0).amount()).isEqualByComparingTo("15");
        assertThat(debits.get(1).branchId()).isEqualTo("B2");
    }
}
