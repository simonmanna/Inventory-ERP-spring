package com.inventoryerp.license.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LicenseEventRepository extends JpaRepository<LicenseEvent, Long> {

    List<LicenseEvent> findTop200ByLicenseIdOrderByAtDesc(String licenseId);

    boolean existsByLicenseIdAndTypeAndAtAfter(String licenseId, String type, Instant after);
}
