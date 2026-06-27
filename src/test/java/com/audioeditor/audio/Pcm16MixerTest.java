package com.audioeditor.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class Pcm16MixerTest {

    @Test
    void saturatesPositiveAndNegativeSamples() {
        byte[] destination = samples((short) 30_000, (short) -30_000);
        byte[] source = samples((short) 10_000, (short) -10_000);

        Pcm16Mixer.add(destination, 0, source, 0, 2, 1);

        assertArrayEquals(samples(Short.MAX_VALUE, Short.MIN_VALUE), destination);
    }

    private byte[] samples(short... values) {
        byte[] bytes = new byte[values.length * 2];
        for (int index = 0; index < values.length; index++) {
            bytes[index * 2] = (byte) values[index];
            bytes[index * 2 + 1] = (byte) (values[index] >> 8);
        }
        return bytes;
    }
}
