package io.github.marishasahay.shortener.web;

import io.github.marishasahay.shortener.link.AliasAlreadyExistsException;
import io.github.marishasahay.shortener.link.CodeGenerationException;
import io.github.marishasahay.shortener.link.InvalidLinkRequestException;
import io.github.marishasahay.shortener.link.LinkExpiredException;
import io.github.marishasahay.shortener.link.LinkNotFoundException;
import io.github.marishasahay.shortener.ratelimit.RateLimitExceededException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as an RFC 9457 {@code application/problem+json} document. Extending
 * {@link ResponseEntityExceptionHandler} covers Spring MVC's own exceptions (malformed JSON,
 * unsupported media type, unknown path, ...) in the same format.
 */
@RestControllerAdvice
public class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

  static final String ERRORS_PROPERTY = "errors";
  static final String INVALID_REQUEST_TITLE = "Invalid request";

  private static final Logger LOG = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

  @ExceptionHandler(LinkNotFoundException.class)
  public ProblemDetail handleNotFound(LinkNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "Link not found", ex.getMessage());
  }

  @ExceptionHandler(LinkExpiredException.class)
  public ProblemDetail handleExpired(LinkExpiredException ex) {
    return problem(HttpStatus.GONE, "Link expired", ex.getMessage());
  }

  @ExceptionHandler(AliasAlreadyExistsException.class)
  public ProblemDetail handleAliasConflict(AliasAlreadyExistsException ex) {
    return problem(HttpStatus.CONFLICT, "Alias already in use", ex.getMessage());
  }

  /**
   * Maps business-rule violations of a create request to 400.
   *
   * @param ex the violation
   * @return a problem listing the invalid field
   */
  @ExceptionHandler(InvalidLinkRequestException.class)
  public ProblemDetail handleInvalidRequest(InvalidLinkRequestException ex) {
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, INVALID_REQUEST_TITLE, ex.getMessage());
    problem.setProperty(ERRORS_PROPERTY, List.of(new FieldViolation(ex.field(), ex.reason())));
    return problem;
  }

  /**
   * Maps an exhausted rate limit to 429 with a {@code Retry-After} header in seconds.
   *
   * @param ex the rejection
   * @return the problem response
   */
  @ExceptionHandler(RateLimitExceededException.class)
  public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException ex) {
    long retryAfterSeconds = ex.retryAfterSeconds();
    ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
        "Rate limit exceeded; retry after " + retryAfterSeconds + " seconds");
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds))
        .body(problem);
  }

  /**
   * Maps an exhausted code-generation retry budget to 500.
   *
   * @param ex the failure
   * @return the problem
   */
  @ExceptionHandler(CodeGenerationException.class)
  public ProblemDetail handleCodeGeneration(CodeGenerationException ex) {
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Short code generation failed",
        ex.getMessage());
  }

  /**
   * Last-resort handler so unexpected failures are also problem documents and leak no details.
   *
   * @param ex the unexpected exception
   * @return a generic 500 problem
   */
  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception ex) {
    LOG.error("Unhandled exception", ex);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
        "An unexpected error occurred");
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
        .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
        .sorted(Comparator.comparing(FieldViolation::field))
        .toList();
    ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, INVALID_REQUEST_TITLE,
        "Request validation failed");
    problem.setProperty(ERRORS_PROPERTY, violations);
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    return problem;
  }
}
