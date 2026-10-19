package com.inventoryerp.core.application;

import com.inventoryerp.core.domain.Contact;
import com.inventoryerp.kernel.crud.BaseCrudService;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Service;

/** Port of {@code contact.service.ts} (generic CRUD). */
@Service
public class ContactService extends BaseCrudService<Contact> {

    public ContactService(EntityManager em) {
        super(em, Contact.class);
    }

    @Override
    protected String entityName() {
        return "Contact";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("firstName", "lastName", "email", "phone");
    }
}
