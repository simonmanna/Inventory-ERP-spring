package com.inventoryerp.pos.application;

import static com.inventoryerp.pos.application.PosSql.NOW;
import static com.inventoryerp.pos.application.PosSql.num;
import static com.inventoryerp.pos.application.PosSql.org;

import com.inventoryerp.accounting.application.AccountDeterminationService;
import com.inventoryerp.accounting.application.PostingRequest;
import com.inventoryerp.accounting.application.PostingService;
import com.inventoryerp.accounting.application.TenderAccount;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.settings.SettingResolverService;
import com.inventoryerp.kernel.settings.SettingResolverService.SettingContext;
import com.inventoryerp.kernel.web.JsonConfig;
import com.inventoryerp.pos.domain.CustomerTab;
import com.inventoryerp.pos.domain.CustomerTabLedger;
import com.inventoryerp.pos.domain.LoyaltyLedger;
import com.inventoryerp.pos.domain.LoyaltyProgram;
import com.inventoryerp.pos.domain.StoreCredit;
import com.inventoryerp.pos.domain.StoreCreditLedger;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Loyalty points, store credit and manual customer tabs (port of {@code pos-loyalty.service.ts}). */
@Service
public class PosLoyaltyService {

    private final PosDb db;
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final PostingService posting;
    private final AccountDeterminationService determination;
    private final SettingResolverService settings;
    private final TransactionTemplate tx;

    public PosLoyaltyService(PosDb db, AuditService audit, PostingService posting,
            AccountDeterminationService determination, SettingResolverService settings, PlatformTransactionManager txm) {
        this.db = db;
        this.jdbc = db.jdbc();
        this.audit = audit;
        this.posting = posting;
        this.determination = determination;
        this.settings = settings;
        this.tx = new TransactionTemplate(txm);
    }

    public Map<String, Object> ensureProgram() {
        Map<String, Object> existing = getProgram();
        if (existing != null) {
            return existing;
        }
        return db.insert(LoyaltyProgram.class, Obj.of("organizationId", org(), "name", "Cafe Rewards",
                "pointsPerCurrency", PosSql.decOf(0.01), "currencyPerPoint", 100, "minPointsToRedeem", 50,
                "pointsExpireDays", 365, "isActive", true));
    }

    public Map<String, Object> getProgram() {
        return db.first(LoyaltyProgram.class, "\"organizationId\" = ? AND \"isActive\" = true", org());
    }

    private double points(String partnerId) {
        Map<String, Object> latest = db.first(LoyaltyLedger.class, "\"organizationId\" = ? AND \"partnerId\" = ?"
                + " ORDER BY \"createdAt\" DESC", org(), partnerId);
        return latest == null ? 0 : num(latest.get("balanceAfter"));
    }

    public Map<String, Object> getBalance(String partnerId) {
        double pts = points(partnerId);
        Instant now = Instant.now();
        Object expiring = jdbc.sql("SELECT sum(delta) FROM \"LoyaltyLedger\" WHERE \"organizationId\" = ? AND \"partnerId\" = ?"
                        + " AND delta > 0 AND \"expiresAt\" >= ? AND \"expiresAt\" <= ?")
                .params(org(), partnerId, SqlRows.ts(now), SqlRows.ts(now.plusSeconds(30L * 24 * 3600)))
                .query(PosSql.SCALAR).optional().orElse(null);
        Map<String, Object> program = getProgram();
        return Obj.of("points", PosSql.js(pts), "expiringSoon", PosSql.js(expiring),
                "programId", program == null ? null : program.get("id"));
    }

    public Map<String, Object> getEarnedLifetime(String partnerId) {
        Object sum = jdbc.sql("SELECT sum(delta) FROM \"LoyaltyLedger\" WHERE \"organizationId\" = ? AND \"partnerId\" = ?"
                + " AND delta > 0").params(org(), partnerId).query(PosSql.SCALAR).optional().orElse(null);
        return Obj.of("totalEarned", PosSql.js(sum));
    }

