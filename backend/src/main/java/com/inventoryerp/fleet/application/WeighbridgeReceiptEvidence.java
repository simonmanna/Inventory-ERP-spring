package com.inventoryerp.fleet.application;

import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.procurement.application.ReceiptEvidenceProvider;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Weighbridge tickets as receipt evidence for purchasing (ADR-PROCUREMENT-001 wave 6). */
@Component
public class WeighbridgeReceiptEvidence implements ReceiptEvidenceProvider {

    private final JdbcClient jdbc;

    public WeighbridgeReceiptEvidence(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> weighedLines(String goodsReceiptId) {
        return new HashSet<>(jdbc.sql("SELECT \"goodsReceiptLineId\" FROM \"WeighbridgeTicket\" WHERE \"organizationId\""
                        + " = ? AND \"goodsReceiptId\" = ? AND status = 'completed' AND \"goodsReceiptLineId\" IS NOT NULL")
                .params(TenantContext.organizationId(), goodsReceiptId).query(String.class).list());
    }
}
