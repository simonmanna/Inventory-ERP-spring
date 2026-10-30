package com.inventoryerp.quality.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates test results against a specification version (ADR-S013 §3). Pure: no I/O.
 *
 * <ul>
 *   <li><b>numeric</b>: every sample within {@code [min, max]} (open bound when null);</li>
 *   <li><b>sieve</b>: every sample has a reading for each envelope sieve and its % passing lies within that sieve's
 *       {@code [minPassingPct, maxPassingPct]};</li>
 *   <li><b>boolean</b>: the value equals {@code expectedText} ({@code true} by default);</li>
 *   <li><b>text</b>: equals {@code expectedText} (case-insensitive) when set, else any non-blank value.</li>
 * </ul>
 * A parameter is {@code pending} until {@code requiredSamples} samples are recorded — unless a recorded sample already
 * fails. A failing non-critical parameter is a {@code deviation}. The lot fails when any critical parameter fails, is
 * pending while any parameter is pending, and passes otherwise.
 */
public final class SpecEvaluator {

    private SpecEvaluator() {}

    public record SievePoint(BigDecimal sieveMm, BigDecimal minPassingPct, BigDecimal maxPassingPct) {}

    public record SieveReading(BigDecimal sieveMm, BigDecimal passingPct) {}

    public record Parameter(String id, String code, String name, String kind, String unit, BigDecimal min,
            BigDecimal max, BigDecimal target, String expectedText, List<SievePoint> envelope, boolean critical) {}

    public record Sample(int sampleNo, BigDecimal numericValue, String textValue, List<SieveReading> sieve) {}

    public record SampleOutcome(int sampleNo, boolean passed, List<String> messages) {}

    public record ParameterOutcome(String parameterId, String code, String name, String kind, String status,
            int samples, BigDecimal mean, BigDecimal minObserved, BigDecimal maxObserved, List<String> messages,
            List<SampleOutcome> sampleOutcomes) {}

    public record Evaluation(String result, int failures, int deviations, int pending,
            List<ParameterOutcome> parameters) {

        /** JSON-friendly snapshot stored on the lot ({@code QualityInspectionLot.evaluation}). */
        public Map<String, Object> toMap() {
            List<Map<String, Object>> ps = new ArrayList<>();
            for (ParameterOutcome p : parameters) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("parameterId", p.parameterId());
                m.put("code", p.code());
                m.put("name", p.name());
                m.put("kind", p.kind());
                m.put("status", p.status());
                m.put("samples", p.samples());
                m.put("mean", p.mean() == null ? null : p.mean().toPlainString());
                m.put("min", p.minObserved() == null ? null : p.minObserved().toPlainString());
                m.put("max", p.maxObserved() == null ? null : p.maxObserved().toPlainString());
                m.put("messages", p.messages());
                ps.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", result);
            out.put("failures", failures);
            out.put("deviations", deviations);
            out.put("pending", pending);
            out.put("parameters", ps);
            return out;
        }
    }

    /** Evaluate {@code samplesByParameter} (parameter id → samples) for a lot needing {@code requiredSamples}. */
    public static Evaluation evaluate(List<Parameter> parameters, Map<String, List<Sample>> samplesByParameter,
            int requiredSamples) {
        List<ParameterOutcome> outcomes = new ArrayList<>();
        int failures = 0;
        int deviations = 0;
        int pending = 0;
        for (Parameter p : parameters) {
            ParameterOutcome o = evaluateParameter(p, samplesByParameter.getOrDefault(p.id(), List.of()),
                    Math.max(1, requiredSamples));
            outcomes.add(o);
            switch (o.status()) {
                case "fail" -> failures++;
                case "deviation" -> deviations++;
                case "pending" -> pending++;
                default -> { }
            }
        }
        String result = failures > 0 ? "fail" : pending > 0 || parameters.isEmpty() ? "pending" : "pass";
        return new Evaluation(result, failures, deviations, pending, outcomes);
    }

    static ParameterOutcome evaluateParameter(Parameter p, List<Sample> samples, int requiredSamples) {
        List<SampleOutcome> sampleOutcomes = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal lo = null;
        BigDecimal hi = null;
        int numeric = 0;
        boolean anyFail = false;
        for (Sample s : samples.stream().sorted(Comparator.comparingInt(Sample::sampleNo)).toList()) {
            List<String> m = check(p, s);
            boolean ok = m.isEmpty();
            anyFail |= !ok;
            sampleOutcomes.add(new SampleOutcome(s.sampleNo(), ok, m));
            for (String x : m) {
                messages.add("Sample " + s.sampleNo() + ": " + x);
            }
            if ("numeric".equals(p.kind()) && s.numericValue() != null) {
                sum = sum.add(s.numericValue());
                lo = lo == null || s.numericValue().compareTo(lo) < 0 ? s.numericValue() : lo;
                hi = hi == null || s.numericValue().compareTo(hi) > 0 ? s.numericValue() : hi;
                numeric++;
            }
        }
        BigDecimal mean = numeric == 0 ? null : sum.divide(BigDecimal.valueOf(numeric), 6, RoundingMode.HALF_UP);
        String status;
        if (anyFail) {
            status = p.critical() ? "fail" : "deviation";
        } else if (samples.size() < requiredSamples) {
            status = "pending";
            messages.add(samples.size() + " of " + requiredSamples + " sample(s) recorded");
        } else {
            status = "pass";
        }
        return new ParameterOutcome(p.id(), p.code(), p.name(), p.kind(), status, samples.size(), mean, lo, hi,
                messages, sampleOutcomes);
    }

