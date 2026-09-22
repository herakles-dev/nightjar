package dev.herakles.nightjar.modules.fireflyjar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Owner report (on-device, 2026-09-22): the firefly detail's message card was silently showing
 * only the first 40 characters of longer messages. [messageTruncationNotice] is the honest
 * disclosure shown on the rare occasions [FireflyRecord.payloadPreview] is still shorter than
 * the real message (beyond [MAX_STORED_MESSAGE_CHARS]) — never a silent cut.
 */
class MessageTruncationNoticeTest {

    @Test
    fun `no notice when the preview holds the whole message`() {
        assertNull(messageTruncationNotice("hello", payloadSizeBytes = 5))
    }

    @Test
    fun `no notice when the preview is exactly as long as the real payload`() {
        // e.g. a firefly caught before this fix but whose original message happened to be
        // exactly 40 bytes -- shownBytes == payloadSizeBytes, not less than, so still whole.
        val exact = "x".repeat(40)
        assertNull(messageTruncationNotice(exact, payloadSizeBytes = 40))
    }

    @Test
    fun `notice fires when the preview is shorter than the real payload`() {
        assertEquals(
            "showing the first 5 of 9000 bytes",
            messageTruncationNotice("hello", payloadSizeBytes = 9000),
        )
    }

    @Test
    fun `byte count, not character count, drives the comparison for multi-byte UTF-8`() {
        // "café" is 4 characters but 5 UTF-8 bytes (the "é" is 2 bytes) -- a preview that looks
        // char-for-char complete must still compare on bytes, since payloadSizeBytes always is.
        val preview = "café"
        assertEquals(5, preview.toByteArray(Charsets.UTF_8).size)
        assertNull(messageTruncationNotice(preview, payloadSizeBytes = 5))
        assertEquals(
            "showing the first 5 of 6 bytes",
            messageTruncationNotice(preview, payloadSizeBytes = 6),
        )
    }

    @Test
    fun `a firefly caught before this fix (old 40-char cap) still gets an honest notice`() {
        // Regression guard: pre-existing rows already have a payloadPreview capped at 40 chars
        // by the old .take(40) call sites. Those rows must keep disclosing truncation rather
        // than silently reading as complete just because they predate this fix.
        val oldPreview = "a".repeat(40)
        assertEquals(
            "showing the first 40 of 142 bytes",
            messageTruncationNotice(oldPreview, payloadSizeBytes = 142),
        )
    }
}
