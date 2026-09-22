package dev.herakles.nightjar

import android.util.Log

/**
 * Debug-build-only runtime state probe ("Runtime Verification Surface").
 *
 * A single logcat-tagged ([TAG] = `COVERT_DEBUG`) JSON line, re-emitted on every state
 * change, reporting:
 *  - the most recent [CovertCarrier] encode/decode outcome ([reportEncodeDecodeResult])
 *  - the active [CovertDetector]'s confidence score, if any ([reportDetectorConfidence] /
 *    [clearDetector])
 *  - the current top-level screen ([reportScreen])
 *  - the Firefly Jar's stored-media state (v4 addition): record count, count carrying
 *    media, total media bytes, and orphan-file count ([reportStoredMedia])
 *  - the v6 addition's send state: the last outgoing send (technique, cache file bytes, the
 *    FileProvider authority used, [reportOutgoingSend]) and the outgoing cache directory's
 *    current file count ([reportOutgoingFileCount]). Sourced from
 *    [dev.herakles.nightjar.share.FireflyShare.prepareOutgoing]/`sweepOutgoing`.
 *  - the last incoming file the receive pipeline handled (v6 addition): action,
 *    sniffed type, detected technique, and outcome ([reportIncoming])
 *  - the riddle trail's own progress (v6 addition): current step, steps done, and
 *    whether the trail was skipped ([reportTrail])
 *
 * Deliberately small and injectable: any screen/controller can call a `report*` method
 * opportunistically as its own state changes, with no shared mutable coupling beyond this
 * object. Only [reportScreen] is currently wired into `MainActivity`'s module-picker navigation
 * to prove the mechanism (`adb logcat --filter COVERT_DEBUG` shows valid JSON on a screen
 * change); per-module wiring of the other two report methods follows as the modem/detector
 * screens stabilize.
 *
 * Every public method is a no-op in release builds — nothing is logged, and
 * `BuildConfig.DEBUG` is checked before any work happens, so a release build never even
 * updates the tracked state.
 */
object DebugProbe {

    /** logcat tag this probe writes to; also the `adb logcat --filter` value. */
    const val TAG = "COVERT_DEBUG"

    /** Which half of a [CovertCarrier] round-trip a report describes. */
    enum class Operation { ENCODE, DECODE }

    /** Snapshot of the most recent [CovertCarrier.encode]/[CovertCarrier.decode] outcome. */
    data class EncodeDecodeResult(
        val module: ModuleId,
        val operation: Operation,
        val success: Boolean,
        val timestampMs: Long,
    )

    /** Snapshot of the most recently observed [CovertDetector.analyze] confidence score. */
    data class DetectorState(
        val module: ModuleId,
        val confidence: Float,
        val timestampMs: Long,
    )

    /**
     * Snapshot of the Firefly Jar's stored-media state (v4 addition): "the same dump gains
     * stored-media state -- record count, count carrying media, total media bytes, and
     * orphan-file count". Sourced from
     * [dev.herakles.nightjar.modules.fireflyjar.FireflyRepository.probeSnapshot], so retention,
     * clear-all, per-firefly delete and the orphan sweep are all assertable as queryable state
     * rather than judged from a screenshot. [orphanFileCount] counts every currently-unreferenced
     * file (`FireflyMediaStore.countUnreferenced`), not only what the age-gated sweep would
     * reclaim if run at that instant -- see that function's KDoc for why.
     */
    data class StoredMediaState(
        val recordCount: Int,
        val recordsWithMediaCount: Int,
        val totalMediaBytes: Long,
        val orphanFileCount: Int,
    )

    /**
     * Snapshot of the most recent [dev.herakles.nightjar.share.FireflyShare.prepareOutgoing]
     * call (v6 addition): "the last send (technique, cache file bytes, authority used)".
     * [technique] carries whatever the caller's `OutgoingKind.name` was --
     * this class stays a plain string so it has no compile-time dependency on the `share`
     * package, same discipline [EncodeDecodeResult] already follows by taking [ModuleId] rather
     * than importing a carrier type.
     */
    data class OutgoingSendState(
        val technique: String,
        val bytes: Long,
        val authority: String,
        val timestampMs: Long,
    )

