-- Submissions are immutable once created; there are no update paths.
-- Money is NUMERIC(15,2): the contract allows 13 integer digits and 2 fractional digits.
-- Columns use VARCHAR rather than CHAR: Postgres reports CHAR as bpchar, which fails
-- Hibernate's ddl-auto=validate for String fields.
-- CHECK constraints hold invariants only. Business rules (USD only, revenue > 0,
-- offered limit pairs) live in code because the contract says they may change.
CREATE TABLE submissions (
    id                            VARCHAR(64)   PRIMARY KEY,
    business_name                 VARCHAR(200)  NOT NULL,
    address_line1                 VARCHAR(100)  NOT NULL,
    address_line2                 VARCHAR(100),
    address_city                  VARCHAR(100)  NOT NULL,
    address_region                VARCHAR(2)    NOT NULL,
    address_postal_code           VARCHAR(10)   NOT NULL,
    address_country               VARCHAR(2)    NOT NULL,
    naics_code                    VARCHAR(6)    NOT NULL,
    annual_revenue_amount         NUMERIC(15,2) NOT NULL,
    annual_revenue_currency       VARCHAR(3)    NOT NULL,
    per_occurrence_limit_amount   NUMERIC(15,2) NOT NULL,
    per_occurrence_limit_currency VARCHAR(3)    NOT NULL,
    aggregate_limit_amount        NUMERIC(15,2) NOT NULL,
    aggregate_limit_currency      VARCHAR(3)    NOT NULL,
    effective_date                DATE          NOT NULL,
    created_at                    TIMESTAMPTZ   NOT NULL,

    CONSTRAINT ck_submissions_amounts_non_negative CHECK (
        annual_revenue_amount >= 0
        AND per_occurrence_limit_amount >= 0
        AND aggregate_limit_amount >= 0),
    CONSTRAINT ck_submissions_currency_format CHECK (
        annual_revenue_currency ~ '^[A-Z]{3}$'
        AND per_occurrence_limit_currency ~ '^[A-Z]{3}$'
        AND aggregate_limit_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_submissions_country_format CHECK (address_country ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_submissions_naics_code_format CHECK (naics_code ~ '^[0-9]{6}$')
);
