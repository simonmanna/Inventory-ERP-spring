package com.inventoryerp.license.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminAuditRepository extends JpaRepository<AdminAudit, Long> {

    Page<AdminAudit> findAllByOrderByAtDesc(Pageable pageable);
}
