package com.inventoryerp.inventory.application;

import static com.inventoryerp.inventory.application.StockService.NOW;

import com.inventoryerp.inventory.application.InventoryPostingRuleService.StructureLine;
import com.inventoryerp.inventory.domain.InventoryPostingRule;
import com.inventoryerp.kernel.common.InventoryPostingRuleDefaults;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD + preview of inventory → GL posting rules (port of {@code posting-rule.controller.ts} service). */
@Service
public class PostingRuleAdminService {

    /** {@code INVENTORY_MOVEMENT_TYPES} (packages/shared/src/enums.ts). */
    public static final List<String> MOVEMENT_TYPES = List.of("STOCK_IN", "STOCK_OUT", "STOCK_TRANSFER_OUT",
            "STOCK_TRANSFER_IN", "ADJUSTMENT_GAIN", "ADJUSTMENT_LOSS", "WASTE", "EXPIRY_WRITE_OFF", "RETURN_RESTOCK",
            "RETURN_TO_SUPPLIER", "INTERNAL_CONSUMPTION", "PROMO_SAMPLE", "PRODUCTION_CONSUME", "PRODUCTION_OUTPUT",
            "REVALUATION", "COGS_CORRECTION");

    private final JdbcClient jdbc;
    private final InventoryPostingRuleService rules;

    public PostingRuleAdminService(JdbcClient jdbc, InventoryPostingRuleService rules) {
        this.jdbc = jdbc;
        this.rules = rules;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Rules of the org, optionally filtered (an empty filter value matches the empty string). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String movementType, String productId, String categoryId) {
        List<Object> params = new ArrayList<>(List.of(org()));
        StringBuilder where = new StringBuilder(" \"organizationId\" = ?");
        if (movementType != null && !movementType.isEmpty()) {
            where.append(" AND \"movementType\"::text = ?");
            params.add(movementType);
        }
        if (productId != null) {
            where.append(" AND \"productId\" = ?");
            params.add(productId);
        }
        if (categoryId != null) {
            where.append(" AND \"categoryId\" = ?");
            params.add(categoryId);
        }
        return SqlRows.list(InventoryPostingRule.class, jdbc.sql("SELECT * FROM \"InventoryPostingRule\" WHERE" + where
                + " ORDER BY \"movementType\" ASC, \"lineIndex\" ASC").params(params));
    }

