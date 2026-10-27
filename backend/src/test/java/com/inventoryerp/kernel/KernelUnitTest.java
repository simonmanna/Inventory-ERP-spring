package com.inventoryerp.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryerp.accounting.domain.Currency;
import com.inventoryerp.core.domain.Partner;
import com.inventoryerp.kernel.auth.EncryptionService;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.domain.Setting;
import com.inventoryerp.kernel.domain.User;
import com.inventoryerp.kernel.idempotency.BusinessOutcome;
import com.inventoryerp.kernel.security.ReadOnlyModeFilter;
import com.inventoryerp.kernel.security.SecretValidatorAccess;
import com.inventoryerp.kernel.sequence.SequenceService;
import com.inventoryerp.kernel.settings.SettingRegistry;
import com.inventoryerp.shared.persistence.TenancyFilters;
import com.inventoryerp.shared.web.ApiException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure kernel units: tenancy filter wiring ({@code tenancy.extension.spec.ts}), sequence names
 * ({@code sequence.service.spec.ts} pure part), Nest-compatible AES-GCM, approval payload hash
 * ({@code business-outcome.spec.ts}), settings coercion, read-only mode, prod secret validation.
 */
class KernelUnitTest {

    private static List<String> filters(Class<?> type) {
        return Arrays.stream(type.getAnnotationsByType(Filter.class)).map(Filter::name).toList();
    }

    @Test
    @DisplayName("scopes org-scoped models by tenant and soft delete; leaves global models alone")
    void tenancyFilters() {
        assertThat(filters(Partner.class)).containsExactly(TenancyFilters.TENANT, TenancyFilters.SOFT_DELETE);
        assertThat(filters(User.class)).contains(TenancyFilters.TENANT, TenancyFilters.SOFT_DELETE);
        assertThat(filters(Currency.class)).isEmpty();
        // Setting has a nullable organizationId and is scoped explicitly in SettingsService.
        assertThat(filters(Setting.class)).isEmpty();
    }

    @Test
    @DisplayName("parseInt mirrors ValidationPipe Number() + DefaultValuePipe + ParseIntPipe (probed on Nest)")
    void queryParseInt() {
        assertThat(QueryParams.parseInt(null, 25)).isEqualTo(25);
        assertThat(QueryParams.parseInt("x", 1)).isEqualTo(1);
        assertThat(QueryParams.parseInt("", 1)).isZero();
        assertThat(QueryParams.parseInt(" 3 ", 1)).isEqualTo(3);
        assertThat(QueryParams.parseInt("1e1", 1)).isEqualTo(10);
        assertThat(QueryParams.parseInt("0x2", 1)).isEqualTo(2);
        assertThat(QueryParams.parseInt("-1", 1)).isEqualTo(-1);
        for (String bad : List.of("1.5", "Infinity", "1e21")) {
            assertThatThrownBy(() -> QueryParams.parseInt(bad, 1)).isInstanceOf(ApiException.class)
                    .hasMessage("Validation failed (numeric string is expected)");
        }
    }

