package com.inventoryerp.accounting.application;

import java.util.Map;

/** Test access to package-private {@link BankMatchingService} rule matching. */
public final class BankMatchingServiceAccess {

    private BankMatchingServiceAccess() {}

    public static boolean matches(Map<String, Object> rule, Map<String, Object> line) {
        return BankMatchingService.matches(rule, line);
    }
}
