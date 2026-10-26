package com.inventoryerp.license.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, String> {

    Optional<Plan> findByCode(String code);

    List<Plan> findAllByOrderBySortOrderAscNameAsc();

    List<Plan> findByIsActiveTrueOrderBySortOrderAscNameAsc();
}
