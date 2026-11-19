package com.inventoryerp.sales.application;

import com.inventoryerp.core.application.ProductSizingService;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;

/** Customer quotation PDF following the organization's {@link QuoteLayout} (Sales settings → Quotation layout). */
public final class SalesQuotationPdf {

    /** Letterhead: printed company name, address / contact lines and the logo (PNG / JPEG bytes or {@code null}). */
    public record Company(String name, List<String> lines, byte[] logo) {}

    /** A label / value shown under the title (date, valid until, customer, salesperson). */
    public record Fact(String label, String value) {}

    /**
     * What to print. {@code lines}: rows with {@code displayType} ({@code null}, {@code line_section},
     * {@code line_note}), {@code description}, {@code quantity}, {@code unit}, {@code unitPrice},
     * {@code discountPercent}, {@code tax}, {@code total}, the size columns and {@code imageBytes}.
     */
    public record Quote(Company company, String number, List<Fact> facts, List<Map<String, Object>> lines,
            String currency, List<SalesDocumentPdf.Total> totals, String notes, String terms, boolean images) {}

    private static final float THUMB = 48;
    private static final Color MUTED = new Color(100, 108, 120);
    private static final Color BAND = new Color(243, 244, 246);
    private static final Color RULE = new Color(209, 213, 219);

    private SalesQuotationPdf() {}

