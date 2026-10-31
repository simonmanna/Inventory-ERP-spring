package com.inventoryerp.invoicing.application;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic provider for tests and demos (code {@value #CODE}); no money moves. The last digit of the number picks
 * the outcome and is carried in the provider reference: {@code 9} is refused at once, {@code 8} stays pending, any other
 * is pending at first and successful on the first {@link #status} call. A successful collection posts a real receipt, so
 * under the {@code prod} profile this provider reports itself unconfigured and no collection can start.
 */
@Component
public class MockMobileMoneyProvider implements MobileMoneyProvider, EnvironmentAware {

    public static final String CODE = "mock";

    private final JsonMapper mapper = JsonMapper.builder().build();
    private boolean production;

    @Override
    public void setEnvironment(Environment environment) {
        production = environment.acceptsProfiles(Profiles.of("prod"));
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean configured() {
        return !production;
    }

    @Override
    public CollectionState collect(CollectRequest request) {
        char digit = request.msisdn().charAt(request.msisdn().length() - 1);
        String reference = "MOCK-" + digit + "-" + request.reference();
        return digit == '9' ? new CollectionState(reference, FAILED, "Insufficient funds")
                : new CollectionState(reference, PENDING, null);
    }

    @Override
    public CollectionState status(String providerReference) {
        return providerReference.startsWith("MOCK-8-") ? new CollectionState(providerReference, PENDING, null)
                : new CollectionState(providerReference, SUCCESSFUL, null);
    }

    /** Body: {@code {"providerReference": "...", "status": "successful" | "failed", "reason": "..."}}. */
    @Override
    public CollectionState parseCallback(String body) {
        JsonNode n = mapper.readTree(body);
        return new CollectionState(n.path("providerReference").asString(""), n.path("status").asString(""),
                n.hasNonNull("reason") ? n.get("reason").asString() : null);
    }
}
