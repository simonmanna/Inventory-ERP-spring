package com.inventoryerp.kernel.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceUnwrapper;

/**
 * IPR review 1 (finding 6): unwrapping the tenant-binding data source to {@link DataSource} must not hand out the raw
 * pool, whose connections keep the previous borrower's {@code app.org_id}. Pool-specific types (metrics) still reach
 * the pool, and Spring Boot's root unwrap (the derived Flyway data source) still finds it.
 */
class TenantBindingDataSourceTest {

    @Test
    @DisplayName("unwrap(DataSource) answers the binding wrapper; pool types and the root unwrap reach the pool")
    void unwrapNeverLeaksThePoolAsADataSource() throws Exception {
        try (HikariDataSource pool = new HikariDataSource()) {
            TenantBindingDataSource wrapper = new TenantBindingDataSource(pool);
            assertThat(wrapper.unwrap(DataSource.class)).isSameAs(wrapper);
            assertThat(wrapper.isWrapperFor(DataSource.class)).isTrue();
            assertThat(wrapper.unwrap(TenantBindingDataSource.class)).isSameAs(wrapper);
            assertThat(wrapper.unwrap(HikariDataSource.class)).isSameAs(pool);
            assertThat(wrapper.unwrap(HikariConfigMXBean.class)).isSameAs(pool);
            assertThat(wrapper.isWrapperFor(HikariDataSource.class)).isTrue();
            assertThat(DataSourceUnwrapper.unwrapRoot(wrapper)).isSameAs(pool);
        }
    }
}
