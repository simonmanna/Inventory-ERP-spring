package com.inventoryerp.pos.application;

import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** House-account (credit) standing of a customer (port of {@code billing/credit-status.ts}). */
public record CreditStatus(double creditLimit, double outstanding, Double available, boolean creditHold) {

    public Map<String, Object> toMap() {
        return Obj.of("creditLimit", PosSql.js(creditLimit), "outstanding", PosSql.js(outstanding),
                "available", available == null ? null : PosSql.js(available), "creditHold", creditHold);
    }

    /** Resolve; {@code lock} takes FOR UPDATE on the Partner and CustomerTab rows (D3, settle path only). */
    public static CreditStatus resolve(JdbcClient db, String orgId, String partnerId, boolean lock) {
        if (lock) {
            db.sql("SELECT id FROM \"Partner\" WHERE \"organizationId\" = ? AND id = ? FOR UPDATE")
                    .params(orgId, partnerId).query().listOfRows();
            db.sql("SELECT id FROM \"CustomerTab\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? FOR UPDATE")
                    .params(orgId, partnerId).query().listOfRows();
        }
        Map<String, Object> partner = SqlRows.one(db.sql("SELECT \"creditLimit\", \"creditHold\" FROM \"Partner\""
                + " WHERE id = ? AND \"organizationId\" = ? AND \"deletedAt\" IS NULL LIMIT 1").params(partnerId, orgId));
        Map<String, Object> tab = SqlRows.one(db.sql("SELECT \"creditLimit\" FROM \"CustomerTab\""
                + " WHERE \"organizationId\" = ? AND \"partnerId\" = ? LIMIT 1").params(orgId, partnerId));
        Object open = db.sql("SELECT sum(\"amountResidual\") FROM \"Invoice\" WHERE \"organizationId\" = ?"
                        + " AND \"partnerId\" = ? AND \"paymentMode\" = 'credit'"
                        + " AND \"settlementStatus\"::text IN ('unsettled', 'partially_settled')")
                .params(orgId, partnerId).query(PosSql.SCALAR).optional().orElse(null);
        double partnerLimit = PosSql.num(partner == null ? null : partner.get("creditLimit"));
        double creditLimit = partnerLimit > 0 ? partnerLimit : PosSql.num(tab == null ? null : tab.get("creditLimit"));
        double outstanding = PosSql.round2(PosSql.num(open));
        return new CreditStatus(creditLimit, outstanding,
                creditLimit > 0 ? PosSql.round2(Math.max(0, creditLimit - outstanding)) : null,
                partner != null && Boolean.TRUE.equals(partner.get("creditHold")));
    }
}
