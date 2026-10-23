package com.kowalchuk.quoteservice.submission;

import com.kowalchuk.quoteservice.web.ResourceNotFoundException;

public class SubmissionNotFoundException extends ResourceNotFoundException {

	public SubmissionNotFoundException(String id) {
		super("No submission with ID '" + id + "' was found.");
	}

}
