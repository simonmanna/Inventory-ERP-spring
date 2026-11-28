package com.inventoryerp.kernel.tenancy;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.JpaTransactionManager;

/** Registers the tenant-aware transaction manager (Boot's default backs off) and the tenant connection binding. */
@Configuration(proxyBeanMethods = false)
public class TenancyConfig {

    /** IPR S-6 switch; on by default. Off only for an emergency (RLS then filters transactions only, as before). */
    static final String BIND_CONNECTIONS = "app.tenancy.bind-connections";

    @Bean
    @Primary
    JpaTransactionManager transactionManager(EntityManagerFactory emf) {
        return new TenantAwareTransactionManager(emf);
    }

    /**
     * IPR S-6 (ADR-S093): wraps the application's {@code dataSource} in {@link TenantBindingDataSource}, so JPA, the
     * transaction manager and every {@code JdbcClient} share one tenant-bound pool.
     */
    @Bean
    static BeanPostProcessor tenantBindingDataSourcePostProcessor(Environment env) {
        boolean enabled = env.getProperty(BIND_CONNECTIONS, Boolean.class, true);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (enabled && "dataSource".equals(beanName) && bean instanceof DataSource ds
                        && !(bean instanceof TenantBindingDataSource)) {
                    return new TenantBindingDataSource(ds);
                }
                return bean;
            }
        };
    }
}
