package com.inventoryerp.kernel;

import com.inventoryerp.kernel.idempotency.Idempotent;
import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Test-only money route ({@code .../pos/checkout} matches OUTCOME_ROUTES) for idempotency ITs. */
@RestController
public class TestIdempotencyController {

    public static final AtomicInteger CALLS = new AtomicInteger();

    @Idempotent(required = true)
    @PostMapping("/test-kernel/pos/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> checkout(@RequestBody Map<String, Object> body) {
        CALLS.incrementAndGet();
        if (Boolean.TRUE.equals(body.get("fail"))) {
            throw ApiException.badRequest("Insufficient credit");
        }
        return Map.of("invoiceId", "inv-" + body.get("n"), "total", body.get("total"));
    }
}
