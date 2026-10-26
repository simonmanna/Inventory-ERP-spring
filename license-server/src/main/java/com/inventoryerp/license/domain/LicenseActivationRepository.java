package com.inventoryerp.license.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LicenseActivationRepository extends JpaRepository<LicenseActivation, String> {

    Optional<LicenseActivation> findByLicenseIdAndFingerprint(String licenseId, String fingerprint);

    List<LicenseActivation> findByLicenseIdOrderByFirstSeenAtAsc(String licenseId);

    long countByLicenseIdAndStatus(String licenseId, String status);

    /**
     * Live activations last seen before {@code before}. The caller decides what counts as silent,
     * because that depends on the plan's heartbeat interval — a daily plan checks in hourly, a
     * yearly one once a day.
     */
    @Query("""
            select a from LicenseActivation a
            where a.status = 'active'
              and a.lastSeenAt < :before
            order by a.lastSeenAt asc""")
    List<LicenseActivation> liveButLastSeenBefore(@Param("before") Instant before);
}
