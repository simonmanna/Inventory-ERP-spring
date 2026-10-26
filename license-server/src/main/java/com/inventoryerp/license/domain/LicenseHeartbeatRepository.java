package com.inventoryerp.license.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LicenseHeartbeatRepository extends JpaRepository<LicenseHeartbeat, Long> {

    List<LicenseHeartbeat> findTop100ByActivationIdOrderByAtDesc(String activationId);

    Optional<LicenseHeartbeat> findFirstByActivationIdOrderByAtDesc(String activationId);

    @Modifying
    @Transactional
    @Query("delete from LicenseHeartbeat h where h.at < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
