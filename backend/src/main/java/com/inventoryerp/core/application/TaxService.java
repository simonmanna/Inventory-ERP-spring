package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.Tax;
import com.inventoryerp.core.domain.TaxType;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of {@code tax.service.ts} (generic CRUD), plus tax groups (ADR-S071): a group tax ({@code isGroup}) lists its
 * ordered children in {@code TaxGroupMember}; requests carry them as {@code children} (tax ids, in order). Group taxes
 * render their {@code children}; other taxes keep their exact shape (the new flags appear only when true).
 */
@Service
public class TaxService extends BaseCrudService<Tax> {

    private final JdbcClient jdbc;

    public TaxService(EntityManager em, JdbcClient jdbc) {
        super(em, Tax.class);
        this.jdbc = jdbc;
    }

    @Override
    protected String entityName() {
        return "Tax";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("name", "code");
    }

    @Override
    protected List<Map<String, Object>> render(List<Tax> rows) {
        List<Map<String, Object>> out = super.render(rows);
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> m = out.get(i);
            // ADR-S071 flags only when set, so plain taxes keep the Nest shape.
            if (!Boolean.TRUE.equals(m.get("isGroup"))) {
                m.remove("isGroup");
            }
            if (!Boolean.TRUE.equals(m.get("reverseCharge"))) {
                m.remove("reverseCharge");
            }
            if (Boolean.TRUE.equals(rows.get(i).getIsGroup())) {
                m.put("children", children(rows.get(i).getId()));
            }
        }
        return out;
    }

    /** Ordered children of a group tax: id, name, code, type, rate, flags. */
    public List<Map<String, Object>> children(String groupId) {
        return com.inventoryerp.kernel.crud.SqlRows.list(jdbc.sql("SELECT t.id, t.name, t.code, t.type::text AS type,"
                + " t.rate, t.\"isInclusive\", t.\"isCompound\", t.\"reverseCharge\", m.sequence FROM \"TaxGroupMember\" m"
                + " JOIN \"Tax\" t ON t.id = m.\"childTaxId\" WHERE m.\"groupTaxId\" = ? AND m.\"organizationId\" = ?"
                + " ORDER BY m.sequence, t.name").params(groupId, TenantContext.organizationId()));
    }

    @Override
    @Transactional
    public Map<String, Object> create(Record dto) {
        List<String> children = childrenOf(dto);
        Tax row = newInstance();
        EntityPatch.apply(row, dto, "children");
        boolean group = Boolean.TRUE.equals(row.getIsGroup());
        validate(row, group, children, null);
        persist(row);
        if (group) {
            replaceChildren(row.getId(), children);
        }
        return render(row);
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, Record dto) {
        List<String> children = childrenOf(dto);
        Tax row = require(id);
        boolean wasGroup = Boolean.TRUE.equals(row.getIsGroup());
        EntityPatch.apply(row, dto, "children");
        boolean group = Boolean.TRUE.equals(row.getIsGroup());
        if (!group && wasGroup) {
            replaceChildren(id, List.of());
        }
        if (group) {
            List<String> effective = children != null ? children
                    : children(id).stream().map(c -> (String) c.get("id")).toList();
            validate(row, true, effective, id);
            if (children != null) {
                replaceChildren(id, children);
            }
        } else {
            validate(row, false, null, id);
        }
        em.flush();
        return render(row);
    }

    private boolean memberOfGroup(String taxId) {
        return jdbc.sql("SELECT count(*) FROM \"TaxGroupMember\" WHERE \"childTaxId\" = ? AND \"organizationId\" = ?")
                .params(taxId, TenantContext.organizationId()).query(Long.class).single() > 0;
    }

    private void validate(Tax row, boolean group, List<String> children, String selfId) {
        if (!group) {
            if (children != null && !children.isEmpty()) {
                throw ApiException.badRequest("Only a group tax has children");
            }
            if (Boolean.TRUE.equals(row.getReverseCharge()) && row.getType() == TaxType.WITHHOLDING) {
                throw ApiException.badRequest("A withholding tax cannot be reverse-charge");
            }
            return;
        }
        if (selfId != null && memberOfGroup(selfId)) {
            throw ApiException.badRequest("A tax inside a group cannot itself be a group");
        }
        if (children == null || children.isEmpty()) {
            throw ApiException.badRequest("A group tax needs at least one child tax");
        }
        if (Boolean.TRUE.equals(row.getReverseCharge()) || Boolean.TRUE.equals(row.getIsInclusive())
                || Boolean.TRUE.equals(row.getIsCompound())) {
            throw ApiException.badRequest("Set inclusive, compound and reverse charge on the child taxes, not the group");
        }
        if (new LinkedHashSet<>(children).size() != children.size()) {
            throw ApiException.badRequest("A child tax is listed twice");
        }
        for (String c : children) {
            if (c == null || c.equals(selfId)) {
                throw ApiException.badRequest("A group tax cannot contain itself");
            }
            Map<String, Object> t = com.inventoryerp.kernel.crud.SqlRows.one(jdbc.sql("SELECT \"isGroup\" FROM \"Tax\""
                    + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL").params(c,
                    TenantContext.organizationId()));
            if (t == null) {
                throw ApiException.badRequest("Child tax " + c + " not found");
            }
            if (Boolean.TRUE.equals(t.get("isGroup"))) {
                throw ApiException.badRequest("A group tax cannot contain another group");
            }
        }
    }

    private void replaceChildren(String groupId, List<String> children) {
        String org = TenantContext.organizationId();
        jdbc.sql("DELETE FROM \"TaxGroupMember\" WHERE \"groupTaxId\" = ? AND \"organizationId\" = ?")
                .params(groupId, org).update();
        int seq = 0;
        for (String c : children) {
            jdbc.sql("INSERT INTO \"TaxGroupMember\" (id, \"organizationId\", \"groupTaxId\", \"childTaxId\", sequence)"
                    + " VALUES (?, ?, ?, ?, ?)").params(UUID.randomUUID().toString(), org, groupId, c, seq++).update();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> childrenOf(Record dto) {
        for (RecordComponent c : dto.getClass().getRecordComponents()) {
            if ("children".equals(c.getName())) {
                try {
                    Object v = c.getAccessor().invoke(dto);
                    return v == null ? null : new ArrayList<>((List<String>) v);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return null;
    }
}
