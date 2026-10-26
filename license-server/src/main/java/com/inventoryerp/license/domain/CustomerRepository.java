package com.inventoryerp.license.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, String> {

    @Query("""
            select c from Customer c
            where :q is null
               or lower(c.name) like lower(concat('%', cast(:q as string), '%'))
               or lower(coalesce(c.email, '')) like lower(concat('%', cast(:q as string), '%'))
            order by c.name asc""")
    Page<Customer> search(@Param("q") String query, Pageable pageable);

    boolean existsByEmailIgnoreCase(String email);
}
