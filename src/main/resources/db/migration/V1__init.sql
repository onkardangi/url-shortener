CREATE TABLE urls
(
    id         BIGSERIAL PRIMARY KEY,
    long_url   TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- We'll add more indexes in later phases. For now, the PK index is what we need
-- because we look up by ID (decoded from the short code).