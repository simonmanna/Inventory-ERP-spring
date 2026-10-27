package com.inventoryerp.rental.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.workflow.LifecycleService;
import com.inventoryerp.pos.application.PosDb;
import com.inventoryerp.rental.domain.RentalUnit;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The physical rentable items (port of {@code rental-unit.service.ts}): one row per item, status lives here.
 * Units are soft-deleted masters; bulk-generate numbers codes from a {@code {n}} template; retire is terminal.
 */
@Service
public class RentalUnitService {

    private static final String LIVE = "\"organizationId\" = ? AND \"deletedAt\" IS NULL";
    private static final Pattern LEADING_INT = Pattern.compile("^\\s*([+-]?\\d+)");

    private final RentalRows rows;
    private final PosDb db;
    private final LifecycleService lifecycle;

    public RentalUnitService(RentalRows rows, LifecycleService lifecycle) {
        this.rows = rows;
        this.db = rows.db();
        this.lifecycle = lifecycle;
    }

    /** {@code {total, page, pageSize, items}} ordered by unit code, with the product brief. */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> q) {
        RentalRows.Paging p = RentalRows.paging(q);
        StringBuilder where = new StringBuilder(LIVE);
        List<Object> params = new ArrayList<>(List.of(RentalRows.org()));
        if (notEmpty(q.get("productId"))) {
            where.append(" AND \"productId\" = ?");
            params.add(q.get("productId"));
        }
        if (notEmpty(q.get("status"))) {
            where.append(" AND status::text = ?");
            params.add(q.get("status"));
        }
        if (notEmpty(q.get("search"))) {
            where.append(" AND (\"unitCode\" ILIKE ? ESCAPE '\\' OR barcode ILIKE ? ESCAPE '\\')");
            String like = "%" + BaseCrudService.escapeLike(q.get("search")) + "%";
            params.add(like);
            params.add(like);
        }
        long total = db.count(RentalUnit.class, where.toString(), params.toArray());
        params.add(p.limit());
        params.add(p.offset());
        List<Map<String, Object>> items = db.list(RentalUnit.class, where + " ORDER BY \"unitCode\" ASC LIMIT ? OFFSET ?",
                params.toArray());
        RentalRows.with(items, "product", u -> rows.productBrief(u.get("productId")));
        return RentalRows.envelope(total, p, items);
    }

    static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /** Bulk-generate N units from a code template ({@code CAM-{n}} → CAM-001, CAM-002, ...). */
    @Transactional
    public Map<String, Object> bulkGenerate(Map<String, Object> dto) {
        String orgId = RentalRows.org();
        double count = Math.floor(RentalRows.num(dto, "count"));
        if (!(count > 0 && count <= 500)) {
            throw ApiException.badRequest("Count must be between 1 and 500");
        }
        Map<String, Object> product = rows.product(dto.get("productId"));
        if (product == null) {
            throw ApiException.notFound("Product not found");
        }
        if (Boolean.TRUE.equals(product.get("rentalIsPooled"))) {
            throw ApiException.badRequest("Pooled products have no individual units");
        }
        String template = RentalRows.str(dto, "codeTemplate");
        if (template == null || template.isEmpty() || !template.contains("{n}")) {
            throw ApiException.badRequest("codeTemplate must contain '{n}' (e.g. CAM-{n})");
        }
        String pattern = template.replaceFirst(Pattern.quote("{n}"), "");
        long maxSeq = 0;
        for (String code : db.jdbc().sql("SELECT \"unitCode\" FROM \"RentalUnit\" WHERE " + LIVE
                + " AND \"productId\" = ?").params(orgId, dto.get("productId")).query(String.class).list()) {
            if (code.startsWith(pattern)) {
                Matcher m = LEADING_INT.matcher(code.substring(pattern.length()));
                if (m.find()) {
                    long n = Long.parseLong(m.group(1));
                    if (n > maxSeq) {
                        maxSeq = n;
                    }
                }
            }
        }
        String barcodePrefix = RentalRows.str(dto, "barcodePrefix");
        List<Map<String, Object>> created = new ArrayList<>();
        for (int i = 1; i <= (int) count; i++) {
            long seq = maxSeq + i;
            String unitCode = template.replaceFirst(Pattern.quote("{n}"), Matcher.quoteReplacement(pad(seq, 3)));
            Map<String, Object> unit = db.insert(RentalUnit.class, Obj.of("organizationId", orgId, "unitCode", unitCode,
                    "barcode", barcodePrefix != null && !barcodePrefix.isEmpty() ? barcodePrefix + pad(seq, 4) : null,
                    "productId", dto.get("productId"),
                    "conditionGrade", dto.get("conditionGrade") != null ? dto.get("conditionGrade") : "good",
                    "attributes", PosDb.json(dto.get("attributes") != null ? dto.get("attributes") : Map.of()),
                    "acquisitionCost", dto.get("acquisitionCost") == null ? null
                            : RentalRows.dec(RentalRows.num(dto, "acquisitionCost")),
                    "createdBy", RentalRows.user()));
            Map<String, Object> brief = new LinkedHashMap<>();
            brief.put("unitCode", unitCode);
            brief.put("id", unit.get("id"));
            created.add(brief);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("createdCount", created.size());
        out.put("units", created);
        return out;
    }

    private static String pad(long n, int width) {
        String s = String.valueOf(n);
        return s.length() >= width ? s : "0".repeat(width - s.length()) + s;
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> dto) {
        String orgId = RentalRows.org();
        String where = "id = ? AND " + LIVE;
        if (db.first(RentalUnit.class, where, id, orgId) == null) {
            throw ApiException.notFound("Rental unit not found");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (String k : List.of("unitCode", "barcode", "conditionGrade", "attributes", "acquisitionCost", "notes")) {
            if (!dto.containsKey(k)) {
                continue;
            }
            Object v = dto.get(k);
            if ("attributes".equals(k)) {
                v = PosDb.json(v);
            } else if ("acquisitionCost".equals(k) && v != null) {
                v = RentalRows.dec(RentalRows.num(v));
            }
            data.put(k, v);
        }
        data.put("updatedBy", RentalRows.user());
        return db.update(RentalUnit.class, data, where, id, orgId);
    }

    /** Retire a unit (terminal state), guarded by the lifecycle registry. */
    @Transactional
    public Map<String, Object> retire(String id, String notes) {
        String orgId = RentalRows.org();
        String where = "id = ? AND " + LIVE;
        Map<String, Object> unit = db.first(RentalUnit.class, where, id, orgId);
        if (unit == null) {
            throw ApiException.notFound("Rental unit not found");
        }
        if ("retired".equals(unit.get("status"))) {
            return unit;
        }
        lifecycle.assertTransition(RentalLifecycles.UNIT, (String) unit.get("status"), "retired", "retire");
        return db.update(RentalUnit.class, Obj.of("status", "retired", "retiredAt", PosDb.raw(RentalRows.NOW),
                "notes", notes != null ? notes : unit.get("notes"), "updatedBy", RentalRows.user()), where, id, orgId);
    }
}
