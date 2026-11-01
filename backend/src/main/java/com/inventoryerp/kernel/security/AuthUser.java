package com.inventoryerp.kernel.security;

/**
 * The authenticated identity of a request ({@code req.auth} in Nest). When a valid {@code X-Pos-User}
 * token is present this is the cashier, otherwise the bearer-token user.
 *
 * @param sub user id ({@code null} for an API client)
 * @param organizationId tenant established by the bearer token
 * @param email user email
 * @param clientId API client row id when the bearer is an OAuth client token (ADR-S029 §5), else {@code null}
 */
public record AuthUser(String sub, String organizationId, String email, String clientId) {

    /** Request attribute holding the {@link AuthUser}. */
    public static final String ATTRIBUTE = "erp.auth";

    /** A user (or cashier) identity. */
    public AuthUser(String sub, String organizationId, String email) {
        this(sub, organizationId, email, null);
    }

    /** True for an API client token. */
    public boolean isClient() {
        return clientId != null;
    }
}
