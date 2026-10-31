package com.inventoryerp.invoicing.application;

import com.inventoryerp.invoicing.application.CustomerDepositService.DepositInput;
import com.inventoryerp.invoicing.application.PaymentService.AllocationInput;
import com.inventoryerp.invoicing.application.PaymentService.PaymentInput;
import com.inventoryerp.kernel.audit.AuditService;
import com.inventoryerp.kernel.audit.AuditService.AuditInput;
import com.inventoryerp.kernel.common.Obj;
import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.tenancy.TenantContext;
import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mobile-money collections (ADR-S011 S6): a request asks the customer's phone to pay; when the provider reports
 * success the money is booked once — as a receipt allocated to the invoice, a customer deposit for a sales order, or
 * an on-account receipt (tender {@code mobile_money}). Completion is idempotent per request.
 */
@Service
public class SalesMobileMoneyService {

    private final JdbcClient jdbc;
    private final List<PaymentGateway> gateways;
    private final PaymentService payments;
    private final CustomerDepositService deposits;
    private final AuditService audit;

    public SalesMobileMoneyService(JdbcClient jdbc, List<PaymentGateway> gateways, PaymentService payments,
            CustomerDepositService deposits, AuditService audit) {
        this.jdbc = jdbc;
        this.gateways = gateways;
        this.payments = payments;
        this.deposits = deposits;
        this.audit = audit;
    }

    private static String org() {
        return TenantContext.organizationId();
    }

    /** Providers and whether they are usable. */
    public List<Map<String, Object>> providers() {
        return gateways.stream().map(g -> Obj.of("code", g.code(), "configured", g.configured())).toList();
    }

    /** Start a collection: {@code provider, partnerId, phone, amount, purpose (invoice|deposit|on_account), documentId | salesOrderId}. */
    @Transactional
    public Map<String, Object> initiate(Map<String, Object> in) {
        PaymentGateway g = gateway(str(in.get("provider")));
        String purpose = in.get("purpose") == null ? "on_account" : str(in.get("purpose"));
        if (!Set.of("invoice", "deposit", "on_account").contains(purpose)) {
            throw ApiException.badRequest("purpose must be one of: deposit, invoice, on_account");
        }
        String phone = str(in.get("phone"));
        if (phone == null || !phone.matches("\\+?[0-9]{9,15}")) {
            throw ApiException.badRequest("A valid phone number is required");
        }
        BigDecimal amount = in.get("amount") == null ? BigDecimal.ZERO : new BigDecimal(in.get("amount").toString());
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("Amount must be positive");
        }
        String partnerId = str(in.get("partnerId"));
        if (partnerId == null || jdbc.sql("SELECT count(*) FROM \"Partner\" WHERE id = ? AND \"organizationId\" = ?")
                .params(partnerId, org()).query(Long.class).single() == 0) {
            throw ApiException.badRequest("Partner not found");
        }
        if ("invoice".equals(purpose) && in.get("documentId") == null) {
            throw ApiException.badRequest("documentId is required to collect an invoice");
        }
        String currency = jdbc.sql("SELECT \"currencyCode\" FROM \"Organization\" WHERE id = ?").param(org())
                .query(String.class).optional().orElse("UGX");
        String id = UUID.randomUUID().toString();
        String ref = g.initiate(id, phone, amount, currency, "Payment " + id.substring(0, 8));
        jdbc.sql("INSERT INTO \"MobileMoneyRequest\" (id, \"organizationId\", provider, \"partnerId\", phone, amount,"
                + " \"currencyCode\", purpose, \"documentId\", \"salesOrderId\", \"providerRef\", \"createdById\") VALUES"
                + " (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").params(id, org(), g.code(), partnerId, phone, amount, currency,
                        purpose, str(in.get("documentId")), str(in.get("salesOrderId")), ref, TenantContext.userId())
                .update();
        audit.record(AuditInput.of("MobileMoneyRequest", id, "create", null, Obj.of("provider", g.code(), "amount",
                amount, "purpose", purpose)));
        return get(id);
    }

    /** Poll the provider and book the money once on success. */
    @Transactional
    public Map<String, Object> refresh(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"MobileMoneyRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ? FOR UPDATE").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Mobile money request not found");
        }
        if (!"pending".equals(r.get("status"))) {
            return get(id);
        }
        String outcome = gateway((String) r.get("provider")).status((String) r.get("providerRef"));
        if ("failed".equals(outcome)) {
            jdbc.sql("UPDATE \"MobileMoneyRequest\" SET status = 'failed', error = 'Declined by the payer',"
                    + " \"completedAt\" = now() WHERE id = ?").param(id).update();
        } else if ("succeeded".equals(outcome)) {
            book(r);
        }
        return get(id);
    }

    private void book(Map<String, Object> r) {
        String id = (String) r.get("id");
        BigDecimal amount = SqlRows.dec(r, "amount");
        String partnerId = (String) r.get("partnerId");
        String paymentId = null;
        String depositId = null;
        String ref = r.get("provider") + " " + r.get("providerRef");
        switch ((String) r.get("purpose")) {
            case "deposit" -> depositId = (String) deposits.receive(new DepositInput(partnerId, (String) r.get(
                    "salesOrderId"), amount, "mobile_money", null, LocalDate.now().toString(), ref, null)).get("id");
            case "invoice" -> paymentId = (String) payments.createReceipt(new PaymentInput(partnerId, LocalDate.now()
                    .toString(), amount, "mobile_money", null, ref, null, List.of(new AllocationInput((String) r.get(
                            "documentId"), null, amount)), null, false, null, null), true, true).get("id");
            default -> paymentId = (String) payments.createReceipt(new PaymentInput(partnerId, LocalDate.now().toString(),
                    amount, "mobile_money", null, ref, null, List.of(), null, false, null, null), true, true).get("id");
        }
        jdbc.sql("UPDATE \"MobileMoneyRequest\" SET status = 'succeeded', \"paymentId\" = ?, \"depositId\" = ?,"
                + " \"completedAt\" = now() WHERE id = ?").params(paymentId, depositId, id).update();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(String id) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT * FROM \"MobileMoneyRequest\" WHERE id = ? AND"
                + " \"organizationId\" = ?").params(id, org()));
        if (r == null) {
            throw ApiException.notFound("Mobile money request not found");
        }
        return r;
    }

    private PaymentGateway gateway(String code) {
        for (PaymentGateway g : gateways) {
            if (g.code().equals(code)) {
                if (!g.configured()) {
                    throw ApiException.badRequest("The " + code + " gateway is not configured");
                }
                return g;
            }
        }
        throw ApiException.badRequest("Unknown payment provider: " + code);
    }

    private static String str(Object v) {
        return v == null || v.toString().isBlank() ? null : v.toString();
    }
}
