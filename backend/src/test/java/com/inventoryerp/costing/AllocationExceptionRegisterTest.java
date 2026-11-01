package com.inventoryerp.costing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-S033 allocation-engine exception register (R3-6): new allocation code goes into the shared engine
 * ({@code costing.application.AllocationCalculator} / {@code CostCycleService}); any other top-level allocator in an
 * application package must be listed here with its decision. Each entry's protecting tests are named in ADR-S033.
 */
class AllocationExceptionRegisterTest {

    /** Engine → decision (see ADR-S033 for owner, reason, tests and review phase). */
    static final Map<String, String> REGISTER = Map.ofEntries(
            Map.entry("com.inventoryerp.costing.application.AllocationCalculator", "shared engine"),
            Map.entry("com.inventoryerp.costing.application.CostCycleService", "shared engine"),
            Map.entry("com.inventoryerp.accounting.application.CostAllocationService",
                    "reimplement as single-segment cycle — review CM-4"),
            Map.entry("com.inventoryerp.hr.application.HrPayrollCostAllocator", "permanently independent (payslip"
                    + " cents)"),
            Map.entry("com.inventoryerp.manufacturing.application.JointCostAllocator", "keep policy layer — review"
                    + " CM-5"));

    @Test
    @DisplayName("every top-level allocator in an application package is registered")
    void registered() {
        Set<String> found = new TreeSet<>();
        for (JavaClass c : new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.inventoryerp")) {
            if (c.isTopLevelClass() && c.getPackageName().endsWith(".application")
                    && c.getSimpleName().contains("Allocat")) {
                found.add(c.getName());
            }
        }
        assertThat(found).isNotEmpty();
        assertThat(REGISTER.keySet()).as("unregistered allocation engines: add them to ADR-S033 and this register")
                .containsAll(found);
    }
}
