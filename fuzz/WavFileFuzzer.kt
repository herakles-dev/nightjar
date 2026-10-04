package dev.herakles.nightjar.fuzz

import dev.herakles.nightjar.WavFile

/** Untrusted audio files reach WavFile.decodePcm16; it must return null/parsed, never throw. */
object WavFileFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        WavFile.decodePcm16(data)
    }
}
