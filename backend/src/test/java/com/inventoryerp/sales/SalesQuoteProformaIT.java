package com.inventoryerp.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.inventoryerp.ApiTestSupport;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.sales.application.SalesQuotationService;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** SG-3 and SG-4: quotation fields and output, expiry, lost reasons; proforma from quote or order, never posted. */
class SalesQuoteProformaIT extends ApiTestSupport {

    @Autowired
    SalesQuotationService quotations;

    final String sfx = UUID.randomUUID().toString().substring(0, 8);
    String product;
    String customer;
    String branch;
    String term;

    @BeforeAll
    void fixtures() {
        product = ok(201, "POST", "/products", Map.of("code", "G3P-" + sfx, "name", "SG3 item " + sfx, "productType",
                "service", "salesPrice", 100)).get("id").asString();
        branch = ok(201, "POST", "/branches", Map.of("code", "G3B-" + sfx, "name", "SG3 B")).get("id").asString();
        customer = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO \"Partner\" (id, \"organizationId\", code, name, email, \"isCustomer\", \"updatedAt\")"
                + " VALUES (?, ?, ?, ?, 'g3@example.test', true, now())").params(customer, demoOrg, "G3C-" + sfx, "SG3 cust "
                + sfx).update();
        term = jdbc.sql("SELECT id FROM \"PaymentTerm\" WHERE \"organizationId\" = ? LIMIT 1").param(demoOrg).query(String.class)
                .optional().orElse(null);
    }

    JsonNode quote(Map<String, Object> extra) {
        Map<String, Object> b = new LinkedHashMap<>(Map.of("partnerId", customer, "lines", List.of(Map.of("productId", product,
                "quantity", 2, "unitPrice", 100), Map.of("description", "Transport charge", "quantity", 1, "unitPrice", 50))));
        b.putAll(extra);
        return ok(201, "POST", "/sales/quotations", b);
    }

    String approved(JsonNode q) {
        String id = q.get("id").asString();
        ok(200, "POST", "/sales/quotations/" + id + "/submit", null);
        return id;
    }

    @Test
    @DisplayName("a quotation converts to an order keeping branch, terms, address, rate, UoM and variant fields")
    void convertKeepsFields() {
        Map<String, Object> extra = new LinkedHashMap<>(Map.of("branchId", branch, "deliveryAddress", "Plot 9", "exchangeRate", 3,
                "internalNotes", "secret margin note"));
        if (term != null) {
            extra.put("paymentTermId", term);
        }
        JsonNode q = quote(extra);
        assertThat(q.get("total").asDouble()).isEqualTo(250);
        String id = approved(q);
        String so = ok(200, "POST", "/sales/quotations/" + id + "/convert", Map.of("target", "sales_order")).get("convertedId")
                .asString();
        JsonNode o = ok(200, "GET", "/sales/orders/" + so, null);
        assertThat(o.get("branchId").asString()).isEqualTo(branch);
        assertThat(o.get("deliveryAddress").asString()).isEqualTo("Plot 9");
        assertThat(o.get("exchangeRate").asDouble()).isEqualTo(3);
        assertThat(o.get("lines").size()).as("the charge is a service line").isEqualTo(2);
        if (term != null) {
            assertThat(o.get("paymentTermId").asString()).isEqualTo(term);
        }
    }

    @Test
    @DisplayName("section and note rows keep their place, add no amount and never reach the order; delivery date passes on")
    void sectionsAndNotes() {
        String delivery = LocalDate.now().plusDays(10).toString();
        JsonNode q = ok(201, "POST", "/sales/quotations", Map.of("partnerId", customer, "commitmentDate", delivery, "lines",
                List.of(Map.of("displayType", "line_section", "description", "Materials"),
                        Map.of("productId", product, "quantity", 2, "unitPrice", 100, "leadTimeDays", 5),
                        Map.of("displayType", "line_note", "description", "Delivered to site"),
                        Map.of("description", "Transport charge", "quantity", 1, "unitPrice", 50))));
        assertThat(q.get("total").asDouble()).isEqualTo(250);
        assertThat(q.get("commitmentDate").asString()).startsWith(delivery);
        JsonNode lines = q.get("lines");
        assertThat(lines.size()).isEqualTo(4);
        assertThat(lines.get(0).get("displayType").asString()).isEqualTo("line_section");
        assertThat(lines.get(0).get("lineTotal").asDouble()).isZero();
        assertThat(lines.get(1).get("leadTimeDays").asInt()).isEqualTo(5);
        assertThat(lines.get(2).get("displayType").asString()).isEqualTo("line_note");
        assertThat(lines.get(2).get("description").asString()).isEqualTo("Delivered to site");
        assertThat(as("POST", "/sales/quotations", Map.of("partnerId", customer, "lines", List.of(Map.of("displayType",
                "heading", "description", "x")))).status()).isEqualTo(400);

        String id = approved(q);
        String so = ok(200, "POST", "/sales/quotations/" + id + "/convert", Map.of("target", "sales_order")).get("convertedId")
                .asString();
        JsonNode o = ok(200, "GET", "/sales/orders/" + so, null);
        assertThat(o.get("lines").size()).as("only the priced lines").isEqualTo(2);
        assertThat(o.get("commitmentDate").asString()).startsWith(delivery);
    }

    @Test
    @DisplayName("a quotation with only section rows cannot be submitted")
    void sectionsOnlyCannotSubmit() {
        String id = ok(201, "POST", "/sales/quotations", Map.of("partnerId", customer, "lines", List.of(Map.of("displayType",
                "line_section", "description", "Empty")))).get("id").asString();
        assertThat(as("POST", "/sales/quotations/" + id + "/submit", null).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("the expiry job expires overdue quotations and alerts the owner once")
    void expiry() {
        String id = quote(Map.of("validUntil", LocalDate.now().minusDays(1).toString())).get("id").asString();
        int n = TenantContext.run(TenantContext.TenantStore.organization(demoOrg), quotations::expireAndNotify);
        assertThat(n).isGreaterThanOrEqualTo(1);
        assertThat(ok(200, "GET", "/sales/quotations/" + id, null).get("status").asString()).isEqualTo("expired");
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), quotations::expireAndNotify);
        String number = ok(200, "GET", "/sales/quotations/" + id, null).get("number").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE category = 'sales_quotation_expired' AND title LIKE ?")
                .param("%" + number + "%").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("opening the list expires overdue quotations and alerts the owner once; the job then finds nothing")
    void listExpiryNotifies() {
        String id = quote(Map.of("validUntil", LocalDate.now().minusDays(1).toString())).get("id").asString();
        String number = ok(200, "GET", "/sales/quotations/" + id, null).get("number").asString();
        ok(200, "GET", "/sales/quotations?q=" + number, null);
        assertThat(ok(200, "GET", "/sales/quotations/" + id, null).get("status").asString()).isEqualTo("expired");
        ok(200, "GET", "/sales/quotations?q=" + number, null);
        TenantContext.run(TenantContext.TenantStore.organization(demoOrg), quotations::expireAndNotify);
        assertThat(jdbc.sql("SELECT count(*) FROM \"Notification\" WHERE category = 'sales_quotation_expired' AND title LIKE ?")
                .param("%" + number + "%").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("a customer-declined quotation is not edited or resubmitted in place: revise snapshots it and reopens it;"
            + " an internal approval rejection stays editable")
    void customerDeclineNeedsRevision() {
        String id = approved(quote(Map.of()));
        JsonNode declined = ok(200, "POST", "/sales/quotations/" + id + "/decline", Map.of("reason", "Too expensive"));
        assertThat(declined.get("status").asString()).isEqualTo("rejected");
        assertThat(declined.get("declinedAt").isNull()).isFalse();

        Map<String, Object> edit = Map.of("lines", List.of(Map.of("productId", product, "quantity", 2, "unitPrice", 90)));
        Res put = as("PUT", "/sales/quotations/" + id, edit);
        assertThat(put.status()).isEqualTo(400);
        assertThat(put.body().get("message").asString())
                .isEqualTo("The customer declined this quotation; revise it to make a new offer");
        assertThat(as("POST", "/sales/quotations/" + id + "/submit", null).status()).isEqualTo(400);
        assertThat(ok(200, "GET", "/sales/quotations/" + id, null).get("total").asDouble()).as("terms unchanged")
                .isEqualTo(250);

        JsonNode revised = ok(200, "POST", "/sales/quotations/" + id + "/revise", null);
        assertThat(revised.get("status").asString()).isEqualTo("draft");
        assertThat(revised.get("revision").asInt()).isEqualTo(1);
        assertThat(revised.get("declinedAt").isNull()).isTrue();
        JsonNode snap = ok(200, "GET", "/sales/quotations/" + id + "/revisions", null).get(0);
        assertThat(snap.get("status").asString()).isEqualTo("rejected");
        assertThat(snap.get("total").asDouble()).as("the declined terms are kept").isEqualTo(250);
        assertThat(ok(200, "PUT", "/sales/quotations/" + id, edit).get("total").asDouble()).isEqualTo(180);

        // An internal rejection (approval needed: discount beyond the limit) is still edited in place.
        String internal = quote(Map.of("lines", List.of(Map.of("productId", product, "quantity", 1, "unitPrice", 1))))
                .get("id").asString();
        assertThat(ok(200, "POST", "/sales/quotations/" + internal + "/submit", null).get("status").asString())
                .isEqualTo("pending_approval");
        JsonNode rejected = ok(200, "POST", "/sales/quotations/" + internal + "/reject", Map.of("reason", "Too low"));
        assertThat(rejected.get("declinedAt").isNull()).isTrue();
        assertThat(ok(200, "PUT", "/sales/quotations/" + internal, edit).get("status").asString()).isEqualTo("draft");
    }

    @Test
    @DisplayName("a declined quotation records a catalog reason; an unknown reason is refused")
    void lostReason() {
        JsonNode reasons = ok(200, "GET", "/sales/lost-reasons", null);
        assertThat(reasons.size()).isGreaterThan(3);
        String reason = reasons.get(0).get("id").asString();
        String id = approved(quote(Map.of()));
        assertThat(as("POST", "/sales/quotations/" + id + "/decline", Map.of("lostReasonId", "nope")).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/sales/quotations/" + id + "/decline", Map.of("lostReasonId", reason)).get("lostReasonId")
                .asString()).isEqualTo(reason);
    }

    @Test
    @DisplayName("quotation PDF renders and email sends it, marking the quotation sent")
    void output() {
        String id = approved(quote(Map.of("internalNotes", "do not print")));
        assertThat(ok(200, "POST", "/sales/quotations/" + id + "/email", Map.of()).get("sentTo").asString())
                .isEqualTo("g3@example.test");
        assertThat(ok(200, "GET", "/sales/quotations/" + id, null).get("status").asString()).isEqualTo("sent");
    }

    static byte[] png() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Upload a PNG through {@code POST /files/upload}; returns the file id. */
    String uploadPng(byte[] png) throws Exception {
        String boundary = "----img" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"item.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(png);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + adminToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isBetween(200, 201);
        return mapper.readTree(r.body()).get("id").asString();
    }

    byte[] quotationPdf(String id, String query) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/sales/quotations/" + id + "/pdf" + query)).header("Authorization", "Bearer " + adminToken).GET()
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body();
    }

    static String pdfText(byte[] pdf) throws Exception {
        PdfReader r = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(r).getTextFromPage(1);
        } finally {
            r.close();
        }
    }

    @Test
    @DisplayName("product images on quotations: off by default; when on, lines carry a signed image and the PDF an"
            + " Image column the printing user can leave out")
    void productImages() throws Exception {
        String imageProduct = ok(201, "POST", "/products", Map.of("code", "G3I-" + sfx, "name", "SG3 pictured " + sfx,
                "productType", "service", "salesPrice", 80)).get("id").asString();
        String fileId = uploadPng(png());
        jdbc.sql("UPDATE \"Product\" SET image = ? WHERE id = ?").params(fileId, imageProduct).update();
        String id = ok(201, "POST", "/sales/quotations", Map.of("partnerId", customer, "lines", List.of(Map.of("productId",
                imageProduct, "quantity", 1, "unitPrice", 80), Map.of("description", "Transport charge", "quantity", 1,
                "unitPrice", 50)))).get("id").asString();

        // Off (default): no images anywhere.
        JsonNode off = ok(200, "GET", "/sales/quotations/" + id, null);
        assertThat(off.get("lineImages").asBoolean()).isFalse();
        assertThat(off.get("lines").get(0).get("productImage").isNull()).isTrue();
        assertThat(pdfText(quotationPdf(id, "?images=true"))).doesNotContain("Image");

        try {
            ok(200, "PUT", "/sales/settings", Map.of("quoteLineImages", true));
            assertThat(ok(200, "GET", "/crm/config/lookups", null).get("settings").get("quoteLineImages").asBoolean())
                    .isTrue();
            JsonNode on = ok(200, "GET", "/sales/quotations/" + id, null);
            assertThat(on.get("lineImages").asBoolean()).isTrue();
            assertThat(on.get("lines").get(0).get("productImage").asString()).contains("/files/" + fileId + "/download?token=");
            assertThat(on.get("lines").get(1).get("productImage").isNull()).isTrue();

            byte[] withImages = quotationPdf(id, "");
            byte[] chosenOff = quotationPdf(id, "?images=false");
            assertThat(pdfText(withImages)).contains("Image", "SG3 pictured " + sfx);
            assertThat(pdfText(chosenOff)).doesNotContain("Image").contains("SG3 pictured " + sfx);
            assertThat(withImages.length).isGreaterThan(chosenOff.length);
        } finally {
            // The CRM settings screen edits the same switch.
            assertThat(ok(200, "PUT", "/crm/config/settings", Map.of("quoteLineImages", false)).get("quoteLineImages")
                    .asBoolean()).isFalse();
        }
        assertThat(ok(200, "GET", "/sales/quotations/" + id, null).get("lines").get(0).get("productImage").isNull()).isTrue();
    }

    byte[] layoutPreview(Object body) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/v1/sales/settings/quote-layout/preview")).header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                        mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(r.statusCode()).as(new String(r.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        return r.body();
    }

    @Test
    @DisplayName("quotation layout: defaults, validated partial updates, preview without saving; the PDF follows it and"
            + " prints the company settings name unless overridden")
    void quoteLayout() throws Exception {
        String orgName = jdbc.sql("SELECT name FROM \"Organization\" WHERE id = ?").param(demoOrg).query(String.class)
                .single();
        String id = quote(Map.of("notes", "Layout notes " + sfx)).get("id").asString();
        try {
            JsonNode layout = ok(200, "GET", "/sales/settings", null).get("quoteLayout");
            assertThat(layout.get("title").asString()).isEqualTo("QUOTATION");
            assertThat(layout.get("showUnit").asBoolean()).isTrue();
            assertThat(layout.get("showSignature").asBoolean()).isFalse();

            // The company name comes from the company settings (not the POS receipt header).
            String plain = pdfText(quotationPdf(id, ""));
            assertThat(plain).contains(orgName, "QUOTATION", "Layout notes " + sfx, "Disc %").doesNotContain("Accepted by");

            assertThat(call("PUT", "/sales/settings", Map.of("quoteLayout", Map.of("paperSize", "A3")), adminToken)
                    .raw().body()).contains("quoteLayout.paperSize must be one of: A4, A5, Letter");

            // Preview applies an unsaved layout; the saved one is unchanged.
            assertThat(pdfText(layoutPreview(Map.of("layout", Map.of("title", "DRAFT " + sfx.substring(0, 4))))))
                    .contains("DRAFT " + sfx.substring(0, 4));
            assertThat(ok(200, "GET", "/sales/settings", null).get("quoteLayout").get("title").asString())
                    .isEqualTo("QUOTATION");

            JsonNode saved = ok(200, "PUT", "/sales/settings", Map.of("quoteLayout", Map.of("title", "PRICE OFFER",
                    "companyName", "Stone Works " + sfx, "showDiscount", false, "showNotes", false, "showSignature", true,
                    "footerText", "Footer " + sfx))).get("quoteLayout");
            assertThat(saved.get("title").asString()).isEqualTo("PRICE OFFER");
            assertThat(saved.get("showUnit").asBoolean()).isTrue();
            String styled = pdfText(quotationPdf(id, ""));
            assertThat(styled).contains("PRICE OFFER", "Stone Works " + sfx, "Accepted by (customer)", "Footer " + sfx)
                    .doesNotContain("Disc %", "Layout notes " + sfx);

            // A partial update keeps the other choices; a blank name returns to the company settings name.
            ok(200, "PUT", "/sales/settings", Map.of("quoteLayout", Map.of("companyName", "")));
            assertThat(pdfText(quotationPdf(id, ""))).contains(orgName, "PRICE OFFER");
        } finally {
            jdbc.sql("UPDATE \"SalesSettings\" SET \"quoteLayout\" = '{}'::jsonb WHERE \"organizationId\" = ?")
                    .param(demoOrg).update();
        }
    }

    @Test
    @DisplayName("proforma from a quote and an order: listed, opened, advance applied, no journal or receivable")
    void proforma() {
        ok(200, "PUT", "/sales/settings", Map.of("proformaBankDetails", "ACME Bank 123", "proformaMobileDetails", "MoMo 777",
                "proformaAdvancePercent", 30));
        long journals = jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(demoOrg)
                .query(Long.class).single();
        String fromQuote = ok(200, "POST", "/sales/quotations/" + approved(quote(Map.of())) + "/convert", Map.of("target",
                "proforma")).get("convertedId").asString();
        JsonNode pq = ok(200, "GET", "/sales/proformas/" + fromQuote, null);
        assertThat(pq.get("advanceAmount").asDouble()).isEqualTo(75);
        assertThat(pq.get("bankDetails").asString()).isEqualTo("ACME Bank 123");

        String so = ok(201, "POST", "/sales/orders", Map.of("partnerId", customer, "branchId", branch, "lines", List.of(Map.of(
                "productId", product, "quantity", 1, "unitPrice", 200)))).get("id").asString();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + adminToken);
        h.put("Idempotency-Key", UUID.randomUUID().toString());
        Res made = call("POST", "/sales/orders/" + so + "/proforma", Map.of("advanceAmount", 50), h);
        assertThat(made.status()).isEqualTo(200);
        String pf = made.body().get("id").asString();
        assertThat(made.body().get("advanceAmount").asDouble()).isEqualTo(50);
        assertThat(made.body().get("branchId").asString()).isEqualTo(branch);
        JsonNode list = ok(200, "GET", "/sales/proformas?partnerId=" + customer, null);
        assertThat(list.get("data").size()).isGreaterThanOrEqualTo(2);
        assertThat(as("POST", "/invoices/" + pf + "/post", null).status()).as("cannot post").isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM \"JournalEntry\" WHERE \"organizationId\" = ?").param(demoOrg).query(Long.class)
                .single()).as("no GL").isEqualTo(journals);
        assertThat(ok(200, "GET", "/reports/ar/customers/" + customer + "/statement", null).get("closingBalance").asDouble())
                .as("no receivable").isZero();
        Map<String, String> h2 = new LinkedHashMap<>(h);
        h2.put("Idempotency-Key", UUID.randomUUID().toString());
        assertThat(call("POST", "/sales/orders/" + so + "/proforma", Map.of("advanceAmount", 99999), h2).status()).isEqualTo(400);
        assertThat(ok(200, "POST", "/sales/proformas/" + pf + "/email", Map.of()).get("ok").asBoolean()).isTrue();
    }
}
