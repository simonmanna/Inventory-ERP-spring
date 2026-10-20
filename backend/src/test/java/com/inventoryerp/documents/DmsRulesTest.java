package com.inventoryerp.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.documents.application.DmsRegistrySeeder;
import com.inventoryerp.documents.application.DmsRules;
import com.inventoryerp.documents.application.DmsRules.GuardContext;
import com.inventoryerp.documents.application.DmsRules.Transition;
import com.inventoryerp.documents.application.DmsSnapshot;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ports of {@code dms.lifecycle.spec.ts}, {@code dms.relations.spec.ts}, {@code dms.snapshot.spec.ts},
 * {@code dms.seed-validation.spec.ts} and {@code document-permissions.service.spec.ts}. Seal / render expectations
 * were produced by running the Nest {@code dms.snapshot.ts} on the same input.
 */
class DmsRulesTest {

    static final List<Transition> SALES = List.of(
            new Transition("draft", "submitted", "submit", Map.of()),
            new Transition("submitted", "approved", "approve", Map.of()),
            new Transition("approved", "posted", "post", Map.of()),
            new Transition("posted", "posted", "reverse", Map.of("requiresReason", true)));

    static GuardContext ctx(String reason, boolean paid, boolean approved) {
        return new GuardContext(reason, paid, approved, false);
    }

    @Nested
    @DisplayName("dms.lifecycle")
    class Lifecycle {

        @Test
        void findTransition() {
            assertThat(DmsRules.findTransition(SALES, "draft", "submit").toState()).isEqualTo("submitted");
            assertThat(DmsRules.findTransition(SALES, "draft", "post")).isNull();
        }

        @Test
        void guards() {
            assertThat(DmsRules.evaluateGuards(Map.of(), ctx(null, false, false))).isEmpty();
            assertThat(DmsRules.evaluateGuards(Map.of("requiresReason", true), ctx("  ", false, false)))
                    .containsExactly("A reason is required for this action");
            assertThat(DmsRules.evaluateGuards(Map.of("requiresReason", true), ctx("typo", false, false))).isEmpty();
            assertThat(DmsRules.evaluateGuards(Map.of("requiresApproval", true), ctx(null, false, false)))
                    .containsExactly("The document requires an approved approval request before this action");
            assertThat(DmsRules.evaluateGuards(Map.of("requiresApproval", true), ctx(null, false, true))).isEmpty();
            assertThat(DmsRules.evaluateGuards(Map.of("requiresPaid", true), ctx(null, false, false)))
                    .containsExactly("The document must be fully paid before this action");
            assertThat(DmsRules.evaluateGuards(Map.of("requiresPaid", true), ctx(null, true, false))).isEmpty();
            assertThat(DmsRules.evaluateGuards(Map.of("requiresReason", true, "requiresPaid", true),
                    ctx(null, false, false))).hasSize(2);
            assertThat(DmsRules.evaluateGuards(Map.of("allowsAnyState", true), ctx(null, false, false))).isEmpty();
        }

        @Test
        void reversalAndCancel() {
            assertThat(DmsRules.isReversal(SALES.get(3))).isTrue();
            assertThat(DmsRules.isReversal(SALES.get(0))).isFalse();
            assertThat(DmsRules.cancelEligibility(false).allowed()).isTrue();
            assertThat(DmsRules.cancelEligibility(true).reason())
                    .isEqualTo("Document has posted effects — use reverse instead of cancel");
            assertThat(DmsRules.reversalEligibility(false, "credit_note").reason())
                    .isEqualTo("Document has no posted effects — use cancel instead of reverse");
            assertThat(DmsRules.reversalEligibility(true, null).reason()).isEqualTo("This document type is not reversable");
            assertThat(DmsRules.reversalEligibility(true, "credit_note").allowed()).isTrue();
            assertThat(DmsRules.REVERSAL_COUNTERPARTS).containsEntry("sales_invoice", "credit_note")
                    .containsEntry("vendor_bill", "debit_note").doesNotContainKey("quotation");
        }

        @Test
        void numbering() {
            assertThat(DmsRules.composeSequenceKey("invoice", 2026)).isEqualTo("invoice:2026");
            assertThat(DmsRules.composeSequencePrefix("INV-", 2026)).isEqualTo("INV-2026-");
            assertThat(DmsRules.composeSequencePrefix(null, 2026)).isEqualTo("2026-");
        }

