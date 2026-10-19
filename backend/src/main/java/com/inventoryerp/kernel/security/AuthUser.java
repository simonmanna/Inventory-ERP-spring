package com.inventoryerp.kernel.security;

/**
 * The authenticated identity of a request ({@code req.auth} in Nest). When a valid {@code X-Pos-User}
 * token is present this is the cashier, otherwise the bearer-token user.
 *
 * @param sub user id
 * @param organizationId tenant established by the bearer token
 * @param email user email
 */
public record AuthUser(String sub, String organizationId, String email) {

    /** Request attribute holding the {@link AuthUser}. */
    public static final String ATTRIBUTE = "erp.auth";
}
