package com.inventoryerp.intercompany.application;

import com.inventoryerp.accounting.application.AccountResolverService;
import com.inventoryerp.core.application.PartnerService;
import com.inventoryerp.intercompany.application.CompanyGroupService.Group;
import com.inventoryerp.intercompany.application.CompanyGroupService.Member;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Intercompany partners (ADR-S028 §3) and per-company intercompany settings (§4). In company X, the partner linked to
 * member Y is how the books recognise X's documents and journal lines with Y.
 */
@Service
public class IntercompanyPartnerService {

    public static final Set<String> MIRROR_MODES = Set.of("off", "draft", "post");

    /** Settings of one company. */
    public record Settings(String mirrorMode, String billExpenseAccountId) {}

    /** Fields of a partner created for a member company ({@link PartnerService#create} patches by component name). */
    public record NewPartner(String name, String code, Boolean isCompany, Boolean isCustomer, Boolean isSupplier) {}

    private final JdbcClient jdbc;
    private final CompanyGroupService groups;
    private final PartnerService partners;
    private final AccountResolverService accounts;
    private final AuditService audit;

    public IntercompanyPartnerService(JdbcClient jdbc, CompanyGroupService groups, PartnerService partners,
            AccountResolverService accounts, AuditService audit) {
        this.jdbc = jdbc;
        this.groups = groups;
        this.partners = partners;
        this.accounts = accounts;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** {@code GET /intercompany/partners}: every other member with its linked partner (or {@code null}). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        Group g = groups.requireGroup();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Member m : g.members()) {
            if (m.organizationId().equals(org())) {
                continue;
            }
            Map<String, Object> link = SqlRows.one(jdbc.sql("SELECT l.\"partnerId\", p.code, p.name FROM"
                    + " \"IntercompanyPartner\" l JOIN \"Partner\" p ON p.id = l.\"partnerId\" WHERE l.\"organizationId\""
                    + " = ? AND l.\"counterpartOrganizationId\" = ?").params(org(), m.organizationId()));
            out.add(Obj.of("counterpartOrganizationId", m.organizationId(), "counterpartCode", m.code(),
                    "counterpartName", m.name(), "partnerId", link == null ? null : link.get("partnerId"),
                    "partnerCode", link == null ? null : link.get("code"), "partnerName",
                    link == null ? null : link.get("name")));
        }
        return out;
    }

    /** {@code PUT /intercompany/partners/:counterpart}: link {@code partnerId}, or create a partner when it is null. */
    @Transactional
    public Map<String, Object> link(String counterpartOrganizationId, String partnerId) {
        Group g = groups.requireSameGroup(counterpartOrganizationId);
        Member counterpart = g.member(counterpartOrganizationId).orElseThrow();
        String id = partnerId;
        if (id == null || id.isBlank()) {
            id = (String) partners.create(new NewPartner(counterpart.name(), "IC-" + counterpart.code(), true, true,
                    true), List.of(), List.of()).get("id");
        } else if (jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ? AND"
                + " \"deletedAt\" IS NULL").params(id, org()).query(Long.class).single() == 0) {
            throw ApiException.notFound("Partner not found");
        }
        String other = jdbc.sql("SELECT \"counterpartOrganizationId\" FROM \"IntercompanyPartner\" WHERE"
                + " \"organizationId\" = ? AND \"partnerId\" = ?").params(org(), id).query(String.class).optional()
                .orElse(null);
        if (other != null && !other.equals(counterpartOrganizationId)) {
            throw ApiException.conflict("The partner already stands for another company of the group");
        }
        jdbc.sql("INSERT INTO \"IntercompanyPartner\" (id, \"organizationId\", \"counterpartOrganizationId\","
                + " \"partnerId\") VALUES (?, ?, ?, ?) ON CONFLICT (\"organizationId\", \"counterpartOrganizationId\")"
                + " DO UPDATE SET \"partnerId\" = EXCLUDED.\"partnerId\", \"updatedAt\" = now()")
                .params(UUID.randomUUID().toString(), org(), counterpartOrganizationId, id).update();
        audit.recordInTx(AuditInput.of("IntercompanyPartner", counterpartOrganizationId, "assign", null,
                Obj.of("partnerId", id)));
        String linked = id;
        return list().stream().filter(r -> linked.equals(r.get("partnerId"))).findFirst().orElseThrow();
    }

    /** The partner standing for {@code counterpartOrganizationId} in {@code organizationId}. */
    public Optional<String> partnerFor(String organizationId, String counterpartOrganizationId) {
        return jdbc.sql("SELECT \"partnerId\" FROM \"IntercompanyPartner\" WHERE \"organizationId\" = ? AND"
                + " \"counterpartOrganizationId\" = ?").params(organizationId, counterpartOrganizationId)
                .query(String.class).optional();
    }

    /** The member company a partner of {@code organizationId} stands for. */
    public Optional<String> counterpartOf(String organizationId, String partnerId) {
        return jdbc.sql("SELECT \"counterpartOrganizationId\" FROM \"IntercompanyPartner\" WHERE \"organizationId\" = ?"
                + " AND \"partnerId\" = ?").params(organizationId, partnerId).query(String.class).optional();
    }

    /** Every intercompany partner of {@code organizationId} whose company is in {@code group}: partnerId → company. */
    public Map<String, String> partnersInGroup(String organizationId, Group group) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : SqlRows.list(jdbc.sql("SELECT \"partnerId\", \"counterpartOrganizationId\" FROM"
                + " \"IntercompanyPartner\" WHERE \"organizationId\" = ?").param(organizationId))) {
            String counterpart = (String) r.get("counterpartOrganizationId");
            if (group.contains(counterpart)) {
                out.put((String) r.get("partnerId"), counterpart);
            }
        }
        return out;
    }

    // ---- settings -------------------------------------------------------------------------------------------------

    /** Settings of {@code organizationId} (defaults when never saved). */
    public Settings settings(String organizationId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT \"mirrorMode\", \"billExpenseAccountId\" FROM"
                + " \"IntercompanySettings\" WHERE \"organizationId\" = ?").param(organizationId));
        return r == null ? new Settings("draft", null)
                : new Settings((String) r.get("mirrorMode"), (String) r.get("billExpenseAccountId"));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSettings() {
        Settings s = settings(org());
        return Obj.of("mirrorMode", s.mirrorMode(), "billExpenseAccountId", s.billExpenseAccountId(),
                "effectiveBillExpenseAccountId", billExpenseAccount(s));
    }

    @Transactional
    public Map<String, Object> putSettings(String mirrorMode, String billExpenseAccountId) {
        Settings before = settings(org());
        String mode = mirrorMode == null ? before.mirrorMode() : mirrorMode;
        String account = billExpenseAccountId == null ? before.billExpenseAccountId()
                : billExpenseAccountId.isBlank() ? null : billExpenseAccountId;
        if (account != null) {
            Boolean postable = jdbc.sql("SELECT \"isPostable\" FROM \"Account\" WHERE id = ? AND \"organizationId\" = ?"
                    + " AND \"deletedAt\" IS NULL AND \"isActive\"").params(account, org()).query(Boolean.class)
                    .optional().orElse(null);
            if (postable == null) {
                throw ApiException.notFound("Account not found");
            }
            if (!postable) {
                throw ApiException.badRequest("billExpenseAccountId must be a postable account");
            }
        }
        jdbc.sql("INSERT INTO \"IntercompanySettings\" (\"organizationId\", \"mirrorMode\", \"billExpenseAccountId\","
                + " \"updatedBy\") VALUES (?, ?, ?, ?) ON CONFLICT (\"organizationId\") DO UPDATE SET \"mirrorMode\" ="
                + " EXCLUDED.\"mirrorMode\", \"billExpenseAccountId\" = EXCLUDED.\"billExpenseAccountId\","
                + " \"updatedBy\" = EXCLUDED.\"updatedBy\", \"updatedAt\" = now()")
                .params(org(), mode, account, TenantContext.userId()).update();
        audit.recordInTx(AuditInput.of("IntercompanySettings", org(), "update", Obj.of("mirrorMode",
                before.mirrorMode(), "billExpenseAccountId", before.billExpenseAccountId()), Obj.of("mirrorMode", mode,
                "billExpenseAccountId", account)));
        return getSettings();
    }

    /** The account of mirrored bill lines: the setting, else the {@code default_expense} mapping (may be null). */
    public String billExpenseAccount(Settings s) {
        return s.billExpenseAccountId() != null ? s.billExpenseAccountId()
                : accounts.byMappingOptional("default_expense");
    }
}
