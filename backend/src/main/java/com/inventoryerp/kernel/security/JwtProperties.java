package com.inventoryerp.kernel.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token configuration (env names as in POS-CAFE: JWT_ACCESS_SECRET, JWT_REFRESH_SECRET, JWT_POS_SECRET,
 * JWT_ACCESS_TTL, JWT_REFRESH_TTL, JWT_POS_TTL). An empty POS secret falls back to the access secret,
 * exactly like Nest.
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(
        String accessSecret,
        String refreshSecret,
        String posSecret,
        String accessTtl,
        String refreshTtl,
        String posTtl) {

    public JwtProperties {
        accessTtl = accessTtl == null || accessTtl.isBlank() ? "15m" : accessTtl;
        refreshTtl = refreshTtl == null || refreshTtl.isBlank() ? "7d" : refreshTtl;
        posTtl = posTtl == null || posTtl.isBlank() ? "12h" : posTtl;
    }

    public String effectivePosSecret() {
        return posSecret == null || posSecret.isEmpty() ? accessSecret : posSecret;
    }
}
