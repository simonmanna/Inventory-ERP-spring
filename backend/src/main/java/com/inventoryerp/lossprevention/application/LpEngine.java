package com.inventoryerp.lossprevention.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A reconciliation engine (ADR-S077 §5): window → expected → observed → findings. An engine is a pure function of
 * ledger events and rule settings, so a run can be repeated from its recorded inputs.
 */
public interface LpEngine {

    /** {@code cashier} or {@code bar}. */
    String code();

    /** Changes whenever the engine's logic changes; stored on every run. */
    String version();

    /** Event types this engine reads. An accepted event of one of them makes the engine's window dirty. */
    Set<String> eventTypes();

    /** Types the engine also reads from before the window's day, with how many days back. */
    Map<String, Integer> lookbackDays();

    List<Finding> evaluate(Input input);

    /** One ledger row as an engine sees it. */
    record LedgerEvent(String id, long seq, String type, String actorId, String transactionId, String orderId,
            String ticketId, String lineId, String sessionId, LocalDate businessDate, Instant occurredAt,
            String evidenceLevel, String observationStatus, Map<String, Object> payload) {

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
     */
    record Input(String branchId, LocalDate businessDate, boolean dayFinal, List<LedgerEvent> events,
            Map<String, RuleConfig> rules) {

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
