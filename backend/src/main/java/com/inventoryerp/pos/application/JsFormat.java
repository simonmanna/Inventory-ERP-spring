package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.common.JsNumbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** JavaScript string / number formatting used by the receipt and report builders. */
public final class JsFormat {

    private static final String[] MONTHS_GB = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sept", "Oct",
        "Nov", "Dec"};

    private JsFormat() {}

    /** {@code Number(n).toLocaleString()} (en-US: grouping commas, at most 3 fraction digits, half away from zero). */
    public static String locale(double n) {
        if (Double.isNaN(n)) {
            return "NaN";
        }
        if (Double.isInfinite(n)) {
            return n > 0 ? "∞" : "-∞";
        }
        BigDecimal d = new BigDecimal(Double.toString(n)).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        if (d.signum() == 0) {
            return n < 0 || (n == 0 && 1 / n < 0) ? "-0" : "0";
        }
        boolean neg = d.signum() < 0;
        String plain = d.abs().toPlainString();
        String intPart = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
        String frac = plain.contains(".") ? plain.substring(plain.indexOf('.')) : "";
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < intPart.length(); i++) {
            if (i > 0 && (intPart.length() - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(intPart.charAt(i));
        }
        return (neg ? "-" : "") + grouped + frac;
    }

    /** {@code `UGX ${Number(n || 0).toLocaleString()}`}. */
    public static String ugx(Object n) {
        double v = PosSql.num(n);
        return "UGX " + locale(Double.isNaN(v) ? 0 : v);
    }

    /** {@code String(n)} of a JS number. */
    public static String str(double n) {
        return JsNumbers.toJsString(n);
    }

    /** {@code n.toFixed(dp)}. */
    public static String toFixed(double n, int dp) {
        return new BigDecimal(n).setScale(dp, RoundingMode.HALF_UP).toPlainString();
    }

    public static String padStart(String s, int width) {
        return s.length() >= width ? s : " ".repeat(width - s.length()) + s;
    }

    public static String padStart(String s, int width, char fill) {
        return s.length() >= width ? s : String.valueOf(fill).repeat(width - s.length()) + s;
    }

    public static String padEnd(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    /** {@code s.slice(0, n)} ({@code n} may be negative like JS). */
    public static String slice(String s, int n) {
        int end = n < 0 ? Math.max(0, s.length() + n) : Math.min(n, s.length());
        return s.substring(0, end);
    }

    private static ZonedDateTime local(Instant i) {
        return i.atZone(ZoneId.systemDefault());
    }

    /** {@code d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })}. */
    public static String gbDate(Instant i) {
        ZonedDateTime z = local(i);
        return z.getDayOfMonth() + " " + MONTHS_GB[z.getMonthValue() - 1] + " " + z.getYear();
    }

    /** {@code HH:mm} in server-local time ({@code getHours()} / {@code getMinutes()}). */
    public static String hhmm(Instant i) {
        ZonedDateTime z = local(i);
        return padStart(String.valueOf(z.getHour()), 2, '0') + ":" + padStart(String.valueOf(z.getMinute()), 2, '0');
    }
}
