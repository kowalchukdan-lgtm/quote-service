package com.kowalchuk.quoteservice.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.kowalchuk.quoteservice.OpenApiContract;
import com.kowalchuk.quoteservice.TestcontainersConfiguration;
import com.kowalchuk.quoteservice.reference.ClassRateRepository;
import com.kowalchuk.quoteservice.reference.EffectiveDatedRow;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end quote tests against a real Postgres. Every response is also checked against
 * {@code quote-api.yaml}, including required headers such as {@code Cache-Control}.
 * <p>
 * Tests that need their own rates use test-only NAICS classes, so the seed data and other
 * tests are untouched. Those rows cannot be cleaned up (rate rows are append-only), which is
 * fine for a disposable container.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class QuoteApiIT {

	private static final String SUBMISSIONS = "/v1/submissions";

	private static final String QUOTES = "/v1/submissions/{submissionId}/quotes";

	private static final String QUOTE = "/v1/quotes/{quoteId}";

	private static final String PROBLEM_BASE = "https://api.example.com/problems/";

	private static final OpenApiContract contract = OpenApiContract.load();

	private static final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ClassRateRepository classRates;

	@Test
	void createsAQuoteAndReadsItBack() throws Exception {
		MvcResult created = createQuote(submit(request -> {
		}));

		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		assertNoStore(created);
		String location = created.getResponse().getHeader(HttpHeaders.LOCATION);
		assertThat(location).matches("^/v1/quotes/qte_[0-9A-HJKMNP-TV-Z]{26}$");
		JsonNode quote = body(created);
		assertThat("/v1/quotes/" + quote.get("id").asString()).isEqualTo(location);
		assertThat(quote.get("status").asString()).isEqualTo("QUOTED");
		// 1,200,000 / 1,000 × 2.1000 × 1.0000
		assertThat(quote.at("/premium/amount").asString()).isEqualTo("2520.00");
		assertThat(quote.at("/premium/currency").asString()).isEqualTo("USD");
		assertThat(factors(quote)).containsExactly("CLASS 722511 2.1000", "LIMIT 1000000/2000000 1.0000");
		assertThat(quote.get("statusReasons")).isEmpty();
		assertThat(Duration.between(OffsetDateTime.parse(quote.get("createdAt").asString()),
				OffsetDateTime.parse(quote.get("expiresAt").asString())))
			.isEqualTo(Duration.ofHours(720));

		MvcResult fetched = perform(get(location), "GET", QUOTE);
		assertThat(fetched.getResponse().getStatus()).isEqualTo(200);
		assertNoStore(fetched);
		assertThat(body(fetched)).as("GET returns exactly what POST returned").isEqualTo(quote);
	}

	@Test
	void bothOfferedLimitPairsAreQuoted() throws Exception {
		JsonNode quote = quoted(submit(request -> limits(request, "2000000.00", "4000000.00")));

		assertThat(quote.get("status").asString()).isEqualTo("QUOTED");
		// 1,200,000 / 1,000 × 2.1000 × 1.3500
		assertThat(quote.at("/premium/amount").asString()).isEqualTo("3402.00");
		assertThat(factors(quote)).containsExactly("CLASS 722511 2.1000", "LIMIT 2000000/4000000 1.3500");
	}

	@Test
	void stateMinimumFollowsTheRiskAddress() throws Exception {
		Consumer<ObjectNode> smallBarberShop = request -> {
			naics(request, "812111");
			revenue(request, "150000.00");
		};
		JsonNode texas = quoted(submit(smallBarberShop));
		JsonNode newYork = quoted(submit(smallBarberShop.andThen(request -> address(request, "New York", "NY", "10001"))));

		// 150,000 / 1,000 × 1.4000 = 210.00, below both states' minimums.
		assertThat(texas.at("/premium/amount").asString()).isEqualTo("500.00");
		assertThat(newYork.at("/premium/amount").asString()).isEqualTo("750.00");
		assertThat(quoteRow(texas)).containsEntry("minimum_premium", new BigDecimal("500.00"))
			.containsEntry("minimum_premium_applied", true)
			.containsEntry("rated_state_code", "TX");
		assertThat(quoteRow(newYork)).containsEntry("minimum_premium", new BigDecimal("750.00"))
			.containsEntry("rated_state_code", "NY");
		assertThat(stateOfRow(quoteRow(newYork).get("rating_state_id"))).isEqualTo("NY");
	}

	@Test
	void unknownStateIsReferred() throws Exception {
		JsonNode quote = quoted(submit(request -> address(request, "Nowhere", "ZZ", "00000")));

		assertThat(quote.get("status").asString()).isEqualTo("REFERRED");
		assertThat(reasonCodes(quote)).containsExactly("NO_MINIMUM_FOR_STATE");
		assertThat(quoteRow(quote).get("rating_state_id")).isNull();
	}

	@Test
	void everyStateHasAMinimumInEffectToday() {
		Integer states = jdbc.queryForObject(
				"SELECT count(DISTINCT state_code) FROM rating_states WHERE effective_from <= ?", Integer.class,
				today());

		assertThat(states).isEqualTo(51);
	}

	@Test
	void classThatLeftAppetiteIsDeclinedButStillRecordsTheRatesItConsulted() throws Exception {
		testClass("999101");
		long rate = classRate("999101", today().minusYears(1), "1.0000", "5000000.00", null);
		String submissionId = submit(request -> naics(request, "999101"));
		leaveAppetite("999101");

		MvcResult created = createQuote(submissionId);
		JsonNode quote = body(created);

		assertThat(created.getResponse().getStatus()).isEqualTo(201);
		assertNoStore(created);
		assertThat(quote.get("status").asString()).isEqualTo("DECLINED");
		assertThat(reasonCodes(quote)).containsExactly("CLASS_NOT_WRITTEN");
		assertThat(quote.has("premium")).isFalse();
		assertThat(quote.has("expiresAt")).isFalse();
		assertNoStore(perform(get(QUOTE, quote.get("id").asString()), "GET", QUOTE));

		// All three rate rows consulted are recorded, and each matches the stored lookup keys.
		Map<String, Object> row = quoteRow(quote);
		assertThat(row).containsEntry("class_rate_id", rate);
		assertThat(row.get("limit_factor_id")).isNotNull();
		assertThat(row.get("rating_state_id")).isNotNull();
		assertThat(jdbc.queryForMap("SELECT per_occurrence_limit, aggregate_limit FROM limit_factors WHERE id = ?",
				row.get("limit_factor_id")))
			.containsEntry("per_occurrence_limit", row.get("rated_per_occurrence_limit"))
			.containsEntry("aggregate_limit", row.get("rated_aggregate_limit"));
		assertThat(stateOfRow(row.get("rating_state_id"))).isEqualTo(row.get("rated_state_code"));
	}

	/**
	 * The database's CHECK constraints are the last line of defence for the quote invariants.
	 * Each case changes one field of a valid row; the baseline is accepted.
	 */
	@ParameterizedTest(name = "{0}")
	@CsvSource({ "QUOTED without a premium, ck_quotes_priced_iff_quoted",
			"REFERRED with a premium, ck_quotes_priced_iff_quoted",
			"QUOTED without a class rate row, ck_quotes_quoted_has_rate_rows",
			"EXPIRED stored, ck_quotes_status",
			"expiry equal to creation, ck_quotes_expiry_after_creation" })
	void databaseRejectsInvalidQuotes(String invalidRow, String constraint) throws Exception {
		String submissionId = submit(request -> {
		});

		assertThat(insertQuote(submissionId, "valid baseline")).isEqualTo(1);
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> insertQuote(submissionId, invalidRow))
			.withMessageContaining(constraint);
	}

	@Test
	void ratesAreSelectedByPolicyEffectiveDateNotQuoteDate() throws Exception {
		LocalDate change = today().plusDays(30);
		testClass("999102");
		long before = classRate("999102", today().minusYears(1), "1.0000", "5000000.00", null);
		long after = classRate("999102", change, "2.0000", "5000000.00", null);
		Consumer<ObjectNode> risk = request -> {
			naics(request, "999102");
			revenue(request, "1000000.00");
		};

		// All three are quoted today.
		JsonNode dayBefore = quoted(submit(risk.andThen(request -> effective(request, change.minusDays(1)))));
		JsonNode dayOf = quoted(submit(risk.andThen(request -> effective(request, change))));
		JsonNode dayAfter = quoted(submit(risk.andThen(request -> effective(request, change.plusDays(1)))));

		assertThat(List.of(dayBefore, dayOf, dayAfter)).extracting(quote -> quote.at("/premium/amount").asString())
			.containsExactly("1000.00", "2000.00", "2000.00");
		assertThat(List.of(dayBefore, dayOf, dayAfter)).extracting(quote -> quoteRow(quote).get("class_rate_id"))
			.containsExactly(before, after, after);
		assertThat(quoteRow(dayOf).get("rated_as_of").toString()).isEqualTo(change.toString());
	}

	/** Reads only the stored quote, its reasons and the rows it references. */
	@Test
	void referralIsExplainableFromTheStoredQuoteAlone() throws Exception {
		JsonNode quote = quoted(submit(request -> revenue(request, "6000000.00")));
		assertThat(quote.get("status").asString()).isEqualTo("REFERRED");
		assertThat(quote.get("ratingFactors")).isEmpty();

		Map<String, Object> row = quoteRow(quote);
		assertThat(row.get("class_rate_id")).isNotNull();
		assertThat(row.get("limit_factor_id")).isNotNull();
		assertThat(row.get("rating_state_id")).isNotNull();
		BigDecimal ceiling = jdbc.queryForObject("SELECT max_auto_revenue FROM class_rates WHERE id = ?",
				BigDecimal.class, row.get("class_rate_id"));
		assertThat(ceiling).isEqualByComparingTo("5000000.00");
		assertThat((BigDecimal) row.get("rated_annual_revenue")).isGreaterThan(ceiling);
		assertThat(storedReasons(quote)).containsExactly("REVENUE_ABOVE_AUTO_LIMIT: Annual revenue 6,000,000.00 "
				+ "exceeds the automatic quoting limit of 5,000,000.00 for NAICS 722511.");
	}

	@Test
	void noRateInEffectIsReferredAndTheAbsenceStaysProvable() throws Exception {
		LocalDate policyDate = today().plusDays(30);
		testClass("999103");
		classRate("999103", policyDate.plusDays(30), "1.0000", "5000000.00", null);

		JsonNode quote = quoted(submit(request -> {
			naics(request, "999103");
			effective(request, policyDate);
		}));

		assertThat(quote.get("status").asString()).isEqualTo("REFERRED");
		assertThat(reasonCodes(quote)).containsExactly("NO_RATE_FOR_CLASS");
		assertThat(quote.has("premium")).isFalse();
		assertThat(quote.get("ratingFactors")).isEmpty();
		Map<String, Object> row = quoteRow(quote);
		assertThat(row.get("class_rate_id")).isNull();
		assertThat(row).containsEntry("rated_naics_code", "999103");
		assertThat(ratesKnownWhenQuoted(quote)).isZero();

		// A backdated row inserted later covers the policy date...
		classRate("999103", today().minusYears(1), "1.0000", "5000000.00", null);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM class_rates WHERE naics_code = ? AND effective_from <= ?",
				Integer.class, "999103", policyDate))
			.isEqualTo(1);
		// ...but it was loaded after the quote read the rates, so the evidence is unchanged.
		assertThat(ratesKnownWhenQuoted(quote)).isZero();
	}

	@Test
	void noRateInEffectAndOutOfAppetiteIsDeclined() throws Exception {
		LocalDate policyDate = today().plusDays(30);
		testClass("999104");
		classRate("999104", policyDate.plusDays(30), "1.0000", "5000000.00", null);
		String submissionId = submit(request -> {
			naics(request, "999104");
			effective(request, policyDate);
		});
		leaveAppetite("999104");

		JsonNode quote = quoted(submissionId);

		assertThat(quote.get("status").asString()).isEqualTo("DECLINED");
		assertThat(reasonCodes(quote)).containsExactly("CLASS_NOT_WRITTEN");
		assertThat(quoteRow(quote).get("class_rate_id")).isNull();
	}

	@Test
	void referralStillTracesToTheOldRowAfterTheCeilingIsRaised() throws Exception {
		testClass("999105");
		long oldRow = classRate("999105", today().minusYears(1), "1.0000", "5000000.00", null);
		String submissionId = submit(request -> {
			naics(request, "999105");
			revenue(request, "6000000.00");
		});
		JsonNode referred = quoted(submissionId);
		assertThat(referred.get("status").asString()).isEqualTo("REFERRED");
		assertThat(quoteRow(referred)).containsEntry("class_rate_id", oldRow);

		// The only way to raise it: a new row covering the same policy date.
		long newRow = classRate("999105", today(), "1.0000", "10000000.00", null);

		Map<String, Object> row = quoteRow(referred);
		assertThat(row).containsEntry("class_rate_id", oldRow).containsEntry("status", "REFERRED");
		assertThat(storedReasons(referred)).singleElement().asString().contains("5,000,000.00");
		assertThat(jdbc.queryForObject("SELECT max_auto_revenue FROM class_rates WHERE id = ?", BigDecimal.class,
				oldRow))
			.isEqualByComparingTo("5000000.00");
		// Today's lookup for the same stored inputs finds the new row, so it was added after the quote.
		assertThat(classRates.findInEffect((String) row.get("rated_naics_code"), localDate(row.get("rated_as_of")))
			.map(EffectiveDatedRow::getId)).contains(newRow);

		JsonNode requoted = quoted(submissionId);
		assertThat(requoted.get("status").asString()).isEqualTo("QUOTED");
		assertThat(quoteRow(requoted)).containsEntry("class_rate_id", newRow);

		assertThatExceptionOfType(DataAccessException.class)
			.isThrownBy(() -> jdbc.update("UPDATE class_rates SET max_auto_revenue = 1 WHERE id = ?", oldRow))
			.withMessageContaining("append-only");
	}

	@Test
	void correctionChangesNewQuotesButEarlierQuotesKeepTheOriginalRow() throws Exception {
		LocalDate filed = today().minusYears(1);
		testClass("999106");
		long original = classRate("999106", filed, "1.0000", "5000000.00", null);
		String submissionId = submit(request -> {
			naics(request, "999106");
			revenue(request, "1000000.00");
		});
		JsonNode first = quoted(submissionId);
		assertThat(first.at("/premium/amount").asString()).isEqualTo("1000.00");

		long correction = classRate("999106", filed, "1.2000", "5000000.00", original);

		JsonNode second = quoted(submissionId);
		assertThat(second.at("/premium/amount").asString()).isEqualTo("1200.00");
		assertThat(factors(second)).first().isEqualTo("CLASS 999106 1.2000");
		assertThat(quoteRow(second)).containsEntry("class_rate_id", correction);

		JsonNode firstAgain = body(perform(get(QUOTE, first.get("id").asString()), "GET", QUOTE));
		assertThat(firstAgain.at("/premium/amount").asString()).isEqualTo("1000.00");
		assertThat(factors(firstAgain)).first().isEqualTo("CLASS 999106 1.0000");
		assertThat(quoteRow(first)).containsEntry("class_rate_id", original);
		assertThat(jdbc.queryForObject("SELECT base_rate_per_thousand FROM class_rates WHERE id = ?",
				BigDecimal.class, original))
			.isEqualByComparingTo("1.0000");

		// The README's affected-quotes query.
		List<String> affected = jdbc.queryForList("SELECT q.id FROM quotes q WHERE q.class_rate_id = "
				+ "(SELECT corrects_id FROM class_rates WHERE id = ?)", String.class, correction);
		assertThat(affected).containsExactly(first.get("id").asString());
	}

	@Test
	void pricedQuoteIsReproducibleFromTheRowsItReferences() throws Exception {
		JsonNode quote = quoted(submit(request -> revenue(request, "1234567.89")));

		Map<String, Object> row = jdbc.queryForMap("SELECT q.premium_amount, q.rated_annual_revenue, "
				+ "q.minimum_premium, c.base_rate_per_thousand, l.factor, s.minimum_premium AS state_minimum "
				+ "FROM quotes q JOIN class_rates c ON c.id = q.class_rate_id "
				+ "JOIN limit_factors l ON l.id = q.limit_factor_id JOIN rating_states s ON s.id = q.rating_state_id "
				+ "WHERE q.id = ?", quote.get("id").asString());
		BigDecimal recomputed = ((BigDecimal) row.get("rated_annual_revenue")).movePointLeft(3)
			.multiply((BigDecimal) row.get("base_rate_per_thousand"))
			.multiply((BigDecimal) row.get("factor"))
			.max((BigDecimal) row.get("minimum_premium"))
			.setScale(2, RoundingMode.HALF_UP);

		assertThat(recomputed).isEqualTo(row.get("premium_amount"));
		assertThat(quote.at("/premium/amount").asString()).isEqualTo(recomputed.toPlainString());
		assertThat((BigDecimal) row.get("state_minimum")).isEqualByComparingTo((BigDecimal) row.get("minimum_premium"));
		assertThat(jdbc.queryForList("SELECT factor FROM quote_rating_factors WHERE quote_id = ? ORDER BY position",
				BigDecimal.class, quote.get("id").asString()))
			.containsExactly((BigDecimal) row.get("base_rate_per_thousand"), (BigDecimal) row.get("factor"));
	}

	@Test
	void expiredQuoteIsReturnedAsExpiredWithItsPremium() throws Exception {
		JsonNode quote = quoted(submit(request -> {
		}));
		jdbc.update("UPDATE quotes SET created_at = now() - interval '31 days', expires_at = now() - interval '1 day' "
				+ "WHERE id = ?", quote.get("id").asString());

		MvcResult fetched = perform(get(QUOTE, quote.get("id").asString()), "GET", QUOTE);

		assertThat(fetched.getResponse().getStatus()).isEqualTo(200);
		assertNoStore(fetched);
		JsonNode expired = body(fetched);
		assertThat(expired.get("status").asString()).isEqualTo("EXPIRED");
		assertThat(expired.at("/premium/amount").asString()).isEqualTo("2520.00");
		assertThat(expired.get("ratingFactors")).hasSize(2);
	}

	@Test
	void pastEffectiveDateIsABusinessRuleViolation() throws Exception {
		String submissionId = submit(request -> {
		});
		jdbc.update("UPDATE submissions SET effective_date = ? WHERE id = ?", today().minusDays(1), submissionId);

		JsonNode problem = assertProblem(createQuote(submissionId), 422, "business-rule-violation",
				"Submission violates a business rule");
		assertThat(problem.at("/errors/0/pointer").asString()).isEmpty();
		assertThat(problem.at("/errors/0/detail").asString()).contains(submissionId).contains("is in the past");
	}

	@Test
	void unknownSubmissionIsNotFound() throws Exception {
		JsonNode problem = assertProblem(createQuote("sub_DOESNOTEXIST"), 404, "not-found", "Resource not found");

		assertThat(problem.get("detail").asString()).isEqualTo("No submission with ID 'sub_DOESNOTEXIST' was found.");
		assertThat(problem.get("instance").asString()).isEqualTo("/v1/submissions/sub_DOESNOTEXIST/quotes");
	}

	@Test
	void unknownQuoteIsNotFound() throws Exception {
		JsonNode problem = assertProblem(perform(get(QUOTE, "qte_DOESNOTEXIST"), "GET", QUOTE), 404, "not-found",
				"Resource not found");

		assertThat(problem.get("detail").asString()).isEqualTo("No quote with ID 'qte_DOESNOTEXIST' was found.");
	}

	// Requests

	private String submit(Consumer<ObjectNode> change) throws Exception {
		ObjectNode request = contract.example("SubmissionRequestRestaurant");
		request.put("effectiveDate", today().plusDays(7).toString());
		change.accept(request);
		MockHttpServletRequestBuilder builder = post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(request));
		MvcResult result = perform(builder, "POST", SUBMISSIONS);
		assertThat(result.getResponse().getStatus()).as("submission created: %s", result.getResponse()
			.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(201);
		return body(result).get("id").asString();
	}

	private MvcResult createQuote(String submissionId) throws Exception {
		return perform(post(QUOTES, submissionId), "POST", QUOTES);
	}

	private JsonNode quoted(String submissionId) throws Exception {
		MvcResult result = createQuote(submissionId);
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertNoStore(result);
		return body(result);
	}

	private MvcResult perform(MockHttpServletRequestBuilder request, String method, String pathTemplate)
			throws Exception {
		MvcResult result = mvc.perform(request).andReturn();
		contract.assertResponseMatches(method, pathTemplate, result);
		return result;
	}

	private static void naics(ObjectNode request, String code) {
		((ObjectNode) request.get("classOfBusiness")).put("naicsCode", code);
	}

	private static void revenue(ObjectNode request, String amount) {
		((ObjectNode) request.get("annualRevenue")).put("amount", amount);
	}

	private static void limits(ObjectNode request, String perOccurrence, String aggregate) {
		((ObjectNode) request.at("/requestedLimit/perOccurrence")).put("amount", perOccurrence);
		((ObjectNode) request.at("/requestedLimit/aggregate")).put("amount", aggregate);
	}

	private static void address(ObjectNode request, String city, String region, String postalCode) {
		ObjectNode address = (ObjectNode) request.get("address");
		address.put("city", city);
		address.put("region", region);
		address.put("postalCode", postalCode);
	}

	private static void effective(ObjectNode request, LocalDate date) {
		request.put("effectiveDate", date.toString());
	}

	// Test reference data

	private void testClass(String code) {
		jdbc.update("INSERT INTO naics_classes (code, title, in_appetite) VALUES (?, 'Test class', TRUE)", code);
	}

	private void leaveAppetite(String code) {
		jdbc.update("UPDATE naics_classes SET in_appetite = FALSE WHERE code = ?", code);
	}

	private long classRate(String code, LocalDate effectiveFrom, String rate, String ceiling,
			@Nullable Long correctsId) {
		String reason = (correctsId != null) ? "Test correction: filed rate differs" : null;
		return jdbc.queryForObject("INSERT INTO class_rates (naics_code, effective_from, base_rate_per_thousand, "
				+ "max_auto_revenue, corrects_id, correction_reason) VALUES (?, ?, ?::numeric, ?::numeric, ?, ?) "
				+ "RETURNING id", Long.class, code, effectiveFrom, rate, ceiling, correctsId, reason);
	}

	/**
	 * Inserts a quote row directly, bypassing the application, as a valid QUOTED row with one
	 * field changed per variant. Each variant breaks exactly one constraint (Postgres checks
	 * them in name order and reports the first failure).
	 */
	private int insertQuote(String submissionId, String variant) {
		long classRate = jdbc.queryForObject(
				"SELECT id FROM class_rates WHERE naics_code = '722511' AND effective_from = '2026-01-01'", Long.class);
		long limitFactor = jdbc.queryForObject("SELECT id FROM limit_factors WHERE per_occurrence_limit = 1000000 "
				+ "AND effective_from = '2026-01-01'", Long.class);
		long ratingState = jdbc.queryForObject(
				"SELECT id FROM rating_states WHERE state_code = 'TX' AND effective_from = '2026-01-01'", Long.class);
		OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);

		String status = "QUOTED";
		BigDecimal premium = new BigDecimal("2520.00");
		String currency = "USD";
		BigDecimal minimum = new BigDecimal("500.00");
		Boolean minimumApplied = false;
		Long classRateId = classRate;
		OffsetDateTime expiresAt = createdAt.plusDays(30);
		switch (variant) {
			case "valid baseline" -> {
			}
			case "QUOTED without a premium" -> {
				premium = null;
				currency = null;
			}
			case "REFERRED with a premium" -> status = "REFERRED";
			case "QUOTED without a class rate row" -> classRateId = null;
			case "EXPIRED stored" -> {
				status = "EXPIRED";
				premium = null;
				currency = null;
				minimum = null;
				minimumApplied = null;
				expiresAt = null;
			}
			case "expiry equal to creation" -> expiresAt = createdAt;
			default -> throw new IllegalArgumentException(variant);
		}
		return jdbc.update("INSERT INTO quotes (id, submission_id, status, rated_as_of, rated_naics_code, "
				+ "rated_state_code, rated_per_occurrence_limit, rated_aggregate_limit, rated_annual_revenue, "
				+ "class_rate_id, limit_factor_id, rating_state_id, premium_amount, premium_currency, minimum_premium, "
				+ "minimum_premium_applied, rating_plan_version, created_at, expires_at) "
				+ "VALUES (?, ?, ?, ?, '722511', 'TX', 1000000, 2000000, 1200000, ?, ?, ?, ?, ?, ?, ?, 'GL-DEMO-1', ?, ?)",
				"qte_CK_" + UUID.randomUUID(), submissionId, status, today().plusDays(7), classRateId, limitFactor,
				ratingState, premium, currency, minimum, minimumApplied, createdAt, expiresAt);
	}

	// Stored evidence

	private Map<String, Object> quoteRow(JsonNode quote) {
		return jdbc.queryForMap("SELECT * FROM quotes WHERE id = ?", quote.get("id").asString());
	}

	private List<String> storedReasons(JsonNode quote) {
		return jdbc.queryForList("SELECT code || ': ' || message FROM quote_status_reasons WHERE quote_id = ? "
				+ "ORDER BY position", String.class, quote.get("id").asString());
	}

	/** Rows in effect on the quote's rating date and already loaded when it read the rates. */
	private int ratesKnownWhenQuoted(JsonNode quote) {
		return jdbc.queryForObject("SELECT count(*) FROM quotes q JOIN class_rates c ON c.naics_code = q.rated_naics_code "
				+ "AND c.effective_from <= q.rated_as_of AND c.loaded_at <= q.rates_read_at WHERE q.id = ?",
				Integer.class, quote.get("id").asString());
	}

	private String stateOfRow(Object ratingStateId) {
		return jdbc.queryForObject("SELECT state_code FROM rating_states WHERE id = ?", String.class, ratingStateId);
	}

	// Assertions

	private static void assertNoStore(MvcResult result) {
		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
	}

	private static JsonNode assertProblem(MvcResult result, int status, String typeSlug, String title)
			throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(status);
		assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		JsonNode problem = body(result);
		assertThat(problem.get("type").asString()).isEqualTo(PROBLEM_BASE + typeSlug);
		assertThat(problem.get("title").asString()).isEqualTo(title);
		assertThat(problem.get("traceId").asString()).matches("^[0-9a-f]{32}$");
		return problem;
	}

	private static List<String> factors(JsonNode quote) {
		return quote.get("ratingFactors")
			.valueStream()
			.map(factor -> factor.get("code").asString() + " " + factor.get("inputValue").asString() + " "
					+ factor.get("factor").asString())
			.toList();
	}

	private static List<String> reasonCodes(JsonNode quote) {
		return quote.get("statusReasons").valueStream().map(reason -> reason.get("code").asString()).toList();
	}

	private static JsonNode body(MvcResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private static LocalDate localDate(Object sqlDate) {
		return ((java.sql.Date) sqlDate).toLocalDate();
	}

	private static LocalDate today() {
		return LocalDate.now(ZoneOffset.UTC);
	}

}
