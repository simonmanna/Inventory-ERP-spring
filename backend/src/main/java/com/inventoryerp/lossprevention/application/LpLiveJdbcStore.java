package com.inventoryerp.lossprevention.application;

import com.inventoryerp.kernel.crud.SqlRows;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Live views and stills in the UNLOGGED tables {@code LpLiveSession} and {@code LpLiveSnapshot} (V577), so any API
 * instance can take the agent's frames and any other can stream them. Frames and stills are encrypted with the evidence
 * key, bound to their session or camera. A viewer polls for a newer frame every {@value #POLL_MILLIS} ms; no
 * transaction or connection is held between polls.
 */
final class LpLiveJdbcStore implements LpLiveStore {

    static final long POLL_MILLIS = 200;

    private final JdbcClient jdbc;
    private final LpEvidenceCipher cipher;

    LpLiveJdbcStore(JdbcClient jdbc, LpEvidenceCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant at(Object v) {
        return v == null ? null : v instanceof Timestamp t ? t.toInstant() : (Instant) v;
    }

    private static Session session(Map<String, Object> r) {
        return new Session((String) r.get("id"), (String) r.get("organizationId"), (String) r.get("agentId"),
                (String) r.get("cameraId"), at(r.get("connectBy")), at(r.get("endsAt")),
                Boolean.TRUE.equals(r.get("stopped")), Boolean.TRUE.equals(r.get("watched")),
                at(r.get("lastViewerAt")));
    }

    private static final String COLUMNS = "id, \"organizationId\", \"agentId\", \"cameraId\", \"connectBy\", \"endsAt\","
            + " stopped, watched, \"lastViewerAt\"";

    @Override
    public void create(Session s) {
        jdbc.sql("INSERT INTO \"LpLiveSession\" (id, \"organizationId\", \"agentId\", \"cameraId\", \"connectBy\","
                + " \"endsAt\") VALUES (?, ?, ?, ?, ?, ?)").params(s.id(), s.organizationId(), s.agentId(),
                        s.cameraId(), ts(s.connectBy()), ts(s.endsAt())).update();
    }

    @Override
    public Session find(String id) {
        if (id == null) {
            return null;
        }
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT " + COLUMNS + " FROM \"LpLiveSession\" WHERE id = ?")
                .param(id));
        return r == null ? null : session(r);
    }

    @Override
    public List<Session> all() {
        return SqlRows.list(jdbc.sql("SELECT " + COLUMNS + " FROM \"LpLiveSession\"")).stream()
                .map(LpLiveJdbcStore::session).toList();
    }

    @Override
    public boolean stop(String id) {
        return jdbc.sql("UPDATE \"LpLiveSession\" SET stopped = true, frame = NULL WHERE id = ? AND NOT stopped")
                .param(id).update() > 0;
    }

    @Override
    public void remove(String id) {
        jdbc.sql("DELETE FROM \"LpLiveSession\" WHERE id = ?").param(id).update();
    }

    @Override
    public boolean putFrame(String id, String organizationId, String agentId, byte[] jpeg, Instant now,
            Duration minGap) {
        int stored = jdbc.sql("UPDATE \"LpLiveSession\" SET frame = ?, seq = seq + 1, \"lastFrameAt\" = ? WHERE id = ?"
                + " AND \"organizationId\" = ? AND \"agentId\" = ? AND NOT stopped AND \"endsAt\" >= ? AND"
                + " (\"lastFrameAt\" IS NULL OR \"lastFrameAt\" <= ?)")
                .params(cipher.encrypt("live:" + id, jpeg), ts(now), id, organizationId, agentId, ts(now),
                        ts(now.minus(minGap)))
                .update();
        if (stored > 0) {
            return true;
        }
        // Either too soon after the last frame (dropped, keep sending) or the session is over.
        Session s = find(id);
        return s != null && s.agentId().equals(agentId) && s.organizationId().equals(organizationId) && !s.stopped()
                && !now.isAfter(s.endsAt());
    }

    @Override
    public Frame next(String id, long afterSeq, long waitMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + waitMillis;
        while (true) {
            Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT seq, stopped FROM \"LpLiveSession\" WHERE id = ?")
                    .param(id));
            if (r == null || Boolean.TRUE.equals(r.get("stopped"))) {
                return new Frame(afterSeq, null, true);
            }
            long seq = ((Number) r.get("seq")).longValue();
            if (seq != afterSeq) {
                Map<String, Object> f = SqlRows.one(jdbc.sql("SELECT seq, frame FROM \"LpLiveSession\" WHERE id = ?")
                        .param(id));
                if (f != null && f.get("frame") != null) {
                    try {
                        return new Frame(((Number) f.get("seq")).longValue(), cipher.decrypt("live:" + id,
                                (byte[]) f.get("frame")), false);
                    } catch (LpEvidenceCipher.IntegrityException e) {
                        // Written under another key (a misconfigured instance): show nothing rather than garbage.
                        return null;
                    }
                }
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            Thread.sleep(Math.min(left, POLL_MILLIS));
        }
    }

    @Override
    public void viewer(String id, Instant now) {
        jdbc.sql("UPDATE \"LpLiveSession\" SET watched = true, \"lastViewerAt\" = ? WHERE id = ?").params(ts(now), id)
                .update();
    }

    @Override
    public void putSnapshot(String organizationId, String cameraId, byte[] jpeg, Instant at) {
        jdbc.sql("INSERT INTO \"LpLiveSnapshot\" (\"organizationId\", \"cameraId\", jpeg, \"receivedAt\") VALUES (?, ?, ?,"
                + " ?) ON CONFLICT (\"organizationId\", \"cameraId\") DO UPDATE SET jpeg = EXCLUDED.jpeg, \"receivedAt\" ="
                + " EXCLUDED.\"receivedAt\"").params(organizationId, cameraId,
                        cipher.encrypt("still:" + organizationId + "|" + cameraId, jpeg), ts(at)).update();
    }

    @Override
    public LpLiveService.Snapshot snapshot(String organizationId, String cameraId) {
        Map<String, Object> r = SqlRows.one(jdbc.sql("SELECT jpeg, \"receivedAt\" FROM \"LpLiveSnapshot\" WHERE"
                + " \"organizationId\" = ? AND \"cameraId\" = ?").params(organizationId, cameraId));
        if (r == null) {
            return null;
        }
        try {
            return new LpLiveService.Snapshot(cipher.decrypt("still:" + organizationId + "|" + cameraId,
                    (byte[]) r.get("jpeg")), at(r.get("receivedAt")));
        } catch (LpEvidenceCipher.IntegrityException e) {
            return null;
        }
    }

    @Override
    public void removeSnapshot(String organizationId, String cameraId) {
        jdbc.sql("DELETE FROM \"LpLiveSnapshot\" WHERE \"organizationId\" = ? AND \"cameraId\" = ?")
                .params(organizationId, cameraId).update();
    }

    @Override
    public void pruneSnapshots(Instant before) {
        jdbc.sql("DELETE FROM \"LpLiveSnapshot\" WHERE \"receivedAt\" < ?").param(ts(before)).update();
    }
}
