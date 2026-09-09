package com.metatron.workforce.workplace;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Founder-authenticated Workplace management projection and Control Room entrypoint. */
@RestController
@RequestMapping("/workplace")
public class WorkplaceDashboardController {
    private final WorkplaceDashboardAuthService auth;
    private final WorkplaceDashboardService dashboard;

    public WorkplaceDashboardController(WorkplaceDashboardAuthService auth, WorkplaceDashboardService dashboard) {
        this.auth = auth;
        this.dashboard = dashboard;
    }

    @GetMapping(value={"", "/", "/index.html"}, produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(new ClassPathResource("static/workplace/control-room.html"));
    }

    @GetMapping("/api/auth/status")
    public Map<String,Object> authStatus(@RequestHeader(value="Authorization", required=false) String authorization) {
        return Map.of("authenticated", auth.valid(bearer(authorization)));
    }

    @PostMapping("/api/auth/request-code")
    public ResponseEntity<?> requestCode() {
        try {
            auth.requestCode();
            return ResponseEntity.accepted().body(Map.of("sent", true, "expiresInSeconds", 300));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(429).body(Map.of("sent", false, "error", e.getMessage()));
        }
    }

    @PostMapping("/api/auth/verify")
    public ResponseEntity<?> verify(@RequestBody Map<String,String> body) {
        try {
            return ResponseEntity.ok(Map.of("token", auth.verify(body.get("code")), "expiresInSeconds", 43200));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid_or_expired_code"));
        }
    }

    @PostMapping("/api/auth/logout")
    public Map<String,Boolean> logout(@RequestHeader(value="Authorization", required=false) String authorization) {
        auth.logout(bearer(authorization));
        return Map.of("ok", true);
    }

    @GetMapping("/api/dashboard")
    public ResponseEntity<?> dashboard(@RequestHeader(value="Authorization", required=false) String authorization) {
        if (!auth.valid(bearer(authorization))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "authentication_required"));
        }
        return ResponseEntity.ok(dashboard.dashboard());
    }

    static String bearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
    }
}
