-- Rating reference data and quotes. All rates are fictional; see README "Rating (fictional)".
--
-- Rate rows are effective-dated and append-only. A rate change is a new row with a later
-- effective_from; a correction is a new row with the same effective_from that names the row
-- it corrects. Lookups take the latest effective_from on or before the policy effective date,
-- then the latest loaded_at, so a correction supersedes the row it corrects.
--
-- loaded_at is set by the database clock. Compare it only with other database timestamps
-- (quotes.rates_read_at), never with application timestamps such as quotes.created_at.

CREATE TABLE class_rates (
    id                      BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    naics_code              VARCHAR(6)    NOT NULL REFERENCES naics_classes (code),
    effective_from          DATE          NOT NULL,
    base_rate_per_thousand  NUMERIC(10,4) NOT NULL,
    max_auto_revenue        NUMERIC(15,2) NOT NULL,
    corrects_id             BIGINT,
    correction_reason       VARCHAR(500),
    loaded_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_class_rates_class_date_load UNIQUE (naics_code, effective_from, loaded_at),
    CONSTRAINT uq_class_rates_id_key UNIQUE (id, naics_code, effective_from),
    -- A correction must correct a row with the same class and effective date.
    CONSTRAINT fk_class_rates_corrects FOREIGN KEY (corrects_id, naics_code, effective_from)
        REFERENCES class_rates (id, naics_code, effective_from),
    CONSTRAINT ck_class_rates_correction_reason CHECK ((corrects_id IS NULL) = (correction_reason IS NULL)),
    CONSTRAINT ck_class_rates_positive CHECK (base_rate_per_thousand > 0 AND max_auto_revenue > 0)
);

-- The factor bound matches the contract's RatingFactor.factor pattern (at most 4 integer digits).
CREATE TABLE limit_factors (
    id                   BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    per_occurrence_limit NUMERIC(15,2) NOT NULL,
    aggregate_limit      NUMERIC(15,2) NOT NULL,
    effective_from       DATE          NOT NULL,
    factor               NUMERIC(10,4) NOT NULL,
    corrects_id          BIGINT,
    correction_reason    VARCHAR(500),
    loaded_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_limit_factors_pair_date_load
        UNIQUE (per_occurrence_limit, aggregate_limit, effective_from, loaded_at),
    CONSTRAINT uq_limit_factors_id_key UNIQUE (id, per_occurrence_limit, aggregate_limit, effective_from),
    CONSTRAINT fk_limit_factors_corrects
        FOREIGN KEY (corrects_id, per_occurrence_limit, aggregate_limit, effective_from)
        REFERENCES limit_factors (id, per_occurrence_limit, aggregate_limit, effective_from),
    CONSTRAINT ck_limit_factors_correction_reason CHECK ((corrects_id IS NULL) = (correction_reason IS NULL)),
    CONSTRAINT ck_limit_factors_factor CHECK (factor > 0 AND factor < 10000),
    CONSTRAINT ck_limit_factors_aggregate CHECK (aggregate_limit >= per_occurrence_limit)
);

