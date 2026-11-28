package com.inventoryerp.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.ApiTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR-S094 §8.2: invoicing's own creators write their source kind (IS-1), and the database refuses a sales invoice
 * or credit note without one, or with an unknown one, on insert and on update (IS-3). Module creators assert their
 * kind in their own ITs (sales, fleet, projects, collections, opening balances, recurring, documents engine).
 */
class DocumentSourceKindIT extends ApiTestSupport {

    private String partner;

    @BeforeAll
    void fixtures() {
        partner = jdbc.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND code = 'CUST-001'")
                .param(demoOrg).query(String.class).single();
    }

    private Map<String, String> keyed() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        return h;
    }

    private String invoice() {
        return ok(200, "POST", "/invoices", Map.of("partnerId", partner, "issueDate", LocalDate.now().toString(),
                "lines", List.of(Map.of("description", "Consulting", "quantity", 2, "unitPrice", 25)))).get("id")
                .asString();
    }

    private Res creditNote(String reversedDocumentId) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("partnerId", partner, "issueDate",
                LocalDate.now().toString(), "lines", List.of(Map.of("description", "Consulting", "quantity", 1,
                        "unitPrice", 25))));
        if (reversedDocumentId != null) {
            body.put("reversedDocumentId", reversedDocumentId);
        }
        return call("POST", "/credit-notes", body, keyed());
    }

    @Test
    @DisplayName("IS-1: POST /invoices is manual; POST /credit-notes is credit with an invoice, manual without")
    void invoicingCreators() {
        String inv = invoice();
        assertThat(sourceKind(inv)).isEqualTo("manual");
        ok(200, "POST", "/invoices/" + inv + "/post", null);

        Res linked = creditNote(inv);
        assertThat(linked.status()).as(linked.raw().body()).isEqualTo(200);
        assertThat(sourceKind(linked.body().get("id").asString())).isEqualTo("credit");

        Res standalone = creditNote(null);
        assertThat(standalone.status()).as(standalone.raw().body()).isEqualTo(200);
        assertThat(sourceKind(standalone.body().get("id").asString())).isEqualTo("manual");

        // the response shape is unchanged (parity): the kind is not serialized
        assertThat(ok(200, "GET", "/invoices/" + inv, null).has("sourceKind")).isFalse();
    }

    @Test
    @DisplayName("IS-3: a sales invoice or credit note without a known source kind is refused, on insert and update")
    void databaseGuard() {
        String inv = invoice();
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"Document\" SET \"sourceKind\" = NULL WHERE id = ?").param(inv)
                .update()).hasMessageContaining("Document_customer_source_kind_check");
        assertThatThrownBy(() -> jdbc.sql("UPDATE \"Document\" SET \"sourceKind\" = 'bogus' WHERE id = ?").param(inv)
                .update()).hasMessageContaining("Document_source_kind_value_check");
        assertThat(sourceKind(inv)).isEqualTo("manual");

        String typeId = jdbc.sql("SELECT id FROM \"DocumentTypeDef\" WHERE code = 'sales_invoice'").query(String.class)
                .single();
        String id = UUID.randomUUID().toString();
        // documentType defaults to a sales invoice: a row inserted without its source is refused
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO \"Document\" (id, \"organizationId\", \"documentNumber\","
                + " \"partnerId\", \"issueDate\", \"updatedAt\", \"documentTypeId\") VALUES (?, ?, ?, ?, now(), now(), ?)")
                .params(id, demoOrg, "RAW-" + id.substring(0, 8), partner, typeId).update())
                .hasMessageContaining("Document_customer_source_kind_check");
        assertThat(jdbc.sql("SELECT count(*) FROM \"Document\" WHERE id = ?").param(id).query(Long.class).single())
                .isZero();
        // a vendor bill needs no source kind
        jdbc.sql("INSERT INTO \"Document\" (id, \"organizationId\", \"documentNumber\", \"documentType\", \"partnerId\","
                + " \"issueDate\", \"updatedAt\", \"documentTypeId\") VALUES (?, ?, ?, 'vendor_bill', ?, now(), now(),"
                + " (SELECT id FROM \"DocumentTypeDef\" WHERE code = 'vendor_bill'))")
                .params(id, demoOrg, "RAW-" + id.substring(0, 8), partner).update();
        assertThat(sourceKind(id)).isNull();
        jdbc.sql("DELETE FROM \"Document\" WHERE id = ?").param(id).update();
    }
}