    /**
     * Snapshot of the most recent incoming file the receive pipeline handled (v6 addition):
     * "the dump gains the last incoming file (action, MIME type, detected technique, outcome)".
     * Sourced from [dev.herakles.nightjar.incoming.IncomingPipeline.route], so `adb shell am
     * start ... ACTION_SEND`/`ACTION_VIEW` bridge-intent injection is assertable as queryable
     * state rather than judged from a screenshot. [sniffedType] is the magic-byte-sniffed
     * container (`dev.herakles.nightjar.incoming.SniffedType.name`, or `"UNREADABLE"` if the file
     * couldn't even be read), not the caller-declared MIME type. [technique] is non-null only for
     * a `caught` [outcome].
     */
    data class IncomingState(
        val action: String,
        val sniffedType: String,
        val technique: String?,
        val outcome: String,
        val timestampMs: Long,
    )

    /**
     * Snapshot of the riddle trail's own progress (v6 addition): "the trail state (current
     * step, steps done, skipped)". Sourced from [dev.herakles.nightjar.trail.TrailStateStore.state], so a fresh
     * install's step-1 start, "start the trail again," skip, and each step's own completion are
     * all assertable as queryable state rather than judged from a screenshot. [currentStep]/
     * [completedSteps] are already-resolved [dev.herakles.nightjar.trail.TrailStep.id] strings --
     * this file stays free of a compile-time dependency on the trail package, same discipline
     * [OutgoingSendState] already follows for `OutgoingKind`. No timestamp field: like
     * [StoredMediaState], this is a plain state snapshot, not a discrete timestamped event.
     */
    data class TrailProbeState(
        val currentStep: String?,
        val completedSteps: List<String>,
        val skipped: Boolean,
    )

    /** Everything the `COVERT_DEBUG` dump reports. */
    data class ProbeState(
        val lastEncodeDecode: EncodeDecodeResult? = null,
        val detector: DetectorState? = null,
        val screen: String? = null,
        val storedMedia: StoredMediaState? = null,
        val lastSend: OutgoingSendState? = null,
        val outgoingFileCount: Int? = null,
        val incoming: IncomingState? = null,
        val trail: TrailProbeState? = null,
    )

    @Volatile
    private var state = ProbeState()

    /**
     * Record an encode/decode outcome and log the full updated state dump. No-op in release
     * builds. Callers pass the outcome they already computed (e.g. from a [DecodeResult]) —
     * this never re-derives success/failure itself.
     */
    fun reportEncodeDecodeResult(
        module: ModuleId,
        operation: Operation,
        success: Boolean,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        if (!BuildConfig.DEBUG) return
        state = state.copy(
            lastEncodeDecode = EncodeDecodeResult(module, operation, success, timestampMs),
        )
        emit()
    }

    /** Record a detector confidence sample and log the full updated state dump. No-op in release builds. */
    fun reportDetectorConfidence(
        module: ModuleId,
        confidence: Float,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        if (!BuildConfig.DEBUG) return
        state = state.copy(detector = DetectorState(module, confidence, timestampMs))
        emit()
    }

    /**
     * Mark no detector as currently active (e.g. its screen was left/stopped) and log the
     * updated state dump. No-op in release builds.
     */
    fun clearDetector() {
        if (!BuildConfig.DEBUG) return
        state = state.copy(detector = null)
        emit()
    }

    /** Record the current top-level screen and log the full updated state dump. No-op in release builds. */
    fun reportScreen(screenName: String) {
        if (!BuildConfig.DEBUG) return
        state = state.copy(screen = screenName)
        emit()
    }

