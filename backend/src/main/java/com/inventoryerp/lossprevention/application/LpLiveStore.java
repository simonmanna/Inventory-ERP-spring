package com.inventoryerp.lossprevention.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Where live views and stills are kept (LP1). {@link Memory} keeps them in this API instance (one instance, or sticky
 * routing); {@link LpLiveJdbcStore} keeps them in UNLOGGED tables so every instance serves every session. A session
 * holds only its latest frame.
 */
interface LpLiveStore {

    /** A live view as stored. */
    record Session(String id, String organizationId, String agentId, String cameraId, Instant connectBy,
            Instant endsAt, boolean stopped, boolean watched, Instant lastViewerAt) {}

    /** A frame newer than the one the viewer has, or the news that the session ended ({@code stopped}). */
    record Frame(long seq, byte[] jpeg, boolean stopped) {}

    void create(Session s);

    /** The session, or {@code null}. */
    Session find(String id);

    List<Session> all();

    /** Marks the session stopped. True only for the call that stopped it, so the agent is told once. */
    boolean stop(String id);

    void remove(String id);

    /**
     * Stores a frame of the agent's session unless one arrived within {@code minGap} (that one is dropped). Answers
     * whether the agent should keep sending: false when the session is unknown, another agent's or organization's,
     * stopped or over.
     */
    boolean putFrame(String id, String organizationId, String agentId, byte[] jpeg, Instant now, Duration minGap);

    /**
     * Waits up to {@code waitMillis} for a frame after {@code afterSeq}: the frame, {@code stopped} when the session is
     * gone or stopped, or {@code null} when nothing new came.
     */
    Frame next(String id, long afterSeq, long waitMillis) throws InterruptedException;

    /** A viewer is watching now. */
    void viewer(String id, Instant now);

    void putSnapshot(String organizationId, String cameraId, byte[] jpeg, Instant at);

    /** The still, or {@code null}. */
    LpLiveService.Snapshot snapshot(String organizationId, String cameraId);

    void removeSnapshot(String organizationId, String cameraId);

    void pruneSnapshots(Instant before);

    /** The in-memory store: one API instance. */
    final class Memory implements LpLiveStore {

        private static final class Held {
            Session session;
            byte[] frame;
            long seq;
            Instant lastFrameAt;

            Held(Session session) {
                this.session = session;
            }
        }

        private final java.util.Map<String, Held> sessions = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<String, LpLiveService.Snapshot> snapshots =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void create(Session s) {
            sessions.put(s.id(), new Held(s));
        }

        @Override
        public Session find(String id) {
            Held h = id == null ? null : sessions.get(id);
            if (h == null) {
                return null;
            }
            synchronized (h) {
                return h.session;
            }
        }

        @Override
        public List<Session> all() {
            return sessions.values().stream().map(h -> {
                synchronized (h) {
                    return h.session;
                }
            }).toList();
        }

        @Override
        public boolean stop(String id) {
            Held h = sessions.get(id);
            if (h == null) {
                return false;
            }
            synchronized (h) {
                if (h.session.stopped()) {
                    return false;
                }
                Session s = h.session;
                h.session = new Session(s.id(), s.organizationId(), s.agentId(), s.cameraId(), s.connectBy(),
                        s.endsAt(), true, s.watched(), s.lastViewerAt());
                h.notifyAll();
                return true;
            }
        }

        @Override
        public void remove(String id) {
            Held h = sessions.remove(id);
            if (h != null) {
                synchronized (h) {
                    h.notifyAll();
                }
            }
        }

        @Override
        public boolean putFrame(String id, String organizationId, String agentId, byte[] jpeg, Instant now,
                Duration minGap) {
            Held h = sessions.get(id);
            if (h == null) {
                return false;
            }
            synchronized (h) {
                Session s = h.session;
                if (!s.agentId().equals(agentId) || !s.organizationId().equals(organizationId) || s.stopped()
                        || now.isAfter(s.endsAt())) {
                    return false;
                }
                if (h.lastFrameAt == null || Duration.between(h.lastFrameAt, now).compareTo(minGap) >= 0) {
                    h.frame = jpeg;
                    h.seq++;
                    h.lastFrameAt = now;
                    h.notifyAll();
                }
                return true;
            }
        }

        @Override
        public Frame next(String id, long afterSeq, long waitMillis) throws InterruptedException {
            Held h = sessions.get(id);
            if (h == null) {
                return new Frame(afterSeq, null, true);
            }
            synchronized (h) {
                if (h.seq == afterSeq && !h.session.stopped() && sessions.containsKey(id)) {
                    h.wait(waitMillis);
                }
                if (h.session.stopped() || !sessions.containsKey(id)) {
                    return new Frame(afterSeq, null, true);
                }
                return h.frame == null || h.seq == afterSeq ? null : new Frame(h.seq, h.frame, false);
            }
        }

        @Override
        public void viewer(String id, Instant now) {
            Held h = sessions.get(id);
            if (h != null) {
                synchronized (h) {
                    Session s = h.session;
                    h.session = new Session(s.id(), s.organizationId(), s.agentId(), s.cameraId(), s.connectBy(),
                            s.endsAt(), s.stopped(), true, now);
                }
            }
        }

        @Override
        public void putSnapshot(String organizationId, String cameraId, byte[] jpeg, Instant at) {
            snapshots.put(organizationId + "|" + cameraId, new LpLiveService.Snapshot(jpeg, at));
        }

        @Override
        public LpLiveService.Snapshot snapshot(String organizationId, String cameraId) {
            return snapshots.get(organizationId + "|" + cameraId);
        }

        @Override
        public void removeSnapshot(String organizationId, String cameraId) {
            snapshots.remove(organizationId + "|" + cameraId);
        }

        @Override
        public void pruneSnapshots(Instant before) {
            snapshots.values().removeIf(s -> s.at().isBefore(before));
        }
    }
}
