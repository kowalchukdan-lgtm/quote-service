package com.kowalchuk.quoteservice.quote;

import com.kowalchuk.quoteservice.web.ResourceNotFoundException;

public class QuoteNotFoundException extends ResourceNotFoundException {

	public QuoteNotFoundException(String id) {
		super("No quote with ID '" + id + "' was found.");
	}

}
