package com.metatron.workforce.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Internal runtime-to-runtime actual-effect endpoint. Never a Human ingress or authorization minting surface. */
@RestController
@RequestMapping("/workforce/runtime")
public final class RuntimeExecutionController {
    private final RuntimeCapabilityExecutionService execution;
    private final String transportToken;

    public RuntimeExecutionController(RuntimeCapabilityExecutionService execution,
                                      @Value("${METATRON_RUNTIME_EXECUTION_TOKEN:}") String transportToken) {
        this.execution = Objects.requireNonNull(execution, "execution");
        this.transportToken = transportToken == null ? "" : transportToken.trim();
    }

    @PostMapping("/execute")
    public RuntimeExecutionResult execute(
            @RequestHeader(value = RemoteRuntimeExecutor.EXECUTION_TOKEN_HEADER, required = false) String suppliedToken,
            @RequestBody RuntimeExecutionCommand command) {
        authenticate(suppliedToken);
        try {
            return execution.execute(command);
        } catch (SecurityException denied) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "runtime execution denied", denied);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runtime execution request invalid", invalid);
        } catch (IllegalStateException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "runtime execution failed closed", conflict);
        }
    }

    private void authenticate(String suppliedToken) {
        if (transportToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "remote runtime execution transport is disabled");
        }
        byte[] expected = transportToken.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = (suppliedToken == null ? "" : suppliedToken).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "remote runtime transport authentication failed");
        }
    }
}
