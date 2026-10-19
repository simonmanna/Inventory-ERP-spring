package com.inventoryerp.kernel.search;

import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Cross-entity global search (port of {@code search.service.ts}): a substring pass (ILIKE, 5 rows per
 * entity, catalog order) plus a pg_trgm similarity pass (typo tolerant, top 25 with {@code meta.score}),
 * de-duplicated by (type, id). Plain SQL so the kernel stays independent of the business modules.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    @FunctionalInterface
    private interface Mapper {
        Map<String, Object> map(ResultSet rs) throws SQLException;
    }

    private record Entity(String type, String sql, Mapper mapper) {}

    private final JdbcClient jdbc;
    private final List<Entity> entities = new ArrayList<>();

    public SearchService(JdbcClient jdbc) {
        this.jdbc = jdbc;
        entities.add(new Entity("partner", """
                SELECT id, code, name, email FROM "Partner" WHERE "organizationId" = :org AND "deletedAt" IS NULL
                  AND (name ILIKE :q OR code ILIKE :q OR email ILIKE :q OR phone ILIKE :q OR "taxNumber" ILIKE :q) LIMIT 5""",
                rs -> hit("partner", rs.getString("id"), rs.getString("name"),
                        rs.getString("code") + (blank(rs.getString("email")) ? "" : " · " + rs.getString("email")),
                        "/partners/")));
        entities.add(new Entity("product", """
                SELECT id, code, sku, name FROM "Product" WHERE "organizationId" = :org AND "isActive" = true
                  AND (name ILIKE :q OR code ILIKE :q OR sku ILIKE :q) LIMIT 5""",
                rs -> hit("product", rs.getString("id"), rs.getString("name"),
                        rs.getString("code") + (blank(rs.getString("sku")) ? "" : " · " + rs.getString("sku")), "/products/")));
        entities.add(new Entity("invoice", """
                SELECT d.id, d."documentNumber", d."totalAmount", p.name AS "partnerName" FROM "Document" d
                  LEFT JOIN "Partner" p ON p.id = d."partnerId"
                WHERE d."organizationId" = :org AND d."documentType" = 'sales_invoice'
                  AND (d."documentNumber" ILIKE :q OR d.reference ILIKE :q) LIMIT 5""",
                rs -> hit("invoice", rs.getString("id"), rs.getString("documentNumber"),
                        nz(rs.getString("partnerName")) + " · " + decimal(rs.getBigDecimal("totalAmount")), "/invoices/")));
        entities.add(new Entity("credit_note", """
                SELECT d.id, d."documentNumber", p.name AS "partnerName" FROM "Document" d
                  LEFT JOIN "Partner" p ON p.id = d."partnerId"
                WHERE d."organizationId" = :org AND d."documentType" = 'credit_note' AND d."documentNumber" ILIKE :q LIMIT 5""",
                rs -> hit("credit_note", rs.getString("id"), rs.getString("documentNumber"), nz(rs.getString("partnerName")),
                        "/credit-notes/")));
        entities.add(new Entity("payment", """
                SELECT pm.id, pm."paymentNumber", p.name AS "partnerName" FROM "Payment" pm
                  LEFT JOIN "Partner" p ON p.id = pm."partnerId"
                WHERE pm."organizationId" = :org AND (pm."paymentNumber" ILIKE :q OR pm.reference ILIKE :q) LIMIT 5""",
                rs -> hit("payment", rs.getString("id"), rs.getString("paymentNumber"), nz(rs.getString("partnerName")),
                        "/payments/")));
        entities.add(new Entity("expense", """
                SELECT d.id, d."documentNumber", p.name AS "partnerName" FROM "Document" d
                  LEFT JOIN "Partner" p ON p.id = d."partnerId"
                WHERE d."organizationId" = :org AND d."documentType" = 'vendor_bill' AND d."documentNumber" ILIKE :q LIMIT 5""",
                rs -> hit("expense", rs.getString("id"), rs.getString("documentNumber"), nz(rs.getString("partnerName")),
                        "/expenses/")));
        entities.add(new Entity("account", """
                SELECT id, code, name, "reportSection"::text AS "reportSection" FROM "Account"
                WHERE "organizationId" = :org AND (code ILIKE :q OR name ILIKE :q) LIMIT 5""",
                rs -> hit("account", rs.getString("id"), rs.getString("code") + " " + rs.getString("name"),
                        rs.getString("reportSection") == null ? "account" : rs.getString("reportSection"), "/accounts?id=")));
        entities.add(new Entity("journal_entry", """
                SELECT id, "entryNumber", description FROM "JournalEntry"
                WHERE "organizationId" = :org AND ("entryNumber" ILIKE :q OR description ILIKE :q) LIMIT 5""",
                rs -> hit("journal_entry", rs.getString("id"), rs.getString("entryNumber"), nz(rs.getString("description")),
                        "/journal-entries/")));
        entities.add(new Entity("deal", """
                SELECT d.id, d.name, d.stage::text AS stage, p.name AS "partnerName" FROM "Deal" d
                  LEFT JOIN "Partner" p ON p.id = d."partnerId"
                WHERE d."organizationId" = :org AND d."deletedAt" IS NULL AND (d.name ILIKE :q OR d.notes ILIKE :q) LIMIT 5""",
                rs -> hit("deal", rs.getString("id"), rs.getString("name"),
                        nz(rs.getString("partnerName")) + " · " + nz(rs.getString("stage")), "/crm/deals/")));
    }

    public List<Map<String, Object>> search(String rawQuery) {
        String q = rawQuery.trim();
        if (q.length() < 2) {
            return List.of();
        }
        String orgId = TenantContext.organizationId();
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Entity e : entities) {
            List<Map<String, Object>> rows;
            try {
                rows = jdbc.sql(e.sql()).param("org", orgId).param("q", like).query((rs, n) -> e.mapper().map(rs)).list();
            } catch (RuntimeException err) {
                rows = List.of();
            }
            for (Map<String, Object> h : rows) {
                if (seen.add(h.get("type") + ":" + h.get("id"))) {
                    hits.add(h);
                }
            }
        }
        List<Map<String, Object>> trigram;
        try {
            trigram = trigram(q, orgId);
        } catch (RuntimeException err) {
            log.warn("Trigram search failed (pg_trgm extension missing?): {}", err.toString());
            trigram = List.of();
        }
        for (Map<String, Object> h : trigram) {
            if (seen.add(h.get("type") + ":" + h.get("id"))) {
                hits.add(h);
            }
        }
        return hits;
    }

    private List<Map<String, Object>> trigram(String q, String orgId) {
        String sql = """
                WITH q AS (SELECT CAST(:needle AS text) AS needle)
                SELECT 'partner' AS type, id::text AS id, name AS title, code AS subtitle, '/partners/' || id::text AS href,
                       GREATEST(similarity(name, (SELECT needle FROM q)), similarity(code, (SELECT needle FROM q))) AS score
                  FROM "Partner" WHERE "organizationId" = :org AND "deletedAt" IS NULL
                    AND (name % (SELECT needle FROM q) OR code % (SELECT needle FROM q) OR COALESCE(email, '') % (SELECT needle FROM q))
                UNION ALL
                SELECT 'product', id::text, name, code, '/products/' || id::text,
                       GREATEST(similarity(name, (SELECT needle FROM q)), similarity(code, (SELECT needle FROM q)),
                                similarity(COALESCE(sku, ''), (SELECT needle FROM q)))
                  FROM "Product" WHERE "organizationId" = :org AND "isActive" = true
                    AND (name % (SELECT needle FROM q) OR code % (SELECT needle FROM q) OR COALESCE(sku, '') % (SELECT needle FROM q))
                UNION ALL
                SELECT 'invoice', id::text, "documentNumber", COALESCE(reference, ''), '/invoices/' || id::text,
                       GREATEST(similarity("documentNumber", (SELECT needle FROM q)), similarity(COALESCE(reference, ''), (SELECT needle FROM q)))
                  FROM "Document" WHERE "organizationId" = :org AND "documentType" = 'sales_invoice'
                    AND ("documentNumber" % (SELECT needle FROM q) OR COALESCE(reference, '') % (SELECT needle FROM q))
                UNION ALL
                SELECT 'credit_note', id::text, "documentNumber", COALESCE(reference, ''), '/credit-notes/' || id::text,
                       similarity("documentNumber", (SELECT needle FROM q))
                  FROM "Document" WHERE "organizationId" = :org AND "documentType" = 'credit_note'
                    AND "documentNumber" % (SELECT needle FROM q)
                UNION ALL
                SELECT 'payment', id::text, "paymentNumber", COALESCE(reference, ''), '/payments/' || id::text,
                       GREATEST(similarity("paymentNumber", (SELECT needle FROM q)), similarity(COALESCE(reference, ''), (SELECT needle FROM q)))
                  FROM "Payment" WHERE "organizationId" = :org
                    AND ("paymentNumber" % (SELECT needle FROM q) OR COALESCE(reference, '') % (SELECT needle FROM q))
                UNION ALL
                SELECT 'expense', id::text, "documentNumber", COALESCE(reference, ''), '/expenses/' || id::text,
                       similarity("documentNumber", (SELECT needle FROM q))
                  FROM "Document" WHERE "organizationId" = :org AND "documentType" = 'vendor_bill'
                    AND "documentNumber" % (SELECT needle FROM q)
                UNION ALL
                SELECT 'account', id::text, code || ' ' || name, '' AS subtitle, '/accounts?id=' || id::text,
                       GREATEST(similarity(name, (SELECT needle FROM q)), similarity(code, (SELECT needle FROM q)))
                  FROM "Account" WHERE "organizationId" = :org
                    AND (name % (SELECT needle FROM q) OR code % (SELECT needle FROM q))
                UNION ALL
                SELECT 'deal', id::text, name, COALESCE(notes, ''), '/crm/deals/' || id::text,
                       GREATEST(similarity(name, (SELECT needle FROM q)), similarity(COALESCE(notes, ''), (SELECT needle FROM q)))
                  FROM "Deal" WHERE "organizationId" = :org AND "deletedAt" IS NULL
                    AND (name % (SELECT needle FROM q) OR COALESCE(notes, '') % (SELECT needle FROM q))
                ORDER BY score DESC
                LIMIT 25""";
        return jdbc.sql(sql).param("needle", q).param("org", orgId).query((rs, n) -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("type", rs.getString("type"));
            h.put("id", rs.getString("id"));
            h.put("title", rs.getString("title"));
            h.put("subtitle", rs.getString("subtitle"));
            h.put("href", rs.getString("href"));
            double score = rs.getDouble("score");
            h.put("meta", Map.of("score", BigDecimal.valueOf(score).setScale(3, RoundingMode.HALF_UP).doubleValue()));
            return h;
        }).list();
    }

    private static Map<String, Object> hit(String type, String id, String title, String subtitle, String hrefPrefix) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("type", type);
        h.put("id", id);
        h.put("title", title);
        h.put("subtitle", subtitle);
        h.put("href", hrefPrefix + id);
        return h;
    }

    private static boolean blank(String s) {
        return s == null || s.isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** decimal.js {@code toString()} for the subtitle. */
    private static String decimal(BigDecimal d) {
        if (d == null) {
            return "";
        }
        BigDecimal s = d.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
    }
}
