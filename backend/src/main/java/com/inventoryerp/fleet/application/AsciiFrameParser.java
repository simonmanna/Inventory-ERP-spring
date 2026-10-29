package com.inventoryerp.fleet.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Parser of continuous-output ASCII weight frames (fleet 1.4) as most weighbridge indicators send them, e.g.
 * {@code ST,GS,+0032150kg} (stable, gross, +32 150 kg), {@code US,GS,+0032120kg} (motion), {@code OL,GS,+9999999kg}
 * (overload) or a bare {@code 32.15 t}. The format is configurable per device:
 *
 * <ul>
 *   <li>{@code frameRegex} — a regex with the named group {@code weight} and the optional groups {@code status},
 *       {@code sign}, {@code unit} (default {@link #DEFAULT_REGEX}, case-insensitive);
 *   <li>{@code stableValues} / {@code motionValues} / {@code overloadValues} — status tokens (default {@code ST} /
 *       {@code US, MO, M} / {@code OL, OV}); a frame without a status counts as stable (stability then comes from
 *       {@code stableFrames} equal frames in a row);
 *   <li>{@code unit} — unit when the frame has none (default {@code kg}; {@code kg, t, g, lb});
 *   <li>{@code decimals} — implied decimal places when the weight has no decimal point (default 0);
 *   <li>{@code stableFrames} — consecutive equal stable frames that make a reading stable (default 3, 1–20).
 * </ul>
 *
 * A frame that does not match is ignored (partial frames, prompts, noise). Weights come back in kg (2 decimals).
 */
public final class AsciiFrameParser {

    public static final String DEFAULT_REGEX = "^\\s*(?:(?<status>ST|US|OL|OV|MO|M)\\s*,?\\s*)?"
            + "(?:(?:GS|NT|TR|G|N|T)\\s*,?\\s*)?(?<sign>[+-])?\\s*(?<weight>\\d+(?:\\.\\d+)?)\\s*(?<unit>kg|t|lb|g)?\\s*$";

    private static final Map<String, BigDecimal> KG_PER_UNIT = Map.of(
            "kg", BigDecimal.ONE,
            "t", new BigDecimal("1000"),
            "g", new BigDecimal("0.001"),
            "lb", new BigDecimal("0.45359237"));

    /** One parsed frame: weight in kg and the flags. */
    public record Frame(String raw, BigDecimal kilograms, boolean stable, boolean motion, boolean overload) {}

    private final Pattern pattern;
    private final boolean hasStatus;
    private final boolean hasSign;
    private final boolean hasUnit;
    private final Set<String> stableValues;
    private final Set<String> motionValues;
    private final Set<String> overloadValues;
    private final String defaultUnit;
    private final int decimals;
    private final int stableFrames;

    private AsciiFrameParser(Pattern pattern, Set<String> stableValues, Set<String> motionValues,
            Set<String> overloadValues, String defaultUnit, int decimals, int stableFrames) {
        this.pattern = pattern;
        String p = pattern.pattern();
        this.hasStatus = p.contains("?<status>");
        this.hasSign = p.contains("?<sign>");
        this.hasUnit = p.contains("?<unit>");
        this.stableValues = stableValues;
        this.motionValues = motionValues;
        this.overloadValues = overloadValues;
        this.defaultUnit = defaultUnit;
        this.decimals = decimals;
        this.stableFrames = stableFrames;
    }

    /** Parser for a device configuration; {@link IllegalArgumentException} with a readable message when invalid. */
    public static AsciiFrameParser of(Map<String, Object> config) {
        Map<String, Object> c = config == null ? Map.of() : config;
        String regex = str(c.get("frameRegex"));
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex == null ? DEFAULT_REGEX : regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("The frame regex is invalid: " + e.getDescription());
        }
        if (!pattern.pattern().contains("?<weight>")) {
            throw new IllegalArgumentException("The frame regex needs a named group (?<weight>...)");
        }
        String unit = str(c.get("unit"));
        unit = unit == null ? "kg" : unit.toLowerCase(Locale.ROOT);
        if (!KG_PER_UNIT.containsKey(unit)) {
            throw new IllegalArgumentException("Unit " + unit + " is not supported (kg, t, g, lb)");
        }
        int decimals = integer(c.get("decimals"), 0);
        if (decimals < 0 || decimals > 4) {
            throw new IllegalArgumentException("Implied decimals must be between 0 and 4");
        }
        int stableFrames = integer(c.get("stableFrames"), 3);
        if (stableFrames < 1 || stableFrames > 20) {
            throw new IllegalArgumentException("Stable frames must be between 1 and 20");
        }
        return new AsciiFrameParser(pattern, tokens(c.get("stableValues"), "ST"), tokens(c.get("motionValues"),
                "US,MO,M"), tokens(c.get("overloadValues"), "OL,OV"), unit, decimals, stableFrames);
    }

    public int stableFrames() {
        return stableFrames;
    }

    /** The frame, or {@code null} when it does not match the format. */
    public Frame parse(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.strip();
        if (line.isEmpty()) {
            return null;
        }
        Matcher m = pattern.matcher(line);
        if (!m.find()) {
            return null;
        }
        String w = m.group("weight");
        if (w == null || w.isBlank()) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(w.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (decimals > 0 && !w.contains(".")) {
            value = value.movePointLeft(decimals);
        }
        String sign = hasSign ? m.group("sign") : null;
        if ("-".equals(sign)) {
            value = value.negate();
        }
        String unit = hasUnit && m.group("unit") != null ? m.group("unit").toLowerCase(Locale.ROOT) : defaultUnit;
        BigDecimal factor = KG_PER_UNIT.get(unit);
        if (factor == null) {
            return null;
        }
        BigDecimal kg = value.multiply(factor).setScale(2, RoundingMode.HALF_UP);
        String status = hasStatus && m.group("status") != null ? m.group("status").toUpperCase(Locale.ROOT) : null;
        boolean overload = status != null && overloadValues.contains(status);
        boolean motion = status != null && motionValues.contains(status);
        boolean stable = !overload && !motion && (status == null || stableValues.contains(status));
        return new Frame(line, kg, stable, motion, overload);
    }

    private static Set<String> tokens(Object v, String fallback) {
        List<String> parts;
        if (v instanceof List<?> l) {
            parts = l.stream().map(String::valueOf).toList();
        } else {
            String s = str(v);
            parts = Arrays.asList((s == null ? fallback : s).split(","));
        }
        return parts.stream().map(x -> x.trim().toUpperCase(Locale.ROOT)).filter(x -> !x.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? null : s;
    }

    static int integer(Object v, int fallback) {
        if (v == null || String.valueOf(v).isBlank()) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return new BigDecimal(String.valueOf(v).trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Not a whole number: " + v);
        }
    }
}
