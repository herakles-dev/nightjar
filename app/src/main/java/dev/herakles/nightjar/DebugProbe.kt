package dev.herakles.nightjar

import android.util.Log

/**
 * Debug-build-only runtime state probe (spec.md "Runtime Verification Surface", task #18).
 *
 * A single logcat-tagged ([TAG] = `COVERT_DEBUG`) JSON line, re-emitted on every state
 * change, reporting:
 *  - the most recent [CovertCarrier] encode/decode outcome ([reportEncodeDecodeResult])
 *  - the active [CovertDetector]'s confidence score, if any ([reportDetectorConfidence] /
 *    [clearDetector])
 *  - the current top-level screen ([reportScreen])
 *  - the Firefly Jar's stored-media state (v4 addition, gate-20): record count, count carrying
 *    media, total media bytes, and orphan-file count ([reportStoredMedia])
 *
 * Deliberately small and injectable: any screen/controller can call a `report*` method
 * opportunistically as its own state changes, with no shared mutable coupling beyond this
 * object. Task #18 only wires [reportScreen] into `MainActivity`'s module-picker navigation
 * to prove the mechanism (spec.md gate: `hek logcat --filter COVERT_DEBUG` shows valid JSON
 * on a screen change); per-module wiring of the other two report methods follows as the
 * modem/detector screens stabilize (architecture.md task #7/#9/#11/#12 are concurrent work).
 *
 * Every public method is a no-op in release builds — nothing is logged, and
 * `BuildConfig.DEBUG` is checked before any work happens, so a release build never even
 * updates the tracked state.
 */
object DebugProbe {

    /** logcat tag this probe writes to; also the `hek logcat --filter` value from spec.md. */
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
     * Snapshot of the Firefly Jar's stored-media state (v4 addition, gate-20's probe contract --
     * spec.md's Runtime Verification Surface: "the same dump gains stored-media state -- record
     * count, count carrying media, total media bytes, and orphan-file count"). Sourced from
     * [dev.herakles.nightjar.modules.fireflyjar.FireflyRepository.probeSnapshot], so retention,
     * clear-all and per-firefly delete are all assertable as queryable state rather than judged
     * from a screenshot. [orphanFileCount] is currently always 0 -- see `probeSnapshot`'s own
     * KDoc for the known, documented gap; the orphan sweep itself still runs and reclaims files
     * correctly, only this field can't yet report how many it found.
     */
    data class StoredMediaState(
        val recordCount: Int,
        val recordsWithMediaCount: Int,
        val totalMediaBytes: Long,
        val orphanFileCount: Int,
    )

    /** Everything the `COVERT_DEBUG` dump reports, per spec.md's Runtime Verification Surface. */
    data class ProbeState(
        val lastEncodeDecode: EncodeDecodeResult? = null,
        val detector: DetectorState? = null,
        val screen: String? = null,
        val storedMedia: StoredMediaState? = null,
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
