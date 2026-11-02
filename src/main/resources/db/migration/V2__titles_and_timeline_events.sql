CREATE TABLE titles (
    id VARCHAR(200) PRIMARY KEY
);

CREATE TABLE timeline_events (
    event_id VARCHAR(200) PRIMARY KEY,
    title_id VARCHAR(200) NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
    timestamp_seconds DOUBLE PRECISION NOT NULL CHECK (timestamp_seconds >= 0),
    type VARCHAR(32) NOT NULL,
    event_text TEXT NOT NULL,
    source_label VARCHAR(200) NOT NULL
);

CREATE INDEX idx_timeline_events_title_timestamp
    ON timeline_events (title_id, timestamp_seconds, event_id);

INSERT INTO titles (id) VALUES ('demo-title');

INSERT INTO timeline_events
    (event_id, title_id, timestamp_seconds, type, event_text, source_label)
VALUES
    (
        'demo-title-opening-scene',
        'demo-title',
        12.0,
        'scene',
        'Maya enters Harbor Station after receiving an anonymous message.',
        'Opening scene'
    ),
    (
        'demo-title-rios-introduction',
        'demo-title',
        22.0,
        'character',
        'Detective Rios walks into Harbor Station; he is Maya''s former partner.',
        'Character introduction'
    ),
    (
        'demo-title-clock-trivia',
        'demo-title',
        45.0,
        'trivia',
        'The Harbor Station clock stopped at 9:17 during the blackout.',
        'Production trivia'
    ),
    (
        'demo-title-blackout-glossary',
        'demo-title',
        75.0,
        'glossary',
        'The blackout refers to the citywide power failure that hid the evidence.',
        'Story glossary'
    );
