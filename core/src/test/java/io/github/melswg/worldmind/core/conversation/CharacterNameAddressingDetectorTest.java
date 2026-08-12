package io.github.melswg.worldmind.core.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CharacterNameAddressingDetectorTest {
    private final CharacterNameAddressingDetector detector = new CharacterNameAddressingDetector("Майни");

    @Test
    void recognizesExactWholeMessageAddressAcrossCasePunctuationAndYoNormalization() {
        assertEquals(AddressingSignal.EXACT, detector.detect("Майни"));
        assertEquals(AddressingSignal.EXACT, detector.detect("майни"));
        assertEquals(AddressingSignal.EXACT, detector.detect("«Майни!»"));
        assertEquals(AddressingSignal.EXACT, new CharacterNameAddressingDetector("Елка").detect("ёлка"));
    }

    @Test
    void recognizesLikelyMentionAndBoundedTypoWithoutDecidingIntent() {
        assertEquals(AddressingSignal.LIKELY, detector.detect("Майни, ты здесь?"));
        assertEquals(AddressingSignal.LIKELY, detector.detect("майне"));
        assertEquals(AddressingSignal.LIKELY, new CharacterNameAddressingDetector("Старый Маяк")
            .detect("Старый Маяк сегодня виден издалека"));
    }

    @Test
    void rejectsUnrelatedSubstringsAndKeepsFuzzyMatchingConservativeForShortNames() {
        assertEquals(AddressingSignal.NONE, detector.detect("майник уже построен"));
        assertEquals(AddressingSignal.NONE, new CharacterNameAddressingDetector("Ли").detect("лиса в лесу"));
        assertEquals(AddressingSignal.NONE, new CharacterNameAddressingDetector("Ли").detect("Ле"));
    }
}
