package com.metatron.workforce.interaction.channel;

import com.metatron.workforce.interaction.MetatronInteraction;
import com.metatron.workforce.workers.audit.RepositoryAuditExecutionService;

import java.util.Objects;
import java.util.Optional;

/**
 * Legacy compatibility shell.
 *
 * Telegram no longer owns an institutional command router. Every Human message must continue to
 * the channel-neutral semantic/runtime path so Workforce management, capability admission and
 * Worker execution remain the sole orchestration path. This class is retained temporarily only
 * to avoid a constructor/API break while the production acceptance line migrates.
 */
@Deprecated(forRemoval = true)
public final class TelegramInstitutionalWorkDispatcher {
    public TelegramInstitutionalWorkDispatcher(RepositoryAuditExecutionService repositoryAudit) {
        Objects.requireNonNull(repositoryAudit, "repositoryAudit");
    }

    public Optional<String> dispatch(MetatronInteraction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        return Optional.empty();
    }
}
