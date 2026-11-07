package com.kowalchuk.quoteservice.web;

import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cache headers for the Angular app in {@code classpath:/static/}.
 * <p>
 * {@code index.html} is never stored, so a deploy is picked up on the next load; it names the
 * current bundles. The bundles themselves ({@code outputHashing: all} puts a content hash in every
 * root JS and CSS file name and under {@code media/}) never change, so they are cached for a year.
 * Other files keep Spring Boot's default handler. A missing file still raises
 * {@code NoResourceFoundException}, which {@link ProblemHandler} renders as a 404 Problem.
 */
@Configuration(proxyBeanMethods = false)
class StaticResourceConfig implements WebMvcConfigurer {

	private static final String STATIC = "classpath:/static/";

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/index.html").addResourceLocations(STATIC).setCacheControl(CacheControl.noStore());
		registry.addResourceHandler("/*.js", "/*.css", "/media/**")
			.addResourceLocations(STATIC)
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
	}

}
