package com.metatron.workforce.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local stand-ins for the two external boundaries a governed Worker execution crosses: the isolated
 * execution sandbox (/run contract of runtime-sandbox/server.py, genuinely executing the requested
 * process against the on-disk Objective workspace) and the GitHub REST API call sequence of
 * GitHubWorkspaceProposalPublisher. Same contract as the private servers in
 * GeneralEngineeringRealExecutionEndToEndAcceptanceTest; kept separate so that test is untouched.
 */
public final class LocalExecutionServers {
    private LocalExecutionServers() {}

    public static boolean toolAvailable(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception unavailable) {
            return false;
        }
    }

    public static final class RealProcessSandboxServer {
        public static final String TOKEN = "sandbox-test-token";
        private final HttpServer server;
        private final List<String> executables = new CopyOnWriteArrayList<>();

        public RealProcessSandboxServer(Path root) throws Exception {
            Files.createDirectories(root);
            ObjectMapper json = new ObjectMapper();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/run", exchange -> {
                try {
                    JsonNode body = json.readTree(exchange.getRequestBody());
                    String workspaceKey = body.path("workspaceKey").asText();
                    Path workspaceRoot = safeChild(root, workspaceKey);
                    Files.createDirectories(workspaceRoot);
                    String relative = body.path("workingDirectory").asText("");
                    Path workingDirectory = relative.isBlank() ? workspaceRoot : safeChild(workspaceRoot, relative);
                    Files.createDirectories(workingDirectory);
                    String executable = body.path("executable").asText();
                    executables.add(executable);
                    List<String> command = new ArrayList<>(List.of(executable));
                    body.path("args").forEach(node -> command.add(node.asText()));
                    long startedAt = System.nanoTime();
                    Process process = new ProcessBuilder(command)
                            .directory(workingDirectory.toFile()).redirectErrorStream(true).start();
                    boolean finished = process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
                    String output = readAll(process.getInputStream());
                    if (!finished) process.destroyForcibly();
                    int exitCode = finished ? process.exitValue() : -1;
                    Map<String, Object> response = new java.util.LinkedHashMap<>();
                    response.put("success", finished && exitCode == 0);
                    response.put("exitCode", exitCode);
                    response.put("timedOut", !finished);
                    response.put("outputTruncated", false);
                    response.put("output", output);
                    response.put("workspaceKey", workspaceKey);
                    response.put("executable", executable);
                    response.put("durationMillis", (System.nanoTime() - startedAt) / 1_000_000L);
                    response.put("attemptId", body.path("attemptId").asText(""));
                    response.put("attemptFencingToken", body.path("attemptFencingToken").asLong(0));
                    response.put("workspaceRef", body.path("workspaceRef").asText(""));
                    byte[] bytes = json.writeValueAsBytes(response);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception failure) {
                    byte[] bytes = ("{\"error\":\"" + failure.getClass().getSimpleName() + "\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        public int port() { return server.getAddress().getPort(); }
        public List<String> executables() { return List.copyOf(executables); }
        public void stop() { server.stop(0); }

        private static Path safeChild(Path root, String relative) {
            Path resolved = root.resolve(relative).normalize();
            if (!resolved.startsWith(root)) throw new SecurityException("sandbox path escaped root: " + relative);
            return resolved;
        }
    }

    /** GitHub REST stub for exactly GitHubWorkspaceProposalPublisher.publish's call sequence; records tree paths. */
    public static final class FakeGitHubApiServer {
        private final HttpServer server;
        private final String reposPrefix;
        private final String baseSha = hex40("fake-base-commit");
        private final String treeSha = hex40("fake-base-tree");
        private final AtomicInteger counter = new AtomicInteger();
        private final AtomicInteger pullRequestsCreated = new AtomicInteger();
        private final AtomicInteger mergeCalls = new AtomicInteger();
        private final List<String> publishedTreePaths = new CopyOnWriteArrayList<>();
        private final ObjectMapper json = new ObjectMapper();

        public FakeGitHubApiServer(String repository) throws Exception {
            this.reposPrefix = "/repos/" + repository;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    String method = exchange.getRequestMethod();
                    String path = exchange.getRequestURI().getPath();
                    if (path.endsWith("/merge")) {
                        mergeCalls.incrementAndGet();
                        respondEmpty(exchange, 403);
                    } else if ("GET".equals(method) && path.equals(reposPrefix)) {
                        respond(exchange, 200, Map.of("default_branch", "main"));
                    } else if ("GET".equals(method) && path.equals(reposPrefix + "/git/ref/heads/main")) {
                        respond(exchange, 200, Map.of("object", Map.of("sha", baseSha)));
                    } else if ("GET".equals(method) && path.startsWith(reposPrefix + "/git/ref/heads/")) {
                        respondEmpty(exchange, 404);
                    } else if ("GET".equals(method) && path.equals(reposPrefix + "/git/commits/" + baseSha)) {
                        respond(exchange, 200, Map.of("tree", Map.of("sha", treeSha)));
                    } else if ("POST".equals(method) && path.equals(reposPrefix + "/git/blobs")) {
                        respond(exchange, 201, Map.of("sha", hex40("blob-" + counter.incrementAndGet())));
                    } else if ("POST".equals(method) && path.equals(reposPrefix + "/git/trees")) {
                        JsonNode body = json.readTree(exchange.getRequestBody());
                        body.path("tree").forEach(entry -> publishedTreePaths.add(entry.path("path").asText()));
                        respond(exchange, 201, Map.of("sha", hex40("tree-" + counter.incrementAndGet())));
                    } else if ("POST".equals(method) && path.equals(reposPrefix + "/git/commits")) {
                        respond(exchange, 201, Map.of("sha", hex40("commit-" + counter.incrementAndGet())));
                    } else if ("POST".equals(method) && path.equals(reposPrefix + "/git/refs")) {
                        respond(exchange, 201, Map.of());
                    } else if ("GET".equals(method) && path.equals(reposPrefix + "/pulls")) {
                        respond(exchange, 200, List.of());
                    } else if ("POST".equals(method) && path.equals(reposPrefix + "/pulls")) {
                        pullRequestsCreated.incrementAndGet();
                        respond(exchange, 201, Map.of(
                                "number", 1,
                                "html_url", "http://127.0.0.1:" + port() + "/pr/1",
                                "merged", false));
                    } else {
                        respondEmpty(exchange, 404);
                    }
                } catch (Exception failure) {
                    try { respondEmpty(exchange, 500); } catch (Exception ignored) { /* best effort */ }
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        public int port() { return server.getAddress().getPort(); }
        public String repository() { return reposPrefix.substring("/repos/".length()); }
        public String baseSha() { return baseSha; }
        public int pullRequestsCreated() { return pullRequestsCreated.get(); }
        public int mergeCalls() { return mergeCalls.get(); }
        public List<String> publishedTreePaths() { return List.copyOf(publishedTreePaths); }
        public void stop() { server.stop(0); }

        private void respond(HttpExchange exchange, int status, Object body) throws Exception {
            byte[] bytes = json.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        private static void respondEmpty(HttpExchange exchange, int status) throws Exception {
            exchange.sendResponseHeaders(status, -1);
        }

        private static String hex40(String seed) {
            try {
                byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(seed.getBytes(StandardCharsets.UTF_8));
                return java.util.HexFormat.of().formatHex(hash).substring(0, 40);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        stream.transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
