package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.Branch;
import com.inventoryerp.kernel.crud.BaseCrudService;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;

/** Port of {@code branch.service.ts} (generic CRUD). */
@Service
public class BranchService extends BaseCrudService<Branch> {

    public BranchService(EntityManager em) {
        super(em, Branch.class);
    }

    @Override
    protected String entityName() {
        return "Branch";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("code", "name");
    }
}