    /** Award points for a sale. */
    public Map<String, Object> earnPoints(String partnerId, String documentId, double amount, String reason) {
        Map<String, Object> program = ensureProgram();
        double earned = Math.floor(amount * num(program.get("pointsPerCurrency")));
        if (earned <= 0) {
            return Obj.of("points", 0, "balance", PosSql.js(points(partnerId)));
        }
        double newBalance = points(partnerId) + earned;
        int expireDays = PosSql.intOf(program.get("pointsExpireDays"));
        db.insert(LoyaltyLedger.class, Obj.of("organizationId", org(), "partnerId", partnerId, "delta", PosSql.decOf(earned),
                "balanceAfter", PosSql.decOf(newBalance), "reason", reason == null ? "sale" : reason, "documentId", documentId,
                "expiresAt", expireDays > 0 ? Instant.now().plusSeconds(expireDays * 24L * 3600) : null));
        return Obj.of("points", PosSql.js(earned), "balance", PosSql.js(newBalance));
    }

    public Map<String, Object> redeemPoints(String partnerId, double pointsIn, String documentId) {
        Map<String, Object> program = ensureProgram();
        double pts = Math.floor(pointsIn);
        int min = program.get("minPointsToRedeem") == null ? 50 : PosSql.intOf(program.get("minPointsToRedeem"));
        if (pts < min) {
            throw ApiException.badRequest("Minimum redemption is " + min + " points");
        }
        double current = points(partnerId);
        if (pts > current) {
            throw ApiException.badRequest("Insufficient points — have " + JsFormat.str(current) + ", need " + JsFormat.str(pts));
        }
        double value = pts * num(program.get("currencyPerPoint"));
        double newBalance = current - pts;
        db.insert(LoyaltyLedger.class, Obj.of("organizationId", org(), "partnerId", partnerId, "delta", PosSql.decOf(-pts),
                "balanceAfter", PosSql.decOf(newBalance), "reason", "redemption", "documentId", documentId));
        return Obj.of("redeemed", PosSql.js(pts), "ugxValue", PosSql.js(value), "balance", PosSql.js(newBalance));
    }

    // ─── store credit ───

    public Map<String, Object> getCredit(String partnerId) {
        Map<String, Object> row = db.first(StoreCredit.class, "\"organizationId\" = ? AND \"partnerId\" = ? AND \"isActive\" = true",
                org(), partnerId);
        if (row == null) {
            return Obj.of("balance", 0, "expiresAt", null);
        }
        return Obj.of("balance", PosSql.js(row.get("balance")),
                "expiresAt", row.get("expiresAt") == null ? null : JsonConfig.toJsIso((Instant) row.get("expiresAt")));
    }

