package com.inventoryerp.kernel.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;

/** Registers the tenant-aware transaction manager (Boot's default backs off). */
@Configuration(proxyBeanMethods = false)
public class TenancyConfig {

    @Bean
    @Primary
    JpaTransactionManager transactionManager(EntityManagerFactory emf) {
        return new TenantAwareTransactionManager(emf);
    }
}
