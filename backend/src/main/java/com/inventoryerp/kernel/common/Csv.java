package com.inventoryerp.kernel.common;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Rows to RFC 4180 CSV: UTF-8 with a BOM so Excel reads the accents, a header row, and text cells that a spreadsheet
 * would run as a formula neutralized with a leading apostrophe. Numbers stay numbers.
 */
public final class Csv {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private Csv() {}

    /** One line per row; {@code columns} are the header and the row keys. A missing or null value is an empty cell. */
    public static byte[] of(List<String> columns, List<Map<String, Object>> rows) {
        StringBuilder out = new StringBuilder();
        out.append(String.join(",", columns.stream().map(Csv::quote).toList())).append("\r\n");
        for (Map<String, Object> row : rows) {
            out.append(String.join(",", columns.stream().map(c -> cell(row.get(c))).toList())).append("\r\n");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(BOM);
        bytes.writeBytes(out.toString().getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
    }

    static String cell(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof BigDecimal b) {
            return b.toPlainString();
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return quote(guard(v.toString()));
    }

    private static String guard(String s) {
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    private static String quote(String s) {
        boolean plain = s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0;
        return plain ? s : '"' + s.replace("\"", "\"\"") + '"';
    }
}
