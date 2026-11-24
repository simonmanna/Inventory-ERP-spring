package com.inventoryerp.plm.application;

import static com.inventoryerp.kernel.crud.SqlRows.str;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.plm.application.PlmCommands.SaveTag;
import com.inventoryerp.plm.domain.PlmChangeTag;
import com.inventoryerp.plm.domain.PlmTag;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tags on engineering change orders (ADR-S086): free labels to filter and group ECOs by. */
@Service
public class PlmTagService {

    private final PlmDb db;

    public PlmTagService(PlmDb db) {
        this.db = db;
    }

    /** Tags by name with {@code changes} (how many ECOs carry each). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> tags = db.list(PlmTag.class, " ORDER BY lower(name)");
        Map<String, Long> used = new HashMap<>();
        db.jdbc().sql("SELECT \"tagId\", count(*) FROM \"PlmChangeTag\" WHERE \"organizationId\" = ? GROUP BY \"tagId\"")
                .param(PlmDb.org()).query((rs, i) -> used.put(rs.getString(1), rs.getLong(2))).list();
        tags.forEach(t -> t.put("changes", used.getOrDefault(str(t, "id"), 0L)));
        return tags;
    }

    @Transactional
    public Map<String, Object> create(SaveTag dto) {
        String name = PlmSupport.blank(dto.name());
        if (name == null) {
            throw ApiException.badRequest("name should not be empty");
        }
        uniqueName(name, null);
        return db.insert(PlmTag.class, Obj.of("name", name, "color", PlmSupport.blank(dto.color())));
    }

    @Transactional
    public Map<String, Object> update(String id, SaveTag dto) {
        Map<String, Object> tag = require(id);
        Map<String, Object> data = new LinkedHashMap<>();
        if (dto.name() != null) {
            String name = PlmSupport.blank(dto.name());
            if (name == null) {
                throw ApiException.badRequest("name should not be empty");
            }
            uniqueName(name, id);
            data.put("name", name);
        }
        if (dto.color() != null) {
            data.put("color", PlmSupport.blank(dto.color()));
        }
        return data.isEmpty() ? tag : db.update(PlmTag.class, id, data);
    }

    /** Soft delete; the tag comes off every ECO. */
    @Transactional
    public void remove(String id) {
        require(id);
        db.jdbc().sql("DELETE FROM \"PlmChangeTag\" WHERE \"organizationId\" = ? AND \"tagId\" = ?")
                .params(PlmDb.org(), id).update();
        db.update(PlmTag.class, id, Obj.of("deletedAt", Instant.now()));
    }

    // ---- used by the change service ------------------------------------------------------------------------------

    /** Replace the tags of an ECO; returns their names in the order given (for the activity log). */
    List<String> set(String changeId, List<String> tagIds) {
        List<String> wanted = new ArrayList<>(new LinkedHashSet<>(tagIds.stream()
                .filter(t -> t != null && !t.isBlank()).toList()));
        List<String> names = new ArrayList<>();
        for (String t : wanted) {
            names.add(str(require(t), "name"));
        }
        db.jdbc().sql("DELETE FROM \"PlmChangeTag\" WHERE \"organizationId\" = ? AND \"changeId\" = ?")
                .params(PlmDb.org(), changeId).update();
        for (String t : wanted) {
            db.insert(PlmChangeTag.class, Obj.of("changeId", changeId, "tagId", t));
        }
        return names;
    }

    /** {@code changeId → [{id, name, color}]} for the given ECOs (live tags only). */
    Map<String, List<Map<String, Object>>> of(Collection<String> changeIds) {
        Map<String, List<Map<String, Object>>> out = new HashMap<>();
        List<String> ids = changeIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : SqlRows.list(db.jdbc().sql("SELECT ct.\"changeId\", t.id, t.name, t.color FROM"
                + " \"PlmChangeTag\" ct JOIN \"PlmTag\" t ON t.id = ct.\"tagId\" AND t.\"deletedAt\" IS NULL WHERE"
                + " ct.\"organizationId\" = ? AND ct.\"changeId\" = ANY(?::text[]) ORDER BY lower(t.name)")
                .params(PlmDb.org(), ids.toArray(String[]::new)))) {
            out.computeIfAbsent(str(r, "changeId"), k -> new ArrayList<>()).add(Obj.of("id", r.get("id"), "name",
                    r.get("name"), "color", r.get("color")));
        }
        return out;
    }

    Map<String, Object> require(String id) {
        Map<String, Object> t = db.byId(PlmTag.class, id);
        if (t == null) {
            throw ApiException.notFound("Tag not found");
        }
        return t;
    }

    private void uniqueName(String name, String exceptId) {
        for (Map<String, Object> t : db.list(PlmTag.class, " AND lower(name) = lower(?)", name)) {
            if (!t.get("id").equals(exceptId)) {
                throw ApiException.conflict("A tag named " + name + " already exists.");
            }
        }
    }
}
