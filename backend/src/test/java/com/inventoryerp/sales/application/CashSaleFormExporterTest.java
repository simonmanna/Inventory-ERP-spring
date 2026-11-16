package com.inventoryerp.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

/** The cash sale Excel form follows the sample "CASH SALES FORM" layout and its totals calculate. */
class CashSaleFormExporterTest {

    @Test
    void rendersTheCashSalesFormLayoutWithLiveTotals() throws Exception {
        Map<String, Object> sale = Map.of("number", "CS-KAB-0001", "saleDate", "2026-10-29", "customerName", "Franc",
                "branchName", "Kabalagala", "taxTotal", BigDecimal.ZERO);
        List<Map<String, Object>> lines = List.of(
                Map.of("productName", "Granite", "variantName", "Royal red", "uomCode", "Piece", "quantity",
                        new BigDecimal("2.8"), "unitPrice", new BigDecimal("75000"), "discountPercent", BigDecimal.ZERO),
                Map.of("productName", "Granite", "description", "skirting", "uomCode", "m", "quantity",
                        new BigDecimal("0.504"), "unitPrice", new BigDecimal("125000"), "discountPercent",
                        new BigDecimal("10")));

        byte[] bytes = CashSaleFormExporter.render(sale, lines, "Mena Granite Limited", null);
        String out = System.getenv("CASH_SALE_FORM_OUT");
        if (out != null) {
            Files.write(Path.of(out), bytes);
        }

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getRow(2).getCell(0).getStringCellValue()).isEqualTo("MENA GRANITE LIMITED");
            assertThat(sh.getRow(3).getCell(0).getStringCellValue()).isEqualTo("CASH SALES  FORM");
            assertThat(sh.getRow(6).getCell(0).getStringCellValue()).isEqualTo("DATE");
            assertThat(sh.getRow(10).getCell(1).getStringCellValue()).isEqualTo("Franc");
            assertThat(sh.getRow(11).getCell(1).getStringCellValue()).isEqualTo("KABALAGALA");
            assertThat(sh.getRow(12).getCell(1).getStringCellValue()).isEqualTo("CS-KAB-0001");
            assertThat(sh.getRow(15).getCell(6).getStringCellValue()).isEqualTo("Line Total");
            assertThat(sh.getRow(16).getCell(1).getStringCellValue()).isEqualTo("Royal red");
            assertThat(sh.getRow(16).getCell(6).getCellFormula()).isEqualTo("D17*E17");
            assertThat(sh.getRow(16).getCell(6).getNumericCellValue()).isEqualTo(210000.0);
            assertThat(sh.getRow(17).getCell(1).getStringCellValue()).isEqualTo("skirting");
            assertThat(sh.getRow(17).getCell(6).getNumericCellValue()).isEqualTo(56700.0);
            assertThat(sh.getRow(18).getCell(4).getStringCellValue()).isEqualTo("ORDER TOTAL");
            assertThat(sh.getRow(18).getCell(6).getCellFormula()).isEqualTo("SUM(G17:G18)");
            assertThat(sh.getRow(18).getCell(6).getNumericCellValue()).isEqualTo(266700.0);
            assertThat(sh.getRow(21).getCell(4).getStringCellValue()).isEqualTo("FINAL CASH RECEIVED");
            assertThat(sh.getRow(21).getCell(6).getNumericCellValue()).isEqualTo(266700.0);
        }
    }

    @Test
    void rendersTheSameFormAsPdf() throws Exception {
        Map<String, Object> sale = Map.of("number", "CS-KAB-0001", "saleDate", "2026-10-29", "customerName", "Franc",
                "branchName", "Kabalagala", "customerPhone", "0772000000", "taxTotal", new BigDecimal("1000"));
        List<Map<String, Object>> lines = List.of(
                Map.of("productName", "Granite", "variantName", "Royal red", "uomCode", "Piece", "quantity",
                        new BigDecimal("2.8"), "unitPrice", new BigDecimal("75000"), "discountPercent", BigDecimal.ZERO),
                Map.of("productName", "Granite", "description", "skirting", "uomCode", "m", "quantity",
                        new BigDecimal("0.504"), "unitPrice", new BigDecimal("125000"), "discountPercent",
                        new BigDecimal("10")));

        byte[] bytes = CashSaleFormPdf.render(sale, lines, "Mena Granite Limited", new byte[] {1, 2, 3});
        String out = System.getenv("CASH_SALE_PDF_OUT");
        if (out != null) {
            Files.write(Path.of(out), bytes);
        }

        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        PdfReader reader = new PdfReader(bytes);
        assertThat(reader.getNumberOfPages()).isEqualTo(1);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("MENA GRANITE LIMITED", "CASH SALES FORM", "29/10/2026", "Franc", "0772000000",
                "KABALAGALA", "CS-KAB-0001", "Royal red", "skirting", "210,000.00", "56,700.00", "ORDER TOTAL",
                "266,700.00", "FINAL CASH RECEIVED", "267,700.00");
        reader.close();
    }
}
