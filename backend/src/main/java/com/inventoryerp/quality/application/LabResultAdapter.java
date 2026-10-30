package com.inventoryerp.quality.application;

import com.inventoryerp.quality.application.SpecEvaluator.Parameter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Laboratory SPI (ADR-S013 §3): an external laboratory system delivers test results for inspection lots. Results are
 * mapped to the lot's specification parameters by {@code parameterCode}; a sieve result is one row per sieve.
 * Implementations: {@code rest} ({@link RestLabResultAdapter}) and {@code simulator}
 * ({@link SimulatorLabResultAdapter}); CSV files go through {@link CsvLabResults} (no fetch).
 */
public interface LabResultAdapter {

    /** Adapter key used by {@code POST /quality/lab-imports/fetch}. */
    String code();

    /** True when the adapter can be used (credentials / endpoint configured). */
    boolean configured();

    /** Results for the requested lots (may be empty for a lot the lab has not finished). */
    List<LabResult> fetch(List<LotRef> lots);

    /** A lot the lab is asked about, with its specification parameters. */
    record LotRef(String lotId, String lotNumber, int sampleSize, List<Parameter> parameters) {}

    /** One measured value (numeric / text) or one sieve reading of a sample. */
    record LabResult(String lotNumber, String parameterCode, Integer sampleNo, BigDecimal value, String textValue,
            BigDecimal sieveMm, BigDecimal passingPct, Instant testedAt, String labReference) {}
}
