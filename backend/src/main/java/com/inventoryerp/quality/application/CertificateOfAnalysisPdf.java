package com.inventoryerp.quality.application;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import tools.jackson.databind.JsonNode;

/**
 * Certificate of analysis of an inspection lot (ADR-S013, OpenPDF, A4): company, lot / product / batch / quantity /
 * source, the specification version, one row per parameter (method, unit, limits, results, verdict), a grading table
 * per sieve parameter (envelope vs % passing per sample), the overall result and the disposition. A lot that is not
 * passed is marked so on the certificate.
 */
final class CertificateOfAnalysisPdf {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private CertificateOfAnalysisPdf() {}

    @SuppressWarnings("unchecked")
    static byte[] render(Map<String, Object> lot, String company, String standard) {
        ZoneId zone = com.inventoryerp.kernel.common.BusinessZone.get();
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15);
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 8.5f);
        Font red = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(190, 30, 30));
        Font green = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(20, 120, 50));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 36, 36, 36, 36);
        PdfWriter.getInstance(pdf, out);
        pdf.open();
        pdf.add(new Paragraph(company == null ? "" : company, title));
        Paragraph h = new Paragraph("CERTIFICATE OF ANALYSIS", head);
        h.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(h);
        Paragraph n = new Paragraph("Lot " + lot.get("lotNumber"), bold);
        n.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(n);
        pdf.add(new Paragraph(" ", small));

        PdfPTable hdr = new PdfPTable(new float[] {1.3f, 2.2f, 1.3f, 2.2f});
        hdr.setWidthPercentage(100);
        cells(hdr, bold, small, "Product", s(lot.get("productName")) + (lot.get("productSku") == null ? ""
                : " (" + lot.get("productSku") + ")"), "Batch", s(lot.get("batchNumber")));
        cells(hdr, bold, small, "Quantity", num(lot.get("quantity")) + (lot.get("uom") == null ? "" : " " + lot.get("uom")),
                "Stage", label(s(lot.get("stage"))));
        cells(hdr, bold, small, "Specification", s(lot.get("specificationCode")) + " v" + s(lot.get("specificationVersion"))
                + " — " + s(lot.get("specificationName")), "Standard", s(standard));
        cells(hdr, bold, small, "Source", label(s(lot.get("sourceType"))) + (lot.get("sourceRef") == null ? ""
                : " " + lot.get("sourceRef")), "Supplier", s(lot.get("supplierName")));
        cells(hdr, bold, small, "Samples", s(lot.get("sampleSize")), "Inspected", at(lot.get("inspectedAt"), zone));
        pdf.add(hdr);
        pdf.add(new Paragraph(" ", small));

        List<Map<String, Object>> params = (List<Map<String, Object>>) lot.getOrDefault("parameters", List.of());
        Map<String, String> verdicts = new LinkedHashMap<>();
        if (lot.get("evaluation") instanceof JsonNode ev && ev.has("parameters")) {
            for (JsonNode p : ev.get("parameters")) {
                verdicts.put(p.path("parameterId").asString(""), p.path("status").asString(""));
            }
        }
        PdfPTable t = new PdfPTable(new float[] {2.4f, 1.6f, 0.8f, 1.5f, 2.3f, 1f});
        t.setWidthPercentage(100);
        t.setHeaderRows(1);
        for (String c : List.of("Parameter", "Test method", "Unit", "Specification", "Result", "Verdict")) {
            t.addCell(cell(c, bold, Element.ALIGN_LEFT, new Color(235, 235, 235)));
        }
        List<Map<String, Object>> sieves = new ArrayList<>();
        for (Map<String, Object> p : params) {
            List<Map<String, Object>> results = (List<Map<String, Object>>) p.getOrDefault("results", List.of());
            String kind = s(p.get("kind"));
            String spec;
            String result;
            if ("sieve".equals(kind)) {
                sieves.add(p);
                spec = "Grading envelope";
                result = results.isEmpty() ? "—" : results.size() + " sample(s), see grading";
            } else if ("numeric".equals(kind)) {
                spec = limits(p.get("minValue"), p.get("maxValue"));
                List<String> vals = new ArrayList<>();
                results.forEach(r -> vals.add(num(r.get("numericValue"))));
                result = vals.isEmpty() ? "—" : String.join(" / ", vals);
            } else {
                spec = p.get("expectedText") == null ? ("boolean".equals(kind) ? "true" : "—") : s(p.get("expectedText"));
                List<String> vals = new ArrayList<>();
                results.forEach(r -> vals.add(r.get("textValue") != null ? s(r.get("textValue")) : num(r.get("numericValue"))));
                result = vals.isEmpty() ? "—" : String.join(" / ", vals);
            }
            String verdict = verdicts.getOrDefault(s(p.get("id")), "pending");
            t.addCell(cell(s(p.get("code")) + " — " + s(p.get("name")), small, Element.ALIGN_LEFT, null));
            t.addCell(cell(s(p.get("testMethod")), small, Element.ALIGN_LEFT, null));
            t.addCell(cell(s(p.get("unit")), small, Element.ALIGN_LEFT, null));
            t.addCell(cell(spec, small, Element.ALIGN_LEFT, null));
            t.addCell(cell(result, small, Element.ALIGN_LEFT, null));
            t.addCell(cell(label(verdict), "fail".equals(verdict) ? bold : small, Element.ALIGN_LEFT, null));
        }
        pdf.add(t);

        for (Map<String, Object> p : sieves) {
            pdf.add(new Paragraph(" ", small));
            pdf.add(new Paragraph("Grading — " + p.get("code") + " " + s(p.get("name")) + (p.get("testMethod") == null
                    ? "" : " (" + p.get("testMethod") + ")"), bold));
            List<Map<String, Object>> results = (List<Map<String, Object>>) p.getOrDefault("results", List.of());
            float[] widths = new float[3 + results.size()];
            java.util.Arrays.fill(widths, 1f);
            PdfPTable g = new PdfPTable(widths);
            g.setWidthPercentage(100);
            g.addCell(cell("Sieve (mm)", bold, Element.ALIGN_LEFT, new Color(235, 235, 235)));
            g.addCell(cell("Min % passing", bold, Element.ALIGN_RIGHT, new Color(235, 235, 235)));
            g.addCell(cell("Max % passing", bold, Element.ALIGN_RIGHT, new Color(235, 235, 235)));
            for (Map<String, Object> r : results) {
                g.addCell(cell("Sample " + r.get("sampleNo"), bold, Element.ALIGN_RIGHT, new Color(235, 235, 235)));
            }
            if (p.get("sieveEnvelope") instanceof JsonNode env) {
                for (JsonNode e : env) {
                    String sieve = e.path("sieveMm").asString("");
                    g.addCell(cell(strip(sieve), small, Element.ALIGN_LEFT, null));
                    g.addCell(cell(strip(e.path("minPassingPct").asString("")), small, Element.ALIGN_RIGHT, null));
                    g.addCell(cell(strip(e.path("maxPassingPct").asString("")), small, Element.ALIGN_RIGHT, null));
                    for (Map<String, Object> r : results) {
                        String v = "—";
                        if (r.get("sieveResults") instanceof JsonNode rs) {
                            for (JsonNode x : rs) {
                                if (new BigDecimal(x.path("sieveMm").asString("0")).compareTo(new BigDecimal(
                                        sieve.isEmpty() ? "0" : sieve)) == 0) {
                                    v = strip(x.path("passingPct").asString(""));
                                }
                            }
                        }
                        g.addCell(cell(v, small, Element.ALIGN_RIGHT, null));
                    }
                }
            }
            pdf.add(g);
        }

        pdf.add(new Paragraph(" ", small));
        String result = s(lot.get("result"));
        pdf.add(new Paragraph("Overall result: " + label(result).toUpperCase(java.util.Locale.ROOT),
                "pass".equals(result) ? green : red));
        if (!"pass".equals(result)) {
            pdf.add(new Paragraph("This lot has not passed its specification.", red));
        }
        PdfPTable d = new PdfPTable(new float[] {1.3f, 2.2f, 1.3f, 2.2f});
        d.setWidthPercentage(100);
        cells(d, bold, small, "Status", label(s(lot.get("status"))), "Released", num(lot.get("releasedQty")));
        cells(d, bold, small, "Rejected", num(lot.get("rejectedQty")), "Downgraded", num(lot.get("downgradedQty")));
        pdf.add(d);
        Paragraph issued = new Paragraph("Issued " + AT.format(Instant.now().atZone(zone)), small);
        issued.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(issued);
        pdf.close();
        return out.toByteArray();
    }

    private static void cells(PdfPTable t, Font kf, Font vf, String k1, String v1, String k2, String v2) {
        t.addCell(cell(k1, kf, Element.ALIGN_LEFT, null));
        t.addCell(cell(v1, vf, Element.ALIGN_LEFT, null));
        t.addCell(cell(k2, kf, Element.ALIGN_LEFT, null));
        t.addCell(cell(v2, vf, Element.ALIGN_LEFT, null));
    }

    private static PdfPCell cell(String text, Font font, int align, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        if (bg != null) {
            c.setBackgroundColor(bg);
        }
        return c;
    }

    private static String limits(Object min, Object max) {
        if (min != null && max != null) {
            return num(min) + " – " + num(max);
        }
        return min != null ? "≥ " + num(min) : max != null ? "≤ " + num(max) : "—";
    }

    private static String num(Object v) {
        if (v == null) {
            return "—";
        }
        return v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : String.valueOf(v);
    }

    private static String strip(String v) {
        if (v == null || v.isEmpty()) {
            return "—";
        }
        try {
            return new BigDecimal(v).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static String at(Object v, ZoneId zone) {
        if (v instanceof Instant i) {
            return AT.format(i.atZone(zone));
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        return "—";
    }

    private static String s(Object v) {
        return v == null ? "—" : String.valueOf(v);
    }

    private static String label(String v) {
        return v == null || v.isEmpty() ? "—" : Character.toUpperCase(v.charAt(0)) + v.substring(1).replace('_', ' ');
    }
}
