package com.inventoryerp.crm.application;

import static com.inventoryerp.crm.application.CrmDb.NOW;
import static com.inventoryerp.crm.application.CrmDb.blank;
import static com.inventoryerp.crm.application.CrmDb.dec;
import static com.inventoryerp.crm.application.CrmDb.org;
import static com.inventoryerp.crm.application.CrmDb.str;
import static com.inventoryerp.crm.application.CrmDb.strings;
import static com.inventoryerp.crm.application.CrmDb.user;

import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.events.EventBus;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRM enhancements T3b on one deal: expected product lines (which pre-fill a quotation), the other contacts on the deal,
 * and merging a duplicate lead or opportunity into another.
 */
@Service
public class CrmDealExtrasService {

    static final int MAX_LINES = 200;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CrmDb db;
    private final CrmPipelineService pipeline;
    private final AuditService audit;
    private final EventBus events;

    public CrmDealExtrasService(CrmDb db, CrmPipelineService pipeline, AuditService audit, EventBus events) {
        this.db = db;
        this.pipeline = pipeline;
        this.audit = audit;
        this.events = events;
    }

    // ------------------------------------------------------------------ product lines

    /**
     * Replace the deal's product lines ({@code lines}: productId?, variantId?, description, quantity, unitPrice,
     * discountPercent). With {@code updateAmount} (default true) the expected revenue becomes the lines' total.
     */
    @Transactional
    public Map<String, Object> saveLines(String dealId, Map<String, Object> in) {
        Map<String, Object> deal = pipeline.lock(dealId);
        if (!(in.get("lines") instanceof List<?> raw)) {
            throw ApiException.badRequest("lines must be an array");
        }
        if (raw.size() > MAX_LINES) {
            throw ApiException.badRequest("At most " + MAX_LINES + " lines");
        }
        List<Object[]> rows = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int i = 0;
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> m)) {
                throw ApiException.badRequest("Each line must be an object");
            }
            i++;
            String productId = str(m.get("productId"));
            String variantId = str(m.get("variantId"));
            String description = str(m.get("description"));
            if (!blank(productId)) {
                Map<String, Object> p = db.row("SELECT name FROM \"Product\" WHERE id = ? AND \"organizationId\" = ? AND"
                        + " \"deletedAt\" IS NULL", productId, org());
                if (p == null) {
                    throw ApiException.notFound("Line " + i + ": product not found");
                }
                if (blank(description)) {
                    description = str(p.get("name"));
                }
                if (!blank(variantId) && db.row("SELECT id FROM \"ProductVariant\" WHERE id = ? AND \"productId\" = ?",
                        variantId, productId) == null) {
                    throw ApiException.notFound("Line " + i + ": variant not found");
                }
            } else {
                variantId = null;
            }
            if (blank(description)) {
                throw ApiException.badRequest("Line " + i + ": choose a product or type a description");
            }
            if (description.length() > 500) {
                throw ApiException.badRequest("Line " + i + ": description must be at most 500 characters");
            }
            BigDecimal qty = number(m.get("quantity"), BigDecimal.ONE, "Line " + i + ": quantity");
            BigDecimal price = number(m.get("unitPrice"), BigDecimal.ZERO, "Line " + i + ": unitPrice");
            BigDecimal disc = number(m.get("discountPercent"), BigDecimal.ZERO, "Line " + i + ": discountPercent");
            if (qty.signum() <= 0) {
                throw ApiException.badRequest("Line " + i + ": quantity must be greater than 0");
            }
            if (price.signum() < 0) {
                throw ApiException.badRequest("Line " + i + ": unitPrice must not be negative");
            }
            if (disc.signum() < 0 || disc.compareTo(HUNDRED) > 0) {
                throw ApiException.badRequest("Line " + i + ": discountPercent must be between 0 and 100");
            }
            total = total.add(lineTotal(qty, price, disc));
            rows.add(new Object[] {CrmDb.newId(), org(), dealId, i, blank(productId) ? null : productId, variantId,
                description.trim(), qty, price, disc});
        }
        db.update("DELETE FROM \"CrmDealLine\" WHERE \"dealId\" = ? AND \"organizationId\" = ?", dealId, org());
        for (Object[] r : rows) {
            db.update("INSERT INTO \"CrmDealLine\" (id, \"organizationId\", \"dealId\", \"sortOrder\", \"productId\","
                    + " \"variantId\", description, quantity, \"unitPrice\", \"discountPercent\") VALUES (?, ?, ?, ?, ?, ?,"
                    + " ?, ?, ?, ?)", r);
        }
        boolean updateAmount = !Boolean.FALSE.equals(in.get("updateAmount"));
        if (updateAmount && !rows.isEmpty() && total.compareTo(dec(deal.get("amount"))) != 0) {
            db.update("UPDATE \"Deal\" SET amount = ?, \"updatedAt\" = " + NOW + " WHERE id = ? AND \"organizationId\" = ?",
                    total, dealId, org());
        }
        audit.record(AuditInput.of("Deal", dealId, "update", null, Obj.of("lines", rows.size(), "linesTotal", total)));
        return pipeline.get(dealId);
    }

    static BigDecimal lineTotal(BigDecimal qty, BigDecimal price, BigDecimal discountPercent) {
        return qty.multiply(price).multiply(HUNDRED.subtract(discountPercent)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal number(Object v, BigDecimal fallback, String field) {
        if (v == null || (v instanceof String s && s.isBlank())) {
            return fallback;
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(field + " must be a number");
        }
    }

    // ------------------------------------------------------------------ contacts

    /** Add a contact (any contact of this organization) to the deal with an optional {@code role}. */
    @Transactional
    public Map<String, Object> addContact(String dealId, Map<String, Object> in) {
        pipeline.lock(dealId);
        String contactId = str(in.get("contactId"));
        if (blank(contactId) || db.row("SELECT id FROM \"Contact\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL", contactId, org()) == null) {
            throw ApiException.notFound("Contact not found");
        }
        String role = str(in.get("role"));
        if (role != null && role.length() > 60) {
            throw ApiException.badRequest("role must be at most 60 characters");
        }
        db.update("INSERT INTO \"CrmDealContact\" (id, \"organizationId\", \"dealId\", \"contactId\", role) VALUES (?, ?, ?,"
                + " ?, ?) ON CONFLICT (\"dealId\", \"contactId\") DO UPDATE SET role = EXCLUDED.role", CrmDb.newId(), org(),
                dealId, contactId, blank(role) ? null : role.trim());
        audit.record(AuditInput.of("Deal", dealId, "update", null, Obj.of("contactAdded", contactId, "role", role)));
        return Obj.of("contacts", pipeline.dealContacts(dealId));
    }

    @Transactional
    public Map<String, Object> removeContact(String dealId, String contactId) {
        pipeline.lock(dealId);
        db.update("DELETE FROM \"CrmDealContact\" WHERE \"dealId\" = ? AND \"contactId\" = ? AND \"organizationId\" = ?",
                dealId, contactId, org());
        audit.record(AuditInput.of("Deal", dealId, "update", null, Obj.of("contactRemoved", contactId)));
        return Obj.of("contacts", pipeline.dealContacts(dealId));
    }

    // ------------------------------------------------------------------ merge

    /**
     * Merge the duplicate {@code sourceId} (a lead or an open opportunity) into {@code targetId}: activities,
     * quotations, product lines and contacts move over; empty fields of the target are filled from the source;
     * tags are combined and the notes appended. The source is archived. A won record cannot be merged away.
     */
    @Transactional
    public Map<String, Object> merge(String targetId, Map<String, Object> in) {
        String sourceId = str(in.get("sourceId"));
        if (blank(sourceId)) {
            throw ApiException.badRequest("sourceId should not be empty");
        }
        if (sourceId.equals(targetId)) {
            throw ApiException.badRequest("Choose a different record to merge");
        }
        // lock in id order so two opposite merges cannot deadlock
        Map<String, Object> target;
        Map<String, Object> source;
        if (targetId.compareTo(sourceId) < 0) {
            target = pipeline.lock(targetId);
            source = pipeline.lock(sourceId);
        } else {
            source = pipeline.lock(sourceId);
            target = pipeline.lock(targetId);
        }
        if ("won".equals(source.get("status"))) {
            throw ApiException.badRequest("A won opportunity cannot be merged into another; merge the other way round");
        }
        if (!"open".equals(target.get("status"))) {
            throw ApiException.badRequest("Merge into an open record");
        }
        if (source.get("partnerId") != null && target.get("partnerId") != null
                && !source.get("partnerId").equals(target.get("partnerId"))) {
            throw ApiException.badRequest("The two records belong to different customers; merge the customers first");
        }
        Map<String, Object> moved = new LinkedHashMap<>();
        moved.put("activities", db.update("UPDATE \"Activity\" SET \"dealId\" = ? WHERE \"dealId\" = ? AND"
                + " \"organizationId\" = ?", targetId, sourceId, org()));
        moved.put("quotations", db.update("UPDATE \"SalesQuotation\" SET \"dealId\" = ? WHERE \"dealId\" = ? AND"
                + " \"organizationId\" = ?", targetId, sourceId, org()));
        // Production-readiness P1: every other record that points at the deal follows it, so nothing is left linked
        // to the archived source (sales orders, projects, queued CRM emails). The source's own stage history stays with
        // it: it describes that record's journey, and the target keeps its own. Legacy CrmQuotation rows are frozen
        // since V24 (a trigger refuses writes) and stay on the archived, still readable source.
        moved.put("salesOrders", db.update("UPDATE \"SalesOrder\" SET \"dealId\" = ? WHERE \"dealId\" = ? AND"
                + " \"organizationId\" = ?", targetId, sourceId, org()));
        moved.put("projects", db.update("UPDATE \"Project\" SET \"dealId\" = ? WHERE \"dealId\" = ? AND"
                + " \"organizationId\" = ?", targetId, sourceId, org()));
        db.update("UPDATE \"CrmEmailOutbox\" SET \"dealId\" = ? WHERE \"dealId\" = ? AND \"organizationId\" = ?",
                targetId, sourceId, org());
        Number maxOrder = (Number) db.row("SELECT COALESCE(max(\"sortOrder\"), 0) AS m FROM \"CrmDealLine\" WHERE"
                + " \"dealId\" = ?", targetId).get("m");
        moved.put("lines", db.update("UPDATE \"CrmDealLine\" SET \"dealId\" = ?, \"sortOrder\" = \"sortOrder\" + ? WHERE"
                + " \"dealId\" = ? AND \"organizationId\" = ?", targetId, maxOrder.intValue(), sourceId, org()));
        db.update("INSERT INTO \"CrmDealContact\" (id, \"organizationId\", \"dealId\", \"contactId\", role) SELECT"
                + " md5(random()::text || id), \"organizationId\", ?, \"contactId\", role FROM \"CrmDealContact\" WHERE"
                + " \"dealId\" = ? ON CONFLICT (\"dealId\", \"contactId\") DO NOTHING", targetId, sourceId);
        if (source.get("contactId") != null && !source.get("contactId").equals(target.get("contactId"))
                && target.get("contactId") != null) {
            db.update("INSERT INTO \"CrmDealContact\" (id, \"organizationId\", \"dealId\", \"contactId\") VALUES (?, ?, ?,"
                    + " ?) ON CONFLICT (\"dealId\", \"contactId\") DO NOTHING", CrmDb.newId(), org(), targetId,
                    source.get("contactId"));
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        for (String k : List.of("partnerId", "contactId", "contactName", "companyName", "email", "phone", "city",
                "country", "sourceId", "expectedClose", "teamId", "ownerId")) {
            if (target.get(k) == null && source.get(k) != null) {
                patch.put(k, source.get(k));
            }
        }
        if (dec(target.get("amount")).signum() == 0 && dec(source.get("amount")).signum() > 0) {
            patch.put("amount", dec(source.get("amount")));
        }
        Set<String> tags = new LinkedHashSet<>(strings(target.get("tagIds")));
        tags.addAll(strings(source.get("tagIds")));
        if (tags.size() != strings(target.get("tagIds")).size()) {
            patch.put("tagIds", new ArrayList<>(tags));
        }
        if (!blank(str(source.get("notes")))) {
            String head = "Merged from \"" + source.get("name") + "\":\n" + source.get("notes");
            patch.put("notes", blank(str(target.get("notes"))) ? head : target.get("notes") + "\n\n" + head);
        }
        if (!patch.isEmpty()) {
            db.patch("Deal", targetId, patch, true);
        }
        db.update("INSERT INTO \"Activity\" (id, \"organizationId\", type, title, body, \"dealId\", \"partnerId\", status,"
                + " completed, \"completedAt\", \"assignedToId\", \"createdById\", \"occurredAt\", \"updatedAt\") VALUES (?, ?,"
                + " 'note'::\"ActivityType\", ?, ?, ?, ?, 'done'::\"TaskStatus\", true, " + NOW + ", ?, ?, " + NOW + ", "
                + NOW + ")", CrmDb.newId(), org(), "Merged \"" + source.get("name") + "\" into this record",
                moved.get("activities") + " activities, " + moved.get("quotations") + " quotations, "
                        + moved.get("salesOrders") + " sales orders, " + moved.get("projects") + " projects and "
                        + moved.get("lines") + " product lines moved.", targetId,
                patch.containsKey("partnerId") ? patch.get("partnerId") : target.get("partnerId"), user(), user());
        db.update("UPDATE \"Deal\" SET \"deletedAt\" = " + NOW + ", \"updatedAt\" = " + NOW + ", \"lostNote\" ="
                + " COALESCE(\"lostNote\", ?) WHERE id = ? AND \"organizationId\" = ?", "Merged into " + target.get("name"),
                sourceId, org());
        audit.record(AuditInput.of("Deal", sourceId, "delete", null, Obj.of("mergedInto", targetId)));
        events.publishOnCommit("crm.deal.deleted", Obj.of("organizationId", org(), "dealId", sourceId));
        audit.record(AuditInput.of("Deal", targetId, "update", null, Obj.of("mergedFrom", sourceId, "moved", moved)));
        Map<String, Object> out = pipeline.get(targetId);
        out.put("merged", moved);
        return out;
    }
}
