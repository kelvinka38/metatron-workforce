package com.metatron.workforce.interaction.intelligence;

import java.util.Objects;

/** Channel-neutral Human control surface for the approved FAST / ANALYZE / DEEP depth contract. */
public final class IntelligenceDepthControlService {
    private static final String USAGE =
            "Depth controls: ⚡ Fast, 🧠 Analyze, 🔬 Deep, 🤖 Auto, 🎛 Mode (slash commands remain supported).";

    private final IntelligenceDepthPreferenceStore store;
    private final IntelligenceDepthSelectionParser parser;

    public IntelligenceDepthControlService(IntelligenceDepthPreferenceStore store) {
        this(store, new IntelligenceDepthSelectionParser());
    }

    IntelligenceDepthControlService(IntelligenceDepthPreferenceStore store, IntelligenceDepthSelectionParser parser) {
        this.store = Objects.requireNonNull(store, "store");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public ControlResult handle(String conversationId, String text) {
        IntelligenceDepthSelectionParser.Selection selection = parser.parse(text);
        if (!selection.control()) return ControlResult.notControl(store.get(conversationId));
        return switch (selection.action()) {
            case SELECT -> {
                store.set(conversationId, selection.depth());
                yield new ControlResult(true, IntelligenceDepthContract.selected(selection.depth()),
                        signature(IntelligenceDepthContract.selected(selection.depth()))
                                + "\nMode enabled. This changes reasoning depth/resources only; it does not create authority.");
            }
            case AUTO -> {
                store.clear(conversationId);
                yield new ControlResult(true, IntelligenceDepthContract.automatic(),
                        signature(IntelligenceDepthContract.automatic())
                                + "\nMetatron will select FAST / ANALYZE / DEEP semantically for each request.");
            }
            case STATUS -> {
                IntelligenceDepthContract current = store.get(conversationId);
                yield new ControlResult(true, current, signature(current) + "\n" + renderStatus(current));
            }
            case INVALID -> new ControlResult(true, store.get(conversationId),
                    signature(store.get(conversationId)) + "\nInvalid intelligence-depth control. " + USAGE);
            case NONE -> ControlResult.notControl(store.get(conversationId));
        };
    }

    public IntelligenceDepthContract contract(String conversationId) {
        return store.get(conversationId);
    }

    public String responseSignature(String conversationId) {
        return signature(store.get(conversationId));
    }

    static String signature(IntelligenceDepthContract contract) {
        if (!contract.explicitlySelected()) return "🤖 AUTO · METATRON";
        return switch (contract.selectedDepth()) {
            case FAST -> "⚡ FAST · METATRON";
            case ANALYZE -> "🧠 ANALYZE · METATRON";
            case DEEP -> "🔬 DEEP · METATRON";
        };
    }

    private static String renderStatus(IntelligenceDepthContract contract) {
        return contract.explicitlySelected()
                ? "Current depth: " + contract.selectedDepth() + " (Human-selected)."
                : "Current depth: AUTO (semantic selection per request).";
    }

    public record ControlResult(boolean controlHandled, IntelligenceDepthContract contract, String response) {
        static ControlResult notControl(IntelligenceDepthContract contract) {
            return new ControlResult(false, contract, "");
        }
    }
}
