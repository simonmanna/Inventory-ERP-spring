package com.inventoryerp.invoicing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

class CustomerDepositReceiptPdfTest {

    private static final DepositReceiptBranding.Branding BRAND = new DepositReceiptBranding.Branding(
            "DEPOSIT RECEIPT", "Demo Trading Ltd", List.of("Plot 1 Kampala Road", "TIN: 1000123456"), null,
            CustomerDepositService.DEFAULT_FOOTER, true);

    private static CustomerDepositReceiptPdf.Receipt receipt(String status) {
        return receipt(status, BRAND);
    }

    private static CustomerDepositReceiptPdf.Receipt receipt(String status, DepositReceiptBranding.Branding brand) {
        return new CustomerDepositReceiptPdf.Receipt(brand, "DEP-2026-00042", "2026-11-19", status,
                "Acme Builders", "C-001", "1009988776", "SO-2026-00007", "Advance / deposit", "Bank transfer", "TRX-77",
                "Advance for blocks", "UGX", new BigDecimal("30000000"), new BigDecimal("12000000"),
                new BigDecimal("5000000"), new BigDecimal("13000000"), "Ann Admin", List.of(
                        new CustomerDepositReceiptPdf.HistoryRow("2026-11-20", "Applied to invoice INV-2026-00010",
                                new BigDecimal("12000000"), false),
                        new CustomerDepositReceiptPdf.HistoryRow("2026-11-21", "Refund", new BigDecimal("5000000"),
                                false)), null);
    }

    private static String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        StringBuilder s = new StringBuilder();
        for (int p = 1; p <= reader.getNumberOfPages(); p++) {
            s.append(new PdfTextExtractor(reader).getTextFromPage(p)).append('\n');
        }
        return s.toString();
    }

    @Test
    void printsHeaderCustomerAmountsAndHistory() throws Exception {
        String t = text(CustomerDepositReceiptPdf.render(receipt("open")));
        assertThat(t).contains("Demo Trading Ltd", "TIN: 1000123456", "DEPOSIT RECEIPT", "DEP-2026-00042",
                "Acme Builders (C-001)", "1009988776", "SO-2026-00007", "Bank transfer", "TRX-77",
                "UGX 30,000,000", "12,000,000", "13,000,000", "INV-2026-00010", "Refund", "Ann Admin",
                "It is not a tax invoice");
        assertThat(t).doesNotContain("VOID");
    }

    @Test
    void voidDepositIsMarked() throws Exception {
        assertThat(text(CustomerDepositReceiptPdf.render(receipt("void")))).contains("DEPOSIT RECEIPT — VOID");
    }

    @Test
    void customBrandingTitleNoSignatureNoFooter() throws Exception {
        String t = text(CustomerDepositReceiptPdf.render(receipt("void", new DepositReceiptBranding.Branding(
                "ADVANCE PAYMENT RECEIPT", "Granite Quarries Uganda Ltd", List.of("P.O. Box 1, Mukono"), null, "",
                false))));
        assertThat(t).contains("Granite Quarries Uganda Ltd", "P.O. Box 1, Mukono", "ADVANCE PAYMENT RECEIPT — VOID")
                .doesNotContain("Demo Trading Ltd", "Signature", "It is not a tax invoice");
    }

    @Test
    void moneyFormatting() {
        assertThat(CustomerDepositReceiptPdf.money(new BigDecimal("1234567.505"))).isEqualTo("1,234,567.51");
        assertThat(CustomerDepositReceiptPdf.money(null)).isEqualTo("0");
    }
}