    /**
     * Record a Firefly Jar stored-media snapshot and log the full updated state dump. No-op in
     * release builds. Callers pass the counts they already computed (e.g. from
     * [dev.herakles.nightjar.modules.fireflyjar.FireflyRepository.probeSnapshot]) -- this never
     * re-derives them itself, same discipline [reportEncodeDecodeResult] already follows.
     */
    fun reportStoredMedia(
        recordCount: Int,
        recordsWithMediaCount: Int,
        totalMediaBytes: Long,
        orphanFileCount: Int,
    ) {
        if (!BuildConfig.DEBUG) return
        state = state.copy(
            storedMedia = StoredMediaState(recordCount, recordsWithMediaCount, totalMediaBytes, orphanFileCount),
        )
        emit()
    }

    /**
     * Record an outgoing-send outcome (v6 addition) and log the full updated state
     * dump. No-op in release builds. Callers (currently only
     * [dev.herakles.nightjar.share.FireflyShare.prepareOutgoing]) pass the values they already
     * computed -- this never re-derives them itself, same discipline [reportEncodeDecodeResult]
     * follows.
     */
    fun reportOutgoingSend(
        technique: String,
        bytes: Long,
        authority: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        if (!BuildConfig.DEBUG) return
        state = state.copy(lastSend = OutgoingSendState(technique, bytes, authority, timestampMs))
        emit()
    }

    /**
     * Record the outgoing cache directory's current file count (v6 addition) and log
     * the full updated state dump. No-op in release builds.
     */
    fun reportOutgoingFileCount(count: Int) {
        if (!BuildConfig.DEBUG) return
        state = state.copy(outgoingFileCount = count)
        emit()
    }

    /**
     * Record the receive pipeline's most recent incoming-file outcome and log the updated state
     * dump. No-op in release builds. Callers (
     * [dev.herakles.nightjar.incoming.IncomingPipeline.route]) pass the outcome they already
     * computed -- this never re-derives it itself, same discipline [reportEncodeDecodeResult]
     * and [reportStoredMedia] already follow.
     */
    fun reportIncoming(
        action: String,
        sniffedType: String,
        technique: String?,
        outcome: String,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        if (!BuildConfig.DEBUG) return
        state = state.copy(incoming = IncomingState(action, sniffedType, technique, outcome, timestampMs))
        emit()
    }

    /**
     * Record the riddle trail's current progress and log the updated state dump (v6 addition).
     * No-op in release builds. Callers (
     * [dev.herakles.nightjar.trail.TrailStateStore] via `MainActivity`'s own collector) pass
     * already-resolved [dev.herakles.nightjar.trail.TrailStep.id] strings -- this never re-derives
     * them itself, same discipline every other `report*` method here follows.
     */
    fun reportTrail(currentStep: String?, completedSteps: List<String>, skipped: Boolean) {
        if (!BuildConfig.DEBUG) return
        state = state.copy(trail = TrailProbeState(currentStep, completedSteps, skipped))
        emit()
    }

    private fun emit() {
        Log.d(TAG, toJson(state))
    }
}

/**
 * Renders [state] as the `COVERT_DEBUG` JSON line.
 *
 * A free function, not a [DebugProbe] member, and hand-rolled without an `org.json`
 * dependency — that keeps it a pure, plain-JVM-testable unit with no Android/BuildConfig
 * surface, so `DebugProbeTest` can exercise the exact JSON shape directly and
 * deterministically regardless of build variant, matching CovertModule.kt's precedent of
 * keeping shared contracts Android-import-free where possible.
 */
internal fun toJson(state: DebugProbe.ProbeState): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"last_encode_decode\":")
    appendEncodeDecode(sb, state.lastEncodeDecode)
    sb.append(",\"detector_confidence\":")
    appendDetector(sb, state.detector)
    sb.append(",\"screen\":")
    appendJsonString(sb, state.screen)
    sb.append(",\"stored_media\":")
    appendStoredMedia(sb, state.storedMedia)
    sb.append(",\"last_send\":")
    appendOutgoingSend(sb, state.lastSend)
    sb.append(",\"outgoing_file_count\":")
    appendNullableInt(sb, state.outgoingFileCount)
    sb.append(",\"incoming\":")
    appendIncoming(sb, state.incoming)
    sb.append(",\"trail\":")
    appendTrail(sb, state.trail)
    sb.append('}')
    return sb.toString()
}

