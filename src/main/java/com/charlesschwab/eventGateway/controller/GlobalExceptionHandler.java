package com.charlesschwab.eventGateway.controller;

import com.charlesschwab.eventGateway.exception.AccountServiceUnavailableException;
import com.charlesschwab.eventGateway.model.ApiError;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.web.context.request.WebRequest;
import java.util.concurrent.TimeoutException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleAccountUnavailable(AccountServiceUnavailableException ex, WebRequest req) {
        String trace = MDC.get("traceId");
        String path = extractPath(req);
        ApiError err = new ApiError(HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable", ex.getMessage(), path, trace);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err);
    }

    @ExceptionHandler({CallNotPermittedException.class, TimeoutException.class})
    public ResponseEntity<ApiError> handleResilienceExceptions(Exception ex, WebRequest req) {
        String trace = MDC.get("traceId");
        String path = extractPath(req);
        ApiError err = new ApiError(HttpStatus.SERVICE_UNAVAILABLE.value(), "Service Unavailable", ex.getMessage(), path, trace);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err);
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex, WebRequest req) {
        String trace = MDC.get("traceId");
        String path = extractPath(req);
        ApiError err = new ApiError(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), path, trace);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, WebRequest req) {
        String trace = MDC.get("traceId");
        String path = extractPath(req);
        ApiError err = new ApiError(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error", ex.getMessage(), path, trace);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
    }

    private String extractPath(WebRequest req) {
        String desc = req.getDescription(false); // typically "uri=/path"
        if (desc != null && desc.startsWith("uri=")) return desc.substring(4);
        return desc != null ? desc : "";
    }
}

