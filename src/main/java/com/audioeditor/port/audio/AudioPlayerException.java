package com.audioeditor.port.audio;

/** Failure while decoding audio or opening the platform playback device. */
public final class AudioPlayerException extends Exception {

    public AudioPlayerException(String message, Throwable cause) {
        super(message, cause);
    }
}
