package com.monitoring.proxyapi.infrastructure.rest;

import com.monitoring.proxyapi.application.proxy.AuditedInvocationException;
import com.monitoring.proxyapi.domain.exception.DomainException;
import com.monitoring.proxyapi.domain.exception.ErrorCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

/**
 * Translates failures into RFC 7807 {@code application/problem+json} responses.
 * <p>
 * Failed service calls arrive here already audited by the proxy, so this handler only maps the
 * error category to an HTTP status and echoes the {@code requestId}, letting an operator jump from
 * the client error straight to the matching row in the dashboard.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuditedInvocationException.class)
    public ResponseEntity<ProblemDetail> handleAuditedInvocation(AuditedInvocationException ex) {
        Throwable cause = ex.getCause();
        HttpStatus status;
        String errorCode;
        String detail;
        if (cause instanceof DomainException domainException) {
            status = statusFor(domainException.getCategory());
            errorCode = domainException.getErrorCode();
            detail = domainException.getMessage();
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            errorCode = "UNEXPECTED_ERROR";
            detail = "Unexpected failure while executing the operation; see the audit log entry for details";
        }

        ProblemDetail problem = problem(status, errorCode, detail);
        problem.setTitle(cause.getClass().getSimpleName());
        problem.setProperty("requestId", ex.getRequestId());
        problem.setProperty("serviceId", ex.getServiceId());
        problem.setProperty("operation", ex.getOperation());
        problem.setProperty("durationMs", ex.getDurationMs());
        return ResponseEntity.status(status)
                .header(ServiceGatewayController.REQUEST_ID_HEADER, ex.getRequestId())
                .body(problem);
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
        HttpStatus status = statusFor(ex.getCategory());
        ProblemDetail problem = problem(status, ex.getErrorCode(), ex.getMessage());
        problem.setTitle(ex.getClass().getSimpleName());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> handleMalformedRequest(Exception ex) {
        String detail = ex instanceof MethodArgumentTypeMismatchException mismatch
                ? "Invalid value '" + mismatch.getValue() + "' for parameter '" + mismatch.getName() + "'"
                : "Request body must be a valid JSON object";
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", detail);
        problem.setTitle("Malformed request");
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ProblemDetail> handleIllegalState(IllegalStateException ex) {
        log.error("Unhandled illegal state", ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "UNEXPECTED_ERROR", "The request could not be completed");
        problem.setTitle("Internal error");
        return ResponseEntity.internalServerError().body(problem);
    }

    private static ProblemDetail problem(HttpStatus status, String errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:problem-type:" + errorCode.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    static HttpStatus statusFor(ErrorCategory category) {
        return switch (category) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BUSINESS_RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UPSTREAM_FAILURE -> HttpStatus.BAD_GATEWAY;
        };
    }
}