        @Test
        void amendAndSnapshotPolicy() {
            assertThat(DmsRules.validateAmendData(Map.of(), Map.of()).reason())
                    .isEqualTo("amend requires a non-empty data payload");
            assertThat(DmsRules.validateAmendData(Map.of(), Map.of("rent", "5")).reason())
                    .isEqualTo("'rent' is not amendable — document-owned content fields are: terms, notes, clauses,"
                            + " conditions");
            var ok = DmsRules.validateAmendData(Map.of("terms", "old"), Map.of("terms", "new", "notes", "n"));
            assertThat(ok.mergedData()).containsEntry("terms", "new").doesNotContainKey("notes");
            assertThat(ok.notes()).isEqualTo("n");
            assertThat(DmsRules.shouldCaptureSnapshot(null, "reverse", "posted")).isTrue();
            assertThat(DmsRules.shouldCaptureSnapshot(List.of("post"), "confirm", "posted")).isTrue();
            assertThat(DmsRules.shouldCaptureSnapshot(List.of("issue"), "post", "posted")).isFalse();
        }
    }

    @Nested
    @DisplayName("validateRelationCreation")
    class Relations {

        @Test
        void semantics() {
            assertThat(DmsRules.validateRelationCreation("FULFILLS", List.of("retail"), List.of("retail"), "many",
                    "retail", "retail", 3)).isNull();
            assertThat(DmsRules.validateRelationCreation("FULFILLS", List.of(), List.of(), "many", "pos", "school", 0))
                    .isNull();
            assertThat(DmsRules.validateRelationCreation("FULFILLS", List.of("retail"), List.of(), "many", "pos",
                    "retail", 0)).isEqualTo("Relation 'FULFILLS' does not allow source category 'pos' (allowed: retail)");
            assertThat(DmsRules.validateRelationCreation("FULFILLS", List.of(), List.of("retail", "finance"), "many",
                    "pos", "school", 0))
                    .isEqualTo("Relation 'FULFILLS' does not allow target category 'school' (allowed: retail, finance)");
            assertThat(DmsRules.validateRelationCreation("CANCELS", List.of(), List.of(), "one", "retail", "retail", 1))
                    .isEqualTo("Relation 'CANCELS' is cardinality ONE — this document already has one");
        }
    }

    @Nested
    @DisplayName("document permissions")
    class Permissions {

        @Test
        void resolution() {
            assertThat(DmsRules.resolveKey(List.of("document:sales_invoice:post", "document:retail:post", "document:post"),
                    "sales_invoice", "retail", null, "post")).isEqualTo("document:sales_invoice:post");
            assertThat(DmsRules.resolveKey(List.of("document:retail:post"), "sales_invoice", "retail", null, "post"))
                    .isEqualTo("document:retail:post");
            assertThat(DmsRules.resolveKey(List.of("document:post"), "sales_invoice", "retail", null, "post"))
                    .isEqualTo("document:post");
            assertThat(DmsRules.resolveKey(List.of("document:vendor_bill:post"), "sales_invoice", "retail", null, "post"))
                    .isNull();
            assertThat(DmsRules.resolveKey(List.of("school:report:issue"), "report", "school",
                    Map.of("basePermission", "school"), "issue")).isEqualTo("school:report:issue");
            assertThat(DmsRules.can(List.of("document:manage"), null, null, null, "manage")).isTrue();
        }
    }

    @Nested
    @DisplayName("dms.snapshot")
    class Snapshot {

        Map<String, Object> doc() {
            Map<String, Object> d = new HashMap<>();
            d.put("id", "doc-1");
            d.put("documentNumber", "INV-2026-000001");
            d.put("status", "posted");
            d.put("issueDate", Instant.parse("2026-03-01T00:00:00Z"));
            d.put("dueDate", null);
            d.put("partnerId", "p-123456789");
            d.put("sourceDocument", null);
            d.put("branchId", null);
            d.put("subtotal", "100.50");
            d.put("discountTotal", "0");
            d.put("taxAmount", "18.09");
            d.put("totalAmount", "118.59");
            d.put("amountPaid", "0");
            d.put("amountResidual", "118.59");
            d.put("currencyId", null);
            d.put("exchangeRate", "1");
            d.put("notes", "Thanks \"friend\"");
            return d;
        }

        List<Map<String, Object>> lines() {
            List<Map<String, Object>> out = new ArrayList<>();
            Map<String, Object> a = new HashMap<>(Map.of("productId", "x", "description", "Latte", "quantity", "2",
                    "unitPrice", "50.25", "discountPercent", "0", "taxAmount", "18.09", "subtotal", "100.5", "total",
                    "118.59", "lineNumber", 1, "lineType", "product"));
            Map<String, Object> s = new HashMap<>(Map.of("description", "Section", "quantity", "0", "unitPrice", "0",
                    "discountPercent", "0", "taxAmount", "0", "subtotal", "0", "total", "0", "lineNumber", 2, "lineType",
                    "section"));
            s.put("productId", null);
            out.add(a);
            out.add(s);
            return out;
        }

