package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.common.BusinessZone;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Printable delivery note of a dispatched trip (fleet 1.3) — OpenPDF like the other printable documents
 * (invoices, statements): organization, note number, customer and site (address, GPS, contact, access notes), truck /
 * driver or transporter, products with the dispatched quantity in the line unit, and — once delivered — the
 * received / returned quantities and the receiver. Reprints are marked COPY.
 */
@Service
public class FleetDeliveryNoteService {

    public record Pdf(String filename, byte[] content) {}

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final FleetDb db;
    private final FleetDispatchService trips;

    public FleetDeliveryNoteService(FleetDb db, FleetDispatchService trips) {
        this.db = db;
        this.trips = trips;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public Pdf render(String tripId) {
        int print = trips.countDeliveryNotePrint(tripId);
        Map<String, Object> t = trips.get(tripId);
        Map<String, Object> o = (Map<String, Object>) t.get("deliveryOrder");
        Map<String, Object> site = o == null ? null : (Map<String, Object>) o.get("site");
        Map<String, Object> pod = (Map<String, Object>) t.get("pod");
        List<Map<String, Object>> lines = (List<Map<String, Object>>) t.get("lines");
        String orgName = db.jdbc().sql("SELECT name FROM \"Organization\" WHERE id = ?").param(FleetDb.org())
                .query(String.class).optional().orElse("");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4, 40, 40, 40, 40);
        PdfWriter.getInstance(pdf, out);
        pdf.open();
        Font gray = FontFactory.getFont(FontFactory.HELVETICA, 9, java.awt.Color.GRAY);
        Font f10 = FontFactory.getFont(FontFactory.HELVETICA, 10);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
        pdf.add(new Paragraph(orgName, FontFactory.getFont(FontFactory.HELVETICA, 18)));
        Paragraph title = new Paragraph("DELIVERY NOTE" + (print > 1 ? " (COPY " + print + ")" : ""),
                FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16));
        title.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(title);
        Paragraph no = new Paragraph("# " + t.get("deliveryNoteNumber") + "   ·   Trip " + t.get("code")
                + "   ·   Order " + (o == null ? "" : o.get("code")), gray);
        no.setAlignment(Element.ALIGN_RIGHT);
        pdf.add(no);
        pdf.add(new Paragraph(" "));

        PdfPTable head = new PdfPTable(new float[] {1f, 1f});
        head.setWidthPercentage(100);
        StringBuilder to = new StringBuilder();
        to.append(o == null ? "" : s(o.get("partnerName")));
        if (site != null) {
            to.append("\n").append(s(site.get("name")));
            if (site.get("address") != null) {
                to.append("\n").append(site.get("address"));
            }
            if (site.get("city") != null) {
                to.append(", ").append(site.get("city"));
            }
            if (site.get("latitude") != null) {
                to.append("\nGPS ").append(site.get("latitude")).append(", ").append(site.get("longitude"));
            }
            if (site.get("accessNotes") != null) {
                to.append("\nAccess: ").append(site.get("accessNotes"));
            }
        }
        if (o != null && o.get("contactName") != null) {
            to.append("\nContact: ").append(o.get("contactName"))
                    .append(o.get("contactPhone") == null ? "" : " " + o.get("contactPhone"));
        }
        head.addCell(cell("Deliver to\n" + to, f10, Element.ALIGN_LEFT));
        StringBuilder carrier = new StringBuilder();
        if ("transporter".equals(t.get("carrier"))) {
            carrier.append("Transporter: ").append(s(t.get("transporterName")));
            if (t.get("transporterPlate") != null) {
                carrier.append("\nTruck: ").append(t.get("transporterPlate"));
            }
            if (t.get("transporterDriverName") != null) {
                carrier.append("\nDriver: ").append(t.get("transporterDriverName"));
            }
        } else {
            carrier.append("Truck: ").append(s(t.get("vehicleCode"))).append(" ").append(s(t.get("vehiclePlate")));
            if (t.get("trailerCode") != null) {
                carrier.append("\nTrailer: ").append(t.get("trailerCode"));
            }
            carrier.append("\nDriver: ").append(s(t.get("driverName"))).append(" (").append(s(t.get("driverCode")))
                    .append(")");
        }
        carrier.append("\nDispatched: ").append(dt(t.get("dispatchedAt")));
        if (t.get("loadedTonnes") != null) {
            carrier.append("\nLoaded: ").append(plain(t.get("loadedTonnes"))).append(" t");
        }
        if (t.get("weighSourceType") != null && !"manual".equals(t.get("weighSourceType"))) {
            carrier.append("\nWeighed: ").append(t.get("weighSourceType")).append(" ")
                    .append(s(t.get("weighSourceId")));
        }
        head.addCell(cell(carrier.toString(), f10, Element.ALIGN_LEFT));
        pdf.add(head);
        pdf.add(new Paragraph(" "));

