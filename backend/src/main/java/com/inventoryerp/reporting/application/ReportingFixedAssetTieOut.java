package com.inventoryerp.reporting.application;

import com.inventoryerp.accounting.application.FixedAssetTieOut;
import com.inventoryerp.reporting.spi.ReportBody;
import com.inventoryerp.reporting.spi.ReportRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * {@link FixedAssetTieOut} over the {@code fa.reconciliation} report (finance readiness audit, check I10): the same
 * measure, evaluated for the whole organisation without recording a report run.
 */
@Service
public class ReportingFixedAssetTieOut implements FixedAssetTieOut {

    private final ReportEngine engine;

    public ReportingFixedAssetTieOut(ReportEngine engine) {
        this.engine = engine;
    }

    @Override
    public List<Line> measure(LocalDate asOf) {
        ReportBody body = engine.evaluate("fa.reconciliation", new ReportRequest(null, null, null, asOf.toString(), null,
                null, null, null, null, null, null, null, null, null, null, null, null));
        List<Line> out = new ArrayList<>();
        for (ReportBody.Row row : body.rows()) {
            Map<String, Object> v = row.values();
            if (v != null && ("COST".equals(row.id()) || "ACC".equals(row.id()))) {
                out.add(new Line(row.id(), v.get("label") == null ? row.id() : String.valueOf(v.get("label")),
                        dec(v.get("register")), dec(v.get("gl"))));
            }
        }
        if (out.size() != 2) {
            throw new IllegalStateException("fa.reconciliation returned " + out.size() + " of its 2 lines");
        }
        return List.copyOf(out);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }
}
