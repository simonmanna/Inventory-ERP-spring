package com.inventoryerp.invoicing.application;

import com.inventoryerp.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mobile-money gateway beans (ADR-S011 S6): {@code mock} (always available; succeeds unless the phone ends in 0) and
 * the MTN MoMo / Airtel Money adapters, which stay unconfigured until their API credentials are provided
 * ({@code app.payments.mtn.*}, {@code app.payments.airtel.*}). The HTTP integrations are the next increment.
 */
@Configuration
public class MobileMoneyGateways {

    @Bean
    PaymentGateway mockMobileMoney() {
        Map<String, String> outcomes = new ConcurrentHashMap<>();
        return new PaymentGateway() {
            @Override
            public String code() {
                return "mock";
            }

            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public String initiate(String requestId, String phone, BigDecimal amount, String currencyCode,
                    String narrative) {
                String ref = "MOCK-" + requestId;
                outcomes.put(ref, phone.endsWith("0") ? "failed" : "succeeded");
                return ref;
            }

            @Override
            public String status(String providerRef) {
                return outcomes.getOrDefault(providerRef, "pending");
            }
        };
    }

    @Bean
    PaymentGateway mtnMomo(@Value("${app.payments.mtn.subscription-key:}") String key) {
        return unconfigured("mtn_momo", key);
    }

    @Bean
    PaymentGateway airtelMoney(@Value("${app.payments.airtel.client-id:}") String key) {
        return unconfigured("airtel_money", key);
    }

    private static PaymentGateway unconfigured(String code, String key) {
        return new PaymentGateway() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public boolean configured() {
                return key != null && !key.isBlank();
            }

            @Override
            public String initiate(String requestId, String phone, BigDecimal amount, String currencyCode,
                    String narrative) {
                throw ApiException.badRequest("The " + code + " gateway is not connected yet");
            }

            @Override
            public String status(String providerRef) {
                return "pending";
            }
        };
    }
}
