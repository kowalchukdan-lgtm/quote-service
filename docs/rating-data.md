# Rating data: how it works and how to change it

Operational detail for the rating reference data. The [README](../README.md) has the summary.
All rates are fictional.

## Which rates apply

Rates are selected by the **policy effective date on the submission, not the date of the
quote**. A rate filing applies to policies effective on or after its date, and a quote can be
requested up to 90 days before cover starts, so a December quote for a January policy uses
January's rates. Quoting the same submission twice gives the same rates whenever you ask.

Base rates (`class_rates`), limit factors (`limit_factors`) and state minimums (`rating_states`)
live in effective-dated, append-only tables. The row in effect is the one with the latest
`effective_from` on or before the policy date; for rows sharing that date, the most recently
loaded one (so a correction wins). One shared method, `EffectiveDatedRepository.findInEffect`,
implements this for all three tables.

Rate rows can't be changed from application code: the repositories expose no write methods, the
entities are immutable, and a database trigger rejects `UPDATE` and `DELETE` from any client.

Every quote, whatever its outcome, records:

- the IDs of the rate rows it consulted (`class_rate_id`, `limit_factor_id`,
  `rating_state_id`; null means no row was in effect);
- its rating inputs and lookup keys (`rated_as_of`, `rated_naics_code`, `rated_state_code`,
  the limits and revenue);
- the factors as charged (`quote_rating_factors`), the minimum premium used and whether it
  applied, and `rating_plan_version` (the formula version).

The rating state is the **risk address's state** (`address.region`): the address is the risk
location, not a mailing or head-office address. Each submission has one location.

## Clocks

Database timestamps (`loaded_at`, `rates_read_at`) are only compared with each other;
application timestamps (`created_at`, `expires_at`, the injected `Clock`) are only compared with
each other. Where a quote found a rate row, its stored row ID is the evidence and no timestamp is
compared. `rates_read_at` is set by the database in the same transaction as the rate lookups, and
exists only to prove that *no* row was in effect when a quote had nothing to point at.

This relies on rate rows being loaded by Flyway migrations, which run before the service accepts
requests, so no rate load overlaps a quote.

## Explaining a quote

Each outcome can be explained from the quote and the rows it references.

Priced: recompute the premium.

```sql
SELECT q.premium_amount,
       round(greatest(q.rated_annual_revenue / 1000 * c.base_rate_per_thousand * l.factor,
                      q.minimum_premium), 2) AS recomputed
FROM quotes q
JOIN class_rates c   ON c.id = q.class_rate_id
JOIN limit_factors l ON l.id = q.limit_factor_id
WHERE q.id = :quote_id;
```

Referred above the ceiling: the stored revenue exceeds the referenced row's ceiling.

```sql
SELECT q.rated_annual_revenue, c.max_auto_revenue
FROM quotes q JOIN class_rates c ON c.id = q.class_rate_id
WHERE q.id = :quote_id;
```

No rate in effect: no row for the stored class was in effect on the rating date **and already
loaded** when the quote read the rates. This stays true even if a backdated row or a correction
is inserted later.

```sql
SELECT count(*)
FROM quotes q
JOIN class_rates c ON c.naics_code = q.rated_naics_code
                  AND c.effective_from <= q.rated_as_of
                  AND c.loaded_at <= q.rates_read_at
WHERE q.id = :quote_id;   -- 0
```

## Changing a rate

Add a Flyway migration that inserts new rows dated for the filing, and deploy it as soon as the
filing is approved. Any time before the effective date works; future-dated rows have no effect on
earlier policies. Add rows only for values that change.

```sql
-- V4__q1_2027_rate_filing.sql (fictional)
-- Applies to policies effective on or after 2027-01-01. Earlier policies keep the 2026 rows.
INSERT INTO class_rates (naics_code, effective_from, base_rate_per_thousand, max_auto_revenue)
VALUES ('722511', DATE '2027-01-01', 2.2500, 5000000.00);

INSERT INTO rating_states (state_code, effective_from, minimum_premium)
VALUES ('NY', DATE '2027-01-01', 800.00);
```

- **Timing (a suggestion, not a requirement):** where the filing timeline allows, load rows
  about 90 days before their effective date, which matches how far ahead submissions can start
  (`SubmissionRules.MAX_DAYS_AHEAD`). Then every policy on or after that date is quoted on the
  new rate. State approvals often arrive later than that, so later loading is normal and
  supported.
- **When rows are loaded later:** quotes created before the load, for policies effective on or
  after the new date, were priced on the previous row. They stay valid records of what was
  offered; whether to re-quote them is a business decision. This query lists them using row IDs
  only, with no timestamps: if the new row had existed, the lookup would have chosen it.

  ```sql
  SELECT q.id, q.status, q.premium_amount
  FROM quotes q
  JOIN class_rates new_row ON new_row.id = :new_row_id
  WHERE q.rated_naics_code = new_row.naics_code
    AND q.rated_as_of >= new_row.effective_from
    AND q.class_rate_id = :superseded_row_id;
  ```

Never edit a merged migration: Flyway checksums applied migrations, and an edited one is never
re-run on databases that already applied it. Fix forward with a new version.

## Correcting a rate

When a rate row is wrong (it doesn't match the filing), insert a **correction row with the same
key and `effective_from`**, with `corrects_id` and a `correction_reason`.

- Don't use a later-dated row: that leaves policies between the two dates on the wrong rate.
- Don't update the row: quotes that used it would point at values they weren't priced on.

```sql
-- V4__correct_722511_base_rate.sql (fictional)
-- Correction: NAICS 722511 base rate from 2026-01-01 was loaded as 2.1000 in V3;
-- the approved filing (ref <filing-id>) says 2.0100. Ceiling unchanged.
INSERT INTO class_rates (naics_code, effective_from, base_rate_per_thousand, max_auto_revenue,
                         corrects_id, correction_reason)
SELECT naics_code, effective_from, 2.0100, max_auto_revenue,
       id, 'Data-entry error in V3: filed base rate is 2.0100 (filing <filing-id>)'
FROM class_rates
WHERE naics_code = '722511' AND effective_from = DATE '2026-01-01'
ORDER BY loaded_at DESC
LIMIT 1;   -- the row currently in effect for that date (original or an earlier correction)
```

The database only allows a correction of a row with the same key and effective date, and
requires a reason. After the migration runs, new quotes for every affected policy date use the
corrected row. Quotes already priced keep pointing at the original row and keep their premium,
as accurate records of what was offered.

List the affected quotes, across every outcome (a wrong ceiling can wrongly refer some risks and
wrongly quote others):

```sql
SELECT q.id, q.status, q.premium_amount, q.created_at
FROM quotes q
WHERE q.class_rate_id = (SELECT corrects_id FROM class_rates WHERE id = :correction_id);
```

What to do with them (re-quote, contact brokers, or let unbound quotes expire) is a business and
compliance decision: quoting a rate other than the filed one can be a regulatory matter, so the
code doesn't decide. Once binding exists, corrections affecting bound policies will need their
own process.
