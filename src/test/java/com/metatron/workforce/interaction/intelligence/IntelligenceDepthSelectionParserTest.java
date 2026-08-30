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

    @Test void proseIsNotClassifiedByKeywords() {
        assertFalse(parser.parse("deep audit this").control());
        assertFalse(parser.parse("fast please").control());
    }
}
