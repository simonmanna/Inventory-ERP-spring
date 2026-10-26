package com.inventoryerp.license.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<AdminUser, String> {

    Optional<AdminUser> findByEmailIgnoreCase(String email);

    List<AdminUser> findAllByOrderByEmailAsc();
}
