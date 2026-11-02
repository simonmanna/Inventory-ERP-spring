package com.inventoryerp.reporting.application;

import com.inventoryerp.reporting.application.ReportEngine.Header;
import com.inventoryerp.reporting.application.ReportEngine.ReportResult;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.shared.web.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/**
 * Renders any {@link ReportResult} as CSV, XLSX or PDF with the standard header (company, report, period, currency,
 * branches, generated at / by, run id). Negative amounts print in parentheses in PDF, as in a financial statement.
 */
@Component
public class ReportExporter {

    /** A rendered file. */
    public record Export(String filename, String contentType, byte[] content) {}

    public static final List<String> FORMATS = List.of("csv", "xlsx", "pdf");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC);

    public Export render(ReportResult r, String format) {
        String base = r.header().reportId().replace('.', '-') + "-" + (r.header().asOf() != null ? r.header().asOf()
                : r.header().from() + "_" + r.header().to());
        return switch (format == null ? "" : format) {
            case "csv" -> new Export(base + ".csv", "text/csv; charset=utf-8", csv(r));
            case "xlsx" -> new Export(base + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(r));
            case "pdf" -> new Export(base + ".pdf", "application/pdf", pdf(r));
            default -> throw ApiException.badRequest("format must be one of: " + String.join(", ", FORMATS));
        };
    }

    static List<String> headerLines(Header h) {
        List<String> out = new ArrayList<>();
        out.add(h.company() == null ? "" : h.company());
        out.add(h.title());
        out.add((h.asOf() != null ? "As of " + h.asOf() : "Period " + h.from() + " to " + h.to()) + " · Amounts in "
                + h.currency() + " · " + h.basis() + " basis · " + h.postingStatus() + " entries");
        out.add("Branches: " + (h.branches().isEmpty() ? "All" : String.join(", ", h.branches())));
        out.add("Generated " + STAMP.format(h.generatedAt()) + (h.generatedBy() == null ? "" : " by "
                + h.generatedBy()) + " · Run " + h.runId());
        return out;
    }

    private static Object cell(ReportBody.Row row, ReportBody.Column c) {
        Object v = row.values().get(c.key());
        if ("label".equals(c.key()) && v != null && row.depth() > 0) {
            return "  ".repeat(row.depth()) + v;
        }
        return v;
    }

    // ---------------------------------------------------------------- CSV

    byte[] csv(ReportResult r) {
        StringBuilder out = new StringBuilder();
        for (String l : headerLines(r.header())) {
            out.append(field(l)).append('\n');
        }
        out.append('\n');
        out.append(String.join(",", r.columns().stream().map(c -> field(c.label())).toList())).append('\n');
        for (ReportBody.Row row : r.rows()) {
            List<String> cells = new ArrayList<>();
            for (ReportBody.Column c : r.columns()) {
                Object v = cell(row, c);
                cells.add(field(v instanceof BigDecimal b ? b.toPlainString() : v == null ? "" : v.toString()));
            }
            out.append(String.join(",", cells)).append('\n');
        }
        return ("﻿" + out).getBytes(StandardCharsets.UTF_8);
    }

    private static String field(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ---------------------------------------------------------------- XLSX

    byte[] xlsx(ReportResult r) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(safeSheet(r.header().title()));
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle title = wb.createCellStyle();
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            title.setFont(big);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00;(#,##0.00)"));
            CellStyle moneyBold = wb.createCellStyle();
            moneyBold.cloneStyleFrom(money);
            moneyBold.setFont(bold);
            CellStyle textBold = wb.createCellStyle();
            textBold.setFont(bold);
            CellStyle pct = wb.createCellStyle();
            pct.setDataFormat(wb.createDataFormat().getFormat("0.00\"%\""));

            int rowNo = 0;
            List<String> lines = headerLines(r.header());
            for (int i = 0; i < lines.size(); i++) {
                Cell c = sheet.createRow(rowNo++).createCell(0);
                c.setCellValue(lines.get(i));
                if (i <= 1) {
                    c.setCellStyle(title);
                }
            }
            rowNo++;
            Row hr = sheet.createRow(rowNo++);
            for (int i = 0; i < r.columns().size(); i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(r.columns().get(i).label());
                c.setCellStyle(head);
            }
            sheet.createFreezePane(0, rowNo);
            for (ReportBody.Row row : r.rows()) {
                Row xr = sheet.createRow(rowNo++);
                boolean percentRow = "percent".equals(row.values().get("_format"));
                for (int i = 0; i < r.columns().size(); i++) {
                    ReportBody.Column col = r.columns().get(i);
                    Object v = cell(row, col);
                    Cell c = xr.createCell(i);
                    if (v instanceof BigDecimal b) {
                        c.setCellValue(b.doubleValue());
                        c.setCellStyle("percent".equals(col.type()) || percentRow ? pct : row.bold() ? moneyBold
                                : money);
                    } else if (v instanceof Number n) {
                        c.setCellValue(n.doubleValue());
                    } else if (v != null) {
                        c.setCellValue(v.toString());
                        if (row.bold()) {
                            c.setCellStyle(textBold);
                        }
                    }
                }
            }
            for (int i = 0; i < r.columns().size(); i++) {
                sheet.setColumnWidth(i, i == 0 ? 50 * 256 : 18 * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String safeSheet(String s) {
        String clean = s.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return clean.length() > 31 ? clean.substring(0, 31) : clean;
    }

    // ---------------------------------------------------------------- PDF

    byte[] pdf(ReportResult r) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean wide = r.columns().size() > 5;
        Document doc = new Document(wide ? PageSize.A4.rotate() : PageSize.A4, 30, 30, 30, 30);
        PdfWriter.getInstance(doc, out);
        doc.open();
        List<String> lines = headerLines(r.header());
        doc.add(new Paragraph(lines.get(0), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
        doc.add(new Paragraph(lines.get(1), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12)));
        for (String l : lines.subList(2, lines.size())) {
            doc.add(new Paragraph(l, FontFactory.getFont(FontFactory.HELVETICA, 8)));
        }
        doc.add(new Paragraph(" "));
        PdfPTable table = new PdfPTable(r.columns().size());
        table.setWidthPercentage(100);
        float[] widths = new float[r.columns().size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = "text".equals(r.columns().get(i).type()) ? (i == 0 ? 3f : 1.6f) : 1.2f;
        }
        table.setWidths(widths);
        table.setHeaderRows(1);
        var headFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        var bodyFont = FontFactory.getFont(FontFactory.HELVETICA, 8);
        var boldFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        for (ReportBody.Column c : r.columns()) {
            PdfPCell cell = new PdfPCell(new Phrase(c.label(), headFont));
            cell.setHorizontalAlignment("text".equals(c.type()) ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            table.addCell(cell);
        }
        DecimalFormat fmt = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        for (ReportBody.Row row : r.rows()) {
            boolean percentRow = "percent".equals(row.values().get("_format"));
            for (ReportBody.Column c : r.columns()) {
                Object v = cell(row, c);
                String text;
                if (v instanceof BigDecimal b) {
                    String n = fmt.format(b.abs());
                    text = "percent".equals(c.type()) || percentRow ? fmt.format(b) + "%" : b.signum() < 0 ? "(" + n
                            + ")" : n;
                } else {
                    text = v == null ? "" : v.toString();
                }
                PdfPCell cell = new PdfPCell(new Phrase(text, row.bold() ? boldFont : bodyFont));
                cell.setHorizontalAlignment("text".equals(c.type()) || "date".equals(c.type()) ? Element.ALIGN_LEFT
                        : Element.ALIGN_RIGHT);
                cell.setBorder(row.bold() ? PdfPCell.TOP : PdfPCell.NO_BORDER);
                table.addCell(cell);
            }
        }
        doc.add(table);
        if (!r.checks().isEmpty()) {
            doc.add(new Paragraph(" "));
            for (ReportBody.Check c : r.checks()) {
                doc.add(new Paragraph((c.ok() ? "[OK] " : "[FAIL] ") + c.label() + (c.detail() == null ? "" : " — "
                        + c.detail()), FontFactory.getFont(FontFactory.HELVETICA, 7)));
            }
        }
        doc.close();
        return out.toByteArray();
    }
}
