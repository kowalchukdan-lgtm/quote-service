package com.kowalchuk.quoteservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kowalchuk.quoteservice.rating.RatingEngine;

/**
 * Exposes the rating engine as a bean, so the engine itself stays free of Spring.
 */
@Configuration(proxyBeanMethods = false)
public class RatingConfig {

	@Bean
	RatingEngine ratingEngine() {
		return new RatingEngine();
	}

}
