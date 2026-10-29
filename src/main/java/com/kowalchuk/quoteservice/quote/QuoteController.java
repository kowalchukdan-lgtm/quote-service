package com.kowalchuk.quoteservice.quote;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.kowalchuk.quoteservice.api.QuotesApi;
import com.kowalchuk.quoteservice.api.model.QuoteDto;

/**
 * Implements the generated contract interface. Routes, media types, and validation come from
 * {@link QuotesApi}, so they cannot drift from the YAML.
 * <p>
 * A quote's status can change from QUOTED to EXPIRED with no data change, purely as time
 * passes, so responses must never be served from a cache: both operations send
 * {@code Cache-Control: no-store}, as the contract declares.
 */
@RestController
public class QuoteController implements QuotesApi {

	private final QuoteService service;

	private final QuoteMapper mapper;

	public QuoteController(QuoteService service, QuoteMapper mapper) {
		this.service = service;
		this.mapper = mapper;
	}

	@Override
	public ResponseEntity<QuoteDto> createQuote(String submissionId, String idempotencyKey) {
		// TODO: Idempotency-Key is validated but not yet honoured; retries create duplicates.
		// Same deferred design as SubmissionController.
		Quote quote = service.create(submissionId);
		// Relative Location (uri-reference), as for submissions.
		URI location = UriComponentsBuilder.fromPath(PATH_GET_QUOTE).buildAndExpand(quote.getId()).toUri();
		return ResponseEntity.created(location).cacheControl(CacheControl.noStore()).body(mapper.toDto(quote));
	}

	@Override
	public ResponseEntity<QuoteDto> getQuote(String quoteId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapper.toDto(service.get(quoteId)));
	}

}
