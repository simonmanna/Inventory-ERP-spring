package com.inventoryerp.quality.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.quality.application.LabResultAdapter.LabResult;
import com.inventoryerp.quality.application.LabResultAdapter.LotRef;
import com.inventoryerp.quality.application.QualityCommands.LabCsvImport;
import com.inventoryerp.quality.application.QualityCommands.LabFetch;
import com.inventoryerp.quality.application.QualityCommands.ResultEntry;
import com.inventoryerp.quality.application.QualityCommands.SieveReadingInput;
import com.inventoryerp.quality.domain.QualityInspectionLot;
import com.inventoryerp.quality.domain.QualityLabImport;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import com.inventoryerp.shared.web.PageResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Laboratory result import (ADR-S013 §3): CSV upload or a fetch through a {@link LabResultAdapter}. Rows are grouped
 * per lot; each lot is recorded in its own transaction, so a bad lot is reported (rows rejected with the reason)
 * without losing the others. Every run is a {@code QualityLabImport} row (written last, in its own transaction).
 */
@Service
public class QualityLabService {

    private final QualityDb db;
    private final QualityLotService lots;
    private final QualitySpecService specs;
    private final List<LabResultAdapter> adapters;
    private final TransactionTemplate tx;

    public QualityLabService(QualityDb db, QualityLotService lots, QualitySpecService specs,
            List<LabResultAdapter> adapters, PlatformTransactionManager txm) {
        this.db = db;
        this.lots = lots;
        this.specs = specs;
        this.adapters = adapters;
        this.tx = new TransactionTemplate(txm);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> adapters() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Obj.of("code", "csv", "configured", true, "fetch", false));
        for (LabResultAdapter a : adapters) {
            out.add(Obj.of("code", a.code(), "configured", a.configured(), "fetch", true));
        }
        return out;
    }

    /** Not transactional on purpose: one transaction per lot, then one for the import record. */
    public Map<String, Object> importCsv(LabCsvImport in) {
        List<CsvLabResults.Row> rows;
        try {
            rows = CsvLabResults.parse(in.content());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        List<Map<String, Object>> errors = new ArrayList<>();
        List<Indexed> ok = new ArrayList<>();
        for (CsvLabResults.Row r : rows) {
            if (r.error() != null) {
                errors.add(Obj.of("row", r.row(), "message", r.error()));
            } else {
                ok.add(new Indexed(r.row(), r.result()));
            }
        }
        return run("csv", Q.trim(in.filename()), rows.size(), ok, errors);
    }

    /** Not transactional on purpose: one transaction per lot, then one for the import record. */
    public Map<String, Object> fetch(LabFetch in) {
        LabResultAdapter adapter = adapters.stream().filter(a -> a.code().equals(in.adapter())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Unknown laboratory adapter " + in.adapter()));
        List<LotRef> refs = new ArrayList<>();
        tx.executeWithoutResult(s -> {
            for (String lotId : in.lotIds()) {
                Map<String, Object> lot = db.require(QualityInspectionLot.class, lotId, false, "Inspection lot not found");
                refs.add(new LotRef(lotId, Q.str(lot, "lotNumber"), (Integer) lot.get("sampleSize"),
                        specs.parameters(Q.str(lot, "specificationId"))));
            }
        });
        List<Indexed> ok = new ArrayList<>();
        int i = 0;
        for (LabResult r : adapter.fetch(refs)) {
            ok.add(new Indexed(++i, r));
        }
        return run(adapter.code(), null, ok.size(), ok, new ArrayList<>());
    }

    private record Indexed(int row, LabResult result) {}

    private Map<String, Object> run(String source, String filename, int total, List<Indexed> rows,
            List<Map<String, Object>> errors) {
        Map<String, List<Indexed>> byLot = new LinkedHashMap<>();
        rows.forEach(r -> byLot.computeIfAbsent(r.result().lotNumber(), k -> new ArrayList<>()).add(r));
        int imported = 0;
        List<String> lotIds = new ArrayList<>();
        for (Map.Entry<String, List<Indexed>> e : byLot.entrySet()) {
            String lotId = tx.execute(s -> db.jdbc().sql("SELECT id FROM \"QualityInspectionLot\" WHERE"
                    + " \"organizationId\" = ? AND \"lotNumber\" = ?").params(QualityDb.org(), e.getKey())
                    .query(String.class).optional().orElse(null));
            if (lotId == null) {
                for (Indexed r : e.getValue()) {
                    errors.add(Obj.of("row", r.row(), "lotNumber", e.getKey(), "message", "Unknown lot " + e.getKey()));
                }
                continue;
            }
            List<ResultEntry> entries = entries(e.getValue());
            try {
                tx.executeWithoutResult(s -> lots.recordResults(lotId, entries, source));
                imported += e.getValue().size();
                lotIds.add(lotId);
            } catch (ApiException ex) {
                for (Indexed r : e.getValue()) {
                    errors.add(Obj.of("row", r.row(), "lotNumber", e.getKey(), "parameterCode",
                            r.result().parameterCode(), "message", ex.getMessage()));
                }
            }
        }
        int rejected = total - imported;
        String status = imported == 0 && total > 0 ? "failed" : rejected > 0 ? "partial" : "completed";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("adapter", source);
        data.put("filename", filename);
        data.put("status", status);
        data.put("rowsTotal", total);
        data.put("rowsImported", imported);
        data.put("rowsRejected", rejected);
        data.put("errors", EntityJson.toJson(errors));
        data.put("lotIds", EntityJson.toJson(lotIds));
        data.put("importedBy", TenantContext.userId());
        return tx.execute(s -> db.insert(QualityLabImport.class, data));
    }

    /** Group a lot's rows into result entries: one per (parameter, sample); sieve rows become the sieve list. */
    static List<ResultEntry> entries(List<Indexed> rows) {
        Map<String, List<LabResult>> groups = new LinkedHashMap<>();
        for (Indexed r : rows) {
            LabResult x = r.result();
            groups.computeIfAbsent(x.parameterCode() + "#" + (x.sampleNo() == null ? 1 : x.sampleNo()),
                    k -> new ArrayList<>()).add(x);
        }
        List<ResultEntry> out = new ArrayList<>();
        for (List<LabResult> g : groups.values()) {
            LabResult first = g.getFirst();
            List<SieveReadingInput> sieve = new ArrayList<>();
            java.math.BigDecimal value = null;
            String text = null;
            for (LabResult x : g) {
                if (x.sieveMm() != null && x.passingPct() != null) {
                    sieve.add(new SieveReadingInput(x.sieveMm(), x.passingPct()));
                }
                value = x.value() != null ? x.value() : value;
                text = x.textValue() != null ? x.textValue() : text;
            }
            out.add(new ResultEntry(null, first.parameterCode(), first.sampleNo() == null ? 1 : first.sampleNo(), value,
                    text, sieve.isEmpty() ? null : sieve, first.labReference(), first.testedAt()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public PageResult<Map<String, Object>> list(Integer page, Integer pageSize) {
        int p = Q.page(page);
        int size = Q.size(pageSize);
        long total = db.jdbc().sql("SELECT count(*) FROM \"QualityLabImport\" WHERE \"organizationId\" = ?")
                .param(QualityDb.org()).query(Long.class).single();
        return PageResult.of(SqlRows.list(db.jdbc().sql("SELECT i.*, TRIM(CONCAT(u.\"firstName\", ' ', u.\"lastName\"))"
                + " AS \"importedByName\" FROM \"QualityLabImport\" i LEFT JOIN \"User\" u ON u.id = i.\"importedBy\""
                + " WHERE i.\"organizationId\" = ? ORDER BY i.\"createdAt\" DESC LIMIT ? OFFSET ?")
                .params(QualityDb.org(), size, (p - 1) * size)), p, size, total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        return db.require(QualityLabImport.class, id, false, "Lab import not found");
    }
}
