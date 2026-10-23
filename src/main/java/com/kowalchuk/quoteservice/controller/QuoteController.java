package com.kowalchuk.quoteservice.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

	@GetMapping("/hello")
	public Map<String, String> hello() {
		return Map.of(
				"service", "quote-service",
				"message", "Hello from the quote service");
	}

}
