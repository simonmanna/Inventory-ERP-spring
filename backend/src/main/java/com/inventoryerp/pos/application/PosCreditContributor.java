package com.inventoryerp.pos.application;

import com.inventoryerp.credit.application.CreditExposureContributor;
import com.inventoryerp.kernel.tenancy.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** POS share of credit exposure: unsettled residual of credit sales (POS invoices paid "on credit"). */
@Component
public class PosCreditContributor implements CreditExposureContributor {

    private final JdbcClient jdbc;

    public PosCreditContributor(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Contribution contribute(String partnerId) {
        BigDecimal open = jdbc.sql("SELECT COALESCE(sum(\"amountResidual\"), 0) FROM \"Invoice\" WHERE \"organizationId\""
                + " = ? AND \"partnerId\" = ? AND \"paymentMode\" = 'credit' AND \"settlementStatus\"::text IN"
                + " ('unsettled', 'partially_settled')").params(TenantContext.organizationId(), partnerId)
                .query(BigDecimal.class).single();
        return new Contribution(BigDecimal.ZERO, BigDecimal.ZERO, open.setScale(2, RoundingMode.HALF_UP),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }
}
