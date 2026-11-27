package com.inventoryerp.kernel.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * IPR S-6 (ADR-S093): binds every pooled connection to the caller's tenant when it is taken from the pool, so the
 * row-level security policy (V214) is a backstop for every statement run for a tenant — also the many reads that run
 * outside a transaction ({@link TenantAwareTransactionManager} only covers transactions).
 *
 * <p>On each checkout it runs {@code set_config('app.org_id', <tenant or ''>, false)}: the tenant's id when
 * {@link TenantContext} has one, otherwise the empty string (the documented system mode of jobs, login, bootstrap,
 * {@link InstallWide} and {@link TenantContext#unscoped}). Setting it on every checkout, never only on tenant ones, means
 * a value can never leak from one borrower of a connection to the next. A transaction still applies its own
 * {@code SET LOCAL} on top, as before.
 */
public class TenantBindingDataSource extends DelegatingDataSource implements AutoCloseable {

    static final String BIND_SQL = "SELECT set_config('app.org_id', ?, false)";

    public TenantBindingDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(obtainTargetDataSource().getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(obtainTargetDataSource().getConnection(username, password));
    }

    /** Set {@code app.org_id} for the current tenant (or none) on this connection's session. */
    static Connection bind(Connection connection) throws SQLException {
        String org = TenantContext.optionalOrganizationId();
        try (PreparedStatement ps = connection.prepareStatement(BIND_SQL)) {
            ps.setString(1, org == null ? "" : org);
            ps.execute();
            if (!connection.getAutoCommit()) {
                // A pool handing out connections with auto-commit off: commit so a later rollback cannot undo the
                // binding (a GUC set in a rolled-back transaction reverts to the previous borrower's value).
                connection.commit();
            }
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
        return connection;
    }

    /** Closes the pool on context shutdown (Spring calls {@code close()} on the exposed bean, which is this wrapper). */
    @Override
    public void close() throws Exception {
        if (obtainTargetDataSource() instanceof AutoCloseable pool) {
            pool.close();
        }
    }

    /**
     * Unwrapping to {@link DataSource} answers the pool itself, so Spring Boot can derive the Flyway data source and
     * read the pool's metrics through this wrapper.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface == TenantBindingDataSource.class) {
            return (T) this;
        }
        DataSource target = obtainTargetDataSource();
        if (iface.isInstance(target)) {
            return (T) target;
        }
        return target.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        DataSource target = obtainTargetDataSource();
        return iface == TenantBindingDataSource.class || iface.isInstance(target) || target.isWrapperFor(iface);
    }
}
