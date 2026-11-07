-- AI-drafted submissions read from broker documents (see the extraction package).
-- The document itself is not stored: only its name, size and SHA-256, so a draft can be matched
-- to the file it came from without keeping broker documents.
-- The draft is JSON text written and read by the application. It is shown to a reviewer, never
-- queried by field, and its shape can change without a migration.
-- A row is updated once, on confirmation; the CHECK keeps status, submission and time consistent.
CREATE TABLE extractions (
    id                    VARCHAR(64)  PRIMARY KEY,
    status                VARCHAR(20)  NOT NULL,
    document_name         VARCHAR(255) NOT NULL,
    document_size_bytes   INTEGER      NOT NULL,
    document_sha256       VARCHAR(64)  NOT NULL,
    draft_json            TEXT         NOT NULL,
    model                 VARCHAR(100) NOT NULL,
    input_tokens          INTEGER      NOT NULL,
    output_tokens         INTEGER      NOT NULL,
    duration_ms           BIGINT       NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    submission_id         VARCHAR(64)  REFERENCES submissions (id),
    confirmed_at          TIMESTAMPTZ,

    CONSTRAINT ck_extractions_status CHECK (status IN ('DRAFT', 'CONFIRMED')),
    CONSTRAINT ck_extractions_confirmation CHECK (
        (status = 'DRAFT' AND submission_id IS NULL AND confirmed_at IS NULL)
        OR (status = 'CONFIRMED' AND submission_id IS NOT NULL AND confirmed_at IS NOT NULL)),
    CONSTRAINT ck_extractions_sha256_format CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_extractions_counts_non_negative CHECK (
        document_size_bytes >= 0 AND input_tokens >= 0 AND output_tokens >= 0 AND duration_ms >= 0)
);

-- One submission per confirmed draft.
CREATE UNIQUE INDEX uq_extractions_submission_id ON extractions (submission_id);
