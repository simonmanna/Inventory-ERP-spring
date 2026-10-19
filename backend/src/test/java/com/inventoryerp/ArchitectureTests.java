package com.inventoryerp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/** Layering rules from docs/ARCHITECTURE.md. */
@AnalyzeClasses(packages = "com.inventoryerp", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersAreNotTransactional = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersDoNotExposeEntities = noMethods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .should().haveRawReturnType(com.tngtech.archunit.base.DescribedPredicate.describe(
                    "a JPA entity", (com.tngtech.archunit.core.domain.JavaClass c) -> c.isAnnotatedWith(Entity.class)))
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule sharedDoesNotDependOnModules = noClasses()
            .that().resideInAPackage("com.inventoryerp.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.inventoryerp.kernel..", "com.inventoryerp.core..", "com.inventoryerp.pos..",
                    "com.inventoryerp.accounting..", "com.inventoryerp.inventory..")
            .allowEmptyShould(true);
}