    /** Problems of one sample (empty = within specification). */
    public static List<String> check(Parameter p, Sample s) {
        List<String> out = new ArrayList<>();
        switch (p.kind()) {
            case "sieve" -> {
                List<SieveReading> readings = s.sieve() == null ? List.of() : s.sieve();
                for (SievePoint point : p.envelope()) {
                    SieveReading r = readings.stream().filter(x -> x.sieveMm().compareTo(point.sieveMm()) == 0)
                            .findFirst().orElse(null);
                    String sieve = plain(point.sieveMm()) + " mm";
                    if (r == null || r.passingPct() == null) {
                        out.add("no reading for sieve " + sieve);
                    } else if (outside(r.passingPct(), point.minPassingPct(), point.maxPassingPct())) {
                        out.add(sieve + " passing " + plain(r.passingPct()) + " % outside "
                                + range(point.minPassingPct(), point.maxPassingPct()) + " %");
                    }
                }
            }
            case "boolean" -> {
                String expected = p.expectedText() == null || p.expectedText().isBlank() ? "true"
                        : p.expectedText().trim().toLowerCase(Locale.ROOT);
                String v = bool(s);
                if (v == null) {
                    out.add("no value");
                } else if (!v.equals(expected)) {
                    out.add("is " + v + ", expected " + expected);
                }
            }
            case "text" -> {
                String v = s.textValue() == null ? null : s.textValue().trim();
                if (v == null || v.isEmpty()) {
                    out.add("no value");
                } else if (p.expectedText() != null && !p.expectedText().isBlank()
                        && !v.equalsIgnoreCase(p.expectedText().trim())) {
                    out.add("is \"" + v + "\", expected \"" + p.expectedText().trim() + "\"");
                }
            }
            default -> {
                if (s.numericValue() == null) {
                    out.add("no value");
                } else if (outside(s.numericValue(), p.min(), p.max())) {
                    out.add(plain(s.numericValue()) + (p.unit() == null ? "" : " " + p.unit()) + " outside "
                            + range(p.min(), p.max()) + (p.unit() == null ? "" : " " + p.unit()));
                }
            }
        }
        return out;
    }

    /**
     * Validate a sieve reading set before it is stored: % passing within 0–100, one reading per sieve, and % passing
     * not increasing as the sieve gets smaller. Returns the problem, or null.
     */
    public static String validateSieve(List<SieveReading> readings) {
        if (readings == null || readings.isEmpty()) {
            return "A sieve analysis needs at least one sieve reading.";
        }
        List<SieveReading> sorted = new ArrayList<>(readings);
        sorted.sort(Comparator.comparing(SieveReading::sieveMm).reversed());
        BigDecimal previous = null;
        BigDecimal previousSieve = null;
        for (SieveReading r : sorted) {
            if (r.sieveMm() == null || r.sieveMm().signum() <= 0) {
                return "Sieve size must be positive.";
            }
            if (r.passingPct() == null || r.passingPct().signum() < 0
                    || r.passingPct().compareTo(BigDecimal.valueOf(100)) > 0) {
                return "Passing % on sieve " + plain(r.sieveMm()) + " mm must be between 0 and 100.";
            }
            if (previousSieve != null && previousSieve.compareTo(r.sieveMm()) == 0) {
                return "Sieve " + plain(r.sieveMm()) + " mm is listed twice.";
            }
            if (previous != null && r.passingPct().compareTo(previous) > 0) {
                return "Passing % cannot increase on a smaller sieve (" + plain(r.sieveMm()) + " mm passes "
                        + plain(r.passingPct()) + " %, " + plain(previousSieve) + " mm passes " + plain(previous)
                        + " %).";
            }
            previous = r.passingPct();
            previousSieve = r.sieveMm();
        }
        return null;
    }

    private static String bool(Sample s) {
        if (s.textValue() != null && !s.textValue().isBlank()) {
            String t = s.textValue().trim().toLowerCase(Locale.ROOT);
            if (List.of("true", "yes", "pass", "ok", "1").contains(t)) {
                return "true";
            }
            if (List.of("false", "no", "fail", "0").contains(t)) {
                return "false";
            }
            return t;
        }
        if (s.numericValue() != null) {
            return s.numericValue().signum() != 0 ? "true" : "false";
        }
        return null;
    }

    private static boolean outside(BigDecimal v, BigDecimal min, BigDecimal max) {
        return (min != null && v.compareTo(min) < 0) || (max != null && v.compareTo(max) > 0);
    }

    private static String range(BigDecimal min, BigDecimal max) {
        if (min != null && max != null) {
            return plain(min) + "–" + plain(max);
        }
        return min != null ? "≥ " + plain(min) : max != null ? "≤ " + plain(max) : "any";
    }

    static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }
}
