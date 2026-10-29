# Quote Service

Brokers submit a small-business general liability risk as a **submission**, then request a
**quote** for it. The API is contract-first: `src/main/resources/openapi/quote-api.yaml` is the
source of truth, and the controllers implement interfaces generated from it.

## Running

- Start Postgres on `localhost:5432` with a `quotes` database (see `application.properties`).
- `./mvnw spring-boot:run` starts the service; Flyway applies the migrations on startup.
- `./mvnw verify` runs unit tests and the `*IT` integration tests. The database tests use
  Testcontainers, so Docker must be running.

## Tests and coverage

### Conventions

All tests live in `src/test/java`; there are no per-layer source folders. The layer is set by
the class name, and the build enforces it: Surefire runs `*Test` during `test`, and Failsafe runs
`*IT` during `integration-test`/`verify` (configured by the Spring Boot parent).

| Layer | Convention | What it's for | Runner | Docker |
|---|---|---|---|---|
| Unit | `*Test`; JUnit 5, AssertJ, Mockito; no Spring context | Business logic and edge cases, exhaustively and fast (`RatingEngineTest`, `SubmissionRulesTest`) | Surefire | No |
| Slice | `*Test` + `@Tag("slice")` + `@WebMvcTest` | The web layer with services mocked: status codes, headers, Problem Details, validation (`ApiWebLayerTest`) | Surefire | No |
| Integration | `*IT`; full context + `TestcontainersConfiguration` | Real wiring against a throwaway Postgres: SQL, migrations, transactions, end to end (`QuoteApiIT`) | Failsafe | Yes |

Slice and integration tests also check every response against `quote-api.yaml`
(`OpenApiContract`). `RatingArchitectureTest` (ArchUnit) fails the build if the `rating`
package, or its tests, depend on Spring, JPA, the database, or the JPA-backed application
packages, so the pricing engine stays plain Java.

Integration tests never touch your local database: Testcontainers starts a fresh
`postgres:17-alpine` container on a random port and points the tests at it.

### Which command runs which tests

| Command | Runs | Docker | Coverage |
|---|---|---|---|
| `./mvnw test` | Unit + slice (Surefire) | No | Unit report, no gate |
| `./mvnw test -DexcludedGroups=slice` | Unit only | No | — |
| `./mvnw test -Dgroups=slice` | Slice only | No | — |
| `./mvnw test -Dtest=RatingEngineTest` | One test class | No | — |
| `./mvnw verify` | Everything: Surefire, then Failsafe | Yes | Both reports and both gates |
| `./mvnw verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=QuoteApiIT` | One integration test class | Yes | Partial: the gates aren't meaningful |

### Coverage reports and gates

JaCoCo writes two reports:

- `target/site/jacoco-unit/index.html`: Surefire tests only (unit + slice).
- `target/site/jacoco-combined/index.html`: Surefire and Failsafe data merged.

`./mvnw verify` fails the build if either rule isn't met:

- **Rating package ≥ 90% line coverage from unit tests alone**, so the pricing logic is proven by
  fast, focused tests.
- **Overall ≥ 80% line coverage on the combined data.**

The OpenAPI-generated code (`com.kowalchuk.quoteservice.api`) is excluded; everything hand-written
counts. Reports are written before the checks, so a failed build still leaves both to inspect.

The gates run only when the build reaches `verify` (`verify`, `install`, `deploy`), not on `test`
or `package`. They are bypassed by `-DskipTests` (no coverage data, so the checks are skipped),
`-Djacoco.skip=true`, and `-DskipITs` (the combined report then has only unit data). CI should run
`./mvnw verify` with none of these.

The JaCoCo agent is attached through the `argLine` property. To add JVM options for tests, write
`<argLine>@{argLine} …</argLine>`; replacing `argLine` silently drops coverage to zero.

## Rating (fictional)

> **The rating formula, rates, factors and rules here are illustrative only. They are not
> actuarially derived and must not be used for real underwriting.**

```
premium = max(annual revenue ÷ 1,000 × class base rate × limit factor, state minimum premium)
```

rounded half-up to cents, once, at the end.

