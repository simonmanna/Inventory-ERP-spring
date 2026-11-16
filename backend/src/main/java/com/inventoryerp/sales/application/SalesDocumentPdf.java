package com.inventoryerp.sales.application;

import com.inventoryerp.core.application.ProductSizingService;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Plain A4 PDF of a customer-facing sales document (quotation, proforma, credit note, statement). */
public final class SalesDocumentPdf {

    /** One row of the money block at the bottom. */
    public record Total(String label, BigDecimal amount) {}

    /** A titled text block after the totals: terms, bank details and so on. */
    public record Block(String title, String text) {}

    /** Image column thumbnail size, in points. */
    private static final float THUMB = 48;

    private SalesDocumentPdf() {}

    /**
     * @param lines rows with {@code description}, {@code quantity}, {@code unitPrice}, {@code discountPercent},
     *     {@code total}; values may be null
     */
    public static byte[] render(String company, String title, String number, List<String> facts,
            List<Map<String, Object>> lines, String currency, List<Total> totals, List<Block> blocks) {
        return render(company, title, number, facts, lines, currency, totals, blocks, false);
    }

    /**
     * As {@link #render(String, String, String, List, List, String, List, List)}, with an Image column first when
     * {@code images} is set: each line's {@code imageBytes} (PNG, JPEG, GIF…) as a thumbnail, blank when missing or
     * unreadable.
     */
    public static byte[] render(String company, String title, String number, List<String> facts,
            List<Map<String, Object>> lines, String currency, List<Total> totals, List<Block> blocks, boolean images) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 50, 50, 50, 50);
        try {
            PdfWriter.getInstance(pdf, out);
            pdf.open();
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 10);
            Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            pdf.add(new Paragraph(company == null ? "" : company, FontFactory.getFont(FontFactory.HELVETICA, 18)));
            Paragraph heading = new Paragraph(title + " " + number, FontFactory.getFont(FontFactory.HELVETICA, 14));
            heading.setAlignment(Element.ALIGN_RIGHT);
            pdf.add(heading);
            for (String f : facts) {
                pdf.add(new Paragraph(f, body));
            }
            pdf.add(new Paragraph(" "));
            if (!lines.isEmpty()) {
                PdfPTable t = new PdfPTable(images ? new float[] {1.4f, 5, 1.2f, 1.6f, 1, 1.8f}
                        : new float[] {5, 1.2f, 1.6f, 1, 1.8f});
                t.setWidthPercentage(100);
                for (String h : images ? List.of("Image", "Description", "Qty", "Unit price", "Disc %", "Amount")
                        : List.of("Description", "Qty", "Unit price", "Disc %", "Amount")) {
                    t.addCell(new PdfPCell(new Phrase(h, bold)));
                }
                for (Map<String, Object> l : lines) {
                    if (images) {
                        t.addCell(imageCell(l.get("imageBytes") instanceof byte[] b ? b : null));
                    }
                    // ADR-S066: a line sold by size prints its pieces and dimensions under the description
                    String size = ProductSizingService.label(ProductSizingService.Sizing.stored(l),
                            l.get("sizingUnit") == null ? null : String.valueOf(l.get("sizingUnit")));
                    t.addCell(new PdfPCell(new Phrase(String.valueOf(l.get("description")) + (size == null ? ""
                            : "\n" + size), body)));
                    t.addCell(right(num(l.get("quantity"), 3), body));
                    t.addCell(right(num(l.get("unitPrice"), 2), body));
                    t.addCell(right(num(l.get("discountPercent"), 2), body));
                    t.addCell(right(num(l.get("total"), 2), body));
                }
                pdf.add(t);
                pdf.add(new Paragraph(" "));
            }
            PdfPTable m = new PdfPTable(new float[] {3, 2});
            m.setWidthPercentage(45);
            m.setHorizontalAlignment(Element.ALIGN_RIGHT);
            for (Total r : totals) {
                m.addCell(new PdfPCell(new Phrase(r.label(), bold)));
                m.addCell(right((currency == null ? "" : currency + " ") + num(r.amount(), 2), body));
            }
            pdf.add(m);
            for (Block b : blocks) {
                if (b.text() != null && !b.text().isBlank()) {
                    pdf.add(new Paragraph(" "));
                    pdf.add(new Paragraph(b.title(), bold));
                    pdf.add(new Paragraph(b.text(), body));
                }
            }
            pdf.close();
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** A thumbnail of at most {@link #THUMB} points square; an empty cell when there is no readable picture. */
    private static PdfPCell imageCell(byte[] bytes) {
        if (bytes != null && bytes.length > 0) {
            try {
                Image img = Image.getInstance(bytes);
                img.scaleToFit(THUMB, THUMB);
                PdfPCell c = new PdfPCell(img, false);
                c.setPadding(3);
                c.setHorizontalAlignment(Element.ALIGN_CENTER);
                c.setVerticalAlignment(Element.ALIGN_MIDDLE);
                return c;
            } catch (Exception e) {
                // unsupported format (e.g. WebP) or a damaged file: leave the cell blank
            }
        }
        return new PdfPCell(new Phrase(""));
    }

    private static PdfPCell right(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private static String num(Object v, int scale) {
        return v == null ? "" : new BigDecimal(v.toString()).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }
}