private fun appendEncodeDecode(sb: StringBuilder, result: DebugProbe.EncodeDecodeResult?) {
    if (result == null) {
        sb.append("null")
        return
    }
    sb.append('{')
    sb.append("\"module\":")
    appendJsonString(sb, result.module.name)
    sb.append(",\"operation\":")
    appendJsonString(sb, result.operation.name)
    sb.append(",\"success\":").append(result.success)
    sb.append(",\"timestamp_ms\":").append(result.timestampMs)
    sb.append('}')
}

private fun appendDetector(sb: StringBuilder, detector: DebugProbe.DetectorState?) {
    if (detector == null) {
        sb.append("null")
        return
    }
    sb.append('{')
    sb.append("\"module\":")
    appendJsonString(sb, detector.module.name)
    sb.append(",\"confidence\":").append(detector.confidence)
    sb.append(",\"timestamp_ms\":").append(detector.timestampMs)
    sb.append('}')
}

private fun appendStoredMedia(sb: StringBuilder, storedMedia: DebugProbe.StoredMediaState?) {
    if (storedMedia == null) {
        sb.append("null")
        return
    }
    sb.append('{')
    sb.append("\"record_count\":").append(storedMedia.recordCount)
    sb.append(",\"records_with_media_count\":").append(storedMedia.recordsWithMediaCount)
    sb.append(",\"total_media_bytes\":").append(storedMedia.totalMediaBytes)
    sb.append(",\"orphan_file_count\":").append(storedMedia.orphanFileCount)
    sb.append('}')
}

private fun appendOutgoingSend(sb: StringBuilder, send: DebugProbe.OutgoingSendState?) {
    if (send == null) {
        sb.append("null")
        return
    }
    sb.append('{')
    sb.append("\"technique\":")
    appendJsonString(sb, send.technique)
    sb.append(",\"bytes\":").append(send.bytes)
    sb.append(",\"authority\":")
    appendJsonString(sb, send.authority)
    sb.append(",\"timestamp_ms\":").append(send.timestampMs)
    sb.append('}')
}

private fun appendNullableInt(sb: StringBuilder, value: Int?) {
    if (value == null) sb.append("null") else sb.append(value)
}

private fun appendIncoming(sb: StringBuilder, incoming: DebugProbe.IncomingState?) {
    if (incoming == null) {
        sb.append("null")
        return
    }
    sb.append('{')
    sb.append("\"action\":")
    appendJsonString(sb, incoming.action)
    sb.append(",\"sniffed_type\":")
    appendJsonString(sb, incoming.sniffedType)
    sb.append(",\"technique\":")
    appendJsonString(sb, incoming.technique)
    sb.append(",\"outcome\":")
    appendJsonString(sb, incoming.outcome)
    sb.append(",\"timestamp_ms\":").append(incoming.timestampMs)
    sb.append('}')
}

private fun appendTrail(sb: StringBuilder, trail: DebugProbe.TrailProbeState?) {
    if (trail == null) {
        sb.append("null")
        return
    }
    sb.append('{')
    sb.append("\"current_step\":")
    appendJsonString(sb, trail.currentStep)
    sb.append(",\"completed_steps\":[")
    trail.completedSteps.forEachIndexed { index, step ->
        if (index > 0) sb.append(',')
        appendJsonString(sb, step)
    }
    sb.append(']')
    sb.append(",\"skipped\":").append(trail.skipped)
    sb.append('}')
}

private fun appendJsonString(sb: StringBuilder, value: String?) {
    if (value == null) {
        sb.append("null")
        return
    }
    sb.append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) {
                sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            } else {
                sb.append(c)
            }
        }
    }
    sb.append('"')
}
