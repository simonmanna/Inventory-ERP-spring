package com.inventoryerp.costing;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-S042 (CM-11): the simulation never posts, never moves stock and never writes a ledger. */
class SimulationIsolationTest {

    @Test
    @DisplayName("simulation classes do not depend on posting, stock or lineage writers")
    void noWriters() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.inventoryerp");
        noClasses().that().haveSimpleNameStartingWith("Simulation").and().resideInAPackage("..costing..")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "com.inventoryerp.accounting.application.PostingService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "com.inventoryerp.inventory.application.StockService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "com.inventoryerp.inventory.application.StockPostingService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "com.inventoryerp.inventory.application.CostLineageService")
                .orShould().dependOnClassesThat().haveFullyQualifiedName(
                        "com.inventoryerp.costing.application.LineageCapitalisation")
                .check(classes);
    }
}