    @Test
    @DisplayName("seqName builds a safe identifier from an org UUID and strips unsafe key characters")
    void seqName() {
        assertThat(SequenceService.seqName("12345678-aaaa-bbbb-cccc-1234567890ab", "invoice:2026"))
                .isEqualTo("seq_12345678_invoice_2026");
        assertThat(SequenceService.seqName("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "a/b:c d")).isEqualTo("seq_aaaaaaaa_a_b_c_d");
    }

    @Test
    @DisplayName("decrypts a ciphertext produced by the Nest EncryptionService (node:crypto) and round-trips")
    void encryptionCompat() {
        var svc = new EncryptionService("test-access-secret-at-least-32-chars-long", "");
        var fromNode = new EncryptionService.Encrypted("djHVaROwHR8nfIbODpAOpS4e", "HbpMtJQnO2g1lkMG", "KU2rdwfcSQFd0qIO7clVVA==");
        assertThat(svc.decrypt(fromNode)).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(svc.decrypt(svc.encrypt("secret-value"))).isEqualTo("secret-value");
        assertThat(svc.encrypt("")).isNull();
        var tampered = new EncryptionService.Encrypted("djHVaROwHR8nfIbODpAOpS4f", "HbpMtJQnO2g1lkMG", "KU2rdwfcSQFd0qIO7clVVA==");
        assertThatThrownBy(() -> svc.decrypt(tampered)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("binds approval to every economic field while excluding the proof itself")
    void approvalPayloadHash() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(Map.of("quantity", 2, "unitPrice", 100)));
        body.put("tenders", List.of(Map.of("method", "card", "accountId", "bank1", "amount", 200)));
        body.put("overridePin", "1234");
        Map<String, Object> withProof = new LinkedHashMap<>(body);
        withProof.remove("overridePin");
        withProof.put("approvalToken", "proof");
        assertThat(BusinessOutcome.approvalPayloadHash(body)).isEqualTo(BusinessOutcome.approvalPayloadHash(withProof));
        Map<String, Object> otherAccount = new LinkedHashMap<>(body);
        otherAccount.put("tenders", List.of(Map.of("method", "card", "accountId", "bank2", "amount", 200)));
        assertThat(BusinessOutcome.approvalPayloadHash(body)).isNotEqualTo(BusinessOutcome.approvalPayloadHash(otherAccount));
    }

    @Test
    @DisplayName("setting registry coercion and scope rules")
    void settingRegistry() {
        var atp = SettingRegistry.get("inventory.atpMode");
        assertThat(SettingRegistry.coerce(atp, "strict")).isEqualTo("strict");
        assertThatThrownBy(() -> SettingRegistry.coerce(atp, "x"))
                .hasMessage("Setting 'inventory.atpMode' must be one of: post_commit, pre_invoice, strict.");
        var timing = SettingRegistry.get("inventory.stockPostingTiming");
        assertThatThrownBy(() -> SettingRegistry.coerce(timing, "at_fulfillment_start"))
                .hasMessageStartingWith("Setting 'inventory.stockPostingTiming' value 'at_fulfillment_start' is not available yet:");
        assertThat(SettingRegistry.coerce(SettingRegistry.get("inventory.allowNegativeStock"), "false")).isEqualTo(false);
        assertThat(SettingRegistry.coerce(SettingRegistry.get("accounting.fiscalYearStartMonth"), "4")).isEqualTo(4L);
        assertThatThrownBy(() -> SettingRegistry.assertScopeAllowed(SettingRegistry.get("inventory.reservationMode"), "product"))
                .hasMessage("Setting 'inventory.reservationMode' cannot be set at product level.");
    }

    @Test
    @DisplayName("read-only mode refuses writes except auth, with the Nest message")
    void readOnly() throws Exception {
        var filter = new ReadOnlyModeFilter(true, JsonMapper.builder().build());
        var res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/roles"), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("This server is a read-only reporting replica.");
        var ok = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/auth/login"), ok, chain);
        assertThat(chain.getRequest()).isNotNull();
        var get = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/roles"), new MockHttpServletResponse(), get);
        assertThat(get.getRequest()).isNotNull();
    }

    @Test
    @DisplayName("prod secret validation rejects placeholders, short and shared secrets")
    void secrets() {
        assertThat(SecretValidatorAccess.validate("dev-only-access-secret-change-me-32bytes!!", "short", null))
                .anyMatch(e -> e.contains("JWT_ACCESS_SECRET")).anyMatch(e -> e.contains("at least 32"));
        String s = "a-very-long-and-random-production-secret-0001";
        assertThat(SecretValidatorAccess.validate(s, s, null)).contains("JWT_ACCESS_SECRET and JWT_REFRESH_SECRET must be different");
        assertThat(SecretValidatorAccess.validate(s, s + "x", s + "y")).isEmpty();
    }
}
