-- 2022 NAICS classes we know about. in_appetite marks the classes we write;
-- a well-formed code that is absent or out of appetite is rejected with 422.
-- Titles are resolved at read time, so submissions store only the code.
CREATE TABLE naics_classes (
    code        VARCHAR(6)   PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    in_appetite BOOLEAN      NOT NULL,

    CONSTRAINT ck_naics_classes_code_format CHECK (code ~ '^[0-9]{6}$')
);

INSERT INTO naics_classes (code, title, in_appetite) VALUES
    ('722511', 'Full-Service Restaurants', TRUE),
    ('722513', 'Limited-Service Restaurants', TRUE),
    ('238220', 'Plumbing, Heating, and Air-Conditioning Contractors', TRUE),
    ('541511', 'Custom Computer Programming Services', TRUE),
    ('812111', 'Barber Shops', TRUE),
    ('213111', 'Drilling Oil and Gas Wells', FALSE);

ALTER TABLE submissions
    ADD CONSTRAINT fk_submissions_naics_class
    FOREIGN KEY (naics_code) REFERENCES naics_classes (code);
