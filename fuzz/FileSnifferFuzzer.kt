package dev.herakles.nightjar.fuzz

import dev.herakles.nightjar.incoming.FileSniffer

/** Every shared/opened file is sniffed by magic bytes first; arbitrary bytes must classify, not throw. */
object FileSnifferFuzzer {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        FileSniffer.sniff(data)
    }
}
