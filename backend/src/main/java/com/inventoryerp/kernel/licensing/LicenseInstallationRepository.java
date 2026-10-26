package com.inventoryerp.kernel.licensing;

import org.springframework.data.jpa.repository.JpaRepository;

/** The single-row licence table. Install-scoped, so no tenant filter applies. */
public interface LicenseInstallationRepository extends JpaRepository<LicenseInstallation, String> {}
