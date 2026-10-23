package com.kowalchuk.quoteservice.web;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.ReflectionUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Renders every error as RFC 9457 Problem Details matching the contract's {@code Problem}
 * and {@code ValidationProblem} schemas.
 * <p>
 * Extends Spring's handler so framework errors (415, 406, 405, missing body) are covered;
 * {@link #handleExceptionInternal} gives every problem a contract {@code type}, a fixed
 * {@code title}, and a {@code traceId}. Spring fills {@code instance} with the request path.
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

	private static final URI ABOUT_BLANK = URI.create("about:blank");

	private static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred. Quote the traceId when contacting support.";

	private final String problemBaseUri;

	public ProblemHandler(@Value("${quote.problems.base-uri}") String problemBaseUri) {
		this.problemBaseUri = problemBaseUri;
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> errors = new ArrayList<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			errors.add(new FieldViolation(JsonPointers.fromPropertyPath(error.getField()), describe(error)));
		}
		ex.getBindingResult()
			.getGlobalErrors()
			.forEach(error -> errors.add(new FieldViolation("", error.getDefaultMessage())));
		return validationError(ex, errors, headers, request);
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Throwable cause = ex.getCause();
		if (cause instanceof UnrecognizedPropertyException unknown) {
			return validationError(ex,
					List.of(new FieldViolation(JsonPointers.fromJacksonPath(unknown.getPath()), "Unknown property.")),
					headers, request);
		}
		if (cause instanceof MismatchedInputException mismatch) {
			return validationError(ex, List.of(new FieldViolation(JsonPointers.fromJacksonPath(mismatch.getPath()),
					expectation(mismatch.getTargetType()))), headers, request);
		}
		String detail = (cause != null) ? "The request body is not valid JSON." : "The request body is missing.";
		return respond(ex, problem(ProblemType.MALFORMED_REQUEST, detail), headers, request);
	}

	@Override
	protected @Nullable ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return respond(ex, problem(ProblemType.NOT_FOUND, "No resource exists at /" + ex.getResourcePath() + "."),
				headers, request);
	}

	/**
	 * Parameter constraints declared on the generated API interfaces. The interfaces carry
	 * {@code @Validated}, so Spring's AOP method validation raises this exception.
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	public @Nullable ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex,
			WebRequest request) {
		List<MethodParameter> parameters = ex.getConstraintViolations()
			.stream()
			.map(ProblemHandler::parameterOf)
			.toList();
		// An identifier that breaks the ID constraints cannot exist; the contract's GET
		// operations define 404, not 400.
		if (parameters.stream().anyMatch(p -> p != null && p.hasParameterAnnotation(PathVariable.class))) {
			return respond(ex, problem(ProblemType.NOT_FOUND, "The requested resource was not found."),
					new HttpHeaders(), request);
		}
		// Header and query constraints have no body location, so they go in detail only.
		String detail = ex.getConstraintViolations()
			.stream()
			.map(violation -> label(parameterOf(violation)) + ": " + violation.getMessage())
			.sorted()
			.collect(Collectors.joining("; "));
		return respond(ex, problem(ProblemType.VALIDATION_ERROR, detail), new HttpHeaders(), request);
	}

	@ExceptionHandler(BusinessRuleViolationException.class)
	public @Nullable ResponseEntity<Object> handleBusinessRuleViolation(BusinessRuleViolationException ex,
			WebRequest request) {
		ProblemDetail problem = problem(ProblemType.BUSINESS_RULE_VIOLATION,
				"The submission is well-formed but cannot be accepted.");
		problem.setProperty("errors", sorted(ex.getViolations()));
		return respond(ex, problem, new HttpHeaders(), request);
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	public @Nullable ResponseEntity<Object> handleResourceNotFound(ResourceNotFoundException ex,
			WebRequest request) {
		return respond(ex, problem(ProblemType.NOT_FOUND, ex.getMessage()), new HttpHeaders(), request);
	}

	@ExceptionHandler(Exception.class)
	public @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Unhandled exception (traceId={})", traceId(request), ex);
		return respond(ex, problem(ProblemType.INTERNAL_ERROR, INTERNAL_ERROR_DETAIL), new HttpHeaders(), request);
	}

	/**
	 * Every handler, ours and the framework's, ends here. Framework problems arrive with no
	 * type (Spring 7 leaves it null rather than {@code about:blank}) and get the contract type
	 * for their status.
	 */
	@Override
	protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
		if (body == null && ex instanceof ErrorResponse errorResponse) {
			body = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
		}
		if (body instanceof ProblemDetail problem) {
			if (problem.getType() == null || ABOUT_BLANK.equals(problem.getType())) {
				ProblemType.defaultFor(statusCode.value()).ifPresent(type -> {
					applyType(problem, type);
					if (type == ProblemType.INTERNAL_ERROR) {
						problem.setDetail(INTERNAL_ERROR_DETAIL);
					}
				});
			}
			problem.setProperty("traceId", traceId(request));
		}
		return super.handleExceptionInternal(ex, body, headers, statusCode, request);
	}

	private @Nullable ResponseEntity<Object> validationError(Exception ex, List<FieldViolation> errors,
			HttpHeaders headers, WebRequest request) {
		int count = errors.size();
		ProblemDetail problem = problem(ProblemType.VALIDATION_ERROR,
				"The request body has " + count + " validation error" + (count == 1 ? "" : "s") + ".");
		problem.setProperty("errors", sorted(errors));
		return respond(ex, problem, headers, request);
	}

	private @Nullable ResponseEntity<Object> respond(Exception ex, ProblemDetail problem, HttpHeaders headers,
			WebRequest request) {
		return handleExceptionInternal(ex, problem, headers, HttpStatusCode.valueOf(problem.getStatus()), request);
	}

	private ProblemDetail problem(ProblemType type, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(type.status(), detail);
		applyType(problem, type);
		return problem;
	}

	private void applyType(ProblemDetail problem, ProblemType type) {
		problem.setType(URI.create(problemBaseUri + type.slug()));
		problem.setTitle(type.title());
	}

	private static List<FieldViolation> sorted(List<FieldViolation> errors) {
		return errors.stream()
			.sorted(Comparator.comparing(FieldViolation::pointer).thenComparing(FieldViolation::detail))
			.toList();
	}

	private static String describe(FieldError error) {
		return "NotNull".equals(error.getCode()) ? "Required property is missing." : error.getDefaultMessage();
	}

	private static String expectation(@Nullable Class<?> targetType) {
		if (targetType == null) {
			return "Invalid value.";
		}
		if (targetType == String.class) {
			return "Must be a string.";
		}
		if (targetType == LocalDate.class) {
			return "Must be a full-date (YYYY-MM-DD).";
		}
		if (targetType == OffsetDateTime.class) {
			return "Must be a date-time.";
		}
		if (targetType == Boolean.class || targetType == boolean.class) {
			return "Must be a boolean.";
		}
		if (Number.class.isAssignableFrom(targetType) || targetType.isPrimitive()) {
			return "Must be a number.";
		}
		if (targetType.isArray() || Collection.class.isAssignableFrom(targetType)) {
			return "Must be an array.";
		}
		return "Must be an object.";
	}

	/**
	 * Finds the handler method parameter a violation refers to, including annotations
	 * declared on the generated interface.
	 */
	private static @Nullable MethodParameter parameterOf(ConstraintViolation<?> violation) {
		Path.MethodNode methodNode = null;
		for (Path.Node node : violation.getPropertyPath()) {
			if (node.getKind() == ElementKind.METHOD) {
				methodNode = node.as(Path.MethodNode.class);
			}
			else if (node.getKind() == ElementKind.PARAMETER && methodNode != null) {
				Method method = ReflectionUtils.findMethod(violation.getRootBeanClass(), methodNode.getName(),
						methodNode.getParameterTypes().toArray(Class<?>[]::new));
				int index = node.as(Path.ParameterNode.class).getParameterIndex();
				return (method != null) ? new AnnotatedMethod(method).getMethodParameters()[index] : null;
			}
		}
		return null;
	}

	private static String label(@Nullable MethodParameter parameter) {
		if (parameter == null) {
			return "Request";
		}
		RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
		if (header != null) {
			return "Header " + (header.value().isEmpty() ? header.name() : header.value());
		}
		return "Parameter " + parameter.getParameterName();
	}

	private static String traceId(WebRequest request) {
		Object traceId = request.getAttribute(TraceIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (traceId instanceof String value) {
			return value;
		}
		String fromMdc = MDC.get(TraceIdFilter.MDC_KEY);
		return (fromMdc != null) ? fromMdc : UUID.randomUUID().toString().replace("-", "");
	}

}
