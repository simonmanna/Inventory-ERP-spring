package com.inventoryerp.license.domain;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminRefreshTokenRepository extends JpaRepository<AdminRefreshToken, String> {

    Optional<AdminRefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update AdminRefreshToken t set t.revokedAt = :now where t.adminId = :adminId and t.revokedAt is null")
    int revokeAllFor(@Param("adminId") String adminId, @Param("now") Instant now);

    @Modifying
    @Query("delete from AdminRefreshToken t where t.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
