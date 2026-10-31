package com.inventoryerp;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.inventoryerp.kernel.security.Public;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Layering rules from docs/ARCHITECTURE.md. */
@AnalyzeClasses(packages = "com.inventoryerp", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

    /**
     * Controllers with routes open to any signed-in user, kept from the Nest contract. Nothing new goes here: a new route
     * declares a permission or {@code @Public}. The admin routes of OrganizationsController and FeatureFlagsController declare permissions since PR-0;
     * their read routes stay open.
     */
    private static final Set<String> SIGNED_IN_CONTROLLERS = Set.of("ApprovalsController", "AuthController",
            "CompanySettingsController", "DigitalMenuPublicController", "DocumentsController",
            "FeatureFlagsController", "FilesController", "HealthController", "HrController", "LicenseController",
            "NotificationsController", "OrganizationsController", "PermissionsController", "PosMenuController",
            "PosSplitController", "PosTablesController", "RecurringController", "ReportAccessController",
            "ReportsDashboardController", "SearchController", "TaskController");

    private static final DescribedPredicate<JavaMethod> ROUTES = DescribedPredicate.describe("a route",
            (JavaMethod m) -> m.isMetaAnnotatedWith(RequestMapping.class) || m.isAnnotatedWith(RequestMapping.class));

    private static final ArchCondition<JavaMethod> DECLARE_ACCESS =
            new ArchCondition<JavaMethod>("declare @RequirePermissions or @Public") {
                @Override
                public void check(JavaMethod m, ConditionEvents events) {
                    boolean declared = m.isAnnotatedWith(RequirePermissions.class) || m.isAnnotatedWith(Public.class)
                            || m.getOwner().isAnnotatedWith(RequirePermissions.class)
                            || m.getOwner().isAnnotatedWith(Public.class);
                    if (!declared && !SIGNED_IN_CONTROLLERS.contains(m.getOwner().getSimpleName())) {
                        events.add(SimpleConditionEvent.violated(m, m.getFullName() + " declares no access rule"));
                    }
                }
            };

    /** Route census for the authorization matrix: every route outside the list above states who may call it. */
    @ArchTest
    static final ArchRule everyRouteDeclaresAccess = methods()
            .that(ROUTES)
            .should(DECLARE_ACCESS)
            .allowEmptyShould(true);

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
