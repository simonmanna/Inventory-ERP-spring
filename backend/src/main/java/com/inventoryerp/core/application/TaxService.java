package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.Tax;
import com.inventoryerp.kernel.crud.BaseCrudService;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;

/** Port of {@code tax.service.ts} (generic CRUD). */
@Service
public class TaxService extends BaseCrudService<Tax> {

    public TaxService(EntityManager em) {
        super(em, Tax.class);
    }

    @Override
    protected String entityName() {
        return "Tax";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("name", "code");
    }
}