| Class (NAICS) | Base rate per $1,000 | Automatic-quoting ceiling |
|---|---|---|
| 722511 Full-Service Restaurants | 2.1000 | 5,000,000 |
| 722513 Limited-Service Restaurants | 1.8000 | 5,000,000 |
| 238220 Plumbing, Heating, and Air-Conditioning Contractors | 4.5000 | 3,000,000 |
| 541511 Custom Computer Programming Services | 0.6000 | 10,000,000 |
| 812111 Barber Shops | 1.4000 | 1,000,000 |

Limit factors: 1,000,000 / 2,000,000 → 1.0000; 2,000,000 / 4,000,000 → 1.3500.
State minimum premiums: 500.00, except CA 650.00, FL 600.00 and NY 750.00. Minimums vary by
state because rates are filed with, and approved by, each state's regulator.

Outcomes, in order of precedence:

1. **DECLINED** `CLASS_NOT_WRITTEN`: the class has left appetite since the submission was made.
2. **REFERRED**, with every reason that applies: `NO_RATE_FOR_CLASS`, `LIMIT_NOT_RATED` or
   `NO_MINIMUM_FOR_STATE` (no row in effect), or `REVENUE_ABOVE_AUTO_LIMIT` (revenue above the
   class's ceiling). Reason messages state the values compared.
3. **QUOTED** otherwise, with the factors applied (`CLASS`, then `LIMIT`).

Declined and referred quotes are business outcomes, returned with `201`, not errors.

The contract's examples (`TERRITORY` and `REVENUE_BAND` factors, a premium of 2847.50)
illustrate the open set of factor codes; this implementation doesn't produce them.

**Rounding.** In production, rounding rules (mode, precision, and whether any intermediate
rounding applies) come from the filed rating manual. This reference implementation rounds
half-up to cents once, at the end of rating; all intermediate steps are exact, and no other code
path rounds. `RatingEngine` holds the one `HALF_UP`; everything else uses
`RoundingMode.UNNECESSARY`, which fails rather than rounds.

## Which rates apply

Rates are selected by the **policy effective date on the submission, not the date of the
quote**. A rate filing applies to policies effective on or after its date, and a quote can be
requested up to 90 days before cover starts, so a December quote for a January policy uses
January's rates. Quoting the same submission twice gives the same rates whenever you ask.

Base rates, limit factors and state minimums live in effective-dated, append-only tables. The
row in effect is the one with the latest `effective_from` on or before the policy date; for rows
sharing that date, the most recently loaded one (so a correction wins). One shared method,
`EffectiveDatedRepository.findInEffect`, implements this for all three tables.

Rate rows can't be changed from application code: the repositories expose no write methods, the
entities are immutable, and a database trigger rejects `UPDATE` and `DELETE` from any client.

Every quote, whatever its outcome, records:

- the IDs of the rate rows it consulted (`class_rate_id`, `limit_factor_id`,
  `rating_state_id`; null means no row was in effect);
- its rating inputs and lookup keys (`rated_as_of`, `rated_naics_code`, `rated_state_code`,
  the limits and revenue);
- the factors as charged (`quote_rating_factors`), the minimum premium used and whether it
  applied, and `rating_plan_version` (the formula version).

## Which state applies

The rating state, used for the state minimum premium, is the **risk address's state**
(`address.region`). The address is the risk location, not a mailing or head-office address.
Each submission has one location, so multi-state risks are not supported (see Deferred).

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
loaded** when the quote read the rates. This stays true even if a backdated row or a
correction is inserted later.

```sql
SELECT count(*)
FROM quotes q
JOIN class_rates c ON c.naics_code = q.rated_naics_code
                  AND c.effective_from <= q.rated_as_of
                  AND c.loaded_at <= q.rates_read_at
WHERE q.id = :quote_id;   -- 0
```

## Clocks

Database timestamps (`loaded_at`, `rates_read_at`) are only compared with each other;
application timestamps (`created_at`, `expires_at`, the injected `Clock`) are only compared with
each other. Where a quote found a rate row, its stored row ID is the evidence and no timestamp is
compared. `rates_read_at` is set by the database in the same transaction as the rate lookups, and
exists only to prove that *no* row was in effect when a quote had nothing to point at.

This relies on rate rows being loaded by Flyway migrations, which run before the service accepts
requests, so no rate load overlaps a quote.

## Quote expiry

A quote is valid for exactly 30 × 24 hours from `createdAt`, as an instant (`expiresAt`, UTC).
It is not "through the end of the 30th day". Example: created 2026-10-23T14:05:13Z → expires
2026-11-22T14:05:13Z. In Chicago that's 9:05 am CDT on Oct 23 to 8:05 am CST on Nov 22: the
same 720 hours, with the local clock moved for daylight saving. Show `expiresAt` in the viewer's
local time, including the time of day.

A quote is valid only while the current time is **before** `expiresAt`. From that exact instant,
`GET` returns it as `EXPIRED` (still `200`, with the premium kept for the record). The rule lives
in one place, `QuoteValidity`, and the current time always comes from the application's injected
clock, never from the database's `now()`.

**Clients should rely on `status` and `expiresAt` from a fresh response, never a cached copy.**
Quote responses carry `Cache-Control: no-store` because a quote can change from `QUOTED` to
`EXPIRED` with no change to its data.

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

## Deferred

- New business versus renewal rating.
- Rate changes loaded by business users instead of migrations. Loading while the service runs
  breaks the assumption that no rate load overlaps a quote, so loads would need to be published
  atomically (staged, then made visible in one statement with a commit-time `published_at`) to
  keep `loaded_at <= rates_read_at` evidence exact.
- Multi-location / multi-state risks. This needs a contract change (a list of locations, each
  with its share of revenue; likely `/v2`), rating per location with each state's rates, and
  per-location rows in the audit trail. **Open question for underwriting/compliance:** how
  minimum premiums apply across states (a minimum per state on that state's portion, or one
  policy-level minimum such as the highest state's). It shouldn't be decided in code.
- Withdrawing a rate or limit pair (needs an end date or a "withdrawn" row).
- Per-class exposure bases (payroll, square footage) with `exposure_base` / `rate_unit` columns.
- Other per-state values (state factors, taxes and fees).
- Validating `address.region` against real state codes at submission time.
- Idempotency-Key storage, for submissions and quotes.
- **Downstream rounding and reconciliation** (once binding, installments or ledger feeds exist).
  Policy and accounting systems can round the same amount differently, by mode (HALF_UP vs
  HALF_EVEN; platform defaults differ) and, more often, by *when* they round (round-then-sum vs
  sum-then-round, daily vs monthly earning). It shows up as cent-level reconciliation breaks:
  written premium vs ledger, installments that don't sum to the annual premium, commission
  statements, premium tax by state, reinsurance bordereaux. Decisions to make then:
  - the policy system is the system of record for premium; consumers post what they receive and
    never re-rate;
  - every split (installments, state/line allocation, commissions, cessions) uses an allocation
    rule that preserves the total;
  - each interface specification states its rounding mode, precision and rounding step;
  - reconciliation uses tolerances and a rounding variance account.
- **Business time zone** (one decision covering two rules): whether quotes should be valid
  through the end of the 30th calendar day, and whether "today" for the effective-date check
  should be local rather than UTC, and in which zone (a single company zone, or the risk
  location's zone, which needs ZIP-to-zone mapping). Changing expiry later is a behaviour change:
  it alters `expiresAt` values and the contract example.
- Expiry capped at the policy effective date.
- **Quote validity period varying by state or product** → effective-dated, append-only reference
  data, selected by the **quote creation date** (validity governs the offer, while rates govern
  the policy). Until then it's a code constant: one product, no stated variation, and each quote
  stores its `expiresAt`. A longer validity period widens the window in which a quote created
  before a rate change can be bound at the old rate.
- **Quote validity in SQL** (with the first feature that lists or binds quotes). Any query on
  quote validity must be added to `QuoteValidity`, taking `now` from the injected clock, with a
  test proving it agrees with the Java predicate one microsecond before, at and after
  `expiresAt`. Binding must re-check validity atomically (row lock or conditional update).
