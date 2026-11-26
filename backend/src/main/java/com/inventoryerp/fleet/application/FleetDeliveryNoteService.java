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
        return render(tripId, true);
    }

    /** The delivery note; {@code count} = a print (numbered copy), otherwise a view that is not counted (DEL-12). */
    @Transactional
    @SuppressWarnings("unchecked")
    public Pdf render(String tripId, boolean count) {
        int print = trips.deliveryNotePrint(tripId, count);
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
        if ("weighbridge_ticket".equals(t.get("weighSourceType"))) {
            Map<String, Object> ticket = ((List<Map<String, Object>>) t.getOrDefault("weighbridgeTickets", List.of()))
                    .stream().filter(w -> String.valueOf(t.get("weighSourceId")).equals(w.get("id"))).findFirst()
                    .orElse(null);
            carrier.append("\n").append(weighLine(ticket, t.get("weighSourceId")));
        } else if (t.get("weighSourceType") != null && !"manual".equals(t.get("weighSourceType"))) {
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
            String weight = customerWeightLine(pod);
            if (weight != null) {
                pdf.add(new Paragraph(weight, f10));
            }
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

    /**
     * Phase A A2: "Customer weight 19 000 kg (ticket CW-7) · quarry 20 000 kg · variance 1 000 kg — disputed" when the
     * customer's weighing was recorded; null otherwise. Evidence only: the note's quantities do not change.
     */
    static String customerWeightLine(Map<String, Object> pod) {
        if (pod == null || pod.get("customerNetKg") == null) {
            return null;
        }
        String outcome = switch (s(pod.get("weightOutcome"))) {
            case "within_tolerance" -> "within tolerance";
            case "heavier" -> "customer heavier (flagged)";
            case "disputed" -> "disputed";
            case "awaiting_split" -> "disputed — split to confirm";
            default -> "no quarry weight to compare";
        };
        return "Customer weight " + plain(pod.get("customerNetKg")) + " kg"
                + (pod.get("customerTicketRef") == null ? "" : " (ticket " + pod.get("customerTicketRef") + ")")
                + (pod.get("quarryNetKg") == null ? "" : " · quarry " + plain(pod.get("quarryNetKg")) + " kg · variance "
                        + plain(pod.get("weightVarianceKg")) + " kg")
                + " — " + outcome;
    }

    /** "Weighbridge ticket WBT-… · gross / tare / net kg" for the ticket that fed the trip (flagged when manual). */
    static String weighLine(Map<String, Object> ticket, Object fallbackId) {
        if (ticket == null) {
            return "Weighbridge ticket: " + s(fallbackId);
        }
        return "Weighbridge ticket: " + s(ticket.get("ticketNumber"))
                + "\nGross " + plain(ticket.get("grossKg")) + " kg · Tare " + plain(ticket.get("tareKg"))
                + " kg · Net " + plain(ticket.get("netKg")) + " kg"
                + (Boolean.TRUE.equals(ticket.get("isManual")) ? " (manual weight)" : "");
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
