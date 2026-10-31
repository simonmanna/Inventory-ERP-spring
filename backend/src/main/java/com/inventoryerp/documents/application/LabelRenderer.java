package com.inventoryerp.documents.application;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfWriter;

/**
 * Renders labels (INT-3) on a 100 × 60 mm stock: a PDF with one label per page for any printer, the barcode drawn from
 * a ZXing matrix as vector bars, or ZPL for a Zebra thermal printer, which draws its own barcode. Pure.
 */
public final class LabelRenderer {

    public static final String QR = "qr";
    public static final String CODE128 = "code128";

    private static final float WIDTH = 283.46f;
    private static final float HEIGHT = 170.08f;
    private static final float MARGIN = 12f;
    private static final int MAX_TEXT = 40;

    /** One label: the text lines and the value in the barcode. */
    public record Label(String title, String subtitle, String code) {}

    private LabelRenderer() {}

    /** One page per copy of each label. */
    public static byte[] pdf(List<Label> labels, String symbology, int copies) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(new Rectangle(WIDTH, HEIGHT), 0, 0, 0, 0);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            doc.open();
            BaseFont font = BaseFont.createFont();
            PdfContentByte cb = writer.getDirectContent();
            boolean first = true;
            for (Label label : labels) {
                for (int c = 0; c < copies; c++) {
                    if (!first) {
                        doc.newPage();
                    }
                    first = false;
                    draw(cb, font, label, symbology);
                }
            }
            doc.close();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Label rendering failed", e);
        }
        return out.toByteArray();
    }

    private static void draw(PdfContentByte cb, BaseFont font, Label label, String symbology) {
        boolean qr = QR.equals(symbology);
        int textWidth = qr ? MAX_TEXT / 2 : MAX_TEXT;
        cb.beginText();
        cb.setFontAndSize(font, 13);
        cb.showTextAligned(PdfContentByte.ALIGN_LEFT, fit(label.title(), textWidth), MARGIN, HEIGHT - 26, 0);
        cb.setFontAndSize(font, 9);
        cb.showTextAligned(PdfContentByte.ALIGN_LEFT, fit(label.subtitle(), textWidth), MARGIN, HEIGHT - 42, 0);
        cb.endText();
        cb.setRGBColorFill(0, 0, 0);
        if (qr) {
            BitMatrix m = qrMatrix(label.code());
            float size = HEIGHT - 2 * MARGIN;
            modules(cb, m, WIDTH - MARGIN - size, HEIGHT - MARGIN, size / m.getWidth(), size / m.getHeight());
            cb.beginText();
            cb.setFontAndSize(font, 9);
            cb.showTextAligned(PdfContentByte.ALIGN_LEFT, fit(label.code(), textWidth), MARGIN, MARGIN, 0);
            cb.endText();
        } else {
            BitMatrix m = code128Matrix(label.code());
            float barWidth = (WIDTH - 2 * MARGIN) / m.getWidth();
            modules(cb, m, MARGIN, HEIGHT - 56, barWidth, 80f / m.getHeight());
            cb.beginText();
            cb.setFontAndSize(font, 10);
            cb.showTextAligned(PdfContentByte.ALIGN_CENTER, fit(ascii(label.code()), MAX_TEXT), WIDTH / 2, MARGIN, 0);
            cb.endText();
        }
    }

    /** Fills the dark modules; {@code top} is the y of the matrix's first row. */
    private static void modules(PdfContentByte cb, BitMatrix m, float left, float top, float w, float h) {
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (m.get(x, y)) {
                    cb.rectangle(left + x * w, top - (y + 1) * h, w, h);
                }
            }
        }
        cb.fill();
    }

    static BitMatrix qrMatrix(String text) {
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        } catch (WriterException e) {
            throw new IllegalStateException("QR encoding failed", e);
        }
    }

    static BitMatrix code128Matrix(String text) {
        return new Code128Writer().encode(ascii(text), BarcodeFormat.CODE_128, 0, 1);
    }

    /** ZPL II for a 203 dpi printer; {@code ^PQ} prints the copies. */
    public static String zpl(List<Label> labels, String symbology, int copies) {
        StringBuilder out = new StringBuilder();
        for (Label label : labels) {
            String code = zplField(label.code());
            out.append("^XA^CI28^PW812^LL480\n");
            out.append("^FO30,30^A0N,40,40^FD").append(zplField(fit(label.title(), MAX_TEXT))).append("^FS\n");
            out.append("^FO30,85^A0N,26,26^FD").append(zplField(fit(label.subtitle(), MAX_TEXT))).append("^FS\n");
            if (QR.equals(symbology)) {
                out.append("^FO520,130^BQN,2,7^FDMA,").append(code).append("^FS\n");
                out.append("^FO30,420^A0N,26,26^FD").append(code).append("^FS\n");
            } else {
                out.append("^FO30,160^BY3^BCN,200,Y,N,N^FD").append(ascii(code)).append("^FS\n");
            }
            out.append("^PQ").append(copies).append("\n^XZ\n");
        }
        return out.toString();
    }

    /** {@code ^} and {@code ~} start ZPL commands, so a value can never carry them. */
    private static String zplField(String s) {
        return s == null ? "" : s.replace('^', ' ').replace('~', ' ').replace('\n', ' ');
    }

    /** Code 128 carries printable ASCII. */
    private static String ascii(String s) {
        String v = s == null ? "" : s.replaceAll("[^\\x20-\\x7E]", "?");
        return v.isEmpty() ? "?" : v;
    }

    private static String fit(String s, int max) {
        String v = s == null ? "" : s;
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }
}
