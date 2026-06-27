package com.audioeditor.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayableFrameRangesTest {

    @Test
    void normalizesAndStitchesRangesWithoutAnAudioDevice() {
        long[] ranges = PlayableFrameRanges.normalize(
                new double[]{5, 7, 1, 2, 1.5, 3}, 10, 100);

        assertArrayEquals(new long[]{10, 30, 50, 70}, ranges);
        assertEquals(20, PlayableFrameRanges.sourceToPlayableOffset(50, ranges, 100));
        assertEquals(50, PlayableFrameRanges.playableOffsetToSource(20, ranges, 100));
    }
}
