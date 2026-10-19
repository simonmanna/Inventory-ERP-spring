package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.FiscalPeriod;
import com.inventoryerp.kernel.crud.BaseCrudService;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;

/** Port of {@code fiscal-period.service.ts} (generic CRUD). */
@Service
public class FiscalPeriodService extends BaseCrudService<FiscalPeriod> {

    public FiscalPeriodService(EntityManager em) {
        super(em, FiscalPeriod.class);
    }

    @Override
    protected String entityName() {
        return "FiscalPeriod";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("name");
    }
}
