package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Bulk actions on leads and opportunities, and the lead CSV import (CRM enhancements T2a). Deliberately not
 * transactional itself: every record goes through the ordinary single-record use case of {@link CrmPipelineService}
 * in its own transaction, with the same visibility, rules, history, audit and events. One refused record (hidden, won,
 * invalid) is reported and the rest still go through.
 */
@Service
public class CrmBulkService {

    static final int MAX_BULK = 200;
    static final int MAX_IMPORT = 1000;
    private static final List<String> LEAD_ACTIONS = List.of("assign", "lost", "archive");
    private static final List<String> DEAL_ACTIONS = List.of("assign", "move", "lost", "archive");
    private static final List<String> IMPORT_FIELDS = List.of("name", "companyName", "contactName", "email", "phone",
            "city", "country", "amount", "expectedClose", "notes", "source", "tags", "owner");

    private final CrmPipelineService pipeline;
    private final CrmDb db;

    public CrmBulkService(CrmPipelineService pipeline, CrmDb db) {
        this.pipeline = pipeline;
        this.db = db;
    }

    /**
     * Apply {@code action} (assign / move / lost / archive) to up to 200 {@code ids}. {@code kind = lead} only accepts
     * leads (the {@code /crm/leads/bulk} route) and has no move. Returns {@code {done, failed: [{id, error}]}}.
     */
    public Map<String, Object> bulk(String kind, Map<String, Object> in) {
        boolean leads = "lead".equals(kind);
        String action = str(in.get("action"));
        List<String> allowed = leads ? LEAD_ACTIONS : DEAL_ACTIONS;
        if (action == null || !allowed.contains(action)) {
            throw ApiException.badRequest("action must be one of: " + String.join(", ", allowed));
        }
        List<String> ids = CrmDb.strings(in.get("ids")).stream().filter(s -> !blank(s)).distinct().toList();
        if (ids.isEmpty()) {
            throw ApiException.badRequest("ids should not be empty");
        }
        if (ids.size() > MAX_BULK) {
            throw ApiException.badRequest("At most " + MAX_BULK + " records at a time");
        }
        if ("move".equals(action) && blank(str(in.get("stageId")))) {
            throw ApiException.badRequest("stageId is required");
        }
        int done = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String id : ids) {
            Supplier<Object> one = switch (action) {
                case "assign" -> () -> pipeline.assign(id, ownerOnly(in));
                case "move" -> () -> pipeline.move(id, Obj.of("stageId", in.get("stageId")));
                case "lost" -> () -> pipeline.lost(id, lostOnly(in));
                default -> () -> pipeline.archive(id);
            };
            try {
                if (leads) {
                    pipeline.asLead(id, one);
                } else {
                    one.get();
                }
                done++;
            } catch (ApiException e) {
                failed.add(Obj.of("id", id, "error", e.getMessage()));
            }
        }
        return Obj.of("action", action, "done", done, "failed", failed);
    }

    private static Map<String, Object> ownerOnly(Map<String, Object> in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ownerId", in.get("ownerId"));
        return m;
    }

    private static Map<String, Object> lostOnly(Map<String, Object> in) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lostReasonId", in.get("lostReasonId"));
        m.put("lostNote", in.get("lostNote"));
        return m;
    }

    /**
     * Lead import. {@code rows}: objects with name, companyName, contactName, email, phone, city, country, amount,
     * expectedClose, notes, source (name), tags ("a; b"), owner (user email). {@code dryRun = true} only checks and
     * reports duplicates; otherwise each valid row becomes a lead ({@code skipDuplicates} leaves out rows that match an
     * existing lead, opportunity or partner). Unknown sources and tags are reported as warnings, not errors; a row
     * without an owner is assigned by the configured rule.
     */
    public Map<String, Object> importLeads(Map<String, Object> in) {
        if (!(in.get("rows") instanceof List<?> raw) || raw.isEmpty()) {
            throw ApiException.badRequest("rows should not be empty");
        }
        if (raw.size() > MAX_IMPORT) {
            throw ApiException.badRequest("At most " + MAX_IMPORT + " rows at a time");
        }
        boolean dryRun = Boolean.TRUE.equals(in.get("dryRun"));
        boolean skipDuplicates = Boolean.TRUE.equals(in.get("skipDuplicates"));
        Map<String, String> sources = byName("SELECT id, name FROM \"CrmSource\" WHERE \"organizationId\" = ?");
        Map<String, String> tags = byName("SELECT id, name FROM \"CrmTag\" WHERE \"organizationId\" = ?");
        Map<String, String> users = byName("SELECT id, lower(email) AS name FROM \"User\" WHERE \"organizationId\" = ?"
                + " AND \"deletedAt\" IS NULL AND \"isActive\"");
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int created = 0;
        int skipped = 0;
        int errors = 0;
        int rowNo = 0;
        for (Object o : raw) {
            rowNo++;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("row", rowNo);
            List<String> warnings = new ArrayList<>();
            if (!(o instanceof Map<?, ?> m)) {
                result.put("status", "error");
                result.put("error", "Row is not an object");
                out.add(result);
                errors++;
                continue;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (IMPORT_FIELDS.contains(key) && v != null && !String.valueOf(v).isBlank()) {
                    r.put(key, String.valueOf(v).trim());
                }
            });
            result.put("name", r.get("name"));
            Map<String, Object> lead = new LinkedHashMap<>();
            String error = null;
            if (blank(str(r.get("name")))) {
                error = "name should not be empty";
            }
            for (String f : List.of("name", "companyName", "contactName", "email", "phone", "city", "country",
                    "notes")) {
                if (r.get(f) != null) {
                    lead.put(f, r.get(f));
                }
            }
            if (error == null && r.get("amount") != null) {
                try {
                    BigDecimal amount = new BigDecimal(str(r.get("amount")).replace(",", ""));
                    if (amount.signum() < 0) {
                        error = "amount must not be negative";
                    }
                    lead.put("amount", amount);
                } catch (NumberFormatException e) {
                    error = "amount is not a number: " + r.get("amount");
                }
            }
            if (error == null && r.get("expectedClose") != null) {
                try {
                    lead.put("expectedClose", CrmDb.date(str(r.get("expectedClose"))).toString());
                } catch (ApiException e) {
                    error = "expectedClose is not a date (YYYY-MM-DD): " + r.get("expectedClose");
                }
            }
            if (r.get("source") != null) {
                String sid = sources.get(key(r.get("source")));
                if (sid == null) {
                    warnings.add("Unknown source \"" + r.get("source") + "\" left empty");
                } else {
                    lead.put("sourceId", sid);
                }
            }
            if (r.get("tags") != null) {
                List<String> tagIds = new ArrayList<>();
                for (String t : str(r.get("tags")).split("[;|]")) {
                    if (t.isBlank()) {
                        continue;
                    }
                    String tid = tags.get(key(t));
                    if (tid == null) {
                        warnings.add("Unknown tag \"" + t.trim() + "\" skipped");
                    } else if (!tagIds.contains(tid)) {
                        tagIds.add(tid);
                    }
                }
                lead.put("tagIds", tagIds);
            }
            if (error == null && r.get("owner") != null) {
                String uid = users.get(key(r.get("owner")));
                if (uid == null) {
                    error = "No user with email " + r.get("owner");
                } else {
                    lead.put("ownerId", uid);
                }
            }
            if (error != null) {
                result.put("status", "error");
                result.put("error", error);
                result.put("warnings", warnings);
                out.add(result);
                errors++;
                continue;
            }
            Map<String, String> dq = new LinkedHashMap<>();
            for (String f : List.of("email", "phone", "companyName")) {
                if (lead.get(f) != null) {
                    dq.put(f, str(lead.get(f)));
                }
            }
            Map<String, Object> dup = pipeline.duplicates(dq);
            int matches = ((List<?>) dup.get("deals")).size() + ((List<?>) dup.get("partners")).size();
            // The same person twice in one file counts as a duplicate too.
            boolean repeated = false;
            for (Map.Entry<String, String> e : dq.entrySet()) {
                String norm = "phone".equals(e.getKey()) ? e.getValue().replaceAll("\\D", "") : key(e.getValue());
                if (!norm.isEmpty() && !seen.add(e.getKey() + ":" + norm)) {
                    repeated = true;
                }
            }
            if (repeated) {
                matches++;
                warnings.add("Same email, phone or company as an earlier row in this file");
            }
            result.put("duplicates", matches);
            result.put("warnings", warnings);
            if (matches > 0 && skipDuplicates) {
                result.put("status", "skipped");
                out.add(result);
                skipped++;
                continue;
            }
            if (dryRun) {
                result.put("status", matches > 0 ? "duplicate" : "ok");
                out.add(result);
                continue;
            }
            try {
                lead.put("kind", "lead");
                Map<String, Object> made = pipeline.create(lead);
                result.put("status", "created");
                result.put("id", made.get("id"));
                created++;
            } catch (ApiException e) {
                result.put("status", "error");
                result.put("error", e.getMessage());
                errors++;
            }
            out.add(result);
        }
        return Obj.of("dryRun", dryRun, "total", raw.size(), "created", created, "skipped", skipped, "errors", errors,
                "rows", out);
    }

    private Map<String, String> byName(String sql) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> r : db.rows(sql, org())) {
            out.putIfAbsent(key(r.get("name")), str(r.get("id")));
        }
        return out;
    }

    private static String key(Object v) {
        return v == null ? "" : String.valueOf(v).trim().toLowerCase(Locale.ROOT);
    }
}
