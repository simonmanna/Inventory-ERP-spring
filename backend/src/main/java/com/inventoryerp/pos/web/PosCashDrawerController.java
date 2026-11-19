package com.inventoryerp.pos.web;

import static com.inventoryerp.pos.web.PosDtos.DATE_MSG;
import static com.inventoryerp.pos.web.PosDtos.ISO_DATE;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosCashDrawerService;
import com.inventoryerp.shared.validation.OneOf;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /pos/cash-drawer}: drawer openings recorded by the POS (LP2b, owner decision 2026-11-19; no Nest
 * counterpart). Shaped like {@code POST /cash-sessions/movement}: same permission, Idempotency-Key required, 200.
 */
@RestController
@RequestMapping("/pos/cash-drawer")
public class PosCashDrawerController {

    static final String REASON_MSG = "must be one of the following values: sale, refund, cash_movement, no_sale, float,"
            + " other";

    /** {@code RecordDrawerOpeningDto}. */
    public record RecordDrawerOpeningRequest(
            String sessionId,
            String cashRegisterId,
            @NotNull(message = REASON_MSG)
            @OneOf({"sale", "refund", "cash_movement", "no_sale", "float", "other"}) String reason,
            @Size(max = 500, message = "must be shorter than or equal to 500 characters") String note,
            @OneOf({"invoice", "refund", "cash_movement"}) String referenceType,
            String referenceId,
            @Pattern(regexp = ISO_DATE, message = DATE_MSG) String occurredAt) {}

    private final PosCashDrawerService drawer;

    public PosCashDrawerController(PosCashDrawerService drawer) {
        this.drawer = drawer;
    }

    @PostMapping("/openings")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.CASH_SESSION__OPEN)
    public Map<String, Object> recordOpening(@Valid @RequestBody RecordDrawerOpeningRequest dto) {
        return drawer.record(new PosCashDrawerService.OpeningInput(dto.sessionId(), dto.cashRegisterId(), dto.reason(),
                dto.note(), dto.referenceType(), dto.referenceId(), dto.occurredAt()));
    }
}