    public static byte[] render(Quote q, QuoteLayout layout) {
        Color accent = color(layout.accentColor());
        Rectangle size = switch (layout.paperSize()) {
            case "A5" -> PageSize.A5;
            case "Letter" -> PageSize.LETTER;
            default -> PageSize.A4;
        };
        if ("landscape".equals(layout.orientation())) {
            size = size.rotate();
        }
        boolean small = "A5".equals(layout.paperSize()) && !"landscape".equals(layout.orientation());
        float base = small ? 8 : 9.5f;
        Font body = FontFactory.getFont(FontFactory.HELVETICA, base);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, base);
        Font muted = FontFactory.getFont(FontFactory.HELVETICA, base - 1, MUTED);
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, base, Color.WHITE);
        Font italic = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, base, MUTED);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        float margin = small ? 28 : 40;
        Document pdf = new Document(size, margin, margin, margin, margin + (layout.footerText().isBlank() ? 10 : 24));
        try {
            PdfWriter writer = PdfWriter.getInstance(pdf, out);
            writer.setPageEvent(new Footer(layout.footerText(), muted));
            pdf.open();

            // Letterhead: logo + company on the left, title + facts on the right.
            PdfPTable top = new PdfPTable(new float[] {1.2f, 1});
            top.setWidthPercentage(100);
            PdfPCell left = plain();
            if (layout.showLogo() && q.company().logo() != null) {
                try {
                    Image logo = Image.getInstance(q.company().logo());
                    logo.scaleToFit(small ? 90 : 130, small ? 45 : 60);
                    left.addElement(logo);
                } catch (Exception e) {
                    // unreadable logo: print without it
                }
            }
            Paragraph name = new Paragraph(q.company().name(), FontFactory.getFont(FontFactory.HELVETICA_BOLD,
                    small ? 13 : 16, accent));
            name.setSpacingBefore(2);
            left.addElement(name);
            if (layout.showCompanyDetails()) {
                for (String l : q.company().lines()) {
                    left.addElement(new Paragraph(l, muted));
                }
            }
            top.addCell(left);
            PdfPCell right = plain();
            Paragraph title = new Paragraph(layout.title(), FontFactory.getFont(FontFactory.HELVETICA_BOLD,
                    small ? 15 : 20, accent));
            title.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(title);
            Paragraph number = new Paragraph(q.number(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, base + 2));
            number.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(number);
            for (Fact f : q.facts()) {
                if (f.value() == null || f.value().isBlank()) {
                    continue;
                }
                Paragraph p = new Paragraph();
                p.add(new Phrase(f.label() + ": ", muted));
                p.add(new Phrase(f.value(), body));
                p.setAlignment(Element.ALIGN_RIGHT);
                right.addElement(p);
            }
            top.addCell(right);
            pdf.add(top);
            pdf.add(rule(accent));

            if (!layout.headerText().isBlank()) {
                Paragraph h = new Paragraph(layout.headerText(), body);
                h.setSpacingAfter(6);
                pdf.add(h);
            }

            // Lines.
            List<String> cols = new ArrayList<>();
            List<Float> widths = new ArrayList<>();
            if (q.images()) {
                cols.add("Image");
                widths.add(1.3f);
            }
            cols.add("Description");
            widths.add(5f);
            cols.add("Qty");
            widths.add(1.2f);
            if (layout.showUnit()) {
                cols.add("Unit");
                widths.add(0.9f);
            }
            cols.add("Unit price");
            widths.add(1.6f);
            if (layout.showDiscount()) {
                cols.add("Disc %");
                widths.add(1.15f);
            }
            if (layout.showTax()) {
                cols.add("Tax");
                widths.add(1.2f);
            }
            cols.add("Amount");
            widths.add(1.8f);
            float[] w = new float[widths.size()];
            for (int i = 0; i < w.length; i++) {
                w[i] = widths.get(i);
            }
            PdfPTable t = new PdfPTable(w);
            t.setWidthPercentage(100);
            t.setHeaderRows(1);
            t.setSpacingBefore(4);
            for (String c : cols) {
                PdfPCell h = new PdfPCell(new Phrase(c, head));
                h.setBackgroundColor(accent);
                h.setBorderColor(accent);
                h.setPadding(4);
                h.setHorizontalAlignment(List.of("Description", "Image").contains(c) ? Element.ALIGN_LEFT
                        : Element.ALIGN_RIGHT);
                t.addCell(h);
            }
            for (Map<String, Object> l : q.lines()) {
                Object dt = l.get("displayType");
                if (dt != null) {
                    if (!layout.showSections()) {
                        continue;
                    }
                    boolean section = "line_section".equals(dt);
                    PdfPCell c = new PdfPCell(new Phrase(text(l.get("description")), section ? bold : italic));
                    c.setColspan(cols.size());
                    c.setPadding(4);
                    c.setBorderColor(RULE);
                    if (section) {
                        c.setBackgroundColor(BAND);
                    }
                    t.addCell(c);
                    continue;
                }
                if (q.images()) {
                    t.addCell(imageCell(l.get("imageBytes") instanceof byte[] b ? b : null));
                }
                String sizeLabel = layout.showSize() ? ProductSizingService.label(ProductSizingService.Sizing.stored(l),
                        l.get("sizingUnit") == null ? null : String.valueOf(l.get("sizingUnit"))) : null;
                Phrase d = new Phrase(text(l.get("description")), body);
                if (sizeLabel != null) {
                    d.add(new Phrase("\n" + sizeLabel, muted));
                }
                t.addCell(cell(d, Element.ALIGN_LEFT));
                t.addCell(cell(new Phrase(qty(l.get("quantity")), body), Element.ALIGN_RIGHT));
                if (layout.showUnit()) {
                    t.addCell(cell(new Phrase(text(l.get("unit")), body), Element.ALIGN_RIGHT));
                }
                t.addCell(cell(new Phrase(money(l.get("unitPrice")), body), Element.ALIGN_RIGHT));
                if (layout.showDiscount()) {
                    t.addCell(cell(new Phrase(disc(l.get("discountPercent")), body), Element.ALIGN_RIGHT));
                }
                if (layout.showTax()) {
                    t.addCell(cell(new Phrase(text(l.get("tax")), body), Element.ALIGN_RIGHT));
                }
                t.addCell(cell(new Phrase(money(l.get("total")), body), Element.ALIGN_RIGHT));
            }
            pdf.add(t);

            // Totals.
            PdfPTable m = new PdfPTable(new float[] {3, 2.4f});
            m.setWidthPercentage(small ? 60 : 45);
            m.setHorizontalAlignment(Element.ALIGN_RIGHT);
            m.setSpacingBefore(8);
            for (int i = 0; i < q.totals().size(); i++) {
                SalesDocumentPdf.Total r = q.totals().get(i);
                boolean last = i == q.totals().size() - 1;
                Font f = last ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, base + 1, last ? Color.WHITE : null)
                        : body;
                PdfPCell a = new PdfPCell(new Phrase(r.label(), last ? f : bold));
                PdfPCell b = new PdfPCell(new Phrase((q.currency() == null ? "" : q.currency() + " ")
                        + money(r.amount()), f));
                b.setHorizontalAlignment(Element.ALIGN_RIGHT);
                for (PdfPCell c : List.of(a, b)) {
                    c.setPadding(4);
                    c.setBorderColor(RULE);
                    if (last) {
                        c.setBackgroundColor(accent);
                        c.setBorderColor(accent);
                    }
                    m.addCell(c);
                }
            }
            pdf.add(m);

            block(pdf, "Notes", layout.showNotes() ? q.notes() : null, bold, body, accent);
            block(pdf, "Terms and conditions", layout.showTerms() ? q.terms() : null, bold, body, accent);

            if (layout.showSignature()) {
                PdfPTable s = new PdfPTable(new float[] {1, 0.2f, 1});
                s.setWidthPercentage(100);
                s.setSpacingBefore(36);
                s.setKeepTogether(true);
                for (String label : new String[] {"Prepared by", null, "Accepted by (customer)"}) {
                    PdfPCell c = plain();
                    if (label != null) {
                        c.setBorder(Rectangle.TOP);
                        c.setBorderColor(MUTED);
                        c.addElement(new Paragraph(label, bold));
                        c.addElement(new Paragraph("Name, signature and date", muted));
                    }
                    s.addCell(c);
                }
                pdf.add(s);
            }
            pdf.close();
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** Footer text and page number on every page. */
    private static final class Footer extends PdfPageEventHelper {
        private final String text;
        private final Font font;

        Footer(String text, Font font) {
            this.text = text;
            this.font = font;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            float x = (document.left() + document.right()) / 2;
            float y = document.bottom() - 14;
            if (!text.isBlank()) {
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER,
                        new Phrase(text.replace('\n', ' '), font), x, y, 0);
                y -= 11;
            }
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), font), document.right(), y, 0);
        }
    }

    private static void block(Document pdf, String title, String text, Font bold, Font body, Color accent)
            throws DocumentException {
        if (text == null || text.isBlank()) {
            return;
        }
        Paragraph h = new Paragraph(title, FontFactory.getFont(FontFactory.HELVETICA_BOLD, bold.getSize(), accent));
        h.setSpacingBefore(10);
        pdf.add(h);
        pdf.add(new Paragraph(text, body));
    }

    private static Paragraph rule(Color accent) {
        PdfPTable line = new PdfPTable(1);
        line.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(accent);
        c.setBorderWidth(1.5f);
        c.setFixedHeight(4);
        line.addCell(c);
        Paragraph p = new Paragraph();
        p.add(line);
        p.setSpacingAfter(8);
        return p;
    }

    private static PdfPCell plain() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private static PdfPCell cell(Phrase p, int align) {
        PdfPCell c = new PdfPCell(p);
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPadding(4);
        c.setBorderColor(RULE);
        return c;
    }

    private static PdfPCell imageCell(byte[] bytes) {
        if (bytes != null && bytes.length > 0) {
            try {
                Image img = Image.getInstance(bytes);
                img.scaleToFit(THUMB, THUMB);
                PdfPCell c = new PdfPCell(img, false);
                c.setPadding(3);
                c.setBorderColor(RULE);
                c.setHorizontalAlignment(Element.ALIGN_CENTER);
                c.setVerticalAlignment(Element.ALIGN_MIDDLE);
                return c;
            } catch (Exception e) {
                // unsupported format or damaged file: blank cell
            }
        }
        PdfPCell c = new PdfPCell(new Phrase(""));
        c.setBorderColor(RULE);
        return c;
    }

    static Color color(String hex) {
        String h = hex == null ? "" : hex.replace("#", "");
        if (h.length() == 3) {
            h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        }
        try {
            return new Color(Integer.parseInt(h, 16));
        } catch (NumberFormatException e) {
            return color(QuoteLayout.DEFAULT.accentColor());
        }
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String money(Object v) {
        return v == null ? "" : String.format(Locale.ROOT, "%,.2f", new BigDecimal(v.toString()).setScale(2, RoundingMode.HALF_UP));
    }

    private static String qty(Object v) {
        return v == null ? "" : new BigDecimal(v.toString()).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
                .toPlainString();
    }

    private static String disc(Object v) {
        if (v == null || new BigDecimal(v.toString()).signum() == 0) {
            return "";
        }
        return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString() + "%";
    }
}
