package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.PosPaymentMethod;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POS payment modes bound to the finance account the money lands in (port of
 * {@code pos-payment-method.service.ts}); every write is validated against the same category map the tender
 * resolver enforces at posting time.
 */
@Service
public class PosPaymentMethodService {

    private static final List<String> KIND_ORDER = List.of("cash", "mobile_money", "card", "bank", "store_credit");
    private static final Map<String, String> KIND_ICON = Map.of("cash", "Banknote", "mobile_money", "Smartphone",
            "card", "CreditCard", "bank", "Building2", "store_credit", "Gift");
    private static final Map<String, String> KIND_LABEL = Map.of("cash", "Cash", "mobile_money", "Mobile Money",
            "card", "Card", "bank", "Bank", "store_credit", "Store Credit");

    /** {@code PosPaymentMethodView}. */
    public record View(String id, String code, String label, String kind, String provider, String accountId,
            String accountName, String accountCode, String icon, int sortOrder, boolean requiresReference,
            boolean trackInShift, boolean isActive, Boolean synthetic) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("code", code);
            m.put("label", label);
            m.put("kind", kind);
            m.put("provider", provider);
            m.put("accountId", accountId);
            m.put("accountName", accountName);
            m.put("accountCode", accountCode);
            m.put("icon", icon);
            m.put("sortOrder", sortOrder);
            m.put("requiresReference", requiresReference);
            m.put("trackInShift", trackInShift);
            m.put("isActive", isActive);
            if (synthetic != null) {
                m.put("synthetic", synthetic);
            }
            return m;
        }
    }

    /** Create/update body ({@code UpsertPosPaymentMethodDto}); {@code present} lists the keys sent. */
    public record Upsert(String code, String label, String kind, String provider, String accountId, String icon,
            Integer sortOrder, Boolean isActive, Boolean requiresReference, Boolean trackInShift,
            java.util.Set<String> present) {}

    private final EntityManager em;
    private final JdbcClient jdbc;

    public PosPaymentMethodService(EntityManager em, JdbcClient jdbc) {
        this.em = em;
        this.jdbc = jdbc;
    }

    /** Config listing: every non-deleted row, active or not. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<PosPaymentMethod> rows = em.createQuery("select p from PosPaymentMethod p where p.organizationId = :org"
                + " and p.deletedAt is null", PosPaymentMethod.class)
                .setParameter("org", TenantContext.organizationId()).getResultList();
        return sort(rows.stream().map(this::toView).toList()).stream().map(View::toMap).toList();
    }

    /** Terminal tiles: active rows, or a synthesized set when nothing is configured. */
    @Transactional(readOnly = true)
    public List<View> terminalPaymentMethods() {
        String orgId = TenantContext.organizationId();
        List<PosPaymentMethod> rows = em.createQuery("select p from PosPaymentMethod p where p.organizationId = :org"
                + " and p.deletedAt is null and p.isActive = true", PosPaymentMethod.class)
                .setParameter("org", orgId).getResultList();
        if (!rows.isEmpty()) {
            return sort(rows.stream().map(this::toView).toList());
        }
        return synthesize(orgId);
    }

    @Transactional
    public Map<String, Object> create(Upsert dto) {
        String orgId = TenantContext.organizationId();
        String code = dto.code() == null ? "" : dto.code().trim();
        String label = dto.label() == null ? "" : dto.label().trim();
        String kind = dto.kind() == null ? "" : dto.kind().trim();
        if (code.isEmpty() || label.isEmpty()) {
            throw ApiException.badRequest("Code and label are required");
        }
        if (!TenderAccount.POS_TENDER_METHODS.contains(kind)) {
            throw ApiException.badRequest("Payment kind must be one of " + String.join(", ", TenderAccount.POS_TENDER_METHODS));
        }
        boolean exists = !em.createQuery("select p.id from PosPaymentMethod p where p.organizationId = :org and p.code = :code",
                String.class).setParameter("org", orgId).setParameter("code", code).getResultList().isEmpty();
        if (exists) {
            throw ApiException.badRequest("A payment method with this code already exists");
        }
        String accountId = validateAccount(kind, dto.accountId());
        PosPaymentMethod row = new PosPaymentMethod();
        row.setOrganizationId(orgId);
        row.setCode(code);
        row.setLabel(label);
        row.setKind(kind);
        row.setProvider(blankToNull(dto.provider()));
        row.setAccountId(accountId);
        row.setIcon(blankToNull(dto.icon()));
        row.setSortOrder(dto.sortOrder() == null ? 0 : dto.sortOrder());
        row.setIsActive(dto.isActive() == null || dto.isActive());
        row.setRequiresReference(dto.requiresReference() != null && dto.requiresReference());
        row.setTrackInShift("cash".equals(kind) ? false : dto.trackInShift() == null || dto.trackInShift());
        em.persist(row);
        em.flush();
        return toView(row).toMap();
    }

    @Transactional
    public Map<String, Object> update(String id, Upsert dto) {
        PosPaymentMethod current = require(id);
        String kind = (dto.kind() != null ? dto.kind() : current.getKind()).trim();
        if (!TenderAccount.POS_TENDER_METHODS.contains(kind)) {
            throw ApiException.badRequest("Payment kind must be one of " + String.join(", ", TenderAccount.POS_TENDER_METHODS));
        }
        String accountId = validateAccount(kind, dto.present().contains("accountId") ? dto.accountId() : current.getAccountId());
        if (Boolean.FALSE.equals(dto.isActive()) || (dto.kind() != null && !"cash".equals(dto.kind())
                && "cash".equals(current.getKind()))) {
            assertNotLastCashMethod(current);
        }
        if (dto.code() != null && !dto.code().trim().equals(current.getCode())) {
            boolean clash = !em.createQuery("select p.id from PosPaymentMethod p where p.organizationId = :org"
                            + " and p.code = :code and p.id <> :id", String.class)
                    .setParameter("org", current.getOrganizationId()).setParameter("code", dto.code().trim())
                    .setParameter("id", id).getResultList().isEmpty();
            if (clash) {
                throw ApiException.badRequest("A payment method with this code already exists");
            }
        }
        if (dto.code() != null) {
            current.setCode(dto.code().trim());
        }
        if (dto.label() != null) {
            current.setLabel(dto.label().trim());
        }
        current.setKind(kind);
        if (dto.present().contains("provider")) {
            current.setProvider(blankToNull(dto.provider()));
        }
        current.setAccountId(accountId);
        if (dto.present().contains("icon")) {
            current.setIcon(blankToNull(dto.icon()));
        }
        if (dto.sortOrder() != null) {
            current.setSortOrder(dto.sortOrder());
        }
        if (dto.isActive() != null) {
            current.setIsActive(dto.isActive());
        }
        if (dto.requiresReference() != null) {
            current.setRequiresReference(dto.requiresReference());
        }
        if ("cash".equals(kind)) {
            current.setTrackInShift(false);
        } else if (dto.trackInShift() != null) {
            current.setTrackInShift(dto.trackInShift());
        }
        em.flush();
        return toView(current).toMap();
    }

    /** Soft-retire. */
    @Transactional
    public void remove(String id) {
        PosPaymentMethod current = require(id);
        assertNotLastCashMethod(current);
        current.setDeletedAt(Instant.now());
        current.setIsActive(false);
        em.flush();
    }

    private PosPaymentMethod require(String id) {
        PosPaymentMethod row = em.createQuery("select p from PosPaymentMethod p where p.id = :id and p.organizationId = :org"
                        + " and p.deletedAt is null", PosPaymentMethod.class)
                .setParameter("id", id).setParameter("org", TenantContext.organizationId())
                .getResultList().stream().findFirst().orElse(null);
        if (row == null) {
            throw ApiException.notFound("Payment method not found");
        }
        return row;
    }

    private String validateAccount(String kind, String accountId) {
        String orgId = TenantContext.organizationId();
        if ("cash".equals(kind)) {
            return null;
        }
        if (accountId == null || accountId.isEmpty()) {
            throw ApiException.badRequest("A " + KIND_LABEL.getOrDefault(kind, kind) + " payment method needs a receiving account");
        }
        record Acct(String id, String name, String category) {}
        Acct account = jdbc.sql("SELECT a.id, a.name, c.key FROM \"Account\" a LEFT JOIN \"AccountCategory\" c"
                        + " ON c.id = a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ? AND a.\"isActive\" = true"
                        + " AND a.\"deletedAt\" IS NULL")
                .params(accountId, orgId)
                .query((rs, n) -> new Acct(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional().orElse(null);
        if (account == null) {
            throw ApiException.badRequest("Receiving account is inactive or unavailable");
        }
        List<String> allowed = TenderAccount.ALLOWED_CATEGORIES_BY_METHOD.getOrDefault(kind, List.of());
        if (!allowed.contains(account.category())) {
            throw ApiException.badRequest(account.name() + " is not a " + KIND_LABEL.getOrDefault(kind, kind) + " account");
        }
        if ("card".equals(kind) && "current_asset".equals(account.category())) {
            String clearing = mapping(orgId, "card_clearing");
            if (!account.id().equals(clearing)) {
                throw ApiException.badRequest("Card clearing must use the account configured under the card_clearing mapping");
            }
        }
        return account.id();
    }

    private String mapping(String orgId, String key) {
        return jdbc.sql("SELECT \"accountId\" FROM \"AccountMapping\" WHERE \"organizationId\" = ? AND key = ? LIMIT 1")
                .params(orgId, key).query(String.class).optional().orElse(null);
    }

    private void assertNotLastCashMethod(PosPaymentMethod current) {
        if (!"cash".equals(current.getKind())) {
            return;
        }
        long others = em.createQuery("select count(p) from PosPaymentMethod p where p.organizationId = :org"
                        + " and p.deletedAt is null and p.isActive = true and p.kind = 'cash' and p.id <> :id", Long.class)
                .setParameter("org", TenantContext.organizationId()).setParameter("id", current.getId()).getSingleResult();
        if (others == 0) {
            throw ApiException.badRequest("The last cash payment method cannot be removed — the terminal must always be"
                    + " able to take cash");
        }
    }

    private List<View> synthesize(String orgId) {
        List<Map<String, Object>> accounts = jdbc.sql("SELECT a.id, a.code, a.name, c.key FROM \"Account\" a"
                        + " JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.\"organizationId\" = ?"
                        + " AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL"
                        + " AND c.key IN ('bank', 'mobile_money', 'current_asset') ORDER BY a.code ASC")
                .param(orgId).query().listOfRows();
        String clearing = mapping(orgId, "card_clearing");
        List<View> out = new ArrayList<>();
        out.add(new View("synthetic:cash", "cash", "Cash", "cash", null, null, null, null, KIND_ICON.get("cash"), 0,
                false, false, true, true));
        for (Map<String, Object> a : accounts) {
            String key = (String) a.get("key");
            String kind = "current_asset".equals(key) ? "card" : key;
            String id = (String) a.get("id");
            if ("current_asset".equals(key) && !id.equals(clearing)) {
                continue;
            }
            String name = (String) a.get("name");
            String code = (String) a.get("code");
            out.add(new View("synthetic:" + id, kind + "_" + code, "current_asset".equals(key) ? "Card" : name, kind,
                    "mobile_money".equals(key) || "bank".equals(key) ? name : null, id, name, code,
                    KIND_ICON.getOrDefault(kind, "Wallet"), 0, "mobile_money".equals(kind) || "bank".equals(kind), true,
                    true, true));
        }
        if (clearing == null) {
            accounts.stream().filter(a -> "bank".equals(a.get("key"))).findFirst().ifPresent(bank -> out.add(new View(
                    "synthetic:card:" + bank.get("id"), "card_" + bank.get("code"), "Card", "card", null,
                    (String) bank.get("id"), (String) bank.get("name"), (String) bank.get("code"), KIND_ICON.get("card"), 0,
                    false, true, true, true)));
        }
        return sort(out);
    }

    private static List<View> sort(List<View> rows) {
        List<View> out = new ArrayList<>(rows);
        out.sort(Comparator.<View>comparingInt(v -> {
            int i = KIND_ORDER.indexOf(v.kind());
            return i == -1 ? KIND_ORDER.size() : i;
        }).thenComparingInt(View::sortOrder).thenComparing(View::label, AccountResolverService::localeCompare));
        return out;
    }

    private View toView(PosPaymentMethod row) {
        Account account = row.getAccountId() == null ? null : em.find(Account.class, row.getAccountId());
        String icon = row.getIcon() != null && !row.getIcon().isEmpty() ? row.getIcon()
                : KIND_ICON.getOrDefault(row.getKind(), "Wallet");
        return new View(row.getId(), row.getCode(), row.getLabel(), row.getKind(), row.getProvider(), row.getAccountId(),
                account == null ? null : account.getName(), account == null ? null : account.getCode(), icon,
                row.getSortOrder() == null ? 0 : row.getSortOrder(), Boolean.TRUE.equals(row.getRequiresReference()),
                Boolean.TRUE.equals(row.getTrackInShift()), Boolean.TRUE.equals(row.getIsActive()), null);
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
