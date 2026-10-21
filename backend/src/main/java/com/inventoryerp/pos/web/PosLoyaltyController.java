package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.NUM;
import static com.inventoryerp.pos.web.PosDtos.STR;

import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosLoyaltyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /pos/loyalty} (port of {@code pos-loyalty.controller.ts}). */
@RestController
@RequestMapping("/pos/loyalty")
public class PosLoyaltyController {

    static final String MIN0 = "must not be less than 0";
    static final String MIN1 = "must not be less than 1";
    static final String MIN001 = "must not be less than 0.01";

    /** {@code EarnPointsDto}. */
    public record EarnPointsRequest(
            @NotNull(message = STR) String partnerId,
            @NotNull(message = STR) String documentId,
            @NotNull(message = NUM) @DecimalMin(value = "0", message = MIN0) Double amount,
            String reason) {}

    /** {@code RedeemPointsDto}. */
    public record RedeemPointsRequest(
            @NotNull(message = STR) String partnerId,
            @NotNull(message = NUM) @DecimalMin(value = "1", message = MIN1) Double points,
            String documentId) {}

    /** {@code IssueCreditDto}. */
    public record IssueCreditRequest(
            @NotNull(message = STR) String partnerId,
            @NotNull(message = NUM) @DecimalMin(value = "0.01", message = MIN001) Double amount,
            @NotNull(message = STR) String source,
            @NotNull(message = STR) String fundingAccountId,
            String notes) {}

    /** {@code RedeemCreditDto}. */
    public record RedeemCreditRequest(
            @NotNull(message = STR) String partnerId,
            @NotNull(message = NUM) @DecimalMin(value = "0.01", message = MIN001) Double amount,
            String documentId) {}

    /** {@code ChargeTabDto}. */
    public record ChargeTabRequest(
            @NotNull(message = STR) String partnerId,
            @NotNull(message = STR) String documentId,
            @NotNull(message = NUM) @DecimalMin(value = "0.01", message = MIN001) Double amount,
            String reason) {}

    /** {@code SettleTabDto}. */
    public record SettleTabRequest(
            @NotNull(message = STR) String partnerId,
            @NotNull(message = NUM) @DecimalMin(value = "0.01", message = MIN001) Double amount,
            String paymentId,
            String reason) {}

    /** {@code OpenTabDto}. */
    public record OpenTabRequest(
            @NotNull(message = STR) String partnerId,
            Double creditLimit,
            String cashSessionId) {}

    private final PosLoyaltyService svc;

    public PosLoyaltyController(PosLoyaltyService svc) {
        this.svc = svc;
    }

    @GetMapping("/program")
    @RequirePermissions(Permissions.PARTNER__READ)
    public Map<String, Object> getProgram() {
        return svc.getProgram();
    }

    @PostMapping("/program/ensure")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PARTNER__READ)
    public Map<String, Object> ensure() {
        return svc.ensureProgram();
    }

    @GetMapping("/balance/{partnerId}")
    @RequirePermissions(Permissions.PARTNER__READ)
    public Map<String, Object> balance(@PathVariable String partnerId) {
        return svc.getBalance(partnerId);
    }

    @GetMapping("/earned/{partnerId}")
    @RequirePermissions(Permissions.PARTNER__READ)
    public Map<String, Object> earned(@PathVariable String partnerId) {
        return svc.getEarnedLifetime(partnerId);
    }

    @PostMapping("/earn")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> earn(@Valid @RequestBody EarnPointsRequest dto) {
        return svc.earnPoints(dto.partnerId(), dto.documentId(), dto.amount(), dto.reason());
    }

    @PostMapping("/redeem")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> redeem(@Valid @RequestBody RedeemPointsRequest dto) {
        return svc.redeemPoints(dto.partnerId(), dto.points(), dto.documentId());
    }

    @GetMapping("/credit/{partnerId}")
    @RequirePermissions(Permissions.PARTNER__READ)
    public Map<String, Object> credit(@PathVariable String partnerId) {
        return svc.getCredit(partnerId);
    }

    @PostMapping("/credit/issue")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__OVERRIDE)
    public Map<String, Object> issueCredit(@Valid @RequestBody IssueCreditRequest dto) {
        return svc.issueCredit(dto.partnerId(), dto.amount(), dto.source(), dto.fundingAccountId(), dto.notes());
    }

    @PostMapping("/credit/redeem")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> redeemCredit(@Valid @RequestBody RedeemCreditRequest dto) {
        return svc.redeemCredit(dto.partnerId(), dto.amount(), dto.documentId());
    }

    @GetMapping("/tab/{partnerId}")
    @RequirePermissions(Permissions.PARTNER__READ)
    public Map<String, Object> tab(@PathVariable String partnerId) {
        return svc.getTab(partnerId);
    }

    @PostMapping("/tab/open")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.PARTNER__READ)
    public Map<String, Object> openTab(@Valid @RequestBody OpenTabRequest dto) {
        return svc.openTab(dto.partnerId(), dto.creditLimit(), dto.cashSessionId());
    }

    @PostMapping("/tab/charge")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> chargeTab(@Valid @RequestBody ChargeTabRequest dto) {
        return svc.chargeTab(dto.partnerId(), dto.documentId(), dto.amount(), dto.reason());
    }

    @PostMapping("/tab/settle")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.POS__CHECKOUT)
    public Map<String, Object> settleTab(@Valid @RequestBody SettleTabRequest dto) {
        return svc.settleTab(dto.partnerId(), dto.amount(), dto.paymentId(), dto.reason());
    }
}
