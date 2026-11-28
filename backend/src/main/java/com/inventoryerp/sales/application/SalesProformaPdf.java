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

/**
 * Customer proforma invoice PDF: the organization letterhead (company settings name, contact lines, logo), a bill-to
 * panel, an "amount due now" panel, the lines, totals with the advance and the balance, and the payment details. It
 * takes its colour, paper, columns, footer and signature from the quotation layout (Sales settings) so both documents
 * look alike.
 */
public final class SalesProformaPdf {

    /** What to print. {@code lines}: {@code description}, {@code quantity}, {@code unit}, {@code unitPrice},
     * {@code discountPercent}, {@code tax}, {@code total} and the size columns. */
    public record Proforma(SalesQuotationPdf.Company company, String number, List<SalesQuotationPdf.Fact> facts,
            List<String> billTo, String deliverTo, List<Map<String, Object>> lines, String currency, BigDecimal subtotal,
            BigDecimal discount, BigDecimal tax, BigDecimal total, BigDecimal advancePercent, BigDecimal advanceAmount,
            String bankDetails, String mobileDetails, String notes) {}

    private static final Color MUTED = new Color(100, 108, 120);
    private static final Color INK = new Color(17, 24, 39);
    private static final Color RULE = new Color(220, 224, 230);
    private static final Color ZEBRA = new Color(249, 250, 251);

    private SalesProformaPdf() {}

