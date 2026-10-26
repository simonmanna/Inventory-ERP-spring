package com.inventoryerp.license.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LicenseRepository extends JpaRepository<License, String> {

    Optional<License> findByKey(String key);

    @Query("""
            select l from License l
            where (:status is null or l.status = :status)
              and (:customerId is null or l.customerId = :customerId)
              and (:q is null or lower(l.key) like lower(concat('%', cast(:q as string), '%')))
            order by l.createdAt desc""")
    Page<License> search(@Param("status") String status, @Param("customerId") String customerId,
            @Param("q") String query, Pageable pageable);

    /** Active, dated licences whose term ends inside the window — the renewal-reminder feed. */
    @Query("""
            select l from License l
            where l.status = 'active'
              and l.expiresAt is not null
              and l.expiresAt >= :from
              and l.expiresAt < :to
            order by l.expiresAt asc""")
    List<License> expiringBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Active licences whose grace has run out and that should be marked expired. */
    @Query("""
            select l from License l
            where l.status = 'active'
              and l.expiresAt is not null
              and l.expiresAt < :cutoff""")
    List<License> activeButPastExpiry(@Param("cutoff") Instant cutoff);

    long countByStatus(String status);
}
