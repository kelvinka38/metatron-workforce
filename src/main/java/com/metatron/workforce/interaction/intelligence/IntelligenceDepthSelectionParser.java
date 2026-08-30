package com.metatron.workforce.interaction.intelligence;

import java.util.Locale;

/**
 * Explicit channel-neutral Human depth control.
 *
 * Slash commands are interface control syntax, not semantic intent classification. Natural
 * language remains frontier-model interpreted. This parser therefore recognizes only exact
 * control commands and never scans ordinary Human prose for keywords.
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
            default -> Selection.notControl();
        };
    }

    public record Selection(boolean control, Action action, IntelligenceDepth depth) {
        public static Selection notControl() { return new Selection(false, Action.NONE, null); }
        public static Selection select(IntelligenceDepth depth) { return new Selection(true, Action.SELECT, depth); }
        public static Selection automatic() { return new Selection(true, Action.AUTO, null); }
        public static Selection status() { return new Selection(true, Action.STATUS, null); }
    }

    public enum Action { NONE, SELECT, AUTO, STATUS }
}
