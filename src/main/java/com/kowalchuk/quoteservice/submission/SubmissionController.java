package com.kowalchuk.quoteservice.submission;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.kowalchuk.quoteservice.api.SubmissionsApi;
import com.kowalchuk.quoteservice.api.model.SubmissionDto;
import com.kowalchuk.quoteservice.api.model.SubmissionRequestDto;

/**
 * Implements the generated contract interface. Routes, media types, and validation come from
 * {@link SubmissionsApi}, so they cannot drift from the YAML.
 */
@RestController
public class SubmissionController implements SubmissionsApi {

	private final SubmissionService service;

	private final SubmissionMapper mapper;

	public SubmissionController(SubmissionService service, SubmissionMapper mapper) {
		this.service = service;
		this.mapper = mapper;
	}

	@Override
	public ResponseEntity<SubmissionDto> createSubmission(SubmissionRequestDto submissionRequestDto,
			String idempotencyKey) {
		// TODO: Idempotency-Key is validated but not yet honoured; retries create duplicates.
		// Dedupe (key table, request hash, 409/422 semantics, 24h TTL) needs its own design.
		Submission submission = service.create(mapper.toNewSubmission(submissionRequestDto));
		// Relative Location (uri-reference): an absolute URL would expose internal hosts
		// behind the gateway.
		URI location = UriComponentsBuilder.fromPath(PATH_GET_SUBMISSION).buildAndExpand(submission.getId()).toUri();
		return ResponseEntity.created(location).body(mapper.toDto(submission));
	}

	@Override
	public ResponseEntity<SubmissionDto> getSubmission(String submissionId) {
		return ResponseEntity.ok(mapper.toDto(service.get(submissionId)));
	}

}
