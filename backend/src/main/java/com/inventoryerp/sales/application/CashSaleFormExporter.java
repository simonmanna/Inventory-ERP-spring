package com.inventoryerp.sales.application;

import com.inventoryerp.core.application.ProductSizingService;
import static com.inventoryerp.sales.application.SalesDb.org;

import com.inventoryerp.kernel.files.FilesService;
import com.inventoryerp.kernel.files.FilesService.FileMeta;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders one cash sale as the printable "CASH SALES FORM" workbook (layout of {@code sample/CASH SALE FORM.xlsx}):
 * company logo and name, date band, customer details, order items with live line / order total formulas and the
 * final cash received. Read-only; visibility is the same as {@link CashSaleService#get(String)}.
 */
@Service
public class CashSaleFormExporter {

    /** The rendered workbook. */
    public record Export(String filename, byte[] content) {}

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final Pattern FILE_ID = Pattern.compile("/files/([0-9a-fA-F-]{36})/");
    private static final long MAX_LOGO_BYTES = 5L * 1024 * 1024;

    private static final String ORANGE = "F79646";
    private static final String ORANGE_DARK = "E26B0A";
    private static final String ORANGE_LIGHT = "FCD5B4";
    private static final String ORANGE_MID = "FABF8F";
    private static final String HEADER_FILL = "F2E1D9";
    private static final String GOLD = "D9A641";
    private static final String YELLOW = "FFFF00";
    private static final String GRID = "D9D9D9";
    private static final String NAVY = "1F3B57";
    private static final String MONEY = "#,##0.00";
    private static final String QTY = "#,##0.###";

    private final CashSaleService sales;
    private final SalesDb db;
    private final FilesService files;

    public CashSaleFormExporter(CashSaleService sales, SalesDb db, FilesService files) {
        this.sales = sales;
        this.db = db;
        this.files = files;
    }

    /** The cash sale form of submission {@code id} as an XLSX workbook. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Export export(String id) {
        Map<String, Object> s = sales.get(id);
        Map<String, Object> o = db.row("SELECT name, settings->>'logoUrl' AS \"logoUrl\" FROM \"Organization\""
                + " WHERE id = ?", org());
        String company = o == null ? "" : text(o.get("name"));
        byte[] logo = o == null ? null : logo(text(o.get("logoUrl")));
        List<Map<String, Object>> lines = (List<Map<String, Object>>) s.getOrDefault("lines", List.of());
        String number = text(s.get("number"));
        return new Export("cash-sale-" + (number.isEmpty() ? id : number).replaceAll("[^A-Za-z0-9._-]", "_") + ".xlsx",
                render(s, lines, company, logo));
    }

    // ------------------------------------------------------------------ workbook

    static byte[] render(Map<String, Object> s, List<Map<String, Object>> lines, String company, byte[] logo) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            XSSFSheet sh = wb.createSheet("Cash Sale");
            double[] widths = {18.66, 25, 22.22, 14.22, 23.44, 21.22, 16.44, 17.44};
            for (int i = 0; i < widths.length; i++) {
                sh.setColumnWidth(i, (int) Math.round(widths[i] * 256));
            }

            band(sh, 2, company.toUpperCase(), st.title);
            sh.getRow(2).setHeightInPoints(20);
            band(sh, 3, "CASH SALES  FORM", st.subtitle);
            band(sh, 4, "Complete blue cells. Amounts and order total calculate automatically.", st.note);

            Row dr = row(sh, 6);
            fill(dr, 0, 2, st.dateLabel);
            dr.getCell(0).setCellValue("DATE");
            sh.addMergedRegion(new CellRangeAddress(6, 6, 0, 2));
            fill(dr, 4, 7, st.dateValue);
            LocalDate date = date(s.get("saleDate"));
            if (date != null) {
                dr.getCell(4).setCellValue(date);
            }
            sh.addMergedRegion(new CellRangeAddress(6, 6, 4, 7));

            band(sh, 9, "A. CUSTOMER DETAILS", st.section);
            String customer = first(s.get("customerName"), s.get("partnerName"));
            detail(sh, 10, "Customer Name", customer, st);
            detail(sh, 11, "SALES BRANCH", text(s.get("branchName")).toUpperCase(), st);
            detail(sh, 12, "ORDER NUMBER", text(s.get("number")), st);

            band(sh, 14, "C. ORDER ITEMS", st.section);
            Row hr = row(sh, 15);
            String[] heads = {"Product", "Specification / Grade", "Unit", "Quantity", "Unit Price", "Q. IN METERS",
                "Line Total"};
            for (int i = 0; i < heads.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(heads[i]);
                c.setCellStyle(st.colHead);
            }
            hr.createCell(7).setCellStyle(st.boldGrid);

            int first = 16;
            int r = first;
            for (Map<String, Object> l : lines) {
                Row x = row(sh, r);
                String product = text(l.get("productName"));
                String spec = first(l.get("variantName"), product.equals(text(l.get("description"))) ? null
                        : l.get("description"));
                // ADR-S066: pieces and dimensions of a line sold by size
                String size = ProductSizingService.label(ProductSizingService.Sizing.stored(l),
                        l.get("sizingUnit") == null ? null : String.valueOf(l.get("sizingUnit")));
                if (size != null) {
                    spec = spec == null || spec.isBlank() ? size : spec + " — " + size;
                }
                put(x, 0, product, st.product);
                put(x, 1, spec, st.grid);
                put(x, 2, text(l.get("uomCode")), st.grid);
                num(x, 3, l.get("quantity"), st.qty);
                num(x, 4, l.get("unitPrice"), st.money);
                num(x, 5, l.get("quantity"), st.qty);
                BigDecimal disc = dec(l.get("discountPercent"));
                String ref = (r + 1) + "";
                Cell total = x.createCell(6);
                total.setCellFormula(disc.signum() == 0 ? "D" + ref + "*E" + ref
                        : "D" + ref + "*E" + ref + "*(1-" + disc.toPlainString() + "/100)");
                total.setCellStyle(st.money);
                x.createCell(7).setCellStyle(st.grid);
                r++;
            }
            if (r == first) {
                Row x = row(sh, r);
                fill(x, 0, 7, st.grid);
                r++;
            }
            int last = r - 1;

            Row tr = row(sh, r);
            tr.setHeightInPoints(33);
            fill(tr, 0, 3, st.grid);
            fill(tr, 4, 5, st.totalLabel);
            tr.getCell(4).setCellValue("ORDER TOTAL");
            Cell sum = tr.createCell(6);
            sum.setCellFormula("SUM(G" + (first + 1) + ":G" + (last + 1) + ")");
            sum.setCellStyle(st.totalValue);
            tr.createCell(7).setCellStyle(st.totalValue);
            int totalRow = r + 1;

            Row tax = row(sh, r + 1);
            BigDecimal taxTotal = dec(s.get("taxTotal"));
            if (taxTotal.signum() != 0) {
                tax.createCell(4).setCellValue("TAX");
                tax.createCell(6).setCellValue(taxTotal.doubleValue());
            } else {
                tax.createCell(6);
            }
            tax.getCell(6).setCellStyle(st.plainMoney);
            int taxRow = r + 2;

            Row fr = row(sh, r + 3);
            fr.setHeightInPoints(18);
            fill(fr, 4, 5, st.finalLabel);
            fr.getCell(4).setCellValue("FINAL CASH RECEIVED");
            Cell fin = fr.createCell(6);
            fin.setCellFormula("G" + totalRow + "+G" + taxRow);
            fin.setCellStyle(st.finalValue);

            if (logo != null) {
                addLogo(wb, sh, logo);
            }
            sh.getPrintSetup().setLandscape(false);
            sh.getPrintSetup().setScale((short) 62);
            sh.setFitToPage(false);
            wb.setPrintArea(0, 0, 7, 0, r + 4);
            XSSFFormulaEvaluator.evaluateAllFormulaCells(wb);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not render the cash sale form", e);
        }
    }

    private static void addLogo(XSSFWorkbook wb, XSSFSheet sh, byte[] logo) {
        int type = logo.length > 3 && (logo[0] & 0xFF) == 0xFF && (logo[1] & 0xFF) == 0xD8 ? Workbook.PICTURE_TYPE_JPEG
                : logo.length > 3 && (logo[0] & 0xFF) == 0x89 && logo[1] == 'P' ? Workbook.PICTURE_TYPE_PNG : -1;
        if (type < 0) {
            return; // only PNG / JPEG embed reliably in XLSX
        }
        int pic = wb.addPicture(logo, type);
        XSSFDrawing drawing = sh.createDrawingPatriarch();
        // Same box as the sample: column A, rows 2-6 (about 1.1 x 0.9 inch); the picture keeps its aspect ratio.
        XSSFClientAnchor a = new XSSFClientAnchor(0, 76200, 1036320, 19882, 0, 1, 0, 6);
        a.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
        drawing.createPicture(a, pic);
    }

    // ------------------------------------------------------------------ logo

    /** Bytes of the organization logo (a file of this org referenced by its signed URL), or {@code null}. */
    private byte[] logo(String logoUrl) {
        Matcher m = FILE_ID.matcher(logoUrl == null ? "" : logoUrl);
        if (!m.find()) {
            return null;
        }
        try {
            FileMeta meta = files.meta(m.group(1));
            if (meta == null || meta.byteSize() > MAX_LOGO_BYTES) {
                return null;
            }
            try (InputStream in = files.open(meta)) {
                return in.readNBytes((int) MAX_LOGO_BYTES);
            }
        } catch (IOException | RuntimeException e) {
            return null; // the form still renders without a logo
        }
    }

    // ------------------------------------------------------------------ cells

    private static Row row(XSSFSheet sh, int r) {
        Row x = sh.getRow(r);
        return x == null ? sh.createRow(r) : x;
    }

    private static void band(XSSFSheet sh, int r, String value, XSSFCellStyle style) {
        Row x = row(sh, r);
        fill(x, 0, 7, style);
        x.getCell(0).setCellValue(value);
        sh.addMergedRegion(new CellRangeAddress(r, r, 0, 7));
    }

    private static void detail(XSSFSheet sh, int r, String label, String value, Styles st) {
        Row x = row(sh, r);
        put(x, 0, label, st.detailLabel);
        put(x, 1, value, st.detailValue);
        x.createCell(2).setCellStyle(st.detailValue);
        x.createCell(3).setCellStyle(st.boldShade);
        fill(x, 4, 7, st.detailBlank);
    }

    private static void fill(Row x, int from, int to, XSSFCellStyle style) {
        for (int i = from; i <= to; i++) {
            Cell c = x.getCell(i);
            (c == null ? x.createCell(i) : c).setCellStyle(style);
        }
    }

    private static void put(Row x, int col, String value, XSSFCellStyle style) {
        Cell c = x.createCell(col);
        if (value != null && !value.isEmpty()) {
            c.setCellValue(value);
        }
        c.setCellStyle(style);
    }

    private static void num(Row x, int col, Object value, XSSFCellStyle style) {
        Cell c = x.createCell(col);
        if (value != null) {
            c.setCellValue(dec(value).doubleValue());
        }
        c.setCellStyle(style);
    }

    static String text(Object v) {
        return v == null ? "" : v.toString().trim();
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

    // ------------------------------------------------------------------ styles

    /** The sample form's cell styles (Calibri 11, light-grey grid, orange accent bands). */
    private static final class Styles {
        final XSSFCellStyle title;
        final XSSFCellStyle subtitle;
        final XSSFCellStyle note;
        final XSSFCellStyle dateLabel;
        final XSSFCellStyle dateValue;
        final XSSFCellStyle section;
        final XSSFCellStyle detailLabel;
        final XSSFCellStyle detailValue;
        final XSSFCellStyle detailBlank;
        final XSSFCellStyle boldShade;
        final XSSFCellStyle colHead;
        final XSSFCellStyle boldGrid;
        final XSSFCellStyle grid;
        final XSSFCellStyle product;
        final XSSFCellStyle qty;
        final XSSFCellStyle money;
        final XSSFCellStyle totalLabel;
        final XSSFCellStyle totalValue;
        final XSSFCellStyle plainMoney;
        final XSSFCellStyle finalLabel;
        final XSSFCellStyle finalValue;

        Styles(XSSFWorkbook wb) {
            XSSFFont regular = font(wb, false, false, 11, null);
            XSSFFont bold = font(wb, true, false, 11, null);
            XSSFFont white = font(wb, true, false, 11, "FFFFFF");
            short moneyFmt = wb.createDataFormat().getFormat(MONEY);
            short qtyFmt = wb.createDataFormat().getFormat(QTY);
            short dateFmt = wb.createDataFormat().getFormat("dd/mm/yyyy");

            title = style(wb, font(wb, true, false, 16, NAVY), null, true, HorizontalAlignment.CENTER);
            subtitle = style(wb, font(wb, true, false, 13, null), null, true, HorizontalAlignment.CENTER);
            note = style(wb, font(wb, false, true, 11, null), null, true, HorizontalAlignment.CENTER);
            dateLabel = style(wb, white, ORANGE_DARK, true, HorizontalAlignment.CENTER);
            dateValue = style(wb, white, ORANGE_DARK, true, HorizontalAlignment.CENTER);
            dateValue.setDataFormat(dateFmt);
            section = style(wb, white, ORANGE, true, HorizontalAlignment.CENTER);
            detailLabel = style(wb, bold, ORANGE_LIGHT, true, null);
            detailValue = style(wb, regular, ORANGE_LIGHT, true, null);
            detailBlank = style(wb, regular, ORANGE_LIGHT, true, HorizontalAlignment.CENTER);
            boldShade = style(wb, bold, "EEF3F6", true, null);
            colHead = style(wb, bold, HEADER_FILL, true, null);
            boldGrid = style(wb, bold, null, true, null);
            grid = style(wb, regular, null, true, null);
            product = style(wb, regular, ORANGE_LIGHT, true, null);
            qty = style(wb, regular, null, true, null);
            qty.setDataFormat(qtyFmt);
            money = style(wb, regular, null, true, null);
            money.setDataFormat(moneyFmt);
            totalLabel = style(wb, bold, GOLD, true, null);
            totalValue = style(wb, bold, ORANGE_MID, true, null);
            totalValue.setDataFormat(moneyFmt);
            plainMoney = style(wb, regular, null, false, null);
            plainMoney.setDataFormat(moneyFmt);
            XSSFFont big = font(wb, true, false, 14, null);
            finalLabel = style(wb, big, YELLOW, false, null);
            finalValue = style(wb, big, YELLOW, false, null);
            finalValue.setDataFormat(moneyFmt);
        }

        private static XSSFFont font(XSSFWorkbook wb, boolean bold, boolean italic, int size, String rgb) {
            XSSFFont f = wb.createFont();
            f.setFontName("Calibri");
            f.setFontHeightInPoints((short) size);
            f.setBold(bold);
            f.setItalic(italic);
            if (rgb != null) {
                f.setColor(color(rgb));
            }
            return f;
        }

        private static XSSFCellStyle style(XSSFWorkbook wb, XSSFFont font, String fillRgb, boolean border,
                HorizontalAlignment align) {
            XSSFCellStyle s = wb.createCellStyle();
            s.setFont(font);
            s.setWrapText(true);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            if (align != null) {
                s.setAlignment(align);
            }
            if (fillRgb != null) {
                s.setFillForegroundColor(color(fillRgb));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (border) {
                s.setBorderTop(BorderStyle.THIN);
                s.setBorderBottom(BorderStyle.THIN);
                s.setBorderLeft(BorderStyle.THIN);
                s.setBorderRight(BorderStyle.THIN);
                XSSFColor g = color(GRID);
                s.setTopBorderColor(g);
                s.setBottomBorderColor(g);
                s.setLeftBorderColor(g);
                s.setRightBorderColor(g);
            }
            return s;
        }

        private static XSSFColor color(String rgb) {
            int v = Integer.parseInt(rgb, 16);
            return new XSSFColor(new byte[] {(byte) (v >> 16), (byte) (v >> 8), (byte) v}, null);
        }
    }
}
