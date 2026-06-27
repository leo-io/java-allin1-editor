package com.audioeditor.audio;

import java.util.Arrays;

/** Pure conversion and mapping functions for stitched playable audio ranges. */
public final class PlayableFrameRanges {

    private PlayableFrameRanges() {
    }

    public static long[] normalize(double[] secondPairs, float sampleRate, long totalFrames) {
        if (secondPairs == null) {
            return null;
        }
        if (secondPairs.length < 2) {
            return new long[0];
        }
        long total = totalFrames > 0 ? totalFrames : Long.MAX_VALUE;
        long[][] pairs = new long[secondPairs.length / 2][2];
        int count = 0;
        for (int index = 0; index + 1 < secondPairs.length; index += 2) {
            long start = Math.max(0, Math.round(secondPairs[index] * sampleRate));
            long end = Math.min(total, Math.round(secondPairs[index + 1] * sampleRate));
            if (end > start) {
                pairs[count][0] = start;
                pairs[count][1] = end;
                count++;
            }
        }
        if (count == 0) {
            return new long[0];
        }
        Arrays.sort(pairs, 0, count, (left, right) -> Long.compare(left[0], right[0]));
        long[] merged = new long[count * 2];
        int output = 0;
        long currentStart = pairs[0][0];
        long currentEnd = pairs[0][1];
        for (int index = 1; index < count; index++) {
            if (pairs[index][0] <= currentEnd) {
                currentEnd = Math.max(currentEnd, pairs[index][1]);
            } else {
                merged[output++] = currentStart;
                merged[output++] = currentEnd;
                currentStart = pairs[index][0];
                currentEnd = pairs[index][1];
            }
        }
        merged[output++] = currentStart;
        merged[output++] = currentEnd;
        return Arrays.copyOf(merged, output);
    }

    public static long intervalEndContaining(long frame, long[] ranges, long totalFrames) {
        if (ranges == null) {
            return frame < totalFrames ? totalFrames : -1;
        }
        for (int index = 0; index < ranges.length; index += 2) {
            if (frame >= ranges[index] && frame < ranges[index + 1]) {
                return ranges[index + 1];
            }
        }
        return -1;
    }

    public static long nextStartAtOrAfter(long frame, long[] ranges, long totalFrames) {
        if (ranges == null) {
            return frame < totalFrames ? Math.max(0, frame) : -1;
        }
        for (int index = 0; index < ranges.length; index += 2) {
            if (ranges[index] >= frame) {
                return ranges[index];
            }
        }
        return -1;
    }

    public static long firstStart(long[] ranges) {
        return ranges == null ? 0 : ranges.length == 0 ? -1 : ranges[0];
    }

    public static long sourceToPlayableOffset(long sourceFrame, long[] ranges, long totalFrames) {
        long frame = Math.max(0, Math.min(sourceFrame, totalFrames));
        if (ranges == null) {
            return frame;
        }
        long offset = 0;
        for (int index = 0; index < ranges.length; index += 2) {
            long start = ranges[index];
            long end = ranges[index + 1];
            if (frame <= start) {
                return offset;
            }
            if (frame < end) {
                return offset + frame - start;
            }
            offset += end - start;
        }
        return offset;
    }

    public static long playableOffsetToSource(long playableOffset, long[] ranges, long totalFrames) {
        long offset = Math.max(0, playableOffset);
        if (ranges == null) {
            return Math.max(0, Math.min(offset, totalFrames));
        }
        if (ranges.length == 0) {
            return 0;
        }
        for (int index = 0; index < ranges.length; index += 2) {
            long start = ranges[index];
            long end = ranges[index + 1];
            long length = end - start;
            if (offset < length) {
                return start + offset;
            }
            offset -= length;
        }
        return ranges[ranges.length - 1];
    }
}
