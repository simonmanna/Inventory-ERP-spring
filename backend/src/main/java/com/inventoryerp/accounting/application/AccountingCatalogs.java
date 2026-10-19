package com.inventoryerp.accounting.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Accounting contract catalogs generated from packages/shared + the Nest COA template by
 * {@code tools/contracts2java/accounting.mjs}: account categories, the account-mapping registry, report
 * sections and the default chart of accounts.
 */
public final class AccountingCatalogs {

    private AccountingCatalogs() {}

    /** {@code AccountCategoryDef}. */
    public record CategoryDef(String key, String name, String description, String classification,
            String normalBalance, String reportSection, String cashFlowClass, boolean isContra,
            boolean isCashEquivalent, boolean allowReconciliation, boolean allowManualPosting,
            boolean allowBudgeting, int sortOrder, int systemVersion) {}

    /** {@code AccountMappingDef}. */
    public record MappingDef(String key, String label, String group, List<String> expectedCategories,
            boolean required, String description) {}

    /** {@code ReportSectionDef}. */
    public record ReportSectionDef(String key, String label, String side, int order) {}

    /** {@code CoaAccountDef}. */
    public record CoaAccount(String code, String name, String categoryKey, String parentCode, Boolean isPostable,
            String cashFlowCategory, Boolean isDefault, String controlAccountType, String bankName,
            String accountNumber, Integer sortOrder) {}

    /** {@code CoaJournalDef}. */
    public record CoaJournal(String code, String name, String journalType, String defaultDebitCode) {}

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static final List<CategoryDef> CATEGORIES = categories();
    public static final Map<String, CategoryDef> CATEGORY_BY_KEY = index(CATEGORIES);
    public static final JsonNode MAPPINGS_JSON = read("account-mappings.json");
    public static final List<MappingDef> MAPPING_REGISTRY = registry();
    public static final Map<String, MappingDef> MAPPING_BY_KEY = mappingIndex();
    public static final List<ReportSectionDef> REPORT_SECTIONS = sections();
    public static final List<CoaAccount> COA_ACCOUNTS = coaAccounts();
    public static final Map<String, String> COA_MAPPINGS = coaMappings();
    public static final List<CoaJournal> COA_JOURNALS = coaJournals();

    private static JsonNode read(String name) {
        try (InputStream in = AccountingCatalogs.class.getResourceAsStream("/contracts/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing contract resource " + name);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String str(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static List<CategoryDef> categories() {
        List<CategoryDef> out = new ArrayList<>();
        for (JsonNode c : read("account-categories.json")) {
            out.add(new CategoryDef(str(c, "key"), str(c, "name"), str(c, "description"), str(c, "classification"),
                    str(c, "normalBalance"), str(c, "reportSection"), str(c, "cashFlowClass"),
                    c.get("isContra").asBoolean(), c.get("isCashEquivalent").asBoolean(),
                    c.get("allowReconciliation").asBoolean(), c.get("allowManualPosting").asBoolean(),
                    c.get("allowBudgeting").asBoolean(), c.get("sortOrder").asInt(), c.get("systemVersion").asInt()));
        }
        return List.copyOf(out);
    }

    private static Map<String, CategoryDef> index(List<CategoryDef> defs) {
        Map<String, CategoryDef> out = new LinkedHashMap<>();
        defs.forEach(d -> out.put(d.key(), d));
        return out;
    }

    private static List<MappingDef> registry() {
        List<MappingDef> out = new ArrayList<>();
        for (JsonNode m : MAPPINGS_JSON.get("registry")) {
            List<String> expected = new ArrayList<>();
            m.get("expectedCategories").forEach(e -> expected.add(e.asString()));
            out.add(new MappingDef(str(m, "key"), str(m, "label"), str(m, "group"), List.copyOf(expected),
                    m.get("required").asBoolean(), str(m, "description")));
        }
        return List.copyOf(out);
    }

    private static Map<String, MappingDef> mappingIndex() {
        Map<String, MappingDef> out = new LinkedHashMap<>();
        MAPPING_REGISTRY.forEach(m -> out.put(m.key(), m));
        return out;
    }

    private static List<ReportSectionDef> sections() {
        List<ReportSectionDef> out = new ArrayList<>();
        for (JsonNode s : read("report-sections.json")) {
            out.add(new ReportSectionDef(str(s, "key"), str(s, "label"), str(s, "side"), s.get("order").asInt()));
        }
        return List.copyOf(out);
    }

    private static JsonNode coa() {
        return read("coa-template.json");
    }

    private static List<CoaAccount> coaAccounts() {
        List<CoaAccount> out = new ArrayList<>();
        for (JsonNode a : coa().get("accounts")) {
            out.add(new CoaAccount(str(a, "code"), str(a, "name"), str(a, "categoryKey"), str(a, "parentCode"),
                    a.has("isPostable") ? a.get("isPostable").asBoolean() : null, str(a, "cashFlowCategory"),
                    a.has("isDefault") ? a.get("isDefault").asBoolean() : null, str(a, "controlAccountType"),
                    str(a, "bankName"), str(a, "accountNumber"), a.has("sortOrder") ? a.get("sortOrder").asInt() : null));
        }
        return List.copyOf(out);
    }

    private static Map<String, String> coaMappings() {
        Map<String, String> out = new LinkedHashMap<>();
        coa().get("mappings").properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        return out;
    }

    private static List<CoaJournal> coaJournals() {
        List<CoaJournal> out = new ArrayList<>();
        for (JsonNode j : coa().get("journals")) {
            out.add(new CoaJournal(str(j, "code"), str(j, "name"), str(j, "journalType"), str(j, "defaultDebitCode")));
        }
        return List.copyOf(out);
    }
}
