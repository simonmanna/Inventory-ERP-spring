package com.inventoryerp.hr.application;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/** A4 payslip and annual earnings certificate PDFs (HP-7 / HP-8), OpenPDF only. */
final class HrPayslipPdf {

    /** One printed line: label and amount. */
    record Row(String label, BigDecimal amount) {}

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font H = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font F = FontFactory.getFont(FontFactory.HELVETICA, 9);
    private static final Font G = FontFactory.getFont(FontFactory.HELVETICA, 8, java.awt.Color.GRAY);

    private HrPayslipPdf() {}

    static byte[] payslip(String orgName, String title, Map<String, String> header, List<Row> earnings,
            List<Row> deductions, List<Row> employer, BigDecimal gross, BigDecimal totalDeductions, BigDecimal net,
            List<Row> ytd, String currency, int scale, String note) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 40, 40, 40, 40);
        PdfWriter.getInstance(pdf, out);
        pdf.open();
        pdf.add(new Paragraph(orgName == null ? "" : orgName, TITLE));
        pdf.add(new Paragraph(title, H));
        pdf.add(new Paragraph(" "));
        PdfPTable head = new PdfPTable(4);
        head.setWidthPercentage(100);
        header.forEach((k, v) -> {
            head.addCell(cell(k, G, Element.ALIGN_LEFT));
            head.addCell(cell(v == null ? "" : v, F, Element.ALIGN_LEFT));
        });
        if (header.size() % 2 == 1) {
            head.addCell(cell("", F, Element.ALIGN_LEFT));
            head.addCell(cell("", F, Element.ALIGN_LEFT));
        }
        pdf.add(head);
        pdf.add(new Paragraph(" "));
        PdfPTable body = new PdfPTable(new float[] {3, 1.4f, 3, 1.4f});
        body.setWidthPercentage(100);
        body.addCell(cell("Earnings", H, Element.ALIGN_LEFT));
        body.addCell(cell(currency, H, Element.ALIGN_RIGHT));
        body.addCell(cell("Deductions", H, Element.ALIGN_LEFT));
        body.addCell(cell(currency, H, Element.ALIGN_RIGHT));
        int n = Math.max(earnings.size(), deductions.size());
        for (int i = 0; i < n; i++) {
            Row e = i < earnings.size() ? earnings.get(i) : null;
            Row d = i < deductions.size() ? deductions.get(i) : null;
            body.addCell(cell(e == null ? "" : e.label(), F, Element.ALIGN_LEFT));
            body.addCell(cell(e == null ? "" : money(e.amount(), scale), F, Element.ALIGN_RIGHT));
            body.addCell(cell(d == null ? "" : d.label(), F, Element.ALIGN_LEFT));
            body.addCell(cell(d == null ? "" : money(d.amount(), scale), F, Element.ALIGN_RIGHT));
        }
        body.addCell(cell("Gross pay", H, Element.ALIGN_LEFT));
        body.addCell(cell(money(gross, scale), H, Element.ALIGN_RIGHT));
        body.addCell(cell("Total deductions", H, Element.ALIGN_LEFT));
        body.addCell(cell(money(totalDeductions, scale), H, Element.ALIGN_RIGHT));
        pdf.add(body);
        pdf.add(new Paragraph(" "));
        Paragraph netP = new Paragraph("Net pay: " + currency + " " + money(net, scale), TITLE);
        netP.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(netP);
        if (!employer.isEmpty()) {
            pdf.add(new Paragraph(" "));
            pdf.add(new Paragraph("Employer contributions (not deducted from your pay)", H));
            for (Row r : employer) {
                pdf.add(new Paragraph(r.label() + ": " + money(r.amount(), scale), F));
            }
        }
        if (!ytd.isEmpty()) {
            pdf.add(new Paragraph(" "));
            pdf.add(new Paragraph("Year to date", H));
            for (Row r : ytd) {
                pdf.add(new Paragraph(r.label() + ": " + money(r.amount(), scale), F));
            }
        }
        if (note != null && !note.isBlank()) {
            pdf.add(new Paragraph(" "));
            pdf.add(new Paragraph(note, G));
        }
        pdf.close();
        return out.toByteArray();
    }

    static byte[] certificate(String orgName, String title, Map<String, String> header, List<Row> totals,
            String currency, int scale) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 40, 40, 40, 40);
        PdfWriter.getInstance(pdf, out);
        pdf.open();
        pdf.add(new Paragraph(orgName == null ? "" : orgName, TITLE));
        pdf.add(new Paragraph(title, H));
        pdf.add(new Paragraph(" "));
        header.forEach((k, v) -> pdf.add(new Paragraph(k + ": " + (v == null ? "" : v), F)));
        pdf.add(new Paragraph(" "));
        PdfPTable t = new PdfPTable(new float[] {4, 2});
        t.setWidthPercentage(70);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        for (Row r : totals) {
            t.addCell(cell(r.label(), F, Element.ALIGN_LEFT));
            t.addCell(cell(currency + " " + money(r.amount(), scale), F, Element.ALIGN_RIGHT));
        }
        pdf.add(t);
        pdf.add(new Paragraph(" "));
        pdf.add(new Paragraph("Issued from the payroll records of approved runs.", G));
        pdf.close();
        return out.toByteArray();
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setBorderWidth(0.3f);
        c.setPadding(4);
        return c;
    }

    static String money(BigDecimal v, int scale) {
        if (v == null) {
            return "";
        }
        java.text.DecimalFormat f = new java.text.DecimalFormat(scale == 0 ? "#,##0" : "#,##0." + "0".repeat(scale));
        return f.format(v.setScale(scale, RoundingMode.HALF_UP));
    }
}
