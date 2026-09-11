package com.metatron.workforce.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Durable internal store for cognitive work products produced by canonical Founder-defined Workers. */
@Service
public final class FounderWorkerWorkProductStore {
    private final Path root;
    private final ObjectMapper json;

    @Autowired
    public FounderWorkerWorkProductStore(
            ObjectMapper objectMapper,
            @Value("${METATRON_FOUNDER_WORKER_PRODUCT_DIR:/var/lib/metatron-workforce/founder-worker-products}") String configured) {
        this(Path.of(configured), objectMapper);
    }

    FounderWorkerWorkProductStore(Path root, ObjectMapper objectMapper) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.json = Objects.requireNonNull(objectMapper, "objectMapper").copy().registerModule(new JavaTimeModule());
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("founder Worker work-product store unavailable", e);
        }
    }

    public synchronized WorkProduct save(WorkProduct product) {
        Objects.requireNonNull(product, "product");
        Path target = path(product.productId());
        try {
            Files.createDirectories(root);
            Path temp = Files.createTempFile(root, ".work-product-", ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), product);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return product;
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist founder Worker work product: " + product.productId(), e);
        }
    }

    public synchronized Optional<WorkProduct> find(String productId) {
        Path file = path(productId);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(json.readValue(file.toFile(), WorkProduct.class));
        } catch (IOException e) {
            throw new IllegalStateException("cannot load founder Worker work product: " + productId, e);
        }
    }

    public synchronized List<WorkProduct> listForWorker(String workerId) {
        if (!Files.exists(root)) return List.of();
        try (var files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> {
                        try { return json.readValue(path.toFile(), WorkProduct.class); }
                        catch (IOException e) { throw new IllegalStateException("cannot read founder Worker work product: " + path, e); }
                    })
                    .filter(product -> product.workerId().equals(workerId))
                    .sorted(java.util.Comparator.comparing(WorkProduct::createdAt))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list founder Worker work products", e);
        }
    }

    private Path path(String productId) {
        require(productId, "productId");
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(productId.getBytes(StandardCharsets.UTF_8));
        return root.resolve(encoded + ".json");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    }

    public record WorkProduct(
            String productId,
            String objectiveId,
            String assignmentReference,
            String workerId,
            String participationId,
            String roleRef,
            String request,
            String product,
            List<String> evidenceReferences,
            Instant createdAt) {
        public WorkProduct {
            require(productId, "productId");
            require(objectiveId, "objectiveId");
            require(assignmentReference, "assignmentReference");
            require(workerId, "workerId");
            require(participationId, "participationId");
            require(roleRef, "roleRef");
            require(request, "request");
            require(product, "product");
            evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}
