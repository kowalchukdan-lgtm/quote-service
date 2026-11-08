package com.kowalchuk.quoteservice.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import com.kowalchuk.quoteservice.api.model.SubmissionRequestDto;

/**
 * Keeps a submission request's body exactly as received, for the intake archive. Parsing and
 * validation are untouched: Jackson reads the same bytes it always would.
 * <p>
 * The generated controller interfaces only hand over the parsed request, so the bytes are
 * captured on the way in and read back with {@link #current()}.
 */
@ControllerAdvice
public class RawRequestBody extends RequestBodyAdviceAdapter {

	static final String ATTRIBUTE = RawRequestBody.class.getName();

	/** The current request's submission request body, or null if it had none. */
	public static @Nullable String current() {
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		Object body = request == null ? null : request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		// JSON is UTF-8 (RFC 8259).
		return body instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : null;
	}

	@Override
	public boolean supports(MethodParameter methodParameter, Type targetType,
			Class<? extends HttpMessageConverter<?>> converterType) {
		return targetType == SubmissionRequestDto.class;
	}

	@Override
	public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType,
			Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
		byte[] body = inputMessage.getBody().readAllBytes();
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		if (request != null) {
			request.setAttribute(ATTRIBUTE, body, RequestAttributes.SCOPE_REQUEST);
		}
		return new HttpInputMessage() {

			@Override
			public InputStream getBody() {
				return new ByteArrayInputStream(body);
			}

			@Override
			public HttpHeaders getHeaders() {
				return inputMessage.getHeaders();
			}

		};
	}

}
