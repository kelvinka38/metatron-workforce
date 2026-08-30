package com.metatron.workforce.interaction.intelligence;

import java.util.Locale;

/**
 * Explicit channel-neutral Human depth control.
 *
 * Slash commands are interface control syntax, not semantic intent classification. Natural
 * language remains frontier-model interpreted. This parser therefore recognizes only the
 * depth-control namespace and never scans ordinary Human prose for keywords. Invalid syntax
 * inside that namespace is still terminal control input so it cannot leak into semantic/LLM
 * interpretation.
 */
public final class IntelligenceDepthSelectionParser {
    public Selection parse(String text) {
        if (text == null) return Selection.notControl();
        String command = text.trim().toLowerCase(Locale.ROOT);
        return switch (command) {
            case "/fast" -> Selection.select(IntelligenceDepth.FAST);
            case "/analyze", "/analyse" -> Selection.select(IntelligenceDepth.ANALYZE);
            case "/deep" -> Selection.select(IntelligenceDepth.DEEP);
            case "/auto" -> Selection.automatic();
            case "/mode", "/depth" -> Selection.status();
            default -> isDepthControlNamespace(command) ? Selection.invalid() : Selection.notControl();
        };
    }

    private static boolean isDepthControlNamespace(String command) {
        return hasArguments(command, "/fast")
                || hasArguments(command, "/analyze")
                || hasArguments(command, "/analyse")
                || hasArguments(command, "/deep")
                || hasArguments(command, "/auto")
                || hasArguments(command, "/mode")
                || hasArguments(command, "/depth");
    }

    private static boolean hasArguments(String command, String control) {
        return command.startsWith(control + " ") || command.startsWith(control + "\t");
    }

    public record Selection(boolean control, Action action, IntelligenceDepth depth) {
        public static Selection notControl() { return new Selection(false, Action.NONE, null); }
        public static Selection select(IntelligenceDepth depth) { return new Selection(true, Action.SELECT, depth); }
        public static Selection automatic() { return new Selection(true, Action.AUTO, null); }
        public static Selection status() { return new Selection(true, Action.STATUS, null); }
        public static Selection invalid() { return new Selection(true, Action.INVALID, null); }
    }

    public enum Action { NONE, SELECT, AUTO, STATUS, INVALID }
}
