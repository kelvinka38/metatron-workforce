package com.metatron.workforce.interaction.channel;

import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.workers.audit.RepositoryAuditExecutionService;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Maps a deliberately small Telegram command surface onto pre-authorized institutional Work. */
public final class TelegramInstitutionalWorkDispatcher {
    static final String BIOS_REPOSITORY = "kelvinka38/bios";
    static final String AUTHORITY_REFERENCE = "policy:telegram-founder-repository-audit:v1";
    static final String AUTHORIZATION_REFERENCE = "authorization:telegram-founder-readonly-repository-audit:v1";

    private final RepositoryAuditExecutionService repositoryAudit;

    public TelegramInstitutionalWorkDispatcher(RepositoryAuditExecutionService repositoryAudit) {
        this.repositoryAudit = Objects.requireNonNull(repositoryAudit, "repositoryAudit");
    }

    public Optional<String> dispatch(MetatronInteraction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        String intent = normalize(interaction.text());
        if (!("audit bios".equals(intent) || "/audit bios".equals(intent))) {
            return Optional.empty();
        }

        RepositoryAuditExecutionService.ExecutionReceipt receipt = repositoryAudit.execute(
                interaction.human().actorId(),
                AUTHORITY_REFERENCE,
                AUTHORIZATION_REFERENCE,
                interaction.organizationContextId(),
                BIOS_REPOSITORY);

        String workState = receipt.work().status().name();
        String workerState = receipt.workerResult().status();
        String verdict = "COMPLETED".equals(workState) && "PASS".equals(workerState) ? "COMPLETED" : "BLOCKED";
        return Optional.of("METATRON WORK " + verdict
                + " repository=" + receipt.repository()
                + " worker=" + receipt.workerResult().worker()
                + " work=" + receipt.work().id()
                + " status=" + workState
                + " evidence=preserved");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
