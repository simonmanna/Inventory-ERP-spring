package com.inventoryerp.inventory.application;

import com.inventoryerp.core.application.BranchScopeService;
import com.inventoryerp.core.application.BranchScopeService.DataScope;
import com.inventoryerp.shared.web.ApiException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The user data scope of BR-1 as inventory needs it. For an unrestricted user every method here is a no-op that returns
 * its input, so route behaviour stays as it was. Stock movement itself ({@link StockService}) never consults this class:
 * POS, sync and module postings are not user-facing reads.
 */
@Component
public class LocationScope {

    private final BranchScopeService branches;

    public LocationScope(BranchScopeService branches) {
        this.branches = branches;
    }

    public DataScope current() {
        return branches.scope();
    }

    public boolean restricted() {
        return current().restricted();
    }

    public boolean permits(String locationId) {
        return current().allowsLocation(locationId);
    }

    /** 403 when the location is outside the user's access. */
    public void assertAllowed(String locationId) {
        if (!permits(locationId)) {
            throw ApiException.forbidden("This location is outside your data access");
        }
    }

    /** A document between two locations is reachable from either end. */
    public void assertEither(String a, String b) {
        DataScope s = current();
        if (!s.allowsLocation(a) && !s.allowsLocation(b)) {
            throw ApiException.forbidden("This location is outside your data access");
        }
    }

    /** Refuse an organization-wide report for a restricted user. */
    public void assertUnrestricted() {
        branches.assertUnrestricted();
    }

    /**
     * The location a single-location query should use. Unrestricted: {@code locationId} unchanged, blank included. A
     * restricted user's blank filter becomes their one location; with several, they must choose, because a blank
     * filter means "all locations" further down.
     */
    public String resolve(String locationId) {
        DataScope s = current();
        if (!s.restricted()) {
            return locationId;
        }
        if (locationId != null && !locationId.isBlank()) {
            if (!s.allowsLocation(locationId)) {
                throw ApiException.forbidden("This location is outside your data access");
            }
            return locationId;
        }
        if (s.locationIds().isEmpty()) {
            throw ApiException.forbidden("Your data access has no location");
        }
        if (s.locationIds().size() > 1) {
            throw ApiException.badRequest("Choose a location: your access is limited to specific locations");
        }
        return s.locationIds().iterator().next();
    }

    /** {@link #resolve} on a query map; an unrestricted user gets the same map back. */
    public Map<String, String> apply(Map<String, String> q) {
        if (!restricted()) {
            return q;
        }
        Map<String, String> copy = new HashMap<>(q);
        copy.put("locationId", resolve(q.get("locationId")));
        return copy;
    }
}
