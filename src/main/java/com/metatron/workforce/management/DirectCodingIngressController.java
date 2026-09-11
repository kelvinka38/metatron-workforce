package com.metatron.workforce.management;

import com.metatron.workforce.execution.governance.GovernanceDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.Objects;

/** Private Docker-network ingress for the MCP direct coding adapter. Never route this path publicly. */
@RestController
@RequestMapping("/internal/metatron/direct-coding")
public final class DirectCodingIngressController {
    private final DirectCodingIngressService service;

    public DirectCodingIngressController(DirectCodingIngressService service) {
        this.service = Objects.requireNonNull(service);
    }

    @PostMapping("/action")
    public ResponseEntity<?> action(@RequestHeader("X-Metatron-Direct-Client") String client,
                                    @RequestBody DirectCodingIngressService.Command command,
                                    HttpServletRequest request) {
        if (!trustedDirectNetworkRequest(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new Error(false, "direct_coding_private_network_required", ""));
        }
        try {
            return ResponseEntity.ok(service.execute(client, command));
        } catch (GovernanceDeniedException denied) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new Error(false, denied.code(), bounded(denied.getMessage())));
        } catch (SecurityException denied) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new Error(false, bounded(denied.getMessage()), ""));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest()
                    .body(new Error(false, "direct_coding_invalid_request", bounded(invalid.getMessage())));
        } catch (IllegalStateException conflict) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new Error(false, bounded(conflict.getMessage()), ""));
        } catch (RuntimeException failure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new Error(false, "direct_coding_failed", failure.getClass().getSimpleName()));
        }
    }

    static boolean trustedDirectNetworkRequest(HttpServletRequest request) {
        if (request == null) return false;
        if (hasText(request.getHeader("Forwarded")) || hasText(request.getHeader("X-Forwarded-For"))) return false;
        return privateOrLoopback(request.getRemoteAddr());
    }

    static boolean privateOrLoopback(String remoteAddress) {
        if (!hasText(remoteAddress)) return false;
        try {
            InetAddress address = InetAddress.getByName(remoteAddress.trim());
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) return true;
            byte[] bytes = address.getAddress();
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    private static String bounded(String value) {
        if (value == null) return "";
        String clean = value.replace('\r', ' ').replace('\n', ' ').trim();
        return clean.length() <= 300 ? clean : clean.substring(0, 300);
    }

    public record Error(boolean ok, String error, String detail) {
        public Error {
            error = error == null || error.isBlank() ? "direct_coding_error" : error;
            detail = detail == null ? "" : detail;
        }
    }
}
