package com.inventoryerp.rental.web;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.kernel.modules.RequiresModule;
import com.inventoryerp.kernel.security.Permissions;
import com.inventoryerp.kernel.security.RequirePermissions;
import com.inventoryerp.pos.application.PosSql;
import com.inventoryerp.rental.application.RentalAgreementService;
import com.inventoryerp.rental.application.RentalAvailabilityService;
import com.inventoryerp.rental.application.RentalCatalogService;
import com.inventoryerp.rental.application.RentalDepositService;
import com.inventoryerp.rental.application.RentalExtensionService;
import com.inventoryerp.rental.application.RentalLocationConfigService;
import com.inventoryerp.rental.application.RentalReportsService;
import com.inventoryerp.rental.application.RentalReservationService;
import com.inventoryerp.rental.application.RentalReturnService;
import com.inventoryerp.rental.application.RentalRows;
import com.inventoryerp.rental.application.RentalScoreService;
import com.inventoryerp.rental.application.RentalServiceOrderService;
import com.inventoryerp.rental.application.RentalSwapService;
import com.inventoryerp.rental.application.RentalUnitService;
import com.inventoryerp.shared.web.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
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

/**
 * {@code /rental} (port of {@code rental.controller.ts}): catalog → availability → reserve → score → agreement →
 * checkout → extend / swap → return → inspect → settle → service orders → reports. Bodies and queries are untyped
 * in Nest ({@code any}), so they arrive here as plain maps and are coerced like JavaScript would.
 *
 * <p>The seven routes that move money (checkout, deposit collect / refund / forfeit, extension and swap fees,
 * settlement) require an {@code Idempotency-Key} and answer 200 like every idempotent route (AGENTS.md §7/§8);
 * Nest has no {@code @Idempotent} there — a deviation listed in docs/spec/rental.md.
 */
@RestController
@RequestMapping("/rental")
@RequiresModule("rental")
@ConditionalOnProperty(name = "app.modules.rental", havingValue = "true")
public class RentalController {

    private final RentalCatalogService catalog;
    private final RentalUnitService units;
    private final RentalAvailabilityService availability;
    private final RentalReservationService reservations;
    private final RentalScoreService score;
    private final RentalAgreementService agreements;
    private final RentalDepositService deposit;
    private final RentalExtensionService extensions;
    private final RentalSwapService swaps;
    private final RentalReturnService returns;
    private final RentalServiceOrderService serviceOrders;
    private final RentalReportsService reports;
    private final RentalLocationConfigService locations;

    public RentalController(RentalCatalogService catalog, RentalUnitService units,
            RentalAvailabilityService availability, RentalReservationService reservations, RentalScoreService score,
            RentalAgreementService agreements, RentalDepositService deposit, RentalExtensionService extensions,
            RentalSwapService swaps, RentalReturnService returns, RentalServiceOrderService serviceOrders,
            RentalReportsService reports, RentalLocationConfigService locations) {
        this.catalog = catalog;
        this.units = units;
        this.availability = availability;
        this.reservations = reservations;
        this.score = score;
        this.agreements = agreements;
        this.deposit = deposit;
        this.extensions = extensions;
        this.swaps = swaps;
        this.returns = returns;
        this.serviceOrders = serviceOrders;
        this.reports = reports;
        this.locations = locations;
    }

    /** Express hands an absent body to the handler as {@code {}}. */
    private static Map<String, Object> body(Map<String, Object> dto) {
        return dto == null ? new LinkedHashMap<>() : dto;
    }

    /** {@code {agreementId: id, ...dto}} — a body {@code agreementId} wins, like the object spread. */
    private static String agreementId(String id, Map<String, Object> dto) {
        return dto.containsKey("agreementId") ? PosSql.str(dto, "agreementId") : id;
    }

    // ── Catalog ──────────────────────────────────────────────────────────────

    @GetMapping("/catalog")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> listCatalog(@RequestParam Map<String, String> q) {
        return catalog.catalog(q.get("productId"), "true".equals(q.get("includeInactive")));
    }

    @PostMapping("/rates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> createRate(@RequestBody(required = false) Map<String, Object> dto) {
        return catalog.upsertRate(body(dto));
    }

    @PatchMapping("/rates/{id}")
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> updateRate(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return catalog.updateRate(id, body(dto));
    }

    @DeleteMapping("/rates/{id}")
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> deleteRate(@PathVariable String id) {
        return catalog.deleteRate(id);
    }