    /** Movement-type defaults grouped by movement type. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> grouped = new LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(InventoryPostingRule.class, jdbc.sql("SELECT * FROM"
                + " \"InventoryPostingRule\" WHERE \"organizationId\" = ? AND \"productId\" IS NULL AND \"categoryId\" IS NULL"
                + " ORDER BY \"movementType\" ASC, \"lineIndex\" ASC").param(org()))) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) grouped.computeIfAbsent(
                    String.valueOf(r.get("movementType")), k -> new ArrayList<>());
            list.add(r);
        }
        return grouped;
    }

    /** {@code [{code, label}]} for every movement type. */
    public List<Map<String, Object>> movementTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String t : MOVEMENT_TYPES) {
            out.add(Obj.of("code", t, "label", label(t)));
        }
        return out;
    }

    /** {@code t.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase())}. */
    static String label(String t) {
        String spaced = t.replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        boolean boundary = true;
        for (char c : spaced.toCharArray()) {
            boolean word = Character.isLetterOrDigit(c) || c == '_';
            sb.append(boundary && word ? Character.toUpperCase(c) : c);
            boundary = !word;
        }
        return sb.toString();
    }

    /** Insert the missing canonical movement-type defaults; existing rules are untouched. */
    @Transactional
    public Map<String, Object> restoreDefaults() {
        Set<String> seen = new HashSet<>();
        long existing = 0;
        for (Map<String, Object> r : jdbc.sql("SELECT \"movementType\"::text AS mt, \"lineIndex\" FROM"
                        + " \"InventoryPostingRule\" WHERE \"organizationId\" = ? AND \"productId\" IS NULL AND \"categoryId\" IS NULL")
                .param(org()).query().listOfRows()) {
            seen.add(r.get("mt") + "#" + r.get("lineIndex"));
            existing++;
        }
        List<InventoryPostingRuleDefaults.Rule> missing = InventoryPostingRuleDefaults.RULES.stream()
                .filter(r -> !seen.contains(r.movementType() + "#" + r.lineIndex())).toList();
        if (missing.isEmpty()) {
            return Obj.of("created", 0, "skipped", existing);
        }
        for (InventoryPostingRuleDefaults.Rule r : missing) {
            jdbc.sql("INSERT INTO \"InventoryPostingRule\" (id, \"organizationId\", \"movementType\", \"lineIndex\","
                            + " \"debitOrCredit\", \"accountSource\", \"accountMappingKey\", \"createdAt\", \"updatedAt\")"
                            + " VALUES (?, ?, ?::\"InventoryMovementType\", ?, ?, ?, ?, " + NOW + ", " + NOW + ")"
                            + " ON CONFLICT DO NOTHING")
                    .params(UUID.randomUUID().toString(), org(), r.movementType(), r.lineIndex(), r.debitOrCredit(),
                            r.accountSource(), r.accountMappingKey())
                    .update();
        }
        return Obj.of("created", missing.size(), "skipped", existing);
    }

    /**
     * Create a rule. An omitted {@code accountSource} takes the column default ({@code account_mapping}, as Prisma does);
     * a {@code literal} rule's account is checked now (see {@link #assertLiteralAccount}).
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> dto) {
        String source = dto.get("accountSource") == null ? "account_mapping" : (String) dto.get("accountSource");
        assertLiteralAccount(source, (String) dto.get("literalAccountId"));
        return SqlRows.one(InventoryPostingRule.class, jdbc.sql("INSERT INTO \"InventoryPostingRule\" (id,"
                        + " \"organizationId\", \"movementType\", \"lineIndex\", \"debitOrCredit\", \"accountSource\","
                        + " \"accountMappingKey\", \"literalAccountId\", \"productId\", \"categoryId\", \"createdAt\","
                        + " \"updatedAt\") VALUES (?, ?, ?::\"InventoryMovementType\", ?, ?, ?, ?, ?, ?, ?, " + NOW + ", "
                        + NOW + ") RETURNING *")
                .params(UUID.randomUUID().toString(), org(), dto.get("movementType"),
                        dto.get("lineIndex") instanceof Number n ? n.intValue() : dto.get("lineIndex"),
                        dto.get("debitOrCredit"), source, dto.get("accountMappingKey"),
                        dto.get("literalAccountId"), dto.get("productId"), dto.get("categoryId")));
    }

    /**
     * Update the account source / mapping / literal / active flag (omitted = unchanged). The resulting source and
     * literal account are checked like on create.
     */
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> dto) {
        require(id);
        if (dto.containsKey("accountSource") && dto.get("accountSource") == null) {
            throw ApiException.badRequest("accountSource cannot be null");
        }
        if (dto.containsKey("accountSource") || dto.containsKey("literalAccountId")) {
            Map<String, Object> row = jdbc.sql("SELECT \"accountSource\", \"literalAccountId\" FROM"
                    + " \"InventoryPostingRule\" WHERE id = ? AND \"organizationId\" = ?").params(id, org()).query()
                    .singleRow();
            String source = (String) (dto.containsKey("accountSource") ? dto.get("accountSource")
                    : row.get("accountSource"));
            String literal = (String) (dto.containsKey("literalAccountId") ? dto.get("literalAccountId")
                    : row.get("literalAccountId"));
            assertLiteralAccount(source, literal);
        }
        List<Object> params = new ArrayList<>();
        StringBuilder set = new StringBuilder("\"updatedAt\" = " + NOW);
        for (String f : List.of("accountSource", "accountMappingKey", "literalAccountId", "isActive")) {
            if (dto.containsKey(f)) {
                set.append(", \"").append(f).append("\" = ?");
                params.add(dto.get(f));
            }
        }
        params.add(id);
        params.add(org());
        return SqlRows.one(InventoryPostingRule.class, jdbc.sql("UPDATE \"InventoryPostingRule\" SET " + set
                + " WHERE id = ? AND \"organizationId\" = ? RETURNING *").params(params));
    }

    /**
     * A literal account must be an account of this organisation that can take postings (postable, active, not
     * deprecated), checked when the rule is saved instead of failing at the first stock posting. A {@code literal} rule
     * needs one; other sources may carry one (ignored at posting) but it must still be valid.
     */
    private void assertLiteralAccount(String accountSource, String literalAccountId) {
        if (literalAccountId == null || literalAccountId.isBlank()) {
            if ("literal".equals(accountSource)) {
                throw ApiException.badRequest("A literal posting rule needs a literalAccountId");
            }
            return;
        }
        Map<String, Object> account = SqlRows.one(jdbc.sql("SELECT code, \"isPostable\", \"isActive\","
                + " \"deprecatedAt\" FROM \"Account\" WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL")
                .params(literalAccountId, org()));
        if (account == null) {
            throw ApiException.badRequest("literalAccountId " + literalAccountId
                    + " is not an account of this organization");
        }
        // Same wording as AccountResolverService.assertPostable (the check the posting engine applies later).
        if (!Boolean.TRUE.equals(account.get("isPostable"))) {
            throw ApiException.badRequest("Account " + account.get("code")
                    + " is a summary/group account and cannot be posted to");
        }
        if (!Boolean.TRUE.equals(account.get("isActive"))) {
            throw ApiException.badRequest("Account " + account.get("code") + " is inactive and cannot be posted to");
        }
        if (account.get("deprecatedAt") != null) {
            throw ApiException.badRequest("Account " + account.get("code") + " is deprecated and cannot be posted to");
        }
    }

    /** Hard-delete a rule. */
    @Transactional
    public void remove(String id) {
        require(id);
        jdbc.sql("DELETE FROM \"InventoryPostingRule\" WHERE id = ?").param(id).update();
    }

    private void require(String id) {
        if (jdbc.sql("SELECT count(*) FROM \"InventoryPostingRule\" WHERE id = ? AND \"organizationId\" = ?")
                .params(id, org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Posting rule not found");
        }
    }

    /** Resolved accounts per line for a product + movement type, with the resolution level. */
    @Transactional(readOnly = true)
    public Map<String, Object> resolvePreview(String productId, String movementType) {
        List<StructureLine> structure = rules.resolveStructure(movementType, productId);
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        structure.forEach(s -> {
            if (s.debitAccountId() != null) {
                ids.add(s.debitAccountId());
            }
            if (s.creditAccountId() != null) {
                ids.add(s.creditAccountId());
            }
        });
        Map<String, Map<String, Object>> accounts = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> a : jdbc.sql("SELECT id, code, name FROM \"Account\" WHERE id IN (:ids)"
                            + " AND \"organizationId\" = :org AND \"deletedAt\" IS NULL")
                    .param("ids", List.copyOf(ids)).param("org", org()).query().listOfRows()) {
                accounts.put((String) a.get("id"), a);
            }
        }
        List<Map<String, Object>> ruleRows = jdbc.sql("SELECT \"productId\", \"categoryId\" FROM \"InventoryPostingRule\""
                        + " WHERE \"organizationId\" = ? AND \"movementType\"::text = ? AND \"isActive\" = true")
                .params(org(), movementType).query().listOfRows();
        String categoryId = jdbc.sql("SELECT \"categoryId\" FROM \"Product\" WHERE id = ? AND \"organizationId\" = ?"
                        + " AND \"deletedAt\" IS NULL").params(productId, org()).query(String.class).optional().orElse(null);
        boolean productOverride = ruleRows.stream().anyMatch(r -> productId.equals(r.get("productId")));
        String cat = categoryId != null ? categoryId : "__none__";
        boolean categoryDefault = ruleRows.stream().anyMatch(r -> cat.equals(r.get("categoryId")));
        String level = productOverride ? "product_override" : categoryDefault ? "category_default" : "org_default";
        List<Map<String, Object>> lines = new ArrayList<>();
        for (StructureLine s : structure) {
            lines.add(Obj.of("debit", accountRef(s.debitAccountId(), accounts), "credit",
                    accountRef(s.creditAccountId(), accounts), "accountSource", s.accountSource()));
        }
        return Obj.of("movementType", movementType, "resolutionLevel", level, "lines", lines);
    }

    private static Map<String, Object> accountRef(String id, Map<String, Map<String, Object>> accounts) {
        if (id == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        Map<String, Object> a = accounts.get(id);
        if (a != null) {
            m.putAll(a);
        }
        return m;
    }
}
