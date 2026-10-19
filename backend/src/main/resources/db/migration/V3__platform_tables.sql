-- V3: tables owned by the Spring platform (not present in POS-CAFE).

-- ShedLock: one scheduled-job runner across instances (replaces the Nest claim-token crons
-- where a simple lock is enough).
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

-- Spring Modulith JDBC event publication registry (transactional outbox for module events).
CREATE TABLE IF NOT EXISTS event_publication (
    id                     UUID         NOT NULL PRIMARY KEY,
    listener_id            TEXT         NOT NULL,
    event_type             TEXT         NOT NULL,
    serialized_event       TEXT         NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
    ON event_publication USING hash (serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx
    ON event_publication (completion_date);

CREATE TABLE IF NOT EXISTS event_publication_archive (
    id                     UUID         NOT NULL PRIMARY KEY,
    listener_id            TEXT         NOT NULL,
    event_type             TEXT         NOT NULL,
    serialized_event       TEXT         NOT NULL,
    publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
    completion_date        TIMESTAMP WITH TIME ZONE,
    status                 TEXT,
    completion_attempts    INT,
    last_resubmission_date TIMESTAMP WITH TIME ZONE
);
