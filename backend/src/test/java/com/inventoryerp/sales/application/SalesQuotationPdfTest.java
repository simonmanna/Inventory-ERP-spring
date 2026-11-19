package com.inventoryerp.sales.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/** Quotation layout (Sales settings): stored JSON with defaults, strict validation, and the PDF following it. */
class SalesQuotationPdfTest {

    static SalesQuotationPdf.Quote quote() {
        Map<String, Object> section = new HashMap<>(Map.of("displayType", "line_section", "description", "Kitchen"));
        Map<String, Object> sized = new HashMap<>(Map.of("description", "Granite tile", "quantity", new BigDecimal("4.32"),
                "unit", "m²", "unitPrice", new BigDecimal("170000"), "discountPercent", new BigDecimal("5"), "tax",
                "VAT 18%", "total", new BigDecimal("697680"), "pieces", new BigDecimal("12"), "length",
                new BigDecimal("0.6"), "width", new BigDecimal("0.6")));
        sized.put("sizingUnit", "m");
        return new SalesQuotationPdf.Quote(new SalesQuotationPdf.Company("Mena Stone Works", List.of("Plot 5, Kampala",
                "Tel: 0700 000000"), null), "QT-2026-00009", List.of(new SalesQuotationPdf.Fact("Customer", "Acme Ltd"),
                        new SalesQuotationPdf.Fact("Salesperson", "Sarah")), List.of(section, sized), "UGX", List.of(
                                new SalesDocumentPdf.Total("Untaxed", new BigDecimal("697680")),
                                new SalesDocumentPdf.Total("Total", new BigDecimal("697680"))), "Deliver to site",
                "50% advance", false);
    }

    static String text(byte[] pdf) throws Exception {
        PdfReader r = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(r).getTextFromPage(1);
        } finally {
            r.close();
        }
    }

    @Test
    void storedLayoutFillsDefaultsAndIgnoresBadValues() {
        assertThat(QuoteLayout.of(null)).isEqualTo(QuoteLayout.DEFAULT);
        assertThat(QuoteLayout.of(Map.of())).isEqualTo(QuoteLayout.DEFAULT);
        QuoteLayout l = QuoteLayout.of(Map.of("title", "PRO FORMA QUOTE", "showTax", true, "accentColor", "red",
                "paperSize", "A3", "unknown", 1));
        assertThat(l.title()).isEqualTo("PRO FORMA QUOTE");
        assertThat(l.showTax()).isTrue();
        assertThat(l.accentColor()).isEqualTo("#1F2937");
        assertThat(l.paperSize()).isEqualTo("A4");
        assertThat(QuoteLayout.of(l.toMap())).isEqualTo(l);
        // A JSONB column is read back as a Jackson tree or as JSON text.
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        assertThat(QuoteLayout.of(mapper.valueToTree(l.toMap()))).isEqualTo(l);
        assertThat(QuoteLayout.of(mapper.writeValueAsString(l.toMap()))).isEqualTo(l);
    }

    @Test
    void validationMergesAndRejects() {
        QuoteLayout l = QuoteLayout.validate(Map.of("showDiscount", false, "title", "  ", "accentColor", "#0a7"),
                QuoteLayout.DEFAULT);
        assertThat(l.showDiscount()).isFalse();
        assertThat(l.title()).isEqualTo("QUOTATION");
        assertThat(l.accentColor()).isEqualTo("#0a7");
        assertThat(l.showUnit()).isTrue();
        assertThatThrownBy(() -> QuoteLayout.validate(Map.of("colour", "#000"), QuoteLayout.DEFAULT))
                .isInstanceOf(ApiException.class).hasMessage("quoteLayout.colour should not exist");
        assertThatThrownBy(() -> QuoteLayout.validate(Map.of("accentColor", "blue"), QuoteLayout.DEFAULT))
                .hasMessage("quoteLayout.accentColor must be a hex colour like #1F2937");
        assertThatThrownBy(() -> QuoteLayout.validate(Map.of("paperSize", "A3"), QuoteLayout.DEFAULT))
                .hasMessage("quoteLayout.paperSize must be one of: A4, A5, Letter");
        assertThatThrownBy(() -> QuoteLayout.validate(Map.of("showLogo", "yes"), QuoteLayout.DEFAULT))
                .hasMessage("quoteLayout.showLogo must be a boolean value");
        assertThatThrownBy(() -> QuoteLayout.validate(Map.of("title", "x".repeat(61)), QuoteLayout.DEFAULT))
                .hasMessage("quoteLayout.title must be at most 60 characters");
        assertThatThrownBy(() -> QuoteLayout.validate("x", QuoteLayout.DEFAULT))
                .hasMessage("quoteLayout must be an object");
    }

    @Test
    void defaultLayoutPrintsCompanySectionsSizeUnitAndDiscount() throws Exception {
        String t = text(SalesQuotationPdf.render(quote(), QuoteLayout.DEFAULT));
        assertThat(t).contains("Mena Stone Works", "Plot 5, Kampala", "QUOTATION", "QT-2026-00009", "Acme Ltd",
                "Salesperson", "Kitchen", "Granite tile", "12 pcs", "Unit", "m²", "Disc %", "5%", "697,680.00",
                "Deliver to site", "50% advance", "Page 1");
        assertThat(t).doesNotContain("VAT 18%", "Accepted by");
    }

    @Test
    void layoutHidesAndShowsParts() throws Exception {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("title", "PRICE OFFER");
        in.put("showCompanyDetails", false);
        in.put("showUnit", false);
        in.put("showDiscount", false);
        in.put("showTax", true);
        in.put("showSections", false);
        in.put("showSize", false);
        in.put("showNotes", false);
        in.put("showTerms", false);
        in.put("showSignature", true);
        in.put("headerText", "Thank you for your enquiry.");
        in.put("footerText", "Prices valid while stock lasts");
        in.put("paperSize", "A5");
        in.put("orientation", "landscape");
        String t = text(SalesQuotationPdf.render(quote(), QuoteLayout.validate(in, QuoteLayout.DEFAULT)));
        assertThat(t).contains("PRICE OFFER", "Mena Stone Works", "Thank you for your enquiry.", "Tax", "VAT 18%",
                "Prepared by", "Accepted by (customer)", "Prices valid while stock lasts");
        assertThat(t).doesNotContain("Plot 5, Kampala", "Disc %", "Kitchen", "12 pcs", "Deliver to site",
                "50% advance", "QUOTATION");
    }
}
