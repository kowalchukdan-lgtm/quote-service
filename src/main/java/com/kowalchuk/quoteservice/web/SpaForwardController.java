package com.kowalchuk.quoteservice.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Angular app's {@code index.html} for its deep links, so reloading one works.
 * <p>
 * The paths mirror {@code frontend/src/app/app.routes.ts}; add one here when a route is added
 * there. They are listed explicitly rather than caught all, so unknown {@code /v1} and
 * {@code /actuator} paths still reach the static resource handler and its 404 Problem Details.
 * {@code /} needs no mapping: Spring Boot's welcome page serves {@code index.html} there.
 */
@Controller
class SpaForwardController {

	@GetMapping({ "/extractions/{extractionId}", "/submissions/{submissionId}" })
	String app() {
		return "forward:/index.html";
	}

}
