package com.inventoryerp.guide.application;

import com.inventoryerp.kernel.crud.SqlRows;
import com.inventoryerp.kernel.security.AuthUser;
import com.inventoryerp.shared.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each user's progress through the in-app guided tours (ADR-S079). Always scoped to the signed-in user of the
 * signed-in organization: a user reads and writes only their own rows, never anyone else's.
 */
@Service
public class GuideProgressService {

    /** Same rule as the {@code tourKey} check constraint of V580. */
    static final Pattern TOUR_KEY = Pattern.compile("^[a-z0-9.-]{1,100}$");

    /** One tour's progress. {@code completedAt} stays set once the user has finished the tour, even on a replay. */
    public record Progress(String tourKey, int tourVersion, String status, int currentStep, Instant startedAt,
            Instant completedAt, Instant updatedAt) {}

    private final JdbcClient jdbc;

    public GuideProgressService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** All tours this user has started, finished or dismissed, most recently touched first. */
    @Transactional(readOnly = true)
    public List<Progress> list(AuthUser user) {
        return SqlRows.list(jdbc.sql("SELECT * FROM \"GuideTourProgress\" WHERE \"organizationId\" = ? AND \"userId\" = ?"
                        + " ORDER BY \"updatedAt\" DESC, \"tourKey\"")
                .params(user.organizationId(), user.sub()))
                .stream().map(GuideProgressService::toProgress).toList();
    }

    /**
     * Record where the user is in a tour. {@code startedAt} is kept from the first save; {@code completedAt} is set the
     * first time the status is {@code completed} and kept afterwards.
     */
    @Transactional
    public Progress save(AuthUser user, String tourKey, int tourVersion, String status, int currentStep) {
        requireKey(tourKey);
        jdbc.sql("INSERT INTO \"GuideTourProgress\" (id, \"organizationId\", \"userId\", \"tourKey\", \"tourVersion\","
                        + " status, \"currentStep\", \"completedAt\")"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ? = 'completed' THEN CURRENT_TIMESTAMP END)"
                        + " ON CONFLICT (\"organizationId\", \"userId\", \"tourKey\") DO UPDATE SET"
                        + " \"tourVersion\" = EXCLUDED.\"tourVersion\", status = EXCLUDED.status,"
                        + " \"currentStep\" = EXCLUDED.\"currentStep\","
                        + " \"completedAt\" = COALESCE(\"GuideTourProgress\".\"completedAt\", EXCLUDED.\"completedAt\"),"
                        + " \"updatedAt\" = CURRENT_TIMESTAMP")
                .params(UUID.randomUUID().toString(), user.organizationId(), user.sub(), tourKey, tourVersion, status,
                        currentStep, status)
                .update();
        return toProgress(SqlRows.one(jdbc.sql("SELECT * FROM \"GuideTourProgress\" WHERE \"organizationId\" = ?"
                        + " AND \"userId\" = ? AND \"tourKey\" = ?")
                .params(user.organizationId(), user.sub(), tourKey)));
    }

    /** Forget a tour's progress so it shows as new again. Unknown keys are a no-op. */
    @Transactional
    public void reset(AuthUser user, String tourKey) {
        requireKey(tourKey);
        jdbc.sql("DELETE FROM \"GuideTourProgress\" WHERE \"organizationId\" = ? AND \"userId\" = ? AND \"tourKey\" = ?")
                .params(user.organizationId(), user.sub(), tourKey)
                .update();
    }

    private static void requireKey(String tourKey) {
        if (tourKey == null || !TOUR_KEY.matcher(tourKey).matches()) {
            throw ApiException.badRequest("tourKey must be 1-100 lowercase letters, digits, dots or dashes");
        }
    }

    private static Progress toProgress(Map<String, Object> r) {
        return new Progress((String) r.get("tourKey"), ((Number) r.get("tourVersion")).intValue(),
                (String) r.get("status"), ((Number) r.get("currentStep")).intValue(), (Instant) r.get("startedAt"),
                (Instant) r.get("completedAt"), (Instant) r.get("updatedAt"));
    }
}
