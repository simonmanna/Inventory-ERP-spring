package com.inventoryerp;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/** Enforces module boundaries (no cycles, no access to another module's internals). */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(Application.class);

    @Test
    void verifiesModularStructure() {
        MODULES.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