-- Per-state values filed with each state's regulator; for now, the minimum premium.
-- Every state is seeded: this table does not decide which states we write.
CREATE TABLE rating_states (
    id                BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    state_code        VARCHAR(2)    NOT NULL,
    effective_from    DATE          NOT NULL,
    minimum_premium   NUMERIC(15,2) NOT NULL,
    corrects_id       BIGINT,
    correction_reason VARCHAR(500),
    loaded_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uq_rating_states_state_date_load UNIQUE (state_code, effective_from, loaded_at),
    CONSTRAINT uq_rating_states_id_key UNIQUE (id, state_code, effective_from),
    CONSTRAINT fk_rating_states_corrects FOREIGN KEY (corrects_id, state_code, effective_from)
        REFERENCES rating_states (id, state_code, effective_from),
    CONSTRAINT ck_rating_states_correction_reason CHECK ((corrects_id IS NULL) = (correction_reason IS NULL)),
    CONSTRAINT ck_rating_states_code_format CHECK (state_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_rating_states_minimum_positive CHECK (minimum_premium > 0)
);

-- Quotes reference the rate rows they used, so those rows must never change.
CREATE FUNCTION reject_rate_row_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; add a new row (a rate change or a correction) instead', TG_TABLE_NAME;
END $$;

CREATE TRIGGER trg_class_rates_append_only BEFORE UPDATE OR DELETE ON class_rates
    FOR EACH ROW EXECUTE FUNCTION reject_rate_row_change();
CREATE TRIGGER trg_limit_factors_append_only BEFORE UPDATE OR DELETE ON limit_factors
    FOR EACH ROW EXECUTE FUNCTION reject_rate_row_change();
CREATE TRIGGER trg_rating_states_append_only BEFORE UPDATE OR DELETE ON rating_states
    FOR EACH ROW EXECUTE FUNCTION reject_rate_row_change();

-- Fictional rates. 213111 is out of appetite and has no rate.
INSERT INTO class_rates (naics_code, effective_from, base_rate_per_thousand, max_auto_revenue) VALUES
    ('722511', DATE '2026-01-01', 2.1000,  5000000.00),
    ('722513', DATE '2026-01-01', 1.8000,  5000000.00),
    ('238220', DATE '2026-01-01', 4.5000,  3000000.00),
    ('541511', DATE '2026-01-01', 0.6000, 10000000.00),
    ('812111', DATE '2026-01-01', 1.4000,  1000000.00);

INSERT INTO limit_factors (per_occurrence_limit, aggregate_limit, effective_from, factor) VALUES
    (1000000.00, 2000000.00, DATE '2026-01-01', 1.0000),
    (2000000.00, 4000000.00, DATE '2026-01-01', 1.3500);

INSERT INTO rating_states (state_code, effective_from, minimum_premium)
SELECT state_code, DATE '2026-01-01',
       CASE state_code WHEN 'CA' THEN 650.00 WHEN 'FL' THEN 600.00 WHEN 'NY' THEN 750.00 ELSE 500.00 END
FROM unnest(ARRAY[
    'AL', 'AK', 'AZ', 'AR', 'CA', 'CO', 'CT', 'DE', 'DC', 'FL', 'GA', 'HI', 'ID', 'IL', 'IN', 'IA', 'KS',
    'KY', 'LA', 'ME', 'MD', 'MA', 'MI', 'MN', 'MS', 'MO', 'MT', 'NE', 'NV', 'NH', 'NJ', 'NM', 'NY', 'NC',
    'ND', 'OH', 'OK', 'OR', 'PA', 'RI', 'SC', 'SD', 'TN', 'TX', 'UT', 'VT', 'VA', 'WA', 'WV', 'WI', 'WY'
]) AS states (state_code);

-- Quotes are immutable. EXPIRED is never stored: it is derived at read time from expires_at.
-- Every quote, whatever its outcome, records its rating inputs and the rate rows it consulted;
-- a null row ID means no row was in effect for that lookup.
CREATE TABLE quotes (
    id                         VARCHAR(64)   PRIMARY KEY,
    submission_id              VARCHAR(64)   NOT NULL REFERENCES submissions (id),
    status                     VARCHAR(16)   NOT NULL,
    rated_as_of                DATE          NOT NULL,
    rated_naics_code           VARCHAR(6)    NOT NULL,
    rated_state_code           VARCHAR(2)    NOT NULL,
    rated_per_occurrence_limit NUMERIC(15,2) NOT NULL,
    rated_aggregate_limit      NUMERIC(15,2) NOT NULL,
    rated_annual_revenue       NUMERIC(15,2) NOT NULL,
    class_rate_id              BIGINT        REFERENCES class_rates (id),
    limit_factor_id            BIGINT        REFERENCES limit_factors (id),
    rating_state_id            BIGINT        REFERENCES rating_states (id),
    premium_amount             NUMERIC(15,2),
    premium_currency           VARCHAR(3),
    minimum_premium            NUMERIC(15,2),
    minimum_premium_applied    BOOLEAN,
    rating_plan_version        VARCHAR(32)   NOT NULL,
    -- Application clock.
    created_at                 TIMESTAMPTZ   NOT NULL,
    -- Database clock, in the same transaction as the rate lookups. Compare only with loaded_at.
    rates_read_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at                 TIMESTAMPTZ,

    CONSTRAINT ck_quotes_status CHECK (status IN ('QUOTED', 'REFERRED', 'DECLINED')),
    CONSTRAINT ck_quotes_priced_iff_quoted CHECK (
        (status = 'QUOTED') = (premium_amount IS NOT NULL AND premium_currency IS NOT NULL
            AND minimum_premium IS NOT NULL AND minimum_premium_applied IS NOT NULL
            AND expires_at IS NOT NULL)),
    CONSTRAINT ck_quotes_quoted_has_rate_rows CHECK (
        status <> 'QUOTED'
        OR (class_rate_id IS NOT NULL AND limit_factor_id IS NOT NULL AND rating_state_id IS NOT NULL)),
    CONSTRAINT ck_quotes_expiry_after_creation CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE INDEX ix_quotes_submission_id ON quotes (submission_id);
CREATE INDEX ix_quotes_class_rate_id ON quotes (class_rate_id);
CREATE INDEX ix_quotes_limit_factor_id ON quotes (limit_factor_id);
CREATE INDEX ix_quotes_rating_state_id ON quotes (rating_state_id);

-- The factors as charged, in the order applied.
CREATE TABLE quote_rating_factors (
    quote_id    VARCHAR(64)   NOT NULL REFERENCES quotes (id),
    position    INTEGER       NOT NULL,
    code        VARCHAR(32)   NOT NULL,
    description VARCHAR(200)  NOT NULL,
    input_value VARCHAR(100)  NOT NULL,
    factor      NUMERIC(10,4) NOT NULL,
    PRIMARY KEY (quote_id, position)
);

CREATE TABLE quote_status_reasons (
    quote_id VARCHAR(64)  NOT NULL REFERENCES quotes (id),
    position INTEGER      NOT NULL,
    code     VARCHAR(64)  NOT NULL,
    message  VARCHAR(500) NOT NULL,
    PRIMARY KEY (quote_id, position)
);
