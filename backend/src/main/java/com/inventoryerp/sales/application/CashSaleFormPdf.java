package com.inventoryerp.sales.application;

import com.inventoryerp.core.application.ProductSizingService;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * The cash sale "CASH SALES FORM" as an A4 PDF: same sections, columns and totals as the Excel form of
 * {@link CashSaleFormExporter} (logo and company, date, customer details, order items, order total, tax, final cash
 * received), for printing or sending without a spreadsheet program.
 */
final class CashSaleFormPdf {

    private static final Color ORANGE = new Color(0xF7, 0x96, 0x46);
    private static final Color ORANGE_LIGHT = new Color(0xFC, 0xD5, 0xB4);
    private static final Color HEADER_FILL = new Color(0xF2, 0xE1, 0xD9);
    private static final Color YELLOW = new Color(0xFF, 0xFF, 0x00);
    private static final Color GRID = new Color(0xD9, 0xD9, 0xD9);
    private static final Color NAVY = new Color(0x1F, 0x3B, 0x57);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private CashSaleFormPdf() {}

    static byte[] render(Map<String, Object> s, List<Map<String, Object>> lines, String company, byte[] logo) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(pdf, out);
            pdf.open();
            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, NAVY);
            Font subtitle = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.WHITE);
            Font section = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 9);
            Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font big = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);

            // Logo and company name, then the form title band.
            PdfPTable head = new PdfPTable(new float[] {1.3f, 6});
            head.setWidthPercentage(100);
            PdfPCell logoCell = new PdfPCell();
            logoCell.setBorder(Rectangle.NO_BORDER);
            Image img = image(logo);
            if (img != null) {
                img.scaleToFit(72, 60);
                logoCell.addElement(img);
            }
            head.addCell(logoCell);
            PdfPCell name = cell(company == null ? "" : company.toUpperCase(), title, null, Element.ALIGN_CENTER);
            name.setBorder(Rectangle.NO_BORDER);
            name.setVerticalAlignment(Element.ALIGN_MIDDLE);
            head.addCell(name);
            pdf.add(head);
            pdf.add(band("CASH SALES FORM", subtitle, ORANGE, 8));

            // Date.
            PdfPTable date = new PdfPTable(new float[] {3, 1, 4});
            date.setWidthPercentage(100);
            date.setSpacingBefore(8);
            date.addCell(cell("DATE", bold, HEADER_FILL, Element.ALIGN_LEFT));
            PdfPCell gap = new PdfPCell();
            gap.setBorder(Rectangle.NO_BORDER);
            date.addCell(gap);
            LocalDate day = date(s.get("saleDate"));
            date.addCell(cell(day == null ? "" : DAY.format(day), bold, YELLOW, Element.ALIGN_CENTER));
            pdf.add(date);

            // A. Customer details.
            pdf.add(band("A. CUSTOMER DETAILS", section, ORANGE, 12));
            PdfPTable cust = new PdfPTable(new float[] {2, 6});
            cust.setWidthPercentage(100);
            detail(cust, "Customer Name", first(s.get("customerName"), s.get("partnerName")), bold, body);
            String phone = text(s.get("customerPhone"));
            if (!phone.isEmpty()) {
                detail(cust, "Phone", phone, bold, body);
            }
            String address = text(s.get("customerAddress"));
            if (!address.isEmpty()) {
                detail(cust, "Address", address, bold, body);
            }
            detail(cust, "SALES BRANCH", text(s.get("branchName")).toUpperCase(), bold, body);
            detail(cust, "ORDER NUMBER", text(s.get("number")), bold, body);
            pdf.add(cust);

            // C. Order items: line total = quantity × unit price × (1 − discount %), like the Excel formula.
            pdf.add(band("C. ORDER ITEMS", section, ORANGE, 12));
            PdfPTable items = new PdfPTable(new float[] {2.4f, 3, 1.1f, 1.3f, 1.8f, 1.4f, 2});
            items.setWidthPercentage(100);
            items.setHeaderRows(1);
            for (String h : List.of("Product", "Specification / Grade", "Unit", "Quantity", "Unit Price",
                    "Q. IN METERS", "Line Total")) {
                items.addCell(cell(h, bold, HEADER_FILL, Element.ALIGN_CENTER));
            }
            BigDecimal orderTotal = BigDecimal.ZERO;
            for (Map<String, Object> l : lines) {
                String product = text(l.get("productName"));
                String spec = first(l.get("variantName"), product.equals(text(l.get("description"))) ? null
                        : l.get("description"));
                // ADR-S066: pieces and dimensions of a line sold by size
                String size = ProductSizingService.label(ProductSizingService.Sizing.stored(l),
                        l.get("sizingUnit") == null ? null : String.valueOf(l.get("sizingUnit")));
                if (size != null) {
                    spec = spec.isBlank() ? size : spec + " — " + size;
                }
                BigDecimal qty = dec(l.get("quantity"));
                BigDecimal price = dec(l.get("unitPrice"));
                BigDecimal disc = dec(l.get("discountPercent"));
                BigDecimal total = qty.multiply(price);
                if (disc.signum() != 0) {
                    total = total.multiply(BigDecimal.ONE.subtract(disc.movePointLeft(2)));
                }
                total = total.setScale(2, RoundingMode.HALF_UP);
                orderTotal = orderTotal.add(total);
                items.addCell(cell(product, bold, null, Element.ALIGN_LEFT));
                items.addCell(cell(spec, body, null, Element.ALIGN_LEFT));
                items.addCell(cell(text(l.get("uomCode")), body, null, Element.ALIGN_CENTER));
                items.addCell(cell(l.get("quantity") == null ? "" : qty(qty), body, null, Element.ALIGN_RIGHT));
                items.addCell(cell(l.get("unitPrice") == null ? "" : money(price), body, null, Element.ALIGN_RIGHT));
                items.addCell(cell(l.get("quantity") == null ? "" : qty(qty), body, null, Element.ALIGN_RIGHT));
                items.addCell(cell(money(total), body, null, Element.ALIGN_RIGHT));
            }
            if (lines.isEmpty()) {
                for (int i = 0; i < 7; i++) {
                    items.addCell(cell(" ", body, null, Element.ALIGN_LEFT));
                }
            }
            pdf.add(items);

            // Totals.
            BigDecimal tax = dec(s.get("taxTotal"));
            PdfPTable totals = new PdfPTable(new float[] {3, 2});
            totals.setWidthPercentage(45);
            totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totals.setSpacingBefore(6);
            totals.addCell(cell("ORDER TOTAL", big, ORANGE_LIGHT, Element.ALIGN_LEFT));
            totals.addCell(cell(money(orderTotal), big, ORANGE_LIGHT, Element.ALIGN_RIGHT));
            if (tax.signum() != 0) {
                totals.addCell(cell("TAX", bold, null, Element.ALIGN_LEFT));
                totals.addCell(cell(money(tax), body, null, Element.ALIGN_RIGHT));
            }
            totals.addCell(cell("FINAL CASH RECEIVED", big, YELLOW, Element.ALIGN_LEFT));
            totals.addCell(cell(money(orderTotal.add(tax)), big, YELLOW, Element.ALIGN_RIGHT));
            pdf.add(totals);
            pdf.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the cash sale form", e);
        }
        return out.toByteArray();
    }

    private static PdfPTable band(String text, Font font, Color fill, float spacingBefore) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(spacingBefore);
        PdfPCell c = cell(text, font, fill, Element.ALIGN_CENTER);
        c.setPadding(5);
        t.addCell(c);
        return t;
    }

    private static void detail(PdfPTable t, String label, String value, Font bold, Font body) {
        t.addCell(cell(label, bold, HEADER_FILL, Element.ALIGN_LEFT));
        t.addCell(cell(value, body, null, Element.ALIGN_LEFT));
    }

    private static PdfPCell cell(String text, Font font, Color fill, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        c.setBorderColor(GRID);
        if (fill != null) {
            c.setBackgroundColor(fill);
        }
        return c;
    }

    /** PNG / JPEG logo, or {@code null} when absent or unreadable (the form renders without it). */
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

    private static String money(BigDecimal v) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }

    private static String qty(BigDecimal v) {
        return new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }

    private static String text(Object v) {
        return CashSaleFormExporter.text(v);
    }

    private static String first(Object a, Object b) {
        String x = text(a);
        return x.isEmpty() ? text(b) : x;
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static LocalDate date(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        String t = text(v);
        try {
            return t.length() >= 10 ? LocalDate.parse(t.substring(0, 10)) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
