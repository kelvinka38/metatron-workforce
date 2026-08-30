package com.metatron.workforce.interaction.intelligence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntelligenceDepthSelectionParserTest {
    private final IntelligenceDepthSelectionParser parser = new IntelligenceDepthSelectionParser();

    @Test void exactControlsAreRecognized() {
        assertEquals(IntelligenceDepth.FAST, parser.parse("/fast").depth());
        assertEquals(IntelligenceDepth.ANALYZE, parser.parse("/analyze").depth());
        assertEquals(IntelligenceDepth.DEEP, parser.parse("/deep").depth());
        assertEquals(IntelligenceDepthSelectionParser.Action.AUTO, parser.parse("/auto").action());
        assertEquals(IntelligenceDepthSelectionParser.Action.STATUS, parser.parse("/mode").action());
    }

    @Test void humanReadableProviderLabelsMapToTheSameCanonicalControls() {
        assertEquals(IntelligenceDepth.FAST, parser.parse("⚡ Fast").depth());
        assertEquals(IntelligenceDepth.ANALYZE, parser.parse("🧠 Analyze").depth());
        assertEquals(IntelligenceDepth.DEEP, parser.parse("🔬 Deep").depth());
        assertEquals(IntelligenceDepthSelectionParser.Action.AUTO, parser.parse("🤖 Auto").action());
        assertEquals(IntelligenceDepthSelectionParser.Action.STATUS, parser.parse("🎛 Mode").action());
    }

    @Test void malformedDepthControlsRemainTerminalControlInput() {
        assertEquals(IntelligenceDepthSelectionParser.Action.INVALID, parser.parse("/deep now").action());
        assertEquals(IntelligenceDepthSelectionParser.Action.INVALID, parser.parse("/depth wat").action());
        assertEquals(IntelligenceDepthSelectionParser.Action.INVALID, parser.parse("/auto please").action());
        assertTrue(parser.parse("/fast\tplease").control());
    }

    @Test void proseAndUnrelatedSlashCommandsAreNotClassifiedByKeywords() {
        assertFalse(parser.parse("deep audit this").control());
        assertFalse(parser.parse("fast please").control());
        assertFalse(parser.parse("/start").control());
        assertFalse(parser.parse("/deeper").control());
    }
}
