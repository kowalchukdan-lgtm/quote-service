package com.kowalchuk.quoteservice.intake;

import org.springframework.stereotype.Component;

import com.kowalchuk.quoteservice.extraction.ExtractedSubmission;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a draft as JSON text. Its own mapper, not the API's: the stored format is internal and
 * must stay readable as the draft gains fields, so unknown properties are ignored on read.
 */
@Component
public class DraftJson {

	private static final JsonMapper JSON = JsonMapper.builder()
		.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
		.build();

	public String write(ExtractedSubmission draft) {
		return JSON.writeValueAsString(draft);
	}

	public ExtractedSubmission read(String json) {
		return JSON.readValue(json, ExtractedSubmission.class);
	}

}
