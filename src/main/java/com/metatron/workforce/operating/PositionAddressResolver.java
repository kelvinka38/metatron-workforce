package com.metatron.workforce.operating;

import com.metatron.workforce.core.WorkforceCoreService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves who an Objective is addressed to from what occupied Positions declare, never from planner-held names.
 *
 * <p>Only ACTIVE Workers with an ACTIVE participation bound to a standing Position contract are addressable. An
 * alias addresses a Worker only when the Objective opens with {@code "<alias>:"} (case-insensitive, leading
 * whitespace ignored, optionally after the explicit Objective-control prefix); a mention anywhere else addresses
 * nobody. An alias declared by more than one addressable Worker is never guessed: it fails as AMBIGUOUS_ADDRESS.
 * Deterministic and model-free.</p>
 */
public final class PositionAddressResolver {
    private static final Pattern CONTROL_PREFIX = Pattern.compile("(?iu)^\\s*take ownership of one objective:\\s*");

    private final WorkforceCoreService core;
    private final WorkerConstitutionService constitution;

    public PositionAddressResolver(WorkforceCoreService core, WorkerConstitutionService constitution) {
        this.core = core;
        this.constitution = constitution;
    }

    /** A resolver over no Positions: nothing is addressable. */
    public static PositionAddressResolver none() {
        return new PositionAddressResolver(null, null);
    }

    /** An addressable occupied Position. {@code capabilityRequirements} keep the contract's declared order. */
    public record Address(String workerId, String participationId, String positionRef, String roleRef,
                          List<String> capabilityRequirements, List<String> addressAliases) {
        public Address {
            capabilityRequirements = List.copyOf(capabilityRequirements);
            addressAliases = List.copyOf(addressAliases);
        }
    }

    /** Raised instead of routing when one address names more than one addressable Worker. */
    public static final class AmbiguousAddressException extends IllegalStateException {
        private final List<String> workerIds;

        AmbiguousAddressException(String address, List<String> workerIds) {
            super("AMBIGUOUS_ADDRESS:" + address + ":workers=" + String.join(",", workerIds)
                    + " (declare distinct address aliases, or address the Worker by its WORKER- id)");
            this.workerIds = List.copyOf(workerIds);
        }

        public List<String> workerIds() { return workerIds; }
    }

    /** The Worker the Objective opens by addressing through a declared alias, if any. */
    public Optional<String> resolve(String objectiveText) {
        return resolveAlias(objectiveText).map(Address::workerId);
    }

    public Optional<Address> resolveAlias(String objectiveText) {
        if (objectiveText == null || objectiveText.isBlank()) return Optional.empty();
        String text = CONTROL_PREFIX.matcher(objectiveText).replaceFirst("").stripLeading();
        Map<String, Address> matches = new LinkedHashMap<>();
        TreeSet<String> matchedAliases = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Address address : addressable()) {
            for (String alias : address.addressAliases()) {
                if (opensWith(text, alias)) {
                    matches.putIfAbsent(address.workerId(), address);
                    matchedAliases.add(alias);
                }
            }
        }
        if (matches.size() > 1) {
            throw new AmbiguousAddressException("alias=" + String.join("|", matchedAliases),
                    matches.keySet().stream().sorted().toList());
        }
        return matches.values().stream().findFirst();
    }

    /** The addressable Position occupied by an explicitly named Worker. */
    public Optional<Address> byWorker(String workerId) {
        if (workerId == null || workerId.isBlank()) return Optional.empty();
        return addressable().stream().filter(address -> address.workerId().equalsIgnoreCase(workerId.trim())).findFirst();
    }

    /** The addressable Worker occupying an explicitly named role. */
    public Optional<Address> byRole(String roleRef) {
        if (roleRef == null || roleRef.isBlank()) return Optional.empty();
        List<Address> matches = addressable().stream()
                .filter(address -> address.roleRef().equalsIgnoreCase(roleRef.trim()))
                .toList();
        List<String> workers = matches.stream().map(Address::workerId).distinct().sorted().toList();
        if (workers.size() > 1) throw new AmbiguousAddressException("role=" + roleRef.trim(), workers);
        return matches.stream().findFirst();
    }

    private List<Address> addressable() {
        if (core == null || constitution == null) return List.of();
        List<Address> addresses = new ArrayList<>();
        for (WorkforceCoreService.Worker worker : core.allWorkers()) {
            if (worker.status() != WorkforceCoreService.WorkerStatus.ACTIVE) continue;
            for (WorkforceCoreService.Participation participation : core.participations(worker.workerId())) {
                if (participation.status() != WorkforceCoreService.ParticipationStatus.ACTIVE) continue;
                constitution.binding(worker.workerId(), participation.participationId())
                        .flatMap(binding -> constitution.contractForPosition(binding.positionRef())
                                .filter(contract -> contract.contractId().equals(binding.contractId())))
                        .map(contract -> new Address(worker.workerId(), participation.participationId(),
                                contract.positionRef(), contract.roleRef(), contract.capabilityRequirements(),
                                contract.addressAliases()))
                        .ifPresent(addresses::add);
            }
        }
        return addresses;
    }

    private static boolean opensWith(String text, String alias) {
        String words = Arrays.stream(alias.trim().split("\\s+")).map(Pattern::quote).collect(Collectors.joining("\\s+"));
        return Pattern.compile("^" + words + "\\s*:", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).find();
    }
}