    public static byte[] render(Proforma p, QuoteLayout layout) {
        Color accent = SalesQuotationPdf.color(layout.accentColor());
        Color tint = mix(accent, Color.WHITE, 0.90f);
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
        Font body = FontFactory.getFont(FontFactory.HELVETICA, base, INK);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, base, INK);
        Font muted = FontFactory.getFont(FontFactory.HELVETICA, base - 1, MUTED);
        Font label = FontFactory.getFont(FontFactory.HELVETICA_BOLD, base - 1.5f, accent);
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, base - 0.5f, Color.WHITE);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        float margin = small ? 28 : 40;
        Document pdf = new Document(size, margin, margin, margin + 6, margin + 26);
        try {
            PdfWriter writer = PdfWriter.getInstance(pdf, out);
            writer.setPageEvent(new Frame(accent, layout.footerText(), muted));
            pdf.open();

            // Letterhead: logo + company on the left, title + number + facts on the right.
            PdfPTable top = new PdfPTable(new float[] {1.15f, 1});
            top.setWidthPercentage(100);
            PdfPCell left = plain();
            if (layout.showLogo() && p.company().logo() != null) {
                try {
                    Image logo = Image.getInstance(p.company().logo());
                    logo.scaleToFit(small ? 90 : 130, small ? 45 : 60);
                    left.addElement(logo);
                } catch (Exception e) {
                    // unreadable logo: print without it
                }
            }
            Paragraph name = new Paragraph(p.company().name(), FontFactory.getFont(FontFactory.HELVETICA_BOLD,
                    small ? 13 : 17, accent));
            name.setSpacingBefore(2);
            left.addElement(name);
            if (layout.showCompanyDetails()) {
                for (String l : p.company().lines()) {
                    left.addElement(new Paragraph(l, muted));
                }
            }
            top.addCell(left);

            PdfPCell right = plain();
            Paragraph title = new Paragraph("PROFORMA INVOICE", FontFactory.getFont(FontFactory.HELVETICA_BOLD,
                    small ? 15 : 21, accent));
            title.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(title);
            Paragraph number = new Paragraph("No. " + text(p.number()), FontFactory.getFont(FontFactory.HELVETICA_BOLD,
                    base + 2, INK));
            number.setAlignment(Element.ALIGN_RIGHT);
            number.setSpacingAfter(4);
            right.addElement(number);
            PdfPTable facts = new PdfPTable(new float[] {1, 1.3f});
            facts.setWidthPercentage(100);
            for (SalesQuotationPdf.Fact f : p.facts()) {
                if (f.value() == null || f.value().isBlank()) {
                    continue;
                }
                PdfPCell k = plain();
                k.setPhrase(new Phrase(f.label(), muted));
                k.setHorizontalAlignment(Element.ALIGN_RIGHT);
                k.setPaddingRight(8);
                k.setPaddingBottom(2);
                PdfPCell v = plain();
                v.setPhrase(new Phrase(f.value(), bold));
                v.setHorizontalAlignment(Element.ALIGN_RIGHT);
                v.setPaddingBottom(2);
                facts.addCell(k);
                facts.addCell(v);
            }
            right.addElement(facts);
            top.addCell(right);
            pdf.add(top);
            pdf.add(rule(accent, 1.5f, 10));

            // Bill-to (and deliver-to) on the left, amount due now on the right.
            PdfPTable parties = new PdfPTable(new float[] {1.15f, 1});
            parties.setWidthPercentage(100);
            parties.setSpacingAfter(10);
            PdfPTable who = new PdfPTable(p.deliverTo() == null || p.deliverTo().isBlank() ? 1 : 2);
            who.setWidthPercentage(100);
            PdfPCell bill = panel(tint);
            bill.addElement(new Paragraph("BILL TO", label));
            for (int i = 0; i < p.billTo().size(); i++) {
                bill.addElement(new Paragraph(p.billTo().get(i), i == 0 ? FontFactory.getFont(
                        FontFactory.HELVETICA_BOLD, base + 1.5f, INK) : body));
            }
            who.addCell(bill);
            if (p.deliverTo() != null && !p.deliverTo().isBlank()) {
                PdfPCell ship = panel(tint);
                ship.addElement(new Paragraph("DELIVER TO", label));
                ship.addElement(new Paragraph(p.deliverTo(), body));
                who.addCell(ship);
            }
            PdfPCell whoCell = plain();
            whoCell.setPaddingRight(10);
            whoCell.addElement(who);
            parties.addCell(whoCell);

            boolean advance = p.advanceAmount() != null && p.advanceAmount().signum() > 0;
            BigDecimal dueNow = advance ? p.advanceAmount() : p.total();
            PdfPCell due = new PdfPCell();
            due.setBackgroundColor(accent);
            due.setBorder(Rectangle.NO_BORDER);
            due.setPadding(12);
            due.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Paragraph dueLabel = new Paragraph(advance ? "ADVANCE REQUIRED" + percent(p.advancePercent())
                    : "AMOUNT TO PAY", FontFactory.getFont(FontFactory.HELVETICA_BOLD, base - 1, Color.WHITE));
            dueLabel.setAlignment(Element.ALIGN_RIGHT);
            due.addElement(dueLabel);
            Paragraph dueAmount = new Paragraph(cur(p.currency()) + money(dueNow), FontFactory.getFont(
                    FontFactory.HELVETICA_BOLD, small ? 14 : 19, Color.WHITE));
            dueAmount.setAlignment(Element.ALIGN_RIGHT);
            due.addElement(dueAmount);
            if (advance) {
                Paragraph rest = new Paragraph("of " + cur(p.currency()) + money(p.total()) + " total",
                        FontFactory.getFont(FontFactory.HELVETICA, base - 1, mix(Color.WHITE, accent, 0.2f)));
                rest.setAlignment(Element.ALIGN_RIGHT);
                due.addElement(rest);
            }
            parties.addCell(due);
            pdf.add(parties);

            // Lines.
            List<String> cols = new ArrayList<>(List.of("#", "Description", "Qty"));
            List<Float> widths = new ArrayList<>(List.of(0.5f, 5f, 1.1f));
            if (layout.showUnit()) {
                cols.add("Unit");
                widths.add(0.9f);
            }
            cols.add("Unit price");
            widths.add(1.7f);
            if (layout.showDiscount()) {
                cols.add("Disc %");
                widths.add(1.1f);
            }
            if (layout.showTax()) {
                cols.add("Tax");
                widths.add(1.2f);
            }
            cols.add("Amount");
            widths.add(1.9f);
            float[] w = new float[widths.size()];
            for (int i = 0; i < w.length; i++) {
                w[i] = widths.get(i);
            }
            PdfPTable t = new PdfPTable(w);
            t.setWidthPercentage(100);
            t.setHeaderRows(1);
            for (String c : cols) {
                PdfPCell h = new PdfPCell(new Phrase(c.toUpperCase(Locale.ROOT), head));
                h.setBackgroundColor(accent);
                h.setBorder(Rectangle.NO_BORDER);
                h.setPaddingTop(6);
                h.setPaddingBottom(6);
                h.setPaddingLeft(5);
                h.setPaddingRight(5);
                h.setHorizontalAlignment(List.of("Description", "#").contains(c) ? Element.ALIGN_LEFT
                        : Element.ALIGN_RIGHT);
                t.addCell(h);
            }
            int n = 0;
            for (Map<String, Object> l : p.lines()) {
                n++;
                Color bg = n % 2 == 0 ? ZEBRA : Color.WHITE;
                t.addCell(row(new Phrase(String.valueOf(n), muted), Element.ALIGN_LEFT, bg));
                String sizeLabel = layout.showSize() ? ProductSizingService.label(ProductSizingService.Sizing.stored(l),
                        l.get("sizingUnit") == null ? null : String.valueOf(l.get("sizingUnit"))) : null;
                Phrase d = new Phrase(text(l.get("description")), body);
                if (sizeLabel != null) {
                    d.add(new Phrase("\n" + sizeLabel, muted));
                }
                t.addCell(row(d, Element.ALIGN_LEFT, bg));
                t.addCell(row(new Phrase(qty(l.get("quantity")), body), Element.ALIGN_RIGHT, bg));
                if (layout.showUnit()) {
                    t.addCell(row(new Phrase(text(l.get("unit")), body), Element.ALIGN_RIGHT, bg));
                }
                t.addCell(row(new Phrase(money(l.get("unitPrice")), body), Element.ALIGN_RIGHT, bg));
                if (layout.showDiscount()) {
                    t.addCell(row(new Phrase(disc(l.get("discountPercent")), body), Element.ALIGN_RIGHT, bg));
                }
                if (layout.showTax()) {
                    t.addCell(row(new Phrase(text(l.get("tax")), body), Element.ALIGN_RIGHT, bg));
                }
                t.addCell(row(new Phrase(money(l.get("total")), bold), Element.ALIGN_RIGHT, bg));
            }
            pdf.add(t);

            // Notes on the left, totals on the right.
            PdfPTable foot = new PdfPTable(new float[] {1.15f, 1});
            foot.setWidthPercentage(100);
            foot.setSpacingBefore(10);
            foot.setKeepTogether(true);
            PdfPCell notes = plain();
            notes.setPaddingRight(14);
            if (layout.showNotes() && p.notes() != null && !p.notes().isBlank()) {
                notes.addElement(new Paragraph("NOTES", label));
                notes.addElement(new Paragraph(p.notes(), body));
            }
            foot.addCell(notes);
            PdfPTable m = new PdfPTable(new float[] {1.4f, 1.3f});
            m.setWidthPercentage(100);
            // Document.subtotal is net of line discounts: show the gross and the discount above it.
            if (p.discount() != null && p.discount().signum() > 0) {
                sum(m, "Gross amount", p.currency(), p.subtotal().add(p.discount()), body, muted, false, accent);
                sum(m, "Discount", p.currency(), p.discount().negate(), body, muted, false, accent);
            }
            sum(m, "Subtotal", p.currency(), p.subtotal(), body, muted, false, accent);
            sum(m, "Tax", p.currency(), p.tax(), body, muted, false, accent);
            sum(m, "Total", p.currency(), p.total(), body, muted, true, accent);
            if (advance) {
                sum(m, "Advance required" + percent(p.advancePercent()), p.currency(), p.advanceAmount(), bold, bold,
                        false, accent);
                sum(m, "Balance on invoice", p.currency(), p.total().subtract(p.advanceAmount()), body, muted, false,
                        accent);
            }
            PdfPCell totals = plain();
            totals.addElement(m);
            foot.addCell(totals);
            pdf.add(foot);

            // Payment details.
            List<String[]> pay = new ArrayList<>();
            if (p.bankDetails() != null && !p.bankDetails().isBlank()) {
                pay.add(new String[] {"BANK TRANSFER", p.bankDetails()});
            }
            if (p.mobileDetails() != null && !p.mobileDetails().isBlank()) {
                pay.add(new String[] {"MOBILE MONEY", p.mobileDetails()});
            }
            if (!pay.isEmpty()) {
                Paragraph ph = new Paragraph("How to pay", FontFactory.getFont(FontFactory.HELVETICA_BOLD, base + 1,
                        accent));
                ph.setSpacingBefore(14);
                ph.setSpacingAfter(4);
                pdf.add(ph);
                PdfPTable pt = new PdfPTable(pay.size());
                pt.setWidthPercentage(100);
                pt.setKeepTogether(true);
                for (String[] x : pay) {
                    PdfPCell c = panel(tint);
                    c.setBorderWidthLeft(3);
                    c.setBorderColorLeft(accent);
                    c.setBorder(Rectangle.LEFT);
                    c.addElement(new Paragraph(x[0], label));
                    c.addElement(new Paragraph(x[1], body));
                    pt.addCell(c);
                }
                if (pay.size() == 2) {
                    pt.setWidths(new float[] {1, 1});
                }
                pdf.add(pt);
                Paragraph ref = new Paragraph("Please quote " + text(p.number()) + " as the payment reference.", muted);
                ref.setSpacingBefore(4);
                pdf.add(ref);
            }

            if (layout.showSignature()) {
                PdfPTable s = new PdfPTable(new float[] {1, 0.2f, 1});
                s.setWidthPercentage(100);
                s.setSpacingBefore(36);
                s.setKeepTogether(true);
                for (String sig : new String[] {"Authorised signature", null, "Accepted by (customer)"}) {
                    PdfPCell c = plain();
                    if (sig != null) {
                        c.setBorder(Rectangle.TOP);
                        c.setBorderColor(MUTED);
                        c.addElement(new Paragraph(sig, bold));
                        c.addElement(new Paragraph("Name, signature and date", muted));
                    }
                    s.addCell(c);
                }
                pdf.add(s);
            }

            // Disclaimer.
            PdfPTable note = new PdfPTable(1);
            note.setWidthPercentage(100);
            note.setSpacingBefore(16);
            note.setKeepTogether(true);
            PdfPCell nc = new PdfPCell(new Phrase("This is a proforma invoice, not a tax invoice. No amount is due and"
                    + " nothing is recorded in the accounts until an invoice is issued.",
                    FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, base - 1, MUTED)));
            nc.setBorder(Rectangle.TOP);
            nc.setBorderColor(RULE);
            nc.setPaddingTop(6);
            nc.setHorizontalAlignment(Element.ALIGN_CENTER);
            note.addCell(nc);
            pdf.add(note);
            pdf.close();
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    /** Accent band at the top of every page, footer text and page number at the bottom. */
    private static final class Frame extends PdfPageEventHelper {
        private final Color accent;
        private final String text;
        private final Font font;

        Frame(Color accent, String text, Font font) {
            this.accent = accent;
            this.text = text;
            this.font = font;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            var cb = writer.getDirectContentUnder();
            cb.saveState();
            cb.setColorFill(accent);
            cb.rectangle(0, page.getHeight() - 8, page.getWidth(), 8);
            cb.fill();
            cb.restoreState();
            float y = document.bottom() - 16;
            if (!text.isBlank()) {
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                        new Phrase(text.replace('\n', ' '), font), document.left(), y, 0);
            }
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    new Phrase("Page " + writer.getPageNumber(), font), document.right(), y, 0);
        }
    }

    private static void sum(PdfPTable m, String label, String currency, BigDecimal amount, Font body, Font muted,
            boolean grand, Color accent) {
        Font lf = grand ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, body.getSize() + 1.5f, Color.WHITE) : muted;
        Font vf = grand ? lf : body;
        PdfPCell a = new PdfPCell(new Phrase(label, lf));
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        PdfPCell b = new PdfPCell(new Phrase((v.signum() < 0 ? "- " : "") + cur(currency) + money(v.abs()), vf));
        b.setHorizontalAlignment(Element.ALIGN_RIGHT);
        for (PdfPCell c : List.of(a, b)) {
            c.setBorder(grand ? Rectangle.NO_BORDER : Rectangle.BOTTOM);
            c.setBorderColor(RULE);
            c.setPaddingTop(grand ? 7 : 4);
            c.setPaddingBottom(grand ? 7 : 4);
            c.setPaddingLeft(6);
            c.setPaddingRight(6);
            if (grand) {
                c.setBackgroundColor(accent);
            }
            m.addCell(c);
        }
    }

    private static Paragraph rule(Color color, float width, float after) {
        PdfPTable line = new PdfPTable(1);
        line.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(color);
        c.setBorderWidth(width);
        c.setFixedHeight(6);
        line.addCell(c);
        Paragraph p = new Paragraph();
        p.add(line);
        p.setSpacingAfter(after);
        return p;
    }

    private static PdfPCell panel(Color bg) {
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(bg);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(9);
        c.setPaddingTop(6);
        return c;
    }

    private static PdfPCell plain() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private static PdfPCell row(Phrase p, int align, Color bg) {
        PdfPCell c = new PdfPCell(p);
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPaddingTop(5);
        c.setPaddingBottom(5);
        c.setPaddingLeft(5);
        c.setPaddingRight(5);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(RULE);
        c.setBackgroundColor(bg);
        return c;
    }

    /** {@code a} blended towards {@code b} by {@code t} (0 = a, 1 = b). */
    private static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static String percent(BigDecimal p) {
        return p == null || p.signum() <= 0 ? "" : " (" + p.stripTrailingZeros().toPlainString() + "%)";
    }

    private static String cur(String currency) {
        return currency == null || currency.isBlank() ? "" : currency + " ";
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String money(Object v) {
        return v == null ? "" : String.format(Locale.ROOT, "%,.2f", new BigDecimal(v.toString()).setScale(2,
                RoundingMode.HALF_UP));
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
