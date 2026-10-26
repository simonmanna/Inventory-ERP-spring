package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.Customer;
import com.inventoryerp.license.domain.CustomerRepository;
import com.inventoryerp.license.domain.LicenseRepository;
import com.inventoryerp.license.shared.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Who we sell to. Plain CRUD; the interesting rules live on the licence. */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final LicenseRepository licenses;

    public CustomerService(CustomerRepository customers, LicenseRepository licenses) {
        this.customers = customers;
        this.licenses = licenses;
    }

    @Transactional(readOnly = true)
    public Page<Customer> search(String query, int page, int pageSize) {
        return customers.search(query == null || query.isBlank() ? null : query.trim(),
                PageRequest.of(Math.max(0, page - 1), Math.min(200, Math.max(1, pageSize))));
    }

    @Transactional(readOnly = true)
    public Customer get(String id) {
        return customers.findById(id)
                .orElseThrow(() -> ApiException.notFound("No customer with id '" + id + "'"));
    }

    @Transactional
    public Customer create(String name, String email, String phone, String country, String taxId, String notes) {
        if (email != null && !email.isBlank() && customers.existsByEmailIgnoreCase(email.trim())) {
            throw ApiException.conflict("A customer with that email already exists.");
        }
        Customer customer = new Customer();
        apply(customer, name, email, phone, country, taxId, notes);
        return customers.saveAndFlush(customer);
    }

    @Transactional
    public Customer update(String id, String name, String email, String phone, String country, String taxId,
            String notes) {
        Customer customer = get(id);
        if (email != null && !email.isBlank()
                && !email.trim().equalsIgnoreCase(customer.getEmail())
                && customers.existsByEmailIgnoreCase(email.trim())) {
            throw ApiException.conflict("A customer with that email already exists.");
        }
        apply(customer, name == null ? customer.getName() : name, email, phone, country, taxId, notes);
        return customers.saveAndFlush(customer);
    }

    /**
     * Deleting a customer who holds licences would orphan the audit trail of every lockout they ever
     * had, so it is refused while any licence exists. Revoke the licences first.
     */
    @Transactional
    public void delete(String id) {
        Customer customer = get(id);
        long held = licenses.search(null, customer.getId(), null, PageRequest.of(0, 1)).getTotalElements();
        if (held > 0) {
            throw ApiException.conflict("This customer holds " + held
                    + " licence(s). Revoke them before deleting the customer.");
        }
        customers.delete(customer);
    }

    private static void apply(Customer customer, String name, String email, String phone, String country,
            String taxId, String notes) {
        customer.setName(name == null ? null : name.trim());
        customer.setEmail(email == null || email.isBlank() ? null : email.trim());
        customer.setPhone(phone);
        customer.setCountry(country);
        customer.setTaxId(taxId);
        customer.setNotes(notes);
    }
}
