package com.inventoryerp.sales.application;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
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

/**
 * Printable A4 delivery note of a document with a single handover (sales invoice, branch cash sale): letterhead (logo,
 * business name and address), delivery address, note number, source document, shipping / scheduled / deadline dates,
 * transport, and per product the ordered and delivered quantities (returned too, once anything came back), then the
 * receipt or blank signature lines.
 * Sales order deliveries print the trip delivery note instead ({@code FleetDeliveryNoteService}).
 */
public final class SalesDeliveryNotePdf {

    /** One product row. {@code returned} null or zero on every line hides the Returned column. */
    public record Line(String product, String uom, BigDecimal ordered, BigDecimal delivered, BigDecimal returned) {}

    /** Everything printed on the note; null values are left out. */
    public record Note(SalesLetterhead.Head head, String number, String sourceLabel, String sourceNumber, String customer,
            String address, String contact, String method, String shippingDate, String scheduledDate, String deadline,
            String driver, String vehicle, String receivedBy, String deliveredAt, String notes, List<Line> lines) {}

    public record Pdf(String filename, byte[] content) {}

    private SalesDeliveryNotePdf() {}

    public static Pdf render(Note n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 45, 45, 45, 45);
        try {
            PdfWriter.getInstance(pdf, out);
            pdf.open();
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 10);
            Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font gray = FontFactory.getFont(FontFactory.HELVETICA, 9, java.awt.Color.GRAY);
            pdf.add(letterhead(n.head(), body));
            pdf.add(new Paragraph(" "));

            PdfPTable to = new PdfPTable(new float[] {1f, 1f});
            to.setWidthPercentage(100);
            to.addCell(plain("", body));
            StringBuilder addr = new StringBuilder();
            append(addr, n.customer());
            append(addr, n.address());
            append(addr, n.contact());
            PdfPCell deliverTo = plain("", body);
            deliverTo.addElement(new Phrase("Delivery Address", bold));
            deliverTo.addElement(new Phrase(addr.isEmpty() ? "—" : addr.toString(), body));
            to.addCell(deliverTo);
            pdf.add(to);
            pdf.add(new Paragraph(" "));

            pdf.add(new Paragraph("Delivery Note " + s(n.number()), FontFactory.getFont(FontFactory.HELVETICA, 20)));
            pdf.add(new Paragraph(" "));
            List<String[]> facts = new ArrayList<>();
            fact(facts, n.sourceLabel(), n.sourceNumber());
            fact(facts, "Shipping Date", n.shippingDate());
            fact(facts, "Scheduled Date", n.scheduledDate());
            fact(facts, "Deadline", n.deadline());
            fact(facts, "Method", n.method());
            fact(facts, "Driver", n.driver());
            fact(facts, "Vehicle", n.vehicle());
            if (!facts.isEmpty()) {
                int cols = Math.min(4, facts.size());
                PdfPTable f = new PdfPTable(cols);
                f.setWidthPercentage(100);
                for (String[] kv : facts) {
                    PdfPCell c = plain("", body);
                    c.addElement(new Phrase(kv[0], bold));
                    c.addElement(new Phrase(kv[1], body));
                    f.addCell(c);
                }
                for (int i = facts.size() % cols; i > 0 && i < cols; i++) {
                    f.addCell(plain("", body));
                }
                pdf.add(f);
                pdf.add(new Paragraph(" "));
            }

            boolean returns = n.lines().stream().anyMatch(l -> l.returned() != null && l.returned().signum() > 0);
            PdfPTable t = returns ? new PdfPTable(new float[] {5f, 1.4f, 1.4f, 1.4f})
                    : new PdfPTable(new float[] {5f, 1.4f, 1.4f});
            t.setWidthPercentage(100);
            t.addCell(head("Product", bold, Element.ALIGN_LEFT));
            t.addCell(head("Ordered", bold, Element.ALIGN_RIGHT));
            t.addCell(head("Delivered", bold, Element.ALIGN_RIGHT));
            if (returns) {
                t.addCell(head("Returned", bold, Element.ALIGN_RIGHT));
            }
            for (Line l : n.lines()) {
                String unit = l.uom() == null ? "" : " " + l.uom();
                t.addCell(row(s(l.product()), body, Element.ALIGN_LEFT));
                t.addCell(row(num(l.ordered()) + unit, body, Element.ALIGN_RIGHT));
                t.addCell(row(num(l.delivered()) + unit, body, Element.ALIGN_RIGHT));
                if (returns) {
                    t.addCell(row(num(l.returned()) + unit, body, Element.ALIGN_RIGHT));
                }
            }
            pdf.add(t);
            pdf.add(new Paragraph(" "));
            if (n.receivedBy() != null) {
                pdf.add(new Paragraph("Received by " + n.receivedBy()
                        + (n.deliveredAt() == null ? "" : " on " + n.deliveredAt()), body));
            } else {
                pdf.add(new Paragraph(" "));
                pdf.add(new Paragraph("Received in good order by: ______________________   Signature: ______________"
                        + "   Date: ______________", body));
                pdf.add(new Paragraph(" "));
                pdf.add(new Paragraph("Delivered by: ______________________", body));
            }
            if (n.notes() != null && !n.notes().isBlank()) {
                pdf.add(new Paragraph(" "));
                pdf.add(new Paragraph("Notes: " + n.notes(), gray));
            }
            pdf.close();
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
        return new Pdf(s(n.number()).replace('/', '-') + ".pdf", out.toByteArray());
    }

    /** Logo (when uploaded) on the left, business name and address lines beside it. */
    private static PdfPTable letterhead(SalesLetterhead.Head head, Font body) {
        Image logo = image(head == null ? null : head.logo());
        PdfPTable t = logo == null ? new PdfPTable(1) : new PdfPTable(new float[] {1.3f, 2.7f});
        t.setWidthPercentage(100);
        if (logo != null) {
            logo.scaleToFit(150, 60);
            PdfPCell c = new PdfPCell(logo, false);
            c.setBorder(Rectangle.NO_BORDER);
            c.setVerticalAlignment(Element.ALIGN_TOP);
            t.addCell(c);
        }
        PdfPCell who = plain("", body);
        who.addElement(new Phrase(head == null ? "" : s(head.name()), FontFactory.getFont(FontFactory.HELVETICA, 16)));
        if (head != null) {
            for (String line : head.lines()) {
                who.addElement(new Phrase(line, body));
            }
        }
        t.addCell(who);
        return t;
    }

    /** PNG / JPEG logo, or {@code null} when absent or unreadable (the note renders without it). */
    private static Image image(byte[] logo) {
        if (logo == null || logo.length == 0) {
            return null;
        }
        try {
            return Image.getInstance(logo);
        } catch (Exception e) {
            return null;
        }
    }

    private static void append(StringBuilder b, String v) {
        if (v != null && !v.isBlank()) {
            b.append(b.isEmpty() ? "" : "\n").append(v);
        }
    }

    private static void fact(List<String[]> facts, String label, String value) {
        if (label != null && value != null && !value.isBlank()) {
            facts.add(new String[] {label, value});
        }
    }

    private static PdfPCell plain(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(2);
        return c;
    }

    private static PdfPCell head(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.BOTTOM);
        c.setHorizontalAlignment(align);
        c.setPaddingBottom(6);
        return c;
    }

    private static PdfPCell row(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(java.awt.Color.LIGHT_GRAY);
        c.setHorizontalAlignment(align);
        c.setPadding(5);
        return c;
    }

    private static String s(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String num(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
