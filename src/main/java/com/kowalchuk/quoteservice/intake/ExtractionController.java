package com.kowalchuk.quoteservice.intake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import com.kowalchuk.quoteservice.api.ExtractionsApi;
import com.kowalchuk.quoteservice.api.SubmissionsApi;
import com.kowalchuk.quoteservice.api.model.ExtractionDto;
import com.kowalchuk.quoteservice.api.model.SubmissionDto;
import com.kowalchuk.quoteservice.api.model.SubmissionRequestDto;
import com.kowalchuk.quoteservice.submission.Submission;
import com.kowalchuk.quoteservice.submission.SubmissionMapper;
import com.kowalchuk.quoteservice.web.RawRequestBody;

/**
 * Implements the generated {@link ExtractionsApi}. Confirmation takes a full
 * {@code SubmissionRequest}, so the reviewer's corrections are explicit and the submission is
 * validated exactly like one created directly.
 */
@RestController
public class ExtractionController implements ExtractionsApi {

	private final ExtractionService service;

	private final ExtractionMapper mapper;

	private final SubmissionMapper submissionMapper;

	public ExtractionController(ExtractionService service, ExtractionMapper mapper, SubmissionMapper submissionMapper) {
		this.service = service;
		this.mapper = mapper;
		this.submissionMapper = submissionMapper;
	}

	@Override
	public ResponseEntity<ExtractionDto> createExtraction(MultipartFile document) {
		Extraction extraction = service.extract(document.getOriginalFilename(), bytes(document));
		URI location = UriComponentsBuilder.fromPath(PATH_GET_EXTRACTION).buildAndExpand(extraction.getId()).toUri();
		return ResponseEntity.created(location).body(mapper.toDto(extraction, service.draftOf(extraction)));
	}

	@Override
	public ResponseEntity<ExtractionDto> getExtraction(String extractionId) {
		Extraction extraction = service.get(extractionId);
		return ResponseEntity.ok(mapper.toDto(extraction, service.draftOf(extraction)));
	}

	@Override
	public ResponseEntity<SubmissionDto> confirmExtraction(String extractionId,
			SubmissionRequestDto submissionRequestDto) {
		Submission submission = service.confirm(extractionId, submissionMapper.toNewSubmission(submissionRequestDto),
				RawRequestBody.current());
		URI location = UriComponentsBuilder.fromPath(SubmissionsApi.PATH_GET_SUBMISSION)
			.buildAndExpand(submission.getId())
			.toUri();
		return ResponseEntity.created(location).body(submissionMapper.toDto(submission));
	}

	private static byte[] bytes(MultipartFile document) {
		try {
			return document.getBytes();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
