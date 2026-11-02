package com.inventoryerp.helpdesk.application;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfWriter;
import org.openpdf.text.pdf.draw.LineSeparator;

/** Outgoing letters: placeholder merge ({@code {{a.b}}}) and an A4 PDF with letterhead, references and signature. */
public final class LetterRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    private LetterRenderer() {}

    /** Replace {@code {{key}}} with values (unknown keys become empty). */
    public static String merge(String template, Map<String, String> values) {
        if (template == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Letter content. */
    public record Letter(String organization, String letterNumber, String ourRef, String yourRef, String date,
            String recipient, String subject, String body, String closingLine, String signatureName,
            String signatureTitle, String confidentiality) {}

    public static byte[] pdf(Letter l) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 60, 60, 55, 55);
        PdfWriter.getInstance(pdf, out);
        pdf.open();
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 9, java.awt.Color.DARK_GRAY);
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 11);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
        Font red = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, java.awt.Color.RED);
        pdf.add(new Paragraph(nz(l.organization()), head));
        pdf.add(new LineSeparator(0.5f, 100, java.awt.Color.GRAY, Element.ALIGN_CENTER, -4));
        if (l.confidentiality() != null && !"normal".equals(l.confidentiality())) {
            Paragraph c = new Paragraph(l.confidentiality().toUpperCase(), red);
            c.setAlignment(Element.ALIGN_RIGHT);
            pdf.add(c);
        }
        Paragraph refs = new Paragraph();
        refs.setSpacingBefore(14);
        refs.add(line("Our ref: ", nz(l.ourRef()).isEmpty() ? nz(l.letterNumber()) : l.ourRef(), small));
        if (l.yourRef() != null && !l.yourRef().isBlank()) {
            refs.add(line("Your ref: ", l.yourRef(), small));
        }
        refs.add(line("Date: ", nz(l.date()), small));
        pdf.add(refs);
        if (l.recipient() != null && !l.recipient().isBlank()) {
            Paragraph to = new Paragraph(l.recipient(), normal);
            to.setSpacingBefore(14);
            pdf.add(to);
        }
        Paragraph subject = new Paragraph("Subject: " + nz(l.subject()), bold);
        subject.setSpacingBefore(16);
        subject.setSpacingAfter(10);
        pdf.add(subject);
        for (String para : nz(l.body()).split("\\r?\\n\\s*\\r?\\n")) {
            Paragraph p = new Paragraph(para.replaceAll("\\r?\\n", "\n").trim(), normal);
            p.setSpacingAfter(8);
            p.setLeading(15);
            pdf.add(p);
        }
        Paragraph closing = new Paragraph(nz(l.closingLine()).isEmpty() ? "Yours sincerely," : l.closingLine(), normal);
        closing.setSpacingBefore(18);
        pdf.add(closing);
        Paragraph sig = new Paragraph();
        sig.setSpacingBefore(36);
        sig.add(new Paragraph(nz(l.signatureName()), bold));
        if (l.signatureTitle() != null && !l.signatureTitle().isBlank()) {
            sig.add(new Paragraph(l.signatureTitle(), normal));
        }
        pdf.add(sig);
        pdf.close();
        return out.toByteArray();
    }

    private static Paragraph line(String label, String value, Font f) {
        return new Paragraph(label + value, f);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
