package com.inventoryerp.quality.application;

import com.inventoryerp.quality.application.SpecEvaluator.Parameter;
import com.inventoryerp.quality.application.SpecEvaluator.SievePoint;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Laboratory simulator (ADR-S013): deterministic results inside the specification for every sample of every
 * parameter — the target (or the middle of the limits) for numeric values, the middle of each sieve's envelope, the
 * expected boolean / text. For demos and tests; no external system.
 */
@Component
public class SimulatorLabResultAdapter implements LabResultAdapter {

    @Override
    public String code() {
        return "simulator";
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public List<LabResult> fetch(List<LotRef> lots) {
        List<LabResult> out = new ArrayList<>();
        Instant now = Instant.now();
        for (LotRef lot : lots) {
            for (int s = 1; s <= lot.sampleSize(); s++) {
                String ref = "SIM-" + lot.lotNumber() + "-" + s;
                for (Parameter p : lot.parameters()) {
                    switch (p.kind()) {
                        case "sieve" -> {
                            for (SievePoint e : p.envelope()) {
                                out.add(new LabResult(lot.lotNumber(), p.code(), s, null, null, e.sieveMm(),
                                        mid(e.minPassingPct(), e.maxPassingPct(), BigDecimal.valueOf(100)), now, ref));
                            }
                        }
                        case "boolean" -> out.add(new LabResult(lot.lotNumber(), p.code(), s, null,
                                p.expectedText() == null ? "true" : p.expectedText(), null, null, now, ref));
                        case "text" -> out.add(new LabResult(lot.lotNumber(), p.code(), s, null,
                                p.expectedText() == null ? "OK" : p.expectedText(), null, null, now, ref));
                        default -> out.add(new LabResult(lot.lotNumber(), p.code(), s,
                                p.target() != null ? p.target() : mid(p.min(), p.max(), BigDecimal.ONE), null, null,
                                null, now, ref));
                    }
                }
            }
        }
        return out;
    }

    private static BigDecimal mid(BigDecimal min, BigDecimal max, BigDecimal fallback) {
        if (min != null && max != null) {
            return min.add(max).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        }
        if (min != null) {
            return min.multiply(new BigDecimal("1.1")).setScale(4, RoundingMode.HALF_UP);
        }
        if (max != null) {
            return max.multiply(new BigDecimal("0.9")).setScale(4, RoundingMode.HALF_UP);
        }
        return fallback;
    }
}
