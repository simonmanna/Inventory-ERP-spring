package com.inventoryerp.accounting.web;

import com.inventoryerp.accounting.application.CashFlowService;
import com.inventoryerp.accounting.application.CashMovementReportService;
import com.inventoryerp.accounting.application.MoneyActivityService;
import com.inventoryerp.accounting.application.MoneyOverviewService;
import com.inventoryerp.kernel.common.JsNumbers;
import com.inventoryerp.kernel.crud.QueryParams;
import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /accounts/cash-flow} (port of {@code cash-flow.controller.ts}): money accounts and treasury operations. */
@RestController
@RequestMapping("/accounts/cash-flow")
public class CashFlowController {

    /** {@code CreateCashAccountDto}. */
    public record CreateCashAccountRequest(
            @NotBlank(message = "should not be empty") String code,
            @NotBlank(message = "should not be empty") String name,
            @NotNull(message = "accountType must be one of the following values: cash, bank, mobile_money, petty_cash")
            @OneOf({"cash", "bank", "mobile_money", "petty_cash"}) String accountType,
            String currencyId,
            String bankName,
            String accountNumber,
            Boolean isDefault) {}

    /** {@code UpdateCashAccountDto}. */
    public record UpdateCashAccountRequest(String name, String currencyId, String bankName, String accountNumber,
            Boolean isDefault) {}

    /** {@code CashFlowDto}. */
    public record CashFlowRequest(
            @NotBlank(message = "should not be empty") String accountId,
            @NotBlank(message = "should not be empty") String counterpartAccountId,
            @NotBlank(message = "should not be empty") String operationType,
            @NotNull(message = "must be a number conforming to the specified constraints")
            @DecimalMin(value = "0.01", message = "must not be less than 0.01") BigDecimal amount,
            @NotBlank(message = "should not be empty") String description,
            @Pattern(regexp = AccountingDtos.ISO_DATE, message = "must be a valid ISO 8601 date string") String date) {}

    private final CashFlowService cashFlow;
    private final CashMovementReportService movementReport;
    private final MoneyActivityService moneyActivity;
    private final MoneyOverviewService moneyOverview;

    public CashFlowController(CashFlowService cashFlow, CashMovementReportService movementReport,
            MoneyActivityService moneyActivity, MoneyOverviewService moneyOverview) {
        this.cashFlow = cashFlow;
        this.movementReport = movementReport;
        this.moneyActivity = moneyActivity;
        this.moneyOverview = moneyOverview;
    }

