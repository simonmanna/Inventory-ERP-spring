package com.inventoryerp.fleet.application;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * Printable weighbridge ticket (fleet 1.4, OpenPDF, A5): number, station, truck / driver, customer or supplier,
 * reference documents, product and ordered quantity, both weighings with their source (device + frame / manual /
 * stored tare), gross / tare / net, the tolerance result and the copy counter. Manual weights are flagged, a voided
 * ticket is marked VOID with its reason.
 */
final class WeighbridgeTicketPdf {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private WeighbridgeTicketPdf() {}

    @SuppressWarnings("unchecked")
    static byte[] render(Map<String, Object> t, String company, int copy) {
        ZoneId zone = com.inventoryerp.kernel.common.BusinessZone.get();
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font big = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font red = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(200, 30, 30));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A5, 28, 28, 28, 28);
        PdfWriter.getInstance(pdf, out);
        pdf.open();
        pdf.add(new Paragraph(company == null ? "" : company, title));
        Paragraph heading = new Paragraph("WEIGHBRIDGE TICKET " + t.get("ticketNumber"), bold);
        heading.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(heading);
        Paragraph copyLine = new Paragraph(copy <= 1 ? "Original" : "Copy " + copy, small);
        copyLine.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(copyLine);
        if ("void".equals(t.get("status"))) {
            pdf.add(new Paragraph("VOID — " + t.get("voidReason"), red));
        }
        if (Boolean.TRUE.equals(t.get("isManual"))) {
            pdf.add(new Paragraph("MANUAL WEIGHT ENTRY", red));
        }
        pdf.add(new Paragraph(" ", small));
        Map<String, Object> station = (Map<String, Object>) t.get("station");
        PdfPTable h = new PdfPTable(new float[] {1.2f, 2.4f});
        h.setWidthPercentage(100);
        row(h, "Station", station.get("code") + " — " + station.get("name"), bold, small);
        row(h, "Subject", label(String.valueOf(t.get("subjectType"))), bold, small);
        row(h, "Truck", t.get("registrationPlate") + (t.get("trailerPlate") == null ? "" : " / trailer "
                + t.get("trailerPlate")), bold, small);
        if (t.get("driverName") != null || t.get("driver") != null) {
            Map<String, Object> d = (Map<String, Object>) t.get("driver");
            row(h, "Driver", t.get("driverName") != null ? String.valueOf(t.get("driverName"))
                    : String.valueOf(d.get("code")), bold, small);
        }
        if (t.get("transporter") != null) {
            row(h, "Transporter", String.valueOf(((Map<String, Object>) t.get("transporter")).get("name")), bold, small);
        }
        if (t.get("partner") != null) {
            row(h, "inbound".equals(t.get("direction")) ? "Supplier" : "Customer",
                    String.valueOf(((Map<String, Object>) t.get("partner")).get("name")), bold, small);
        }
        if (t.get("purchaseOrder") != null) {
            row(h, "Purchase order", String.valueOf(((Map<String, Object>) t.get("purchaseOrder")).get("orderNumber")),
                    bold, small);
        }
        for (String[] k : new String[][] {{"salesOrderId", "Sales order"}, {"deliveryOrderId", "Delivery order"},
                {"tripId", "Trip"}, {"referenceNumber", "Reference"}}) {
            if (t.get(k[0]) != null) {
                row(h, k[1], String.valueOf(t.get(k[0])), bold, small);
            }
        }
        if (t.get("product") != null) {
            row(h, "Product", String.valueOf(((Map<String, Object>) t.get("product")).get("name")), bold, small);
        }
        if (t.get("orderedQty") != null) {
            row(h, "Ordered", plain(t.get("orderedQty")) + " " + nz(t.get("orderedUomCode")) + " (expected "
                    + plain(t.get("expectedKg")) + " kg)", bold, small);
        }
        pdf.add(h);
        pdf.add(new Paragraph(" ", small));
        PdfPTable w = new PdfPTable(new float[] {1.2f, 1.4f, 1.6f, 2.2f});
        w.setWidthPercentage(100);
        for (String c : new String[] {"Weighing", "Weight (kg)", "Time", "Source"}) {
            w.addCell(cell(c, bold, Element.ALIGN_LEFT));
        }
        Map<String, Object> devices = (Map<String, Object>) t.get("devices");
        for (String slot : new String[] {"first", "second"}) {
            if (t.get(slot + "WeightKg") == null) {
                continue;
            }
            w.addCell(cell("first".equals(slot) ? "1st" : "2nd", small, Element.ALIGN_LEFT));
            w.addCell(cell(plain(t.get(slot + "WeightKg")), small, Element.ALIGN_RIGHT));
            w.addCell(cell(t.get(slot + "WeighedAt") instanceof Instant i ? AT.format(i.atZone(zone)) : "", small,
                    Element.ALIGN_LEFT));
            String source = String.valueOf(t.get(slot + "Source"));
            String text = switch (source) {
                case "device" -> "Device " + nz(devices == null ? null : devices.get(t.get(slot + "DeviceId")))
                        + (t.get(slot + "RawFrame") == null ? "" : " [" + t.get(slot + "RawFrame") + "]");
                case "manual" -> "MANUAL: " + nz(t.get(slot + "ManualReason"));
                case "stored_tare" -> "Stored tare";
                default -> source;
            };
            w.addCell(cell(text, small, Element.ALIGN_LEFT));
        }
        pdf.add(w);
        pdf.add(new Paragraph(" ", small));
        PdfPTable r = new PdfPTable(3);
        r.setWidthPercentage(100);
        r.addCell(cell("Gross " + plain(t.get("grossKg")) + " kg", bold, Element.ALIGN_CENTER));
        r.addCell(cell("Tare " + plain(t.get("tareKg")) + " kg", bold, Element.ALIGN_CENTER));
        r.addCell(cell("NET " + plain(t.get("netKg")) + " kg", big, Element.ALIGN_CENTER));
        pdf.add(r);
        if (t.get("netQty") != null) {
            Paragraph q = new Paragraph("Net quantity: " + plain(t.get("netQty")) + " " + nz(t.get("netUomCode")), bold);
            q.setAlignment(Element.ALIGN_RIGHT);
            pdf.add(q);
        }
        if (t.get("varianceKg") != null) {
            pdf.add(new Paragraph("Variance vs expected: " + plain(t.get("varianceKg")) + " kg (" + plain(t.get(
                    "variancePct")) + " %), allowed ±" + plain(t.get("allowedVarianceKg")) + " kg — "
                    + (Boolean.TRUE.equals(t.get("withinTolerance")) ? "within tolerance"
                            : "OUT OF TOLERANCE (exception approved)"), small));
        }
        if (Boolean.TRUE.equals(t.get("usedStoredTare"))) {
            pdf.add(new Paragraph("Tare taken from the vehicle's stored tare.", small));
        }
        if (t.get("completedAt") instanceof Instant c) {
            pdf.add(new Paragraph("Completed " + AT.format(c.atZone(zone)), small));
        }
        if (t.get("reissuedFrom") instanceof Map<?, ?> from) {
            pdf.add(new Paragraph("Reissue of ticket " + from.get("ticketNumber"), small));
        }
        pdf.add(new Paragraph(" ", small));
        pdf.add(new Paragraph("Operator: ____________________     Driver: ____________________", small));
        pdf.close();
        return out.toByteArray();
    }

    private static void row(PdfPTable t, String k, String v, Font kf, Font vf) {
        t.addCell(cell(k, kf, Element.ALIGN_LEFT));
        t.addCell(cell(v, vf, Element.ALIGN_LEFT));
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(3);
        return c;
    }

    private static String label(String subject) {
        return switch (subject) {
            case "sale_delivery" -> "Sale delivery (outbound)";
            case "purchase_receipt" -> "Purchase receipt (inbound)";
            case "internal_haulage" -> "Internal haulage";
            default -> "Other";
        };
    }

    private static String plain(Object v) {
        return v instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : v == null ? "—" : String.valueOf(v);
    }

    private static String nz(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
