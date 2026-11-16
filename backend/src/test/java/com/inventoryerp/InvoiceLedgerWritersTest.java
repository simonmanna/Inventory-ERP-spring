package com.inventoryerp;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.inventoryerp.fleet.application.DeliveryInvoicing;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Phase A A4 (plan §5): only the invoicing owners write the goods ledgers. Goods allocation goes through Fleet's
 * {@code DeliveryInvoicing}, called only by the Fleet delivery invoice and Sales billing; regular Sales invoice links
 * are written only by Sales billing and the legacy review. The SQL writers are checked in the source, since the
 * ledgers are written with plain SQL.
 */
class InvoiceLedgerWritersTest {

    static final Path MAIN = Path.of("src/main/java/com/inventoryerp");

    static List<String> writers(String table) throws IOException {
        String needle = "INSERT INTO \\\"" + table + "\\\"";
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return Files.readString(p).contains(needle);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void fleetGoodsLedgerIsWrittenByDeliveryInvoicingOnly() throws IOException {
        assertThat(writers("FleetDeliveryInvoiceLine")).containsExactly("DeliveryInvoicing.java");
    }

    @Test
    void salesInvoiceLinksAreWrittenByTheInvoicingOwners() throws IOException {
        // SalesReturnService writes credit-note links only.
        assertThat(Set.copyOf(writers("SalesInvoiceLink"))).isSubsetOf("SalesBillingService.java",
                "SalesLegacyInvoiceService.java", "SalesReturnService.java");
    }

    @Test
    void onlyTheInvoicingOwnersAllocateGoods() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.inventoryerp");
        noClasses().that().doNotHaveSimpleName("DeliveryInvoicing")
                .and().doNotHaveSimpleName("FleetDeliveryInvoiceService")
                .and().doNotHaveSimpleName("SalesBillingService")
                .should().callMethod(DeliveryInvoicing.class, "allocateFleetGoods", String.class, String.class,
                        List.class)
                .orShould().callMethod(DeliveryInvoicing.class, "markGoodsInvoiced", String.class, String.class,
                        String.class, String.class)
                .orShould().callMethod(DeliveryInvoicing.class, "assertSalesGoods", String.class)
                .check(classes);
    }
}
