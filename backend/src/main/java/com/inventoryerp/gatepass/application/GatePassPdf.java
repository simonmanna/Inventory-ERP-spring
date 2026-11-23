package com.inventoryerp.gatepass.application;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.inventoryerp.kernel.common.BusinessZone;
import com.inventoryerp.sales.application.SalesLetterhead;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Printable A5 gate pass: letterhead, "GATE PASS" with number and QR code (number and verification code, scanned at the
 * gate), a red banner per warning (goods not yet issued, not paid), the state stamp (VOID / USED / EXPIRED) and the
 * copy number, the source, the bearer and vehicle, the items, and the issuer / bearer / gate signature lines.
 */
public final class GatePassPdf {

    public record Pdf(String filename, byte[] content) {}

    static final Map<String, String> WARNINGS = Map.of(
            "goods_not_issued", "GOODS NOT YET ISSUED FROM STOCK - CHECK WITH STORES BEFORE RELEASE",
            "unpaid", "NOT PAID - CHECK WITH THE CASHIER BEFORE RELEASE",
            "partially_paid", "PARTIALLY PAID - CHECK WITH THE CASHIER BEFORE RELEASE");

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    private GatePassPdf() {}

    @SuppressWarnings("unchecked")
    public static Pdf render(SalesLetterhead.Head head, Map<String, Object> p, String verifyCode, String issuedBy,
            String gateOutBy) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A5, 30, 30, 28, 28);
        try {
            PdfWriter writer = PdfWriter.getInstance(pdf, out);
            pdf.open();
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 9);
            Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 7, Color.GRAY);
            Font warn = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);

            pdf.add(letterhead(head, body));

            // Title row: GATE PASS + number + state | QR.
            PdfPTable title = new PdfPTable(new float[] {3f, 1.1f});
            title.setWidthPercentage(100);
            title.setSpacingBefore(6);
            PdfPCell left = plain();
            left.addElement(new Phrase("GATE PASS", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20)));
            left.addElement(new Phrase(s(p.get("number")), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13)));
            int prints = p.get("printCount") instanceof Number n ? n.intValue() : 1;
            String stamp = stamp(p);
            if (prints > 1 || stamp != null) {
                left.addElement(new Phrase((stamp == null ? "" : stamp + "   ") + (prints > 1 ? "COPY " + (prints - 1) : ""),
                        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, new Color(185, 28, 28))));
            }
            title.addCell(left);
            PdfPCell qr = plain();
            qr.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Image code = qr(writer, s(p.get("number")) + "|" + s(verifyCode), 72);
            PdfPCell img = new PdfPCell(code, false);
            img.setBorder(Rectangle.NO_BORDER);
            img.setHorizontalAlignment(Element.ALIGN_RIGHT);
            title.addCell(img);
            title.addCell(plain());
            PdfPCell codeText = new PdfPCell(new Phrase("Code " + s(verifyCode), small));
            codeText.setBorder(Rectangle.NO_BORDER);
            codeText.setHorizontalAlignment(Element.ALIGN_RIGHT);
            title.addCell(codeText);
            pdf.add(title);

            for (Object w : (List<Object>) p.getOrDefault("warnings", List.of())) {
                PdfPTable banner = new PdfPTable(1);
                banner.setWidthPercentage(100);
                banner.setSpacingBefore(4);
                PdfPCell c = new PdfPCell(new Phrase(WARNINGS.getOrDefault(String.valueOf(w), String.valueOf(w)), warn));
                c.setBackgroundColor(new Color(185, 28, 28));
                c.setBorder(Rectangle.NO_BORDER);
                c.setPadding(5);
                c.setHorizontalAlignment(Element.ALIGN_CENTER);
                banner.addCell(c);
                pdf.add(banner);
            }

            List<String[]> facts = new ArrayList<>();
            Map<String, Object> src = (Map<String, Object>) p.get("source");
            String label = src == null ? GatePassSources.LABELS.get(s(p.get("sourceType"))) : s(src.get("label"));
            fact(facts, label, s(p.get("sourceNumber")));
            fact(facts, "supplier_return".equals(p.get("sourceType")) ? "Supplier" : "Customer", s(p.get("partnerName")));
            fact(facts, "Issued", when(p.get("issuedAt")));
            fact(facts, "Valid until", when(p.get("validUntil")));
            fact(facts, "Bearer", cap(s(p.get("bearerType"))) + ": " + s(p.get("bearerName")));
            fact(facts, "ID / licence no.", s(p.get("bearerIdNumber")));
            fact(facts, "Phone", s(p.get("bearerPhone")));
            fact(facts, "Vehicle plate", s(p.get("vehiclePlate")));
            fact(facts, "Vehicle type", s(p.get("vehicleType")));
            fact(facts, "Transporter", s(p.get("transportCompany")));
            fact(facts, "Destination", s(p.get("destination")));
            fact(facts, "Purpose", s(p.get("purpose")));
            PdfPTable f = new PdfPTable(2);
            f.setWidthPercentage(100);
            f.setSpacingBefore(8);
            for (String[] kv : facts) {
                PdfPCell c = plain();
                c.addElement(new Phrase(kv[0], bold));
                c.addElement(new Phrase(kv[1], body));
                f.addCell(c);
            }
            if (facts.size() % 2 == 1) {
                f.addCell(plain());
            }
            pdf.add(f);

            PdfPTable t = new PdfPTable(new float[] {0.5f, 5f, 1.6f});
            t.setWidthPercentage(100);
            t.setSpacingBefore(8);
            t.addCell(head("#", bold, Element.ALIGN_LEFT));
            t.addCell(head("Item", bold, Element.ALIGN_LEFT));
            t.addCell(head("Quantity", bold, Element.ALIGN_RIGHT));
            for (Map<String, Object> l : (List<Map<String, Object>>) p.getOrDefault("lines", List.of())) {
                t.addCell(row(s(l.get("lineNo")), body, Element.ALIGN_LEFT));
                t.addCell(row(s(l.get("description")), body, Element.ALIGN_LEFT));
                t.addCell(row(num(l.get("quantity")) + (l.get("unit") == null ? "" : " " + l.get("unit")), body,
                        Element.ALIGN_RIGHT));
            }
            pdf.add(t);
            if (p.get("remarks") != null) {
                pdf.add(new Paragraph("Remarks: " + p.get("remarks"), body));
            }

            pdf.add(new Paragraph(" "));
            PdfPTable sign = new PdfPTable(3);
            sign.setWidthPercentage(100);
            sign.addCell(signature("Issued by", issuedBy, body, bold));
            sign.addCell(signature("Bearer", s(p.get("bearerName")), body, bold));
            sign.addCell(signature("Gate / security", "used".equals(p.get("status"))
                    ? s(gateOutBy) + "\nOut " + when(p.get("gateOutAt")) : "Time out: ________", body, bold));
            pdf.add(sign);
            pdf.add(new Paragraph("Valid once, for the items listed, until " + when(p.get("validUntil"))
                    + ". The gate checks the vehicle and the load against this pass.", small));
            pdf.close();
        } catch (DocumentException e) {
            throw new IllegalStateException(e);
        }
        return new Pdf(s(p.get("number")) + ".pdf", out.toByteArray());
    }

    static String stamp(Map<String, Object> p) {
        if ("void".equals(p.get("status"))) {
            return "VOID";
        }
        if ("used".equals(p.get("status"))) {
            return "USED";
        }
        return Boolean.TRUE.equals(p.get("expired")) ? "EXPIRED" : null;
    }

    private static Image qr(PdfWriter writer, String text, float size) throws DocumentException {
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        } catch (WriterException e) {
            throw new IllegalStateException("QR encoding failed", e);
        }
        PdfContentByte cb = writer.getDirectContent();
        PdfTemplate tpl = cb.createTemplate(size, size);
        float cell = size / Math.max(m.getWidth(), m.getHeight());
        tpl.setColorFill(Color.BLACK);
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (m.get(x, y)) {
                    tpl.rectangle(x * cell, size - (y + 1) * cell, cell, cell);
                }
            }
        }
        tpl.fill();
        return Image.getInstance(tpl);
    }

    private static PdfPTable letterhead(SalesLetterhead.Head head, Font body) {
        Image logo = null;
        if (head != null && head.logo() != null && head.logo().length > 0) {
            try {
                logo = Image.getInstance(head.logo());
            } catch (Exception e) {
                logo = null;
            }
        }
        PdfPTable t = logo == null ? new PdfPTable(1) : new PdfPTable(new float[] {1f, 3f});
        t.setWidthPercentage(100);
        if (logo != null) {
            logo.scaleToFit(90, 40);
            PdfPCell c = new PdfPCell(logo, false);
            c.setBorder(Rectangle.NO_BORDER);
            t.addCell(c);
        }
        PdfPCell who = plain();
        who.addElement(new Phrase(head == null ? "" : s(head.name()), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12)));
        if (head != null) {
            for (String line : head.lines()) {
                who.addElement(new Phrase(line, body));
            }
        }
        t.addCell(who);
        return t;
    }

    private static PdfPCell signature(String label, String name, Font body, Font bold) {
        PdfPCell c = plain();
        c.addElement(new Phrase(label, bold));
        c.addElement(new Phrase(name == null || name.isBlank() ? " " : name, body));
        c.addElement(new Phrase("\nSign: ____________", body));
        return c;
    }

    private static void fact(List<String[]> facts, String label, String value) {
        if (label != null && value != null && !value.isBlank()) {
            facts.add(new String[] {label, value});
        }
    }

    private static PdfPCell plain() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(2);
        return c;
    }

    private static PdfPCell head(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.BOTTOM);
        c.setHorizontalAlignment(align);
        c.setPaddingBottom(4);
        return c;
    }

    private static PdfPCell row(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(Color.LIGHT_GRAY);
        c.setHorizontalAlignment(align);
        c.setPadding(4);
        return c;
    }

    static String when(Object v) {
        return v instanceof Instant i ? WHEN.format(i.atZone(BusinessZone.get())) : v == null ? null : v.toString();
    }

    static String num(Object v) {
        return v == null ? "" : new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
    }

    private static String cap(String v) {
        return v == null || v.isEmpty() ? "" : Character.toUpperCase(v.charAt(0)) + v.substring(1);
    }

    private static String s(Object o) {
        return o == null ? null : o.toString();
    }
}
