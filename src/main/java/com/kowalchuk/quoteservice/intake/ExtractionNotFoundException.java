package com.kowalchuk.quoteservice.intake;

import com.kowalchuk.quoteservice.web.ResourceNotFoundException;

public class ExtractionNotFoundException extends ResourceNotFoundException {

	public ExtractionNotFoundException(String id) {
		super("No extraction with ID '" + id + "' was found.");
	}

}