        @Test
        void buildAndSealMatchNest() {
            Map<String, Object> data = DmsSnapshot.buildSnapshotData(doc(), lines(), "sales_invoice", "Sales Invoice");
            assertThat(data.get("lines")).asList().hasSize(1);
            assertThat(data.get("issueDate")).isEqualTo("2026-03-01T00:00:00.000Z");
            assertThat(data.get("subtotal")).isEqualTo(100.5);
            assertThat(data.get("exchangeRate")).isEqualTo(1L);
            assertThat(DmsSnapshot.seal(data, "tpl-1"))
                    .isEqualTo("4f9781b7526e15874cdc864c20f6f9111fe94a4427d33ae9b5dc7240532f458b");
            assertThat(DmsSnapshot.seal(data, null))
                    .isEqualTo("3163d14e53c16adc8926fa74d84579553a6e1a80596d55ae83f7db1d4810a50f");
            Map<String, Object> reordered = new LinkedHashMap<>();
            new ArrayList<>(data.keySet()).reversed().forEach(k -> reordered.put(k, data.get(k)));
            assertThat(DmsSnapshot.seal(reordered, "tpl-1")).isEqualTo(DmsSnapshot.seal(data, "tpl-1"));
        }

        @Test
        void renderMatchesNest() {
            Map<String, Object> data = DmsSnapshot.buildSnapshotData(doc(), lines(), "sales_invoice", "Sales Invoice");
            String out = DmsSnapshot.renderTemplate("{{documentNumber}}|{{#each lines}}{{quantity}}x{{description}}="
                    + "{{total}};{{/each}}|{{totalAmount}}|{{missing}}|{{partnerCode}}", DmsSnapshot.toRenderContext(data));
            assertThat(out).isEqualTo("INV-2026-000001|2xLatte=118.59;|118.59|{{missing}}|p-123456");
        }
    }

    @Nested
    @DisplayName("DMS seed data")
    class Seed {

        @SuppressWarnings("unchecked")
        Map<String, Object> registry() throws Exception {
            try (InputStream in = getClass().getResourceAsStream("/dms/registry.json")) {
                return JsonMapper.builder().build().readValue(in, Map.class);
            }
        }

        @Test
        @SuppressWarnings("unchecked")
        void registryIsValid() throws Exception {
            Map<String, Object> r = registry();
            List<Map<String, Object>> lifecycles = (List<Map<String, Object>>) r.get("lifecycles");
            List<Map<String, Object>> types = (List<Map<String, Object>>) r.get("types");
            assertThat(DmsRegistrySeeder.validateLifecycleSeeds(lifecycles)).isGreaterThan(0);
            assertThat(lifecycles).hasSize(6);
            assertThat(types).hasSize(17);
            List<String> codes = lifecycles.stream().map(l -> (String) l.get("code")).toList();
            assertThat(types).allSatisfy(t -> assertThat(codes).contains((String) t.get("lifecycle")));
            assertThat(types.stream().map(t -> t.get("code")).toList()).contains("sales_invoice", "credit_note",
                    "vendor_bill", "debit_note", "proforma_invoice");
            List<Map<String, Object>> perms = (List<Map<String, Object>>) r.get("permissions");
            assertThat(perms.size()).isGreaterThan(200);
            assertThat(perms).allSatisfy(p -> assertThat((String) p.get("key")).startsWith("document:"));
        }

        @Test
        void validationRejectsBadSeeds() {
            assertThatThrownBy(() -> DmsRegistrySeeder.validateGuard(Map.of("script", true), "x.draft.post"))
                    .hasMessageContaining("uses unknown keys [script]");
            Map<String, Object> badTarget = Map.of("code", "x", "states", List.of("draft"), "initialState", "draft",
                    "transitions", List.of(Map.of("from", "draft", "to", "posted", "action", "post")));
            assertThatThrownBy(() -> DmsRegistrySeeder.validateLifecycleSeeds(List.of(badTarget)))
                    .hasMessage("DMS lifecycle x: toState 'posted' not in states");
            Map<String, Object> dup = Map.of("code", "y", "states", List.of("draft", "posted"), "initialState", "draft",
                    "transitions", List.of(Map.of("from", "draft", "to", "posted", "action", "post"),
                            Map.of("from", "draft", "to", "draft", "action", "post")));
            assertThatThrownBy(() -> DmsRegistrySeeder.validateLifecycleSeeds(List.of(dup)))
                    .hasMessage("DMS lifecycle y: duplicate transition draft.post");
            assertThat(DmsRules.GUARD_VOCABULARY).containsExactly("permission", "requiresApproval", "requiresReason",
                    "requiresPaid", "requiresSnapshot", "allowsAnyState");
        }
    }
}