    /** Manager-issued, funded store credit (A-001). */
    public Map<String, Object> issueCredit(String partnerId, double amount, String source, String fundingAccountId,
            String notes) {
        String orgId = org();
        if (!Double.isFinite(amount) || amount <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        Object capRaw = settings.resolve("pos.storeCreditIssueLimit", SettingContext.EMPTY);
        double cap = capRaw == null ? 0 : num(capRaw);
        if (!Double.isFinite(cap) || cap < 0) {
            throw ApiException.badRequest("Store credit issue limit is misconfigured");
        }
        if (cap == 0) {
            throw ApiException.forbidden("Manual store-credit issuance is disabled. Credit is created by refunds to store"
                    + " credit, or ask an accountant to raise the pos.storeCreditIssueLimit setting.");
        }
        if (amount > cap) {
            throw ApiException.badRequest("Store credit issue exceeds the configured limit of " + JsFormat.locale(cap));
        }
        if (fundingAccountId == null || fundingAccountId.isEmpty()) {
            throw ApiException.badRequest("Choose the account funding this store credit");
        }
        return tx.execute(st -> {
            jdbc.sql("SELECT id FROM \"StoreCredit\" WHERE \"organizationId\" = ? AND \"partnerId\" = ? FOR UPDATE")
                    .params(orgId, partnerId).query().listOfRows();
            Map<String, Object> row = db.first(StoreCredit.class, "\"organizationId\" = ? AND \"partnerId\" = ?"
                    + " AND \"isActive\" = true", orgId, partnerId);
            if (row == null) {
                row = db.insert(StoreCredit.class, Obj.of("organizationId", orgId, "partnerId", partnerId, "source", source,
                        "notes", notes));
            }
            String liability = TenderAccount.resolve(jdbc, determination, orgId, "store_credit", null, null).accountId();
            Map<String, Object> funding = SqlRows.one(jdbc.sql("SELECT a.id, a.name, c.key AS \"categoryKey\" FROM \"Account\" a"
                    + " LEFT JOIN \"AccountCategory\" c ON c.id = a.\"categoryId\" WHERE a.id = ? AND a.\"organizationId\" = ?"
                    + " AND a.\"isActive\" = true AND a.\"deletedAt\" IS NULL").params(fundingAccountId, orgId));
            if (funding == null) {
                throw ApiException.badRequest("Funding account is inactive or unavailable");
            }
            Object cat = funding.get("categoryKey");
            if (!List.of("cash", "petty_cash", "bank", "operating_expense", "other_expense", "current_asset")
                    .contains(cat == null ? "" : cat)) {
                throw ApiException.badRequest("Store credit must be funded from a cash, bank or expense account");
            }
            if ("cash".equals(cat) || "petty_cash".equals(cat)) {
                long drawer = jdbc.sql("SELECT count(*) FROM \"CashRegister\" WHERE \"organizationId\" = ?"
                        + " AND \"defaultAccountId\" = ? AND \"isActive\" = true AND \"deletedAt\" IS NULL")
                        .params(orgId, funding.get("id")).query(Long.class).single();
                if (drawer > 0) {
                    throw ApiException.badRequest(
                            "Store credit cannot be funded from a cash drawer account. Use a bank or expense account.");
                }
            }
            String amt = JsFormat.str(amount);
            posting.post(new PostingRequest("GEN", Instant.now(), "Store credit issued · " + source
                    + (notes != null && !notes.isEmpty() ? " · " + notes : ""), null, null, "store_credit_issue",
                    (String) row.get("id"), "primary", null, null, null, null, List.of(
                            new PostingRequest.Line((String) funding.get("id"), amt, null, null,
                                    "Store credit funding · " + funding.get("name"), null, null, null),
                            new PostingRequest.Line(liability, null, amt, null, "Store credit issued", null, null, null))));
            double newBalance = num(row.get("balance")) + amount;
            Map<String, Object> data = Obj.of("balance", PosSql.decOf(newBalance));
            if (notes != null && !notes.isEmpty()) {
                data.put("notes", notes);
            }
            db.update(StoreCredit.class, data, "id = ?", row.get("id"));
            db.insert(StoreCreditLedger.class, Obj.of("organizationId", orgId, "storeCreditId", row.get("id"),
                    "delta", PosSql.decOf(amount), "balanceAfter", PosSql.decOf(newBalance), "reason", source));
            audit.recordInTx(AuditInput.of("StoreCredit", (String) row.get("id"), "issue", null, Obj.of("partnerId", partnerId,
                    "amount", PosSql.js(amount), "source", source, "fundingAccountId", funding.get("id"), "notes", notes,
                    "balanceAfter", PosSql.js(newBalance))));
            return Obj.of("balance", PosSql.js(newBalance));
        });
    }

    public Map<String, Object> redeemCredit(String partnerId, double amount, String documentId) {
        String orgId = org();
        if (amount <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        return tx.execute(st -> {
            Map<String, Object> row = db.first(StoreCredit.class, "\"organizationId\" = ? AND \"partnerId\" = ?"
                    + " AND \"isActive\" = true", orgId, partnerId);
            if (row == null || num(row.get("balance")) < amount) {
                throw ApiException.badRequest("Insufficient store credit");
            }
            double newBalance = num(row.get("balance")) - amount;
            db.update(StoreCredit.class, Obj.of("balance", PosSql.decOf(newBalance), "isActive", newBalance > 0), "id = ?",
                    row.get("id"));
            db.insert(StoreCreditLedger.class, Obj.of("organizationId", orgId, "storeCreditId", row.get("id"),
                    "delta", PosSql.decOf(-amount), "balanceAfter", PosSql.decOf(newBalance), "reason", "sale",
                    "documentId", documentId));
            return Obj.of("balance", PosSql.js(newBalance));
        });
    }

    // ─── manual customer tabs ───

    public Map<String, Object> getTab(String partnerId) {
        return db.first(CustomerTab.class, "\"organizationId\" = ? AND \"partnerId\" = ? AND \"isOpen\" = true", org(),
                partnerId);
    }

    public Map<String, Object> openTab(String partnerId, Object creditLimit, String cashSessionId) {
        Map<String, Object> existing = getTab(partnerId);
        if (existing != null) {
            return existing;
        }
        return db.insert(CustomerTab.class, Obj.of("organizationId", org(), "partnerId", partnerId,
                "creditLimit", creditLimit == null ? 0 : PosSql.dec(creditLimit), "cashSessionId", cashSessionId,
                "openedAt", PosDb.raw(NOW)));
    }

    public Map<String, Object> chargeTab(String partnerId, String documentId, double amount, String reason) {
        String orgId = org();
        if (amount <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        return tx.execute(st -> {
            Map<String, Object> tab = getTab(partnerId);
            if (tab == null) {
                tab = db.insert(CustomerTab.class, Obj.of("organizationId", orgId, "partnerId", partnerId,
                        "openedAt", PosDb.raw(NOW)));
            }
            double newBalance = num(tab.get("balance")) + amount;
            if (num(tab.get("creditLimit")) > 0 && newBalance > num(tab.get("creditLimit"))) {
                throw ApiException.badRequest("Tab credit limit exceeded: owe " + JsFormat.toFixed(newBalance, 0) + ", limit "
                        + JsFormat.toFixed(num(tab.get("creditLimit")), 0));
            }
            db.update(CustomerTab.class, Obj.of("balance", PosSql.decOf(newBalance)), "id = ?", tab.get("id"));
            db.insert(CustomerTabLedger.class, Obj.of("organizationId", orgId, "tabId", tab.get("id"),
                    "delta", PosSql.decOf(amount), "balanceAfter", PosSql.decOf(newBalance),
                    "reason", reason == null ? "sale" : reason, "documentId", documentId));
            return Obj.of("balance", PosSql.js(newBalance));
        });
    }

    public Map<String, Object> settleTab(String partnerId, double amount, String paymentId, String reason) {
        String orgId = org();
        if (amount <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        return tx.execute(st -> {
            Map<String, Object> tab = getTab(partnerId);
            if (tab == null) {
                throw ApiException.notFound("No open tab for this customer");
            }
            double newBalance = num(tab.get("balance")) - amount;
            db.update(CustomerTab.class, Obj.of("balance", PosSql.decOf(newBalance)), "id = ?", tab.get("id"));
            db.insert(CustomerTabLedger.class, Obj.of("organizationId", orgId, "tabId", tab.get("id"),
                    "delta", PosSql.decOf(-amount), "balanceAfter", PosSql.decOf(newBalance),
                    "reason", reason == null ? "payment" : reason, "paymentId", paymentId));
            if (newBalance <= 0) {
                db.update(CustomerTab.class, Obj.of("isOpen", false, "closedAt", PosDb.raw(NOW)), "id = ?", tab.get("id"));
            }
            return Obj.of("balance", PosSql.js(newBalance));
        });
    }
}
