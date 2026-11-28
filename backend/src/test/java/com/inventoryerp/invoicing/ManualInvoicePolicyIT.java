package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.recurring.RecurringService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ADR-S094 §8.3 (IS-4, OD-3): the organization setting {@code invoicing.manualCustomerInvoices}. {@code allowed} (the
 * default) keeps {@code POST /invoices} and standalone credit notes working, as in Nest. {@code blocked} refuses both,
 * while a credit note that names its invoice and every module source still create their documents.
 */
class ManualInvoicePolicyIT extends ApiTestSupport {

    private static final String KEY = "/settings/invoicing.manualCustomerInvoices";

    @Autowired
    RecurringService recurring;

    private String partner;

    @BeforeAll
    void fixtures() {
        partner = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND code = 'CUST-001'")
                .param(demoOrg).query(String.class).single();
    }

    private Res invoice() {
        return as("POST", "/invoices", Map.of("partnerId", partner, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("description", "Consulting", "quantity", 1, "unitPrice", 40))));
    }

    private Res creditNote(String reversedDocumentId) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", partner, "issueDate",
                LocalDate.now().toString(), "lines", List.of(Map.of("description", "Consulting", "quantity", 1,
                        "unitPrice", 10))));
        if (reversedDocumentId != null) {
            body.put("reversedDocumentId", reversedDocumentId);
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return call("POST", "/credit-notes", body, h);
    }

    private static String message(Res r) {
        return r.body().get("message").isArray() ? r.body().get("message").get(0).asString()
                : r.body().get("message").asString();
    }

    @Test
    @DisplayName("allowed by default; blocked refuses manual invoices and standalone credit notes, not module sources")
    void manualCustomerInvoices() {
        assertThat(ok(200, "GET", "/settings/effective?group=accounting", null).toString()).as("registered")
                .contains("invoicing.manualCustomerInvoices");
        Res allowed = invoice();
        assertThat(allowed.status()).as(allowed.raw().body()).isEqualTo(200);
        String posted = allowed.body().get("id").asString();
        ok(200, "POST", "/invoices/" + posted + "/post", null);

        assertThat(as("PUT", KEY, Map.of("value", "sometimes")).status()).isEqualTo(400);
        ok(200, "PUT", KEY, Map.of("value", "blocked"));
        try {
            Res manual = invoice();
            assertThat(manual.status()).isEqualTo(400);
            assertThat(message(manual)).isEqualTo(
                    "Manual customer invoices are turned off for this organization: create the invoice from a sale");

            Res standalone = creditNote(null);
            assertThat(standalone.status()).isEqualTo(400);
            assertThat(message(standalone)).isEqualTo("Manual credit notes are turned off for this organization: a"
                    + " credit note must name the invoice it credits");

            Res linked = creditNote(posted);
            assertThat(linked.status()).as(linked.raw().body()).isEqualTo(200);
            assertThat(sourceKind(linked.body().get("id").asString())).isEqualTo("credit");

            // a module source (here the recurring job) is not affected
            Res template = call("POST", "/recurring", Map.of("name", "Policy " + UUID.randomUUID(), "documentType",
                    "sales_invoice", "frequency", "monthly", "nextRunAt", "2026-01-31T00:00:00Z", "template",
                    Map.of("partnerId", partner, "lines", List.of(Map.of("description", "Rent", "quantity", 1,
                            "unitPrice", 100)))), adminToken);
            assertThat(template.status()).as(template.raw().body()).isEqualTo(201);
            String templateId = template.body().get("id").asString();
            recurring.tick(Instant.now());
            String generated = jdbc.sql("SELECT \"documentId\" FROM \"RecurringDocumentRun\" WHERE \"recurringId\" = ?"
                    + " AND status = 'generated'").param(templateId).query(String.class).single();
            assertThat(sourceKind(generated)).isEqualTo("recurring");
            call("PATCH", "/recurring/" + templateId + "/end", null, adminToken);
        } finally {
            assertThat(as("DELETE", KEY, null).status()).isLessThan(300);
        }
        assertThat(invoice().status()).as("allowed again once the setting is removed").isEqualTo(200);
    }
}
