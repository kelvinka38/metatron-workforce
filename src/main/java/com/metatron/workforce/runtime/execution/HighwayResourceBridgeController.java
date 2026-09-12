package com.metatron.workforce.runtime.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Host-private transport adapter used by the persistent Highway release plane to bind its existing
 * resource claims to the canonical ExecutionResourceManager. Transport authentication is not release
 * authority; Highway remains the only release authority and this endpoint can only acquire/renew/
 * validate/release resource leases for a Highway-owned ExecutionAttempt.
 */
@RestController
@RequestMapping("/internal/metatron/highway-resources")
public final class HighwayResourceBridgeController {
    public static final String TOKEN_HEADER = "X-Metatron-Highway-Resource-Token";

    private final HighwayResourceBridgeService bridge;
    private final String transportToken;

    public HighwayResourceBridgeController(HighwayResourceBridgeService bridge,
                                           @Value("${METATRON_RUNTIME_EXECUTION_TOKEN:}") String transportToken) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.transportToken = transportToken == null ? "" : transportToken.trim();
    }

    @PostMapping("/acquire")
    public ResponseEntity<?> acquire(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied,
                                     @RequestBody HighwayResourceBridgeService.AcquireRequest request) {
        return invoke(supplied, () -> bridge.acquire(request, Instant.now()));
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied,
                                       @RequestBody HighwayResourceBridgeService.LeaseRequest request) {
        return invoke(supplied, () -> bridge.heartbeat(request, Instant.now()));
    }

    @PostMapping("/validate")
    public ResponseEntity<?> validate(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied,
                                      @RequestBody HighwayResourceBridgeService.LeaseRequest request) {
        return invoke(supplied, () -> bridge.validate(request, Instant.now()));
    }

    @PostMapping("/finish")
    public ResponseEntity<?> finish(@RequestHeader(value = TOKEN_HEADER, required = false) String supplied,
                                    @RequestBody HighwayResourceBridgeService.FinishRequest request) {
        return invoke(supplied, () -> {
            bridge.finish(request, Instant.now());
            return Map.of("ok", true, "taskId", request.taskId());
        });
    }

    private ResponseEntity<?> invoke(String supplied, java.util.concurrent.Callable<?> action) {
        if (!authenticate(supplied)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new Error("HIGHWAY_RESOURCE_BRIDGE_AUTH_DENIED", "transport authentication failed"));
        }
        try {
            return ResponseEntity.ok(action.call());
        } catch (SecurityException denied) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new Error("RESOURCE_FENCED", bounded(denied.getMessage())));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest()
                    .body(new Error("HIGHWAY_RESOURCE_BRIDGE_INVALID", bounded(invalid.getMessage())));
        } catch (IllegalStateException conflict) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new Error("RESOURCE_CONFLICT", bounded(conflict.getMessage())));
        } catch (Exception failure) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new Error("HIGHWAY_RESOURCE_BRIDGE_FAILED", failure.getClass().getSimpleName()));
        }
    }

    boolean authenticate(String supplied) {
        if (transportToken.isBlank()) return false;
        byte[] expected = transportToken.getBytes(StandardCharsets.UTF_8);
        byte[] actual = (supplied == null ? "" : supplied).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static String bounded(String value) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 300 ? clean : clean.substring(0, 300);
    }

    public record Error(String error, String detail) { }
}
