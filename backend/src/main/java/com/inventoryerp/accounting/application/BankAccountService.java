package com.inventoryerp.accounting.application;

import com.inventoryerp.accounting.domain.Account;
import com.inventoryerp.accounting.domain.BankAccount;
import com.inventoryerp.kernel.crud.BaseCrudService;
import com.inventoryerp.shared.web.EntityJson;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Bank accounts (port of {@code bank-account.service.ts}): generic CRUD with the GL {@code account} included. */
@Service
public class BankAccountService extends BaseCrudService<BankAccount> {

    public BankAccountService(EntityManager em) {
        super(em, BankAccount.class);
    }

    @Override
    protected String entityName() {
        return "BankAccount";
    }

    @Override
    protected List<String> searchFields() {
        return List.of("name", "bankName", "accountNumber");
    }

    @Override
    protected List<Map<String, Object>> render(List<BankAccount> rows) {
        return rows.stream().map(b -> {
            Map<String, Object> m = EntityJson.of(b);
            m.put("account", one(em, Account.class, b.getAccountId()));
            return m;
        }).toList();
    }

}
