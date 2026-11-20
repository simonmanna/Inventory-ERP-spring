package com.inventoryerp.invoicing.application;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/** A5 receipt for a customer deposit / advance (pure rendering, no database access). */
final class CustomerDepositReceiptPdf {

    /** Business header from the POS receipt header (fallback when no branding provider is present). */
    record Header(String businessName, List<String> lines) {}

    /** One row of the deposit's history (application or refund). */
    record HistoryRow(String date, String description, BigDecimal amount, boolean reversed) {}

    /** Everything printed on the receipt. */
    record Receipt(DepositReceiptBranding.Branding branding, String number, String receivedDate, String status, String customerName,
            String customerCode, String customerTaxNumber, String salesOrderNumber, String source, String method,
            String reference, String notes, String currency, BigDecimal amount, BigDecimal applied, BigDecimal refunded,
            BigDecimal available, String receivedBy, List<HistoryRow> history, String exchangeNote) {}

    private CustomerDepositReceiptPdf() {}

    static byte[] render(Receipt r) {
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font muted = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);
        Font big = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A5, 30, 30, 30, 30);
        PdfWriter.getInstance(pdf, out);
        pdf.open();

        DepositReceiptBranding.Branding b = r.branding();
        if (b.logo() != null) {
            try {
                Image logo = Image.getInstance(b.logo());
                logo.scaleToFit(120, 50);
                logo.setAlignment(Element.ALIGN_CENTER);
                pdf.add(logo);
            } catch (Exception e) {
                // unreadable logo: print without it
            }
        }
        if (!blank(b.name())) {
            Paragraph company = new Paragraph(b.name(), title);
            company.setAlignment(Element.ALIGN_CENTER);
            pdf.add(company);
        }
        for (String line : b.lines()) {
            Paragraph p = new Paragraph(line, small);
            p.setAlignment(Element.ALIGN_CENTER);
            pdf.add(p);
        }
        String docTitle = blank(b.title()) ? "DEPOSIT RECEIPT" : b.title();
        Paragraph heading = new Paragraph("void".equals(r.status()) ? docTitle + " — VOID" : docTitle,
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, "void".equals(r.status()) ? Color.RED : Color.BLACK));
        heading.setAlignment(Element.ALIGN_CENTER);
        heading.setSpacingBefore(10);
        heading.setSpacingAfter(10);
        pdf.add(heading);

        PdfPTable facts = new PdfPTable(new float[] {1.3f, 2.7f});
        facts.setWidthPercentage(100);
        fact(facts, "Receipt no.", r.number(), bold, small);
        fact(facts, "Date", r.receivedDate(), bold, small);
        String customer = r.customerName() + (blank(r.customerCode()) ? "" : " (" + r.customerCode() + ")");
        fact(facts, "Received from", customer, bold, small);
        if (!blank(r.customerTaxNumber())) {
            fact(facts, "Customer TIN", r.customerTaxNumber(), bold, small);
        }
        if (!blank(r.salesOrderNumber())) {
            fact(facts, "For sales order", r.salesOrderNumber(), bold, small);
        }
        fact(facts, "Type", r.source(), bold, small);
        if (!blank(r.method())) {
            fact(facts, "Payment method", r.method(), bold, small);
        }
        if (!blank(r.reference())) {
            fact(facts, "Reference", r.reference(), bold, small);
        }
        if (!blank(r.exchangeNote())) {
            fact(facts, "Exchange rate", r.exchangeNote(), bold, small);
        }
        pdf.add(facts);

        PdfPTable amount = new PdfPTable(1);
        amount.setWidthPercentage(100);
        amount.setSpacingBefore(10);
        PdfPCell box = new PdfPCell(new Phrase("Amount received: " + r.currency() + " " + money(r.amount()), big));
        box.setHorizontalAlignment(Element.ALIGN_CENTER);
        box.setPadding(8);
        box.setBackgroundColor(new Color(240, 240, 240));
        amount.addCell(box);
        pdf.add(amount);

        PdfPTable balance = new PdfPTable(new float[] {1, 1, 1});
        balance.setWidthPercentage(100);
        balance.setSpacingBefore(6);
        for (String h : List.of("Applied", "Refunded", "Available")) {
            balance.addCell(cell(h, bold, Element.ALIGN_CENTER, true));
        }
        balance.addCell(cell(money(r.applied()), small, Element.ALIGN_CENTER, true));
        balance.addCell(cell(money(r.refunded()), small, Element.ALIGN_CENTER, true));
        balance.addCell(cell(money(r.available()), bold, Element.ALIGN_CENTER, true));
        pdf.add(balance);

        if (!r.history().isEmpty()) {
            Paragraph hh = new Paragraph("History", bold);
            hh.setSpacingBefore(8);
            pdf.add(hh);
            PdfPTable t = new PdfPTable(new float[] {1.1f, 2.6f, 1.3f});
            t.setWidthPercentage(100);
            for (HistoryRow h : r.history()) {
                Font f = h.reversed() ? muted : small;
                t.addCell(cell(h.date(), f, Element.ALIGN_LEFT, false));
                t.addCell(cell(h.description() + (h.reversed() ? " (reversed)" : ""), f, Element.ALIGN_LEFT, false));
                t.addCell(cell(money(h.amount()), f, Element.ALIGN_RIGHT, false));
            }
            pdf.add(t);
        }
        if (!blank(r.notes())) {
            Paragraph n = new Paragraph("Notes: " + r.notes(), small);
            n.setSpacingBefore(6);
            pdf.add(n);
        }

        if (b.showSignature()) {
            Paragraph sign = new Paragraph("Received by: " + (blank(r.receivedBy()) ? "____________________"
                    : r.receivedBy()) + "          Signature: ____________________", small);
            sign.setSpacingBefore(18);
            pdf.add(sign);
        }
        if (!blank(b.footer())) {
            Paragraph footer = new Paragraph(b.footer(), muted);
            footer.setSpacingBefore(10);
            pdf.add(footer);
        }
        pdf.close();
        return out.toByteArray();
    }

    private static void fact(PdfPTable t, String label, String value, Font bold, Font small) {
        t.addCell(cell(label, bold, Element.ALIGN_LEFT, false));
        t.addCell(cell(value, small, Element.ALIGN_LEFT, false));
    }

    private static PdfPCell cell(String text, Font font, int align, boolean border) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        if (!border) {
            c.setBorder(Rectangle.NO_BORDER);
        }
        return c;
    }

    static String money(BigDecimal v) {
        DecimalFormat f = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return f.format((v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
