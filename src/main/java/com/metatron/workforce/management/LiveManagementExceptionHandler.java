package com.metatron.workforce.management;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** HTTP translation only; authorization semantics remain owned by the underlying service/policy. */
@RestControllerAdvice(assignableTypes = LiveManagementController.class)
public class LiveManagementExceptionHandler {

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> forbidden(SecurityException failure) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "reason", failure.getMessage()));
    }
}
