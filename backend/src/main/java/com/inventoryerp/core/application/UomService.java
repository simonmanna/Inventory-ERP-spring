package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.UnitOfMeasure;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.kernel.crud.EntityPatch;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Port of {@code uom.service.ts}: keeps the authoritative {@code factor} and legacy {@code ratio} in lock-step. */
@Service
public class UomService extends BaseCrudService<UnitOfMeasure> {

    public UomService(EntityManager em) {
        super(em, UnitOfMeasure.class);
    }

    @Override
    protected String entityName() {
        return "UnitOfMeasure";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("code", "name");
    }

    @Override
    @Transactional
    public Map<String, Object> create(Record dto) {
        UnitOfMeasure row = EntityPatch.apply(newInstance(), dto);
        syncFactor(row, dto);
        return render(persist(row));
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, Record dto) {
        UnitOfMeasure row = require(id);
        EntityPatch.apply(row, dto);
        syncFactor(row, dto);
        em.flush();
        return render(row);
    }

    private static void syncFactor(UnitOfMeasure row, Record dto) {
        Object factor = component(dto, "factor");
        Object ratio = component(dto, "ratio");
        if (!EntityPatch.present("factor", factor) && EntityPatch.present("ratio", ratio)) {
            EntityPatch.set(row, "factor", ratio);
        }
        if (!EntityPatch.present("ratio", ratio) && EntityPatch.present("factor", factor)) {
            EntityPatch.set(row, "ratio", factor);
        }
    }

    private static Object component(Record dto, String name) {
        for (var c : dto.getClass().getRecordComponents()) {
            if (c.getName().equals(name)) {
                try {
                    return c.getAccessor().invoke(dto);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return null;
    }
}