    /** {@code ?a=x,y} and {@code ?a=x&a=y} both arrive as a list. */
    private static List<String> toList(MultiValueMap<String, String> q, String key) {
        List<String> raw = q.get(key);
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String v : raw.size() == 1 ? List.of(raw.getFirst().split(",")) : raw) {
            String t = v.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static void whitelist(MultiValueMap<String, String> q, List<String> errors, String... allowed) {
        List<String> ok = List.of(allowed);
        for (String k : q.keySet()) {
            if (!ok.contains(k)) {
                errors.add("property " + k + " should not exist");
            }
        }
    }

    private static void isoDate(MultiValueMap<String, String> q, String key, List<String> errors) {
        String v = q.getFirst(key);
        if (v != null && !v.matches(AccountingDtos.ISO_DATE)) {
            errors.add(key + " must be a valid ISO 8601 date string");
        }
    }

    private static void oneOf(MultiValueMap<String, String> q, String key, List<String> values, List<String> errors) {
        String v = q.getFirst(key);
        if (v != null && !values.contains(v)) {
            errors.add(key + " must be one of the following values: " + String.join(", ", values));
        }
    }

    private static double number(MultiValueMap<String, String> q, String key, double fallback) {
        String v = q.getFirst(key);
        return v == null ? fallback : JsNumbers.number(v);
    }

    @GetMapping
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public List<Map<String, Object>> listAccounts() {
        return cashFlow.getCashAccounts();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.ACCOUNT__CREATE)
    public Map<String, Object> create(@Valid @RequestBody CreateCashAccountRequest dto) {
        return cashFlow.create(dto.code(), dto.name(), dto.accountType(), dto.currencyId(), dto.bankName(), dto.accountNumber(),
                dto.isDefault());
    }

    @PatchMapping("/{id}")
    @RequirePermissions(Permissions.ACCOUNT__UPDATE)
    public Map<String, Object> update(@PathVariable String id, @Valid @RequestBody UpdateCashAccountRequest dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", dto.name());
        data.put("currencyId", dto.currencyId());
        data.put("bankName", dto.bankName());
        data.put("accountNumber", dto.accountNumber());
        data.put("isDefault", dto.isDefault());
        return cashFlow.update(id, data);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermissions(Permissions.ACCOUNT__DELETE)
    public void remove(@PathVariable String id) {
        cashFlow.remove(id);
    }

    @GetMapping("/report")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public Map<String, Object> report(@RequestParam MultiValueMap<String, String> query) {
        List<String> errors = new ArrayList<>();
        whitelist(query, errors, "from", "to", "accountIds", "accountTypes", "categories", "direction", "search", "minAmount",
                "groupBy", "page", "pageSize");
        isoDate(query, "from", errors);
        isoDate(query, "to", errors);
        oneOf(query, "direction", List.of("in", "out", "all"), errors);
        oneOf(query, "groupBy", List.of("day", "week", "month"), errors);
        Double minAmount = null;
        if (query.getFirst("minAmount") != null) {
            double m = JsNumbers.number(query.getFirst("minAmount"));
            if (!(m >= 0)) {
                errors.add("minAmount must not be less than 0");
            }
            if (Double.isNaN(m)) {
                errors.add("minAmount must be a number conforming to the specified constraints");
            }
            minAmount = m;
        }
        if (!errors.isEmpty()) {
            throw QueryParams.validation(errors);
        }
        return movementReport.report(new CashMovementReportService.Filters(query.getFirst("from"), query.getFirst("to"),
                toList(query, "accountIds"), toList(query, "accountTypes"), toList(query, "categories"),
                query.getFirst("direction"), query.getFirst("search"), minAmount, query.getFirst("groupBy"),
                number(query, "page", 1), number(query, "pageSize", 50)));
    }

    @GetMapping("/overview")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public Map<String, Object> overview() {
        return moneyOverview.overview();
    }

    @GetMapping("/activity")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public Map<String, Object> activity(@RequestParam MultiValueMap<String, String> query) {
        List<String> errors = new ArrayList<>();
        whitelist(query, errors, "from", "to", "categories", "accountId", "branchId", "direction", "search", "page", "pageSize");
        isoDate(query, "from", errors);
        isoDate(query, "to", errors);
        oneOf(query, "direction", List.of("in", "out", "internal", "adjustment", "all"), errors);
        if (!errors.isEmpty()) {
            throw QueryParams.validation(errors);
        }
        return moneyActivity.list(new MoneyActivityService.Filters(query.getFirst("from"), query.getFirst("to"),
                toList(query, "categories"), query.getFirst("accountId"), query.getFirst("branchId"),
                query.getFirst("direction"), query.getFirst("search"), number(query, "page", 1), number(query, "pageSize", 25)));
    }

    @GetMapping("/operation-types")
    @RequirePermissions(Permissions.TREASURY__TRANSFER)
    public Map<String, Object> operationTypes() {
        return cashFlow.operationTypes();
    }

    @GetMapping("/transactions")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public Map<String, Object> allTransactions(@RequestParam MultiValueMap<String, String> query) {
        List<String> errors = new ArrayList<>();
        whitelist(query, errors, "page", "pageSize", "type", "search");
        oneOf(query, "type", List.of("deposit", "withdrawal", "transfer"), errors);
        if (!errors.isEmpty()) {
            throw QueryParams.validation(errors);
        }
        return cashFlow.getAllTransactions((int) number(query, "page", 1), (int) number(query, "pageSize", 25),
                query.getFirst("type"), query.getFirst("search"));
    }

    @GetMapping("/{id}/transactions")
    @RequirePermissions(Permissions.ACCOUNT__READ)
    public Map<String, Object> transactions(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) {
        List<String> errors = new ArrayList<>();
        whitelist(query, errors, "page", "pageSize", "type", "search");
        oneOf(query, "type", List.of("deposit", "withdrawal", "transfer"), errors);
        if (!errors.isEmpty()) {
            throw QueryParams.validation(errors);
        }
        return cashFlow.getTransactions(id, (int) number(query, "page", 1), (int) number(query, "pageSize", 25));
    }

    @PostMapping("/deposit")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.TREASURY__TRANSFER)
    public Map<String, Object> deposit(@Valid @RequestBody CashFlowRequest dto) {
        return cashFlow.deposit(new CashFlowService.OperationInput(dto.accountId(), dto.counterpartAccountId(),
                dto.operationType(), dto.amount(), dto.description(), dto.date()));
    }

    @PostMapping("/withdraw")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.TREASURY__TRANSFER)
    public Map<String, Object> withdraw(@Valid @RequestBody CashFlowRequest dto) {
        return cashFlow.withdraw(new CashFlowService.OperationInput(dto.accountId(), dto.counterpartAccountId(),
                dto.operationType(), dto.amount(), dto.description(), dto.date()));
    }
}
