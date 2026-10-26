package com.inventoryerp.license.application;

import com.inventoryerp.license.domain.LicenseEvent;
import com.inventoryerp.license.domain.LicenseEventRepository;
import com.inventoryerp.license.shared.Json;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the licence audit trail.
 *
 * <p>{@code REQUIRES_NEW} on every entry point: an event records that something was <em>attempted</em>,
 * so it must survive the rollback of whatever it was recording. A refused activation rolls its own
 * transaction back; the evidence that it happened — and from which fingerprint — has to stay. This
 * is also why {@code license_event} carries no foreign keys (see {@code V1__license.sql}).
 *
 * <p>Each overload is annotated and writes through the private helper rather than calling a sibling
 * overload: a self-invocation would not pass through the proxy, and the propagation would silently
 * not apply.
 */
@Service
public class LicenseEvents {

    private final LicenseEventRepository events;

    public LicenseEvents(LicenseEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String licenseId, String activationId, String type, String actor,
            Map<String, Object> detail) {
        write(licenseId, activationId, type, actor, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String licenseId, String type, String actor, Map<String, Object> detail) {
        write(licenseId, null, type, actor, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String licenseId, String type, String actor) {
        write(licenseId, null, type, actor, null);
    }

    private void write(String licenseId, String activationId, String type, String actor,
            Map<String, Object> detail) {
        LicenseEvent row = new LicenseEvent();
        row.setLicenseId(licenseId);
        row.setActivationId(activationId);
        row.setType(type);
        row.setActor(actor == null ? "system" : actor);
        row.setDetail(detail == null || detail.isEmpty() ? null : Json.write(detail));
        events.save(row);
    }
}
