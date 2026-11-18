package com.inventoryerp.lossprevention.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A reconciliation engine (ADR-S077 §5): window → expected → observed → findings. An engine is a pure function of
 * ledger events, rule settings and the context recorded with the run, so a run can be repeated from its inputs.
 */
public interface LpEngine {

    /** {@code cashier}, {@code bar}, {@code cashier_vision}, {@code bar_pour}, {@code kitchen_flow}, {@code service_vision}. */
    String code();

    /** Changes whenever the engine's logic changes; stored on every run. */
    String version();

    /** Event types this engine reads. An accepted event of one of them makes the engine's window dirty. */
    Set<String> eventTypes();

    /** Types the engine also reads from before the window's day, with how many days back. */
    Map<String, Integer> lookbackDays();

    /**
     * Types the engine also reads from after the window's day, with how many days forward (LP4: a tab paid after
     * midnight). Such an event makes the earlier windows that read it dirty again. Business-day engines only.
     */
    default Map<String, Integer> lookaheadDays() {
        return Map.of();
    }

    /**
     * The engine reads pour meters (LP3): it runs where the branch has a measurement device (or one of its
     * {@link #zoneTypes()}), and the devices are recorded in the run's context.
     */
    default boolean usesMeasurementDevices() {
        return false;
    }

    List<Finding> evaluate(Input input);

    /**
     * Zone types a branch must have before this engine runs there (LP1, cameras). Empty: the engine needs no camera
     * and runs for every branch with events.
     */
    default Set<String> zoneTypes() {
        return Set.of();
    }

    /**
     * Zero for an engine whose window is a business day of the sender. More than zero for an engine whose window is
     * the calendar day in the organization's zone (vision): its events are chosen by when they happened, and this many
     * seconds of the neighbouring days are read too, so a check that crosses midnight sees both sides.
     */
    default int timeMarginSeconds() {
        return 0;
    }

    /** What the run could not decide because part of its window was not observed. Engines without cameras: none. */
    default Coverage coverage(Input input) {
        return Coverage.FULL;
    }

    /**
     * @param gap a camera reported a period without a usable picture inside a window the engine needed
     * @param pendingUntil the latest instant the engine needed observed and an agent had not yet reported; the window
     *     runs again once an agent's watermark passes it
     */
    record Coverage(boolean gap, Instant pendingUntil) {

        public static final Coverage FULL = new Coverage(false, null);

        public boolean incomplete() {
            return gap || pendingUntil != null;
        }
    }

    /** One ledger row as an engine sees it. */
    record LedgerEvent(String id, long seq, String type, String actorId, String transactionId, String orderId,
            String ticketId, String lineId, String sessionId, LocalDate businessDate, Instant occurredAt,
            String evidenceLevel, String observationStatus, Map<String, Object> payload, String deviceId,
            BigDecimal confidence) {

        /** A row without a device or a confidence (the POS-only engines and their tests). */
        public LedgerEvent(String id, long seq, String type, String actorId, String transactionId, String orderId,
                String ticketId, String lineId, String sessionId, LocalDate businessDate, Instant occurredAt,
                String evidenceLevel, String observationStatus, Map<String, Object> payload) {
            this(id, seq, type, actorId, transactionId, orderId, ticketId, lineId, sessionId, businessDate, occurredAt,
                    evidenceLevel, observationStatus, payload, null, null);
        }

        /** A payload number, or {@code null} when it is absent or not a number. */
        public BigDecimal num(String key) {
            return LpNumbers.decimal(payload.get(key));
        }

        public String text(String key) {
            Object v = payload.get(key);
            return v == null ? null : String.valueOf(v);
        }
    }

    /** A rule as it applied to a run. Version 0 is the catalog default. */
    record RuleConfig(String code, int version, boolean enabled, String severity, Map<String, Object> params) {

        public BigDecimal num(String key) {
            return LpNumbers.decimal(params.get(key));
        }

        public int intValue(String key, int fallback) {
            BigDecimal v = num(key);
            return v == null ? fallback : v.intValue();
        }
    }

    /**
     * @param dayFinal the business day is over and settled; rules that compare a whole day run only then
     * @param events the window's events and the look-back, in ledger order
     * @param context what else the engine read, recorded with the run: for vision, the zones of the branch and the
     *     agents' watermarks
     */
    record Input(String branchId, LocalDate businessDate, boolean dayFinal, List<LedgerEvent> events,
            Map<String, RuleConfig> rules, Map<String, Object> context) {

        /** An input without context (the engines that need none). */
        public Input(String branchId, LocalDate businessDate, boolean dayFinal, List<LedgerEvent> events,
                Map<String, RuleConfig> rules) {
            this(branchId, businessDate, dayFinal, events, rules, Map.of());
        }

        /** The rule when it is switched on (and, for a day-comparison rule, the day is final), else {@code null}. */
        public RuleConfig active(String code) {
            RuleConfig r = rules.get(code);
            if (r == null || !r.enabled()) {
                return null;
            }
            LpRuleCatalog.Definition def = LpRuleCatalog.definition(code);
            return def != null && def.finalOnly() && !dayFinal ? null : r;
        }
    }

    /**
     * What a rule found. {@code key} identifies the finding inside its window and rule, so the same finding in a later
     * run is recognised. A finding states quantities; it never states a cause or blame.
     */
    record Finding(String ruleCode, String key, String title, String subjectType, String subjectId,
            BigDecimal expected, BigDecimal observed, BigDecimal delta, String unit, BigDecimal exposureAmount,
            String exposureMethod, Map<String, Object> detail, List<String> evidenceEventIds) {}
}
