package com.inventoryerp.kernel.recurring;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.shared.domain.DocumentType;
import com.inventoryerp.kernel.domain.RecurringDocument;
import com.inventoryerp.kernel.domain.RecurringFrequency;
import com.inventoryerp.kernel.domain.RecurringStatus;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.kernel.tenancy.TenantContext.TenantStore;
import com.inventoryerp.shared.persistence.DbEnum;
import com.inventoryerp.shared.web.ApiException;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recurring document templates (port of {@code recurring.service.ts}). The tick generates a draft
 * {@code Document} (+ lines, subtotal only, tax 0) for every due active template, then advances the
 * schedule by a fixed interval and ends it past {@code endDate}.
 */
@Service
public class RecurringService {

    private static final Logger log = LoggerFactory.getLogger(RecurringService.class);

    static final Map<String, Duration> FREQUENCY = Map.of(
            "daily", Duration.ofDays(1),
            "weekly", Duration.ofDays(7),
            "monthly", Duration.ofDays(30),
            "quarterly", Duration.ofDays(90),
            "yearly", Duration.ofDays(365));

    private final EntityManager em;
    private final JdbcClient jdbc;
    private final EventBus events;
    private final AuditService audit;
    private final JsonMapper mapper;

    public RecurringService(EntityManager em, JdbcClient jdbc, EventBus events, AuditService audit, JsonMapper mapper) {
        this.em = em;
        this.jdbc = jdbc;
        this.events = events;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> create(String name, String documentType, Object template, String frequency, Instant nextRunAt,
            Instant endDate) {
        RecurringDocument r = new RecurringDocument();
        r.setOrganizationId(TenantContext.organizationId());
        r.setName(name);
        r.setDocumentType(DbEnum.fromValue(DocumentType.class, documentType));
        r.setTemplate(EntityJson.toJson(template));
        r.setFrequency(DbEnum.fromValue(RecurringFrequency.class, frequency));
        r.setNextRunAt(nextRunAt);
        r.setEndDate(endDate);
        r.setCreatedById(TenantContext.userId());
        em.persist(r);
        em.flush();
        em.refresh(r);
        return EntityJson.of(r);
    }

    @Transactional
    public Map<String, Object> setStatus(String id, String status) {
        List<RecurringDocument> rows = em.createQuery("select r from RecurringDocument r where r.id = :id"
                        + " and r.organizationId = :org", RecurringDocument.class)
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .getResultList();
        if (rows.isEmpty()) {
            // Prisma update() on a missing row: P2025, an unhandled 500 in Nest.
            throw new IllegalStateException("No RecurringDocument found for id " + id);
        }
        RecurringDocument r = rows.getFirst();
        r.setStatus(DbEnum.fromValue(RecurringStatus.class, status));
        em.flush();
        return EntityJson.of(r);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        return EntityJson.list(em.createQuery("select r from RecurringDocument r where r.organizationId = :org"
                        + " order by r.nextRunAt asc", RecurringDocument.class)
                .setParameter("org", TenantContext.organizationId())
                .getResultList());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> findOne(String id) {
        var rows = em.createQuery("select r from RecurringDocument r where r.id = :id and r.organizationId = :org",
                        RecurringDocument.class)
                .setParameter("id", id)
                .setParameter("org", TenantContext.organizationId())
                .getResultList();
        return rows.isEmpty() ? null : EntityJson.of(rows.getFirst());
    }

    /** Generate children for due rows (all orgs, like Nest's raw query). */
    public Map<String, Integer> tick(Instant now) {
        record Due(String id, String organizationId, String name, String documentType, String template, String frequency,
                Instant nextRunAt, Instant lastRunAt, Instant endDate) {}
        List<Due> due = jdbc.sql("""
                        SELECT id, "organizationId", name, "documentType"::text AS "documentType", template::text AS template,
                               frequency::text AS frequency, "nextRunAt", "lastRunAt", "endDate"
                        FROM "RecurringDocument" WHERE status = 'active' AND "nextRunAt" <= ? LIMIT 100""")
                .param(Timestamp.from(now))
                .query((rs, n) -> new Due(rs.getString("id"), rs.getString("organizationId"), rs.getString("name"),
                        rs.getString("documentType"), rs.getString("template"), rs.getString("frequency"),
                        rs.getTimestamp("nextRunAt").toInstant(),
                        rs.getTimestamp("lastRunAt") == null ? null : rs.getTimestamp("lastRunAt").toInstant(),
                        rs.getTimestamp("endDate") == null ? null : rs.getTimestamp("endDate").toInstant()))
                .list();
        int processed = 0;
        int errors = 0;
        for (Due r : due) {
            try {
                TenantContext.run(TenantStore.organization(r.organizationId()), () -> generateOne(r.id(), r.organizationId(),
                        r.name(), r.documentType(), r.template(), r.frequency(), r.nextRunAt(), r.lastRunAt(), r.endDate(), now));
                processed++;
            } catch (RuntimeException err) {
                errors++;
                String msg = err.toString();
                jdbc.sql("""
                                INSERT INTO "RecurringDocumentRun" (id, "organizationId", "recurringId", "scheduledFor", "ranAt", status, error)
                                VALUES (?, ?, ?, ?, ?, 'failed', ?)""")
                        .params(UUID.randomUUID().toString(), r.organizationId(), r.id(), Timestamp.from(r.nextRunAt()),
                                Timestamp.from(now), msg.length() > 1000 ? msg.substring(0, 1000) : msg)
                        .update();
                log.warn("Recurring {} failed: {}", r.id(), msg);
            }
        }
        return Map.of("processed", processed, "errors", errors);
    }

    private void generateOne(String id, String orgId, String name, String documentType, String templateJson,
            String frequency, Instant nextRunAt, Instant lastRunAt, Instant endDate, Instant now) {
        JsonNode template = mapper.readTree(templateJson == null ? "{}" : templateJson);
        String typeId = jdbc.sql("SELECT id FROM \"DocumentTypeDef\" WHERE code = ?").param(documentType)
                .query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("Unknown document type code: " + documentType));
        String docId = UUID.randomUUID().toString();
        long stamp = lastRunAt != null ? lastRunAt.toEpochMilli() : now.toEpochMilli();
        jdbc.sql("""
                        INSERT INTO "Document" (id, "organizationId", "documentNumber", "documentType", "documentTypeId", "partnerId",
                          "currencyId", "exchangeRate", "issueDate", status, reference, notes, "sourceType", "sourceId", "updatedAt")
                        VALUES (?, ?, ?, ?::"DocumentType", ?, ?, ?, ?, ?, 'draft', ?, ?, 'recurring', ?, now())""")
                .params(docId, orgId, "DRAFT-" + id.substring(0, 8) + "-" + stamp, documentType, typeId,
                        text(template, "partnerId"), text(template, "currencyId"),
                        template.hasNonNull("exchangeRate") ? template.get("exchangeRate").decimalValue() : BigDecimal.ONE,
                        Timestamp.from(now), template.hasNonNull("reference") ? text(template, "reference") : "recurring:" + name,
                        text(template, "notes"), id)
                .update();
        int lineNo = 1;
        BigDecimal subtotal = BigDecimal.ZERO;
        for (JsonNode ln : template.path("lines")) {
            BigDecimal qty = num(ln.get("quantity"));
            BigDecimal price = num(ln.get("unitPrice"));
            BigDecimal discount = ln.hasNonNull("discountPercent") ? num(ln.get("discountPercent")) : BigDecimal.ZERO;
            BigDecimal lineSubtotal = qty.multiply(price)
                    .multiply(BigDecimal.ONE.subtract(discount.divide(BigDecimal.valueOf(100), 20, RoundingMode.HALF_UP)))
                    .setScale(6, RoundingMode.HALF_UP);
            jdbc.sql("""
                            INSERT INTO "DocumentLine" (id, "organizationId", "documentId", "productId", "accountId", description,
                              quantity, "unitPrice", "discountPercent", "taxId", subtotal, "taxAmount", total, "lineNumber", "updatedAt")
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, now())""")
                    .params(UUID.randomUUID().toString(), orgId, docId, text(ln, "productId"), text(ln, "accountId"),
                            text(ln, "description"), qty, price, discount, text(ln, "taxId"), lineSubtotal, lineSubtotal, lineNo++)
                    .update();
            subtotal = subtotal.add(lineSubtotal);
        }
        jdbc.sql("""
                        UPDATE "Document" SET subtotal = ?, "discountTotal" = 0, "taxAmount" = 0, "totalAmount" = ?, "amountResidual" = ?,
                          "updatedAt" = now() WHERE id = ?""")
                .params(subtotal, subtotal, subtotal, docId)
                .update();
        jdbc.sql("""
                        INSERT INTO "RecurringDocumentRun" (id, "organizationId", "recurringId", "scheduledFor", "ranAt", "documentId", status)
                        VALUES (?, ?, ?, ?, ?, ?, 'generated')""")
                .params(UUID.randomUUID().toString(), orgId, id, Timestamp.from(nextRunAt), Timestamp.from(now), docId)
                .update();
        Instant next = nextRunAt.plus(FREQUENCY.get(frequency));
        boolean ended = endDate != null && next.isAfter(endDate);
        jdbc.sql("""
                        UPDATE "RecurringDocument" SET "lastRunAt" = ?, "nextRunAt" = ?, status = CASE WHEN ? THEN 'ended'::"RecurringStatus" ELSE status END,
                          "updatedAt" = now() WHERE id = ?""")
                .params(Timestamp.from(now), Timestamp.from(next), ended, id)
                .update();
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("organizationId", orgId);
        ev.put("recurringId", id);
        ev.put("documentId", docId);
        events.publish("recurring.generated", ev);
        audit.record(AuditService.AuditInput.of("RecurringDocument", id, "update", null, Map.of("generatedDocumentId", docId)));
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static BigDecimal num(JsonNode n) {
        if (n == null || n.isNull()) {
            return BigDecimal.ZERO;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        double d = com.inventoryerp.kernel.common.JsNumbers.number(n.asString());
        return Double.isNaN(d) ? BigDecimal.ZERO : BigDecimal.valueOf(d);
    }
}
