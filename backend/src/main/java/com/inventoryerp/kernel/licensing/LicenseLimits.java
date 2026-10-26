package com.inventoryerp.kernel.licensing;

import com.inventoryerp.shared.web.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Enforces the seat caps: devices, users, organizations.
 *
 * <p>Going over a cap <b>never</b> takes the install read-only. The route that would have crossed
 * the line is refused and everything already created keeps working — nobody loses access to data
 * because they hired one person too many. The counts are also reported on every check-in, so the
 * authority can see who is at their ceiling and sell them a bigger plan rather than cut them off.
 *
 * <p>Called from the services that create the things being capped, not from a guard: only they know
 * whether a request is really adding a seat or editing one that exists.
 */
@Service
public class LicenseLimits {

    private final LicenseService licenses;

    public LicenseLimits(LicenseService licenses) {
        this.licenses = licenses;
    }

    /** Refuse if registering another POS device would exceed the licence. */
    public void assertCanAddDevice() {
        LicenseState state = licenses.current();
        Integer limit = state.maxDevices();
        if (overLimit(limit, licenses.usage().devices())) {
            throw refusal(LicenseMessages.deviceLimit(limit), "devices", limit, state);
        }
    }

    /** Refuse if adding another active user would exceed the licence. */
    public void assertCanAddUser() {
        LicenseState state = licenses.current();
        Integer limit = state.maxUsers();
        if (overLimit(limit, licenses.usage().users())) {
            throw refusal(LicenseMessages.userLimit(limit), "users", limit, state);
        }
    }

    /** Refuse if adding another organization would exceed the licence. */
    public void assertCanAddOrganization() {
        LicenseState state = licenses.current();
        Integer limit = state.maxOrganizations();
        if (overLimit(limit, licenses.usage().organizations())) {
            throw refusal(LicenseMessages.organizationLimit(limit), "organizations", limit, state);
        }
    }

    /**
     * A null limit is unlimited, and enforcement being off means no cap applies either. The
     * comparison is {@code >=} because the question is whether there is room for <em>one more</em>.
     */
    private boolean overLimit(Integer limit, int inUse) {
        return licenses.enforcing() && limit != null && inUse >= limit;
    }

    private ApiException refusal(String message, String what, int limit, LicenseState state) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, message, Map.of("license", Map.of(
                "mode", state.mode().name(),
                "reason", "limit_exceeded",
                "limit", Map.of(what, limit),
                "plan", String.valueOf(state.plan()))));
    }
}