    @PostMapping("/packages")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> createPackage(@RequestBody(required = false) Map<String, Object> dto) {
        return catalog.createPackage(body(dto));
    }

    @PatchMapping("/packages/{id}")
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> updatePackage(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return catalog.updatePackage(id, body(dto));
    }

    @DeleteMapping("/packages/{id}")
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> deletePackage(@PathVariable String id) {
        return catalog.deletePackage(id);
    }

    // ── Units ────────────────────────────────────────────────────────────────

    @GetMapping("/units")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> listUnits(@RequestParam Map<String, String> q) {
        return units.list(q);
    }

    @PostMapping("/units/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> generateUnits(@RequestBody(required = false) Map<String, Object> dto) {
        return units.bulkGenerate(body(dto));
    }

    @PatchMapping("/units/{id}")
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> updateUnit(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return units.update(id, body(dto));
    }

    @PostMapping("/units/{id}/retire")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> retireUnit(@PathVariable String id, @RequestBody(required = false) Map<String, Object> dto) {
        return units.retire(id, PosSql.str(dto, "notes"));
    }

    // ── Availability ─────────────────────────────────────────────────────────

    private static boolean missing(Map<String, String> q, String... keys) {
        for (String k : keys) {
            if (q.get(k) == null || q.get(k).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @GetMapping("/availability")
    @RequirePermissions(Permissions.RENTAL__READ)
    public long checkAvailability(@RequestParam Map<String, String> q) {
        if (missing(q, "productId", "startAt", "endAt")) {
            throw ApiException.badRequest("availability requires productId, startAt, endAt");
        }
        return availability.countAvailable(q.get("productId"), RentalRows.parseDate(q.get("startAt")),
                RentalRows.parseDate(q.get("endAt")), q.get("excludeSourceId"));
    }

    @GetMapping("/availability/units")
    @RequirePermissions(Permissions.RENTAL__READ)
    public List<String> availabilityUnits(@RequestParam Map<String, String> q) {
        if (missing(q, "productId", "startAt", "endAt")) {
            throw ApiException.badRequest("availability/units requires productId, startAt, endAt");
        }
        return availability.candidateUnits(q.get("productId"), RentalRows.parseDate(q.get("startAt")),
                RentalRows.parseDate(q.get("endAt")));
    }

    // ── Reservations (holds) ─────────────────────────────────────────────────

    @GetMapping("/reservations")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> listReservations(@RequestParam Map<String, String> q) {
        return reservations.list(q);
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__RESERVE)
    public Map<String, Object> createReservation(@RequestBody(required = false) Map<String, Object> dto) {
        return reservations.create(body(dto));
    }

    /** Holds convert at agreement-create; this convenience returns the current hold. */
    @PostMapping("/reservations/{id}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__RESERVE)
    public Map<String, Object> confirmReservation(@PathVariable String id) {
        return reservations.get(id);
    }

    @PostMapping("/reservations/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__RESERVE)
    public Map<String, Object> cancelReservation(@PathVariable String id) {
        return reservations.cancel(id);
    }

    // ── Score ────────────────────────────────────────────────────────────────

    @GetMapping("/scores/{partnerId}")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> getScore(@PathVariable String partnerId) {
        return score.recompute(partnerId);
    }

    @PatchMapping("/scores/{partnerId}/hold")
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> scoreHold(@PathVariable String partnerId,
            @RequestBody(required = false) Map<String, Object> dto) {
        return score.setManualHold(partnerId, PosSql.truthy(body(dto).get("manualHold")));
    }

    // ── Agreements ───────────────────────────────────────────────────────────

    @GetMapping("/agreements")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> listAgreements(@RequestParam Map<String, String> q) {
        return agreements.list(q);
    }

    @GetMapping("/agreements/{id}")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> getAgreement(@PathVariable String id) {
        return agreements.get(id);
    }

    @PostMapping("/agreements")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__SIGN)
    public Map<String, Object> createAgreement(@RequestBody(required = false) Map<String, Object> dto) {
        return agreements.create(body(dto));
    }

    @PostMapping("/agreements/{id}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__SIGN)
    public Map<String, Object> confirmAgreement(@PathVariable String id) {
        return agreements.confirm(id);
    }

    @PostMapping("/agreements/{id}/checkout")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.RENTAL__CHECKOUT)
    public Map<String, Object> checkoutAgreement(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        return agreements.checkout(id, body(dto));
    }

    @PostMapping("/agreements/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__SIGN)
    public Map<String, Object> cancelAgreement(@PathVariable String id) {
        return agreements.cancel(id);
    }

    // ── Deposits ─────────────────────────────────────────────────────────────

    @GetMapping("/agreements/{id}/deposit")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> getDeposit(@PathVariable String id) {
        return deposit.getByAgreement(id);
    }

    @PostMapping("/agreements/{id}/deposit/collect")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.RENTAL__CHECKOUT)
    public Map<String, Object> collectDeposit(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        Map<String, Object> b = body(dto);
        return deposit.collect(agreementId(id, b), b);
    }

    @PostMapping("/agreements/{id}/deposit/refund")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> refundDeposit(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        Map<String, Object> b = body(dto);
        return deposit.refund(agreementId(id, b), b);
    }

    @PostMapping("/agreements/{id}/deposit/forfeit")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> forfeitDeposit(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        Map<String, Object> b = body(dto);
        return deposit.forfeit(agreementId(id, b), b);
    }

    // ── Extensions & swaps ───────────────────────────────────────────────────

    @GetMapping("/extensions")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> listExtensions(@RequestParam Map<String, String> q) {
        return extensions.list(q);
    }

    @PostMapping("/extensions")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> extend(@RequestBody(required = false) Map<String, Object> dto) {
        return extensions.extend(body(dto));
    }

    @GetMapping("/swaps")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> listSwaps(@RequestParam Map<String, String> q) {
        return swaps.list(q);
    }

    @PostMapping("/swaps")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> swap(@RequestBody(required = false) Map<String, Object> dto) {
        return swaps.swap(body(dto));
    }

    // ── Returns, inspection, settlement ──────────────────────────────────────

    @GetMapping("/returns")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> listReturns(@RequestParam Map<String, String> q) {
        return returns.list(q);
    }

    @GetMapping("/returns/{id}")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> getReturn(@PathVariable String id) {
        return returns.get(id);
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__RETURN)
    public Map<String, Object> createReturn(@RequestBody(required = false) Map<String, Object> dto) {
        return returns.createReturn(body(dto));
    }

    @PostMapping("/returns/{id}/inspect")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__RETURN)
    public Map<String, Object> inspectReturn(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        Map<String, Object> b = body(dto);
        return returns.inspect(b.containsKey("returnId") ? PosSql.str(b, "returnId") : id, b);
    }

    @PostMapping("/returns/{id}/settle")
    @Idempotent(required = true)
    @RequirePermissions(Permissions.RENTAL__SETTLE)
    public Map<String, Object> settleReturn(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        Map<String, Object> b = body(dto);
        return returns.settle(b.containsKey("returnId") ? PosSql.str(b, "returnId") : id, b);
    }

    @PostMapping("/damages/{id}/waive")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__WAIVE)
    public Map<String, Object> waiveDamage(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        String reason = PosSql.str(dto, "reason");
        return returns.waiveDamage(id, reason == null ? "" : reason);
    }

    // ── Service orders ───────────────────────────────────────────────────────

    @GetMapping("/service-orders")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> listServiceOrders(@RequestParam Map<String, String> q) {
        return serviceOrders.list(q);
    }

    @PostMapping("/service-orders")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> createServiceOrder(@RequestBody(required = false) Map<String, Object> dto) {
        return serviceOrders.create(body(dto));
    }

    @PostMapping("/service-orders/{id}/complete")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> completeServiceOrder(@PathVariable String id,
            @RequestBody(required = false) Map<String, Object> dto) {
        Map<String, Object> b = body(dto);
        return serviceOrders.complete(b.containsKey("id") ? PosSql.str(b, "id") : id, b);
    }

    @PostMapping("/service-orders/{id}/cancel")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> cancelServiceOrder(@PathVariable String id) {
        return serviceOrders.cancel(id);
    }

    // ── Reports ──────────────────────────────────────────────────────────────

    @GetMapping("/reports/utilization")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> utilization(@RequestParam Map<String, String> q) {
        return reports.utilization(q);
    }

    @GetMapping("/reports/revenue")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> revenue(@RequestParam Map<String, String> q) {
        return reports.revenue(q);
    }

    @GetMapping("/reports/fleet")
    @RequirePermissions(Permissions.RENTAL__READ)
    public Map<String, Object> fleet(@RequestParam Map<String, String> q) {
        return reports.fleet(q);
    }

    // ── Locations ────────────────────────────────────────────────────────────

    @PostMapping("/locations/bootstrap")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermissions(Permissions.RENTAL__MANAGE)
    public Map<String, Object> bootstrapLocations() {
        return locations.bootstrap();
    }
}
