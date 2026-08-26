package com.metatron.workforce.interaction.channel;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Telegram webhook transport must acknowledge malformed/non-actionable updates with 2xx. */
@RestControllerAdvice(assignableTypes = TelegramWebhookController.class)
public final class TelegramWebhookExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramWebhookExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Void> acknowledgeInvalidTelegramUpdate(IllegalArgumentException failure, HttpServletRequest request) {
        LOG.warn("telegram_update_rejected_and_acknowledged path={} reason={}", request.getRequestURI(), failure.getMessage());
        return ResponseEntity.ok().build();
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Void> rejectUnauthorizedTelegramUpdate(SecurityException failure, HttpServletRequest request) {
        LOG.warn("telegram_update_unauthorized path={} reason={}", request.getRequestURI(), failure.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
