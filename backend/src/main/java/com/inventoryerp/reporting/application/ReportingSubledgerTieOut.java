package com.inventoryerp.reporting.application;

import com.inventoryerp.accounting.application.SubledgerTieOut;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * {@link SubledgerTieOut} over the {@code ar.reconciliation} / {@code ap.reconciliation} reports (finance readiness
 * audit A5): the same measure, evaluated for the whole organisation without recording a report run.
 */
@Service
public class ReportingSubledgerTieOut implements SubledgerTieOut {

    private final ReportEngine engine;

    public ReportingSubledgerTieOut(ReportEngine engine) {
        this.engine = engine;
    }

    @Override
    public Measure measure(String side, LocalDate asOf) {
        boolean ap = "AP".equals(side);
        ReportBody body = engine.evaluate(ap ? "ap.reconciliation" : "ar.reconciliation", new ReportRequest(null, null,
                null, asOf.toString(), null, null, null, null, null, null, null, null, null, null, null, null, null));
        List<PartnerDifference> differences = new ArrayList<>();
        for (ReportBody.Row row : body.rows()) {
            Map<String, Object> v = row.values();
            if (!"line".equals(row.kind()) || v == null || v.get("difference") == null) {
                continue;
            }
            BigDecimal difference = dec(v.get("difference"));
            if (difference.abs().compareTo(TOLERANCE) > 0) {
                Object partnerId = v.get("partnerId");
                differences.add(new PartnerDifference(partnerId == null || "".equals(partnerId) ? null
                        : String.valueOf(partnerId), v.get("partner") == null ? null : String.valueOf(v.get("partner")),
                        dec(v.get("subledger")), dec(v.get("gl")), difference));
            }
        }
        differences.sort(Comparator.comparing((PartnerDifference d) -> d.difference().abs()).reversed());
        return new Measure(ap ? "AP" : "AR", asOf, dec(body.totals().get("subledger")), dec(body.totals().get("gl")),
                List.copyOf(differences));
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }
}
