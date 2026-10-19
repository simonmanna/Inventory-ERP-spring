package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.Address;
import com.inventoryerp.kernel.crud.BaseCrudService;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;

/** Port of {@code address.service.ts} (generic CRUD). */
@Service
public class AddressService extends BaseCrudService<Address> {

    public AddressService(EntityManager em) {
        super(em, Address.class);
    }

    @Override
    protected String entityName() {
        return "Address";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("line1", "city", "state", "country");
    }
}
