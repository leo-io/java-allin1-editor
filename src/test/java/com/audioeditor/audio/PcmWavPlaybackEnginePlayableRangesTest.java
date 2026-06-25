package com.audioeditor.audio;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PcmWavPlaybackEnginePlayableRangesTest {

    @Test
    void nullRangesMeanUnrestrictedButEmptyRangesMeanNoPlayableAudio() throws Exception {
        PcmWavPlaybackEngine engine = engineWithAudioGeometry();

        engine.setPlayableTimeRangesSeconds(null);
        assertNull(playableFrameRanges(engine));
        assertEquals(100, invokeLong(engine, "playableIntervalEndContaining", 0L, null));

        engine.setPlayableTimeRangesSeconds(new double[0]);
        long[] ranges = playableFrameRanges(engine);
        assertNotNull(ranges);
        assertEquals(0, ranges.length);
        assertEquals(-1, invokeLong(engine, "playableIntervalEndContaining", 0L, ranges));
        assertEquals(-1, invokeLong(engine, "nextPlayableStartAfter", 0L, ranges));
    }

    @Test
    void playableOffsetsStitchAcrossDeletedGaps() throws Exception {
        PcmWavPlaybackEngine engine = engineWithAudioGeometry();

        engine.setPlayableTimeRangesSeconds(new double[]{1.0, 2.0, 5.0, 7.0});
        long[] ranges = playableFrameRanges(engine);
        assertArrayEquals(new long[]{10, 20, 50, 70}, ranges);

        assertEquals(0, invokeLong(engine, "sourceFrameToPlayableOffset", 10L, ranges));
        assertEquals(5, invokeLong(engine, "sourceFrameToPlayableOffset", 15L, ranges));
        assertEquals(10, invokeLong(engine, "sourceFrameToPlayableOffset", 20L, ranges));
        assertEquals(10, invokeLong(engine, "sourceFrameToPlayableOffset", 49L, ranges));
        assertEquals(10, invokeLong(engine, "sourceFrameToPlayableOffset", 50L, ranges));
        assertEquals(25, invokeLong(engine, "sourceFrameToPlayableOffset", 65L, ranges));
        assertEquals(30, invokeLong(engine, "sourceFrameToPlayableOffset", 100L, ranges));

        assertEquals(10, invokeLong(engine, "playableOffsetToSourceFrame", 0L, ranges));
        assertEquals(19, invokeLong(engine, "playableOffsetToSourceFrame", 9L, ranges));
        assertEquals(50, invokeLong(engine, "playableOffsetToSourceFrame", 10L, ranges));
        assertEquals(65, invokeLong(engine, "playableOffsetToSourceFrame", 25L, ranges));
        assertEquals(70, invokeLong(engine, "playableOffsetToSourceFrame", 30L, ranges));
    }

    private static PcmWavPlaybackEngine engineWithAudioGeometry() throws Exception {
        PcmWavPlaybackEngine engine = new PcmWavPlaybackEngine();
        setField(engine, "sampleRateInHz", 10f);
        setField(engine, "totalAudioFrameCount", 100L);
        return engine;
    }

    private static long[] playableFrameRanges(PcmWavPlaybackEngine engine) throws Exception {
        Field field = PcmWavPlaybackEngine.class.getDeclaredField("playableFrameRanges");
        field.setAccessible(true);
        return (long[]) field.get(engine);
    }

    private static void setField(PcmWavPlaybackEngine engine, String name, Object value) throws Exception {
        Field field = PcmWavPlaybackEngine.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(engine, value);
    }

    private static long invokeLong(PcmWavPlaybackEngine engine, String name, long frame, long[] ranges) throws Exception {
        Method method = PcmWavPlaybackEngine.class.getDeclaredMethod(name, long.class, long[].class);
        method.setAccessible(true);
        return (long) method.invoke(engine, frame, ranges);
    }
}
