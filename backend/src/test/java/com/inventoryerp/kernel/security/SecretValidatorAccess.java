package com.inventoryerp.kernel.security;

import java.util.List;

/** Test bridge to the package-private {@link SecretValidator#validate}. */
public final class SecretValidatorAccess {

    private SecretValidatorAccess() {}

    public static List<String> validate(String access, String refresh, String pos) {
        return SecretValidator.validate(new JwtProperties(access, refresh, pos, null, null, null));
    }
}
