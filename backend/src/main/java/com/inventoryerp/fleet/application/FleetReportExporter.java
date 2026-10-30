package com.inventoryerp.fleet.application;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
 * Renders a fleet report table as CSV (UTF-8 with BOM, formula-injection guarded like the web exporter), XLSX (a
 * minimal SpreadsheetML workbook: inline strings, numbers as numbers, bold header and totals rows) or PDF (OpenPDF,
 * landscape A4). No third-party spreadsheet library: the workbook is the handful of XML parts Excel / LibreOffice need.
 */
public final class FleetReportExporter {

    /** One column: the row key, its header and whether it is numeric (right-aligned, a number cell in XLSX). */
    public record Column(String key, String header, boolean numeric) {

        public static Column text(String key, String header) {
            return new Column(key, header, false);
        }

        public static Column num(String key, String header) {
            return new Column(key, header, true);
        }
    }

    /** A report ready to render: title, subtitle (period / filters), columns, body rows and the totals row. */
    public record Table(String title, String subtitle, List<Column> columns, List<Map<String, Object>> rows,
            Map<String, Object> totals) {}

    /** The rendered file. */
    public record Export(String filename, String contentType, byte[] content) {}

    private FleetReportExporter() {
    }

    public static Export render(Table t, String format, String basename) {
        return switch (format == null ? "csv" : format) {
            case "xlsx" -> new Export(basename + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(t));
            case "pdf" -> new Export(basename + ".pdf", "application/pdf", pdf(t));
            default -> new Export(basename + ".csv", "text/csv; charset=utf-8", csv(t));
        };
    }

    static String cell(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal b) {
            return b.toPlainString();
        }
        if (v instanceof Boolean b) {
            return b ? "yes" : "no";
        }
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ CSV

    static byte[] csv(Table t) {
        StringBuilder sb = new StringBuilder("﻿");
        List<String> head = new ArrayList<>();
        for (Column c : t.columns()) {
            head.add(csvCell(c.header()));
        }
        sb.append(String.join(",", head)).append('\n');
        List<Map<String, Object>> all = new ArrayList<>(t.rows());
        if (t.totals() != null) {
            all.add(t.totals());
        }
        for (Map<String, Object> r : all) {
            List<String> cells = new ArrayList<>();
            for (Column c : t.columns()) {
                cells.add(csvCell(cell(r.get(c.key()))));
            }
            sb.append(String.join(",", cells)).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Quote; a text cell starting with = + - @ (or tab / CR) gets a leading quote so spreadsheets keep it text. */
    static String csvCell(String s) {
        String v = s == null ? "" : s;
        boolean number = v.matches("-?\\d+(\\.\\d+)?");
        String guarded = !number && !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 ? "'" + v : v;
        return "\"" + guarded.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------------ XLSX

    static byte[] xlsx(Table t) {
        StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        int r = 1;
        sheet.append(xlsxRow(r++, t.title(), 1));
        if (t.subtitle() != null) {
            sheet.append(xlsxRow(r++, t.subtitle(), 0));
        }
        r++;
        sheet.append("<row r=\"").append(r).append("\">");
        for (int i = 0; i < t.columns().size(); i++) {
            sheet.append(xlsxText(ref(i, r), t.columns().get(i).header(), 1));
        }
        sheet.append("</row>");
        r++;
        List<Map<String, Object>> all = new ArrayList<>(t.rows());
        if (t.totals() != null) {
            all.add(t.totals());
        }
        for (int k = 0; k < all.size(); k++) {
            boolean bold = t.totals() != null && k == all.size() - 1;
            Map<String, Object> row = all.get(k);
            sheet.append("<row r=\"").append(r).append("\">");
            for (int i = 0; i < t.columns().size(); i++) {
                Column c = t.columns().get(i);
                Object v = row.get(c.key());
                String text = cell(v);
                if (c.numeric() && !text.isEmpty() && text.matches("-?\\d+(\\.\\d+)?")) {
                    sheet.append("<c r=\"").append(ref(i, r)).append("\"").append(bold ? " s=\"1\"" : "")
                            .append("><v>").append(text).append("</v></c>");
                } else if (!text.isEmpty()) {
                    sheet.append(xlsxText(ref(i, r), text, bold ? 1 : 0));
                }
            }
            sheet.append("</row>");
            r++;
        }
        sheet.append("</sheetData></worksheet>");
        String sheetName = xml(t.title().length() > 31 ? t.title().substring(0, 31) : t.title())
                .replaceAll("[\\[\\]:*?/\\\\]", " ");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-"
                    + "officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument"
                    + ".spreadsheetml.styles+xml\"/></Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                    + "relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"" + sheetName + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                    + "relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                    + "relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            put(zip, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                    + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                    + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>"
                    + "<fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>"
                    + "</cellStyleXfs>"
                    + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                    + "</styleSheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String xlsxRow(int r, String text, int style) {
        return "<row r=\"" + r + "\">" + xlsxText(ref(0, r), text, style) + "</row>";
    }

    private static String xlsxText(String ref, String text, int style) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"" + (style > 0 ? " s=\"" + style + "\"" : "") + "><is><t"
                + " xml:space=\"preserve\">" + xml(text) + "</t></is></c>";
    }

    /** A1-style reference of zero-based column {@code col} in row {@code row}. */
    static String ref(int col, int row) {
        StringBuilder s = new StringBuilder();
        int c = col + 1;
        while (c > 0) {
            int m = (c - 1) % 26;
            s.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return s.append(row).toString();
    }

    static String xml(String s) {
        StringBuilder out = new StringBuilder();
        for (char ch : (s == null ? "" : s).toCharArray()) {
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    // ------------------------------------------------------------------ PDF

    static byte[] pdf(Table t) {
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        Font small = FontFactory.getFont(FontFactory.HELVETICA, 7);
        Font head = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, Color.WHITE);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document pdf = new Document(PageSize.A4.rotate(), 24, 24, 24, 24);
        PdfWriter.getInstance(pdf, out);
        pdf.open();
        pdf.add(new Paragraph(t.title(), title));
        if (t.subtitle() != null) {
            pdf.add(new Paragraph(t.subtitle(), small));
        }
        pdf.add(new Paragraph(" ", small));
        PdfPTable table = new PdfPTable(t.columns().size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        for (Column c : t.columns()) {
            PdfPCell h = new PdfPCell(new Phrase(c.header(), head));
            h.setBackgroundColor(new Color(60, 72, 88));
            h.setHorizontalAlignment(c.numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            table.addCell(h);
        }
        List<Map<String, Object>> all = new ArrayList<>(t.rows());
        if (t.totals() != null) {
            all.add(t.totals());
        }
        for (int k = 0; k < all.size(); k++) {
            boolean isTotal = t.totals() != null && k == all.size() - 1;
            for (Column c : t.columns()) {
                PdfPCell cell = new PdfPCell(new Phrase(cell(all.get(k).get(c.key())), isTotal ? bold : small));
                cell.setHorizontalAlignment(c.numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                if (isTotal) {
                    cell.setBackgroundColor(new Color(235, 238, 242));
                }
                table.addCell(cell);
            }
        }
        pdf.add(table);
        pdf.close();
        return out.toByteArray();
    }
}