        boolean delivered = pod != null;
        PdfPTable table = delivered ? new PdfPTable(new float[] {4f, 1f, 1.4f, 1.4f, 1.4f})
                : new PdfPTable(new float[] {4f, 1f, 1.6f});
        table.setWidthPercentage(100);
        table.addCell(cell("Product", bold, Element.ALIGN_LEFT));
        table.addCell(cell("Unit", bold, Element.ALIGN_LEFT));
        table.addCell(cell("Dispatched", bold, Element.ALIGN_RIGHT));
        if (delivered) {
            table.addCell(cell("Received", bold, Element.ALIGN_RIGHT));
            table.addCell(cell("Returned", bold, Element.ALIGN_RIGHT));
        }
        for (Map<String, Object> l : lines) {
            String product = s(l.get("productName")) + (l.get("batchNumber") == null ? "" : "  (batch "
                    + l.get("batchNumber") + ")");
            table.addCell(cell(product, f10, Element.ALIGN_LEFT));
            table.addCell(cell(s(l.get("uomCode")), f10, Element.ALIGN_LEFT));
            table.addCell(cell(plain(l.get("dispatchedQty")), f10, Element.ALIGN_RIGHT));
            if (delivered) {
                table.addCell(cell(plain(l.get("deliveredQty")), f10, Element.ALIGN_RIGHT));
                table.addCell(cell(plain(l.get("returnedQty")), f10, Element.ALIGN_RIGHT));
            }
        }
        pdf.add(table);
        pdf.add(new Paragraph(" "));
        if (delivered) {
            pdf.add(new Paragraph("Received by " + s(pod.get("receiverName")) + (pod.get("receiverPhone") == null ? ""
                    : " (" + pod.get("receiverPhone") + ")") + " on " + dt(pod.get("receivedAt"))
                    + (pod.get("latitude") == null ? "" : " at GPS " + pod.get("latitude") + ", " + pod.get("longitude"))
                    + (pod.get("signatureFileId") == null ? "" : " — signed electronically"), f10));
        } else {
            pdf.add(new Paragraph(" "));
            pdf.add(new Paragraph("Received in good order by: ______________________   Signature: ______________"
                    + "   Date / time: ______________", f10));
            pdf.add(new Paragraph(" "));
            pdf.add(new Paragraph("Driver signature: ______________________", f10));
        }
        if (t.get("notes") != null) {
            pdf.add(new Paragraph(" "));
            pdf.add(new Paragraph("Notes: " + t.get("notes"), gray));
        }
        pdf.close();
        return new Pdf(t.get("deliveryNoteNumber") + ".pdf", out.toByteArray());
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setHorizontalAlignment(align);
        c.setPadding(5);
        return c;
    }

    private static String s(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String plain(Object o) {
        if (o == null) {
            return "—";
        }
        return o instanceof BigDecimal b ? b.stripTrailingZeros().toPlainString() : o.toString();
    }

    private static String dt(Object o) {
        return o instanceof Instant i ? DT.format(i.atZone(BusinessZone.get())) : "—";
    }
}
