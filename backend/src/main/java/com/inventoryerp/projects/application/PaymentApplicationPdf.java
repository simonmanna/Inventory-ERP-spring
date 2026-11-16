package com.inventoryerp.projects.application;

import java.io.ByteArrayOutputStream;
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

/**
 * Printable payment application (ADR-S075 §1, OpenPDF, A4 landscape): the G702-style summary (contract sum, completed
 * and stored, retainage, previous certificates, current payment due) and the G703-style continuation sheet per
 * schedule-of-values line. Rendered from {@link ContractSovService#paymentApplication}, so the PDF and the JSON never
 * disagree.
 */
final class PaymentApplicationPdf {

    private PaymentApplicationPdf() {}

    @SuppressWarnings("unchecked")
    static byte[] render(Map<String, Object> app, String company) {
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(pdf, out);
        pdf.open();
        pdf.add(new Paragraph(company == null ? "" : company, title));
        Paragraph heading = new Paragraph("APPLICATION AND CERTIFICATE FOR PAYMENT " + app.get("number"), bold);
        heading.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(heading);
        pdf.add(new Paragraph(" ", small));
        PdfPTable h = new PdfPTable(new float[] {1.4f, 3f, 1.4f, 3f});
        h.setWidthPercentage(100);
        pair(h, "Project", app.get("projectCode") + " — " + app.get("projectName"), bold, small);
        pair(h, "Customer", nz(app.get("customerName")), bold, small);
        pair(h, "Contract ref.", nz(app.get("contractReference")), bold, small);
        pair(h, "Application date", nz(app.get("claimDate")), bold, small);
        pair(h, "Status", nz(app.get("status")), bold, small);
        pair(h, "Billing", Boolean.TRUE.equals(app.get("lineLevel")) ? "Schedule of values" : "Lump sum", bold, small);
        pdf.add(h);
        pdf.add(new Paragraph(" ", small));
        Map<String, Object> s = (Map<String, Object>) app.get("summary");
        PdfPTable g = new PdfPTable(new float[] {0.4f, 4f, 1.6f});
        g.setWidthPercentage(55);
        g.setHorizontalAlignment(Element.ALIGN_LEFT);
        String[][] rows = {{"1", "Original contract sum", "originalContractSum"},
            {"2", "Net change by variations", "netChangeByVariations"},
            {"3", "Contract sum to date (1 + 2)", "contractSumToDate"},
            {"4", "Total completed and stored to date", "totalCompletedAndStored"},
            {"5", "Retainage", "retainage"},
            {"6", "Total earned less retainage (4 - 5)", "totalEarnedLessRetainage"},
            {"7", "Less previous certificates for payment", "lessPreviousCertificates"},
            {"8", "Current payment due (6 - 7)", "currentPaymentDue"},
            {"", "Less advance recovery", "advanceRecovery"},
            {"", "Net amount invoiced (before tax)", "netAmount"},
            {"9", "Balance to finish, including retainage (3 - 6)", "balanceToFinishIncludingRetainage"}};
        for (String[] r : rows) {
            g.addCell(cell(r[0], small, Element.ALIGN_LEFT));
            g.addCell(cell(r[1], "8".equals(r[0]) ? bold : small, Element.ALIGN_LEFT));
            g.addCell(cell(nz(s.get(r[2])), "8".equals(r[0]) ? bold : small, Element.ALIGN_RIGHT));
        }
        pdf.add(g);
        pdf.add(new Paragraph(" ", small));
        pdf.add(new Paragraph("CONTINUATION SHEET", bold));
        PdfPTable t = new PdfPTable(new float[] {0.8f, 3.2f, 1.3f, 1.3f, 1.3f, 1.3f, 1.4f, 0.7f, 1.3f, 1.2f});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String c : new String[] {"Item", "Description", "Scheduled value (C)", "Previous (D)", "This period (E)",
            "Stored (F)", "Completed & stored (G)", "%", "Balance (H)", "Retention"}) {
            t.addCell(cell(c, bold, Element.ALIGN_CENTER));
        }
        String[] keys = {"scheduledValue", "previousCompleted", "thisPeriod", "materialsStored", "totalCompleted",
            "percentComplete", "balanceToFinish", "retention"};
        for (Map<String, Object> l : (List<Map<String, Object>>) app.get("lines")) {
            t.addCell(cell(nz(l.get("code")), small, Element.ALIGN_LEFT));
            t.addCell(cell(nz(l.get("description")), small, Element.ALIGN_LEFT));
            for (String k : keys) {
                t.addCell(cell(nz(l.get(k)), small, Element.ALIGN_RIGHT));
            }
        }
        Map<String, Object> totals = (Map<String, Object>) app.get("totals");
        t.addCell(cell("", bold, Element.ALIGN_LEFT));
        t.addCell(cell("Totals", bold, Element.ALIGN_LEFT));
        for (String k : keys) {
            t.addCell(cell(nz(totals.get(k)), bold, Element.ALIGN_RIGHT));
        }
        pdf.add(t);
        pdf.close();
        return out.toByteArray();
    }

    private static void pair(PdfPTable t, String k, String v, Font kf, Font vf) {
        t.addCell(cell(k, kf, Element.ALIGN_LEFT));
        t.addCell(cell(v, vf, Element.ALIGN_LEFT));
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        return c;
    }

    private static String nz(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
