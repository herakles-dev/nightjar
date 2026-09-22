package dev.herakles.nightjar.modules.fireflyjar

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.herakles.nightjar.BitmapPixelSurface
import dev.herakles.nightjar.ImageStegoCarrier
import dev.herakles.nightjar.R
import dev.herakles.nightjar.SturdyCoverPrep
import dev.herakles.nightjar.SturdyImageCarrier
import dev.herakles.nightjar.encodeSturdyJpeg
import dev.herakles.nightjar.incoming.SturdyImageFireflyDecoder
import dev.herakles.nightjar.modules.imagestego.encodePngBytes
import dev.herakles.nightjar.picker.Module
import dev.herakles.nightjar.share.FireflyShare
import dev.herakles.nightjar.share.OutgoingKind
import dev.herakles.nightjar.share.sturdyRefusalMessage
import dev.herakles.nightjar.toBitmap
import dev.herakles.nightjar.trail.TrailStateStore
import dev.herakles.nightjar.trail.TrailStep
import dev.herakles.nightjar.ui.theme.FireflyCreated
import dev.herakles.nightjar.ui.theme.JarActionCatchBorder
import dev.herakles.nightjar.ui.theme.JarActionCatchFill
import dev.herakles.nightjar.ui.theme.JarTextPrimary
import dev.herakles.nightjar.ui.theme.JarTextSecondary
import dev.herakles.nightjar.ui.theme.JarTextTertiary
import dev.herakles.nightjar.ui.theme.JarTileFill
import dev.herakles.nightjar.ui.theme.JarType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "hide one in a photo" (task W2-2, design/screen-flow.md's v6 "Two send flows", spec.md
 * gate-33): a NEW encode using the operator's own picked photo, entered by the single new row
 * `jarCatchFlow` (`ImageStegoScreen.kt`) adds to the art jar's action list. Photo Picker ->
 * message -> sturdy (default)/exact choice -> live capacity + estimated file size -> "hidden,
 * not locked" -> `hide it` (encode off-main, insert a CREATED [FireflyRecord], open the share
 * sheet) -- design/screen-flow.md's own wireframe: "no separate 'embedded' pause screen -- the
 * whole point of this flow is getting to the share sheet, not admiring the result."
 *
 * Owner direction (2026-09-22): embedding is CREATE, never CATCH -- this flow writes a
 * `direction = "CREATED"` record like every other embed site, and nothing in its own UI copy
 * uses "catch"/"caught" to describe what it does.
 *
 * Sturdy's fixed [SturdyImageCarrier.PAYLOAD_BYTES] (64 bytes, no length field of its own --
 * see that class's KDoc) can't carry an arbitrary-length message directly the way the exact-LSB
 * frame's own length field can. [sturdyPayloadBytes] pads a shorter message out to exactly 64
 * bytes with ASCII spaces (0x20) rather than zero bytes -- a receiving device's
 * [dev.herakles.nightjar.incoming.IncomingPipeline] decodes a `Caught` sturdy payload with a
 * plain `payload.decodeToString().take(40)` (no padding-aware trim of its own, out of this
 * task's scope to change), and trailing spaces read as harmless trailing whitespace there rather
 * than the literal NUL control characters trailing zero-padding would produce. This sender's own
 * [FireflyRecord] is written directly with the real, un-padded message text/byte count, so this
 * padding choice never reaches this device's own swarm/detail display -- only a receiving
 * device's generic decode path ever sees it.
 *
 * [trailStore]/[sendStepActive] exist only so `hide it` can call
 * [TrailStateStore.advance] the moment the share sheet actually opens, per the owner's
 * 2026-09-22 trail-hook update (design/riddle-trail.md § Welcome + game layer, commit 6b9cedf):
 * the SEND step's own glow now lives on the art jar's "hide one in a photo" ROW
 * (`ImageStegoScreen.kt`'s `JarActionRow`, via `Modifier.trailHighlight`), not inside this flow --
 * this composable renders no highlight of its own.
 */

/** A photo picked for "hide one in a photo": the full-fidelity decode plus the sturdy send-side
 *  prep ([dev.herakles.nightjar.prepareSturdyCover]) run once, at pick time, in `jarCatchFlow`. */
data class HidePhotoSelection(val original: Bitmap, val coverPrep: SturdyCoverPrep)

/** The two techniques this flow offers (design/screen-flow.md v6 "hide one in a photo" wireframe:
 *  "sturdy (default) or exact"). */
enum class HideTechnique { STURDY, EXACT }

@Composable
fun HideInPhotoFlow(
    selection: HidePhotoSelection,
    repository: FireflyRepository,
    trailStore: TrailStateStore,
    sendStepActive: Boolean,
    onCancel: () -> Unit,
    onSent: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var technique by remember { mutableStateOf(HideTechnique.STURDY) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var estimatedFileSizeBytes by remember { mutableStateOf<Long?>(null) }

    val exactCapacityBytes = remember(selection.original) { ImageStegoCarrier(selection.original).maxPayloadBytes }
    val messageBytes = hidePhotoByteCount(message)
    val capacityBytes = hidePhotoCapacityBytes(technique, exactCapacityBytes)
    val sturdyReady = selection.coverPrep is SturdyCoverPrep.Ready
    val canHide = !busy &&
        canHidePhotoMessage(messageBytes, capacityBytes) &&
        (technique == HideTechnique.EXACT || sturdyReady)

    // Live file-size estimate (Task #28's "a real number, not vague copy" precedent, reused
    // here): recomputed off the composition thread whenever the message or technique changes,
    // debounced so a fast typist doesn't trigger a fresh JPEG/PNG re-encode on every keystroke.
    LaunchedEffect(message, technique, selection) {
        if (!canHidePhotoMessage(messageBytes, capacityBytes) || (technique == HideTechnique.STURDY && !sturdyReady)) {
            estimatedFileSizeBytes = null
        } else {
            delay(ESTIMATE_DEBOUNCE_MILLIS)
            estimatedFileSizeBytes = withContext(Dispatchers.Default) {
                encodeForSend(selection, technique, message).bytes.size.toLong()
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HideBackRow(onClick = onCancel)

        Text(text = stringResource(R.string.send_message_label), style = JarType.SectionLabel, color = JarTextTertiary)
        BasicTextField(
            value = message,
            onValueChange = { message = it },
            singleLine = true,
            textStyle = JarType.Body.copy(color = JarTextPrimary),
            cursorBrush = SolidColor(JarTextPrimary),
            enabled = !busy,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(JarTileFill)
                .border(width = 1.dp, color = JarActionCatchBorder, shape = RoundedCornerShape(8.dp))
                .padding(12.dp),
            decorationBox = { innerTextField ->
                if (message.isEmpty()) {
                    Text(text = stringResource(R.string.send_message_hint), style = JarType.Body, color = JarTextTertiary)
                }
                innerTextField()
            },
        )

        HideTechniqueRow(
            label = stringResource(R.string.send_technique_sturdy),
            selected = technique == HideTechnique.STURDY,
            enabled = !busy,
            onClick = { technique = HideTechnique.STURDY },
        )
        HideTechniqueRow(
            label = stringResource(R.string.send_technique_exact),
            selected = technique == HideTechnique.EXACT,
            enabled = !busy,
            onClick = { technique = HideTechnique.EXACT },
        )

        if (technique == HideTechnique.STURDY && selection.coverPrep is SturdyCoverPrep.Rejected) {
            val longSide = maxOf(selection.original.width, selection.original.height)
            Text(text = sturdyRefusalMessage(context, longSide), style = JarType.Footer, color = JarTextTertiary)
        } else if (capacityBytes <= 0) {
            Text(text = stringResource(R.string.send_too_small_to_hide), style = JarType.Footer, color = JarTextTertiary)
        } else {
            val sizeLabel = estimatedFileSizeBytes?.let { fireflyMediaSizeLabel(it) } ?: "…"
            Text(
                text = stringResource(R.string.send_byte_and_size_line, messageBytes, capacityBytes, sizeLabel),
                style = JarType.Footer,
                color = JarTextTertiary,
            )
            Text(
                text = stringResource(
                    if (technique == HideTechnique.EXACT) R.string.send_hide_advice_exact else R.string.send_hide_advice_sturdy,
                ),
                style = JarType.Footer,
                color = JarTextTertiary,
            )
        }

        errorMessage?.let {
            Text(text = it, style = JarType.Footer, color = JarTextTertiary)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(JarActionCatchFill)
                .border(width = 1.dp, color = JarActionCatchBorder, shape = RoundedCornerShape(12.dp))
                .then(
                    if (canHide) {
                        Modifier.clickable {
                            coroutineScope.launch {
                                busy = true
                                errorMessage = null
                                try {
                                    val encoded = withContext(Dispatchers.Default) {
                                        encodeForSend(selection, technique, message)
                                    }
                                    repository.insertWithMedia(
                                        FireflyRecord(
                                            moduleId = Module.IMAGE_STEGANOGRAPHY.name,
                                            direction = "CREATED",
                                            timestampMillis = System.currentTimeMillis(),
                                            payloadSizeBytes = messageBytes,
                                            technique = encoded.technique,
                                            payloadPreview = message.take(40),
                                            carrierKind = "IMAGE",
                                        ),
                                        encoded.bytes,
                                        encoded.extension,
                                    )
                                    val uri = withContext(Dispatchers.IO) {
                                        FireflyShare.prepareOutgoing(context, encoded.bytes, encoded.outgoingKind)
                                    }
                                    context.startActivity(FireflyShare.shareIntent(uri, encoded.outgoingKind.mimeType))
                                    if (sendStepActive) trailStore.advance(TrailStep.SEND)
                                    onSent()
                                } catch (failure: Exception) {
                                    errorMessage = context.getString(R.string.send_hide_failed)
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = stringResource(if (busy) R.string.send_hiding_busy_label else R.string.send_hide_it),
                style = JarType.ButtonLabel,
                color = if (canHide) FireflyCreated else JarTextTertiary,
            )
        }
    }
}

@Composable
private fun HideBackRow(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = "← ${stringResource(R.string.send_back)}", style = JarType.BackLink, color = JarTextSecondary)
    }
}

@Composable
private fun HideTechniqueRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = JarType.TileTitle,
            color = if (selected) FireflyCreated else JarTextTertiary,
        )
    }
}

/** Debounce window for the live file-size estimate -- short enough to still feel live, long
 *  enough that a fast typist doesn't trigger a JPEG/PNG re-encode per keystroke. */
private const val ESTIMATE_DEBOUNCE_MILLIS = 250L

/** UTF-8 byte count of [message] -- multi-byte-safe (an emoji or accented character costs more
 *  than one byte), matching every other payload-size counter in this app
 *  (`ImageStegoScreen.kt`'s `payloadText.encodeToByteArray().size`). */
internal fun hidePhotoByteCount(message: String): Int = message.encodeToByteArray().size

/** [HideTechnique.STURDY]'s capacity is [SturdyImageCarrier.PAYLOAD_BYTES] -- fixed regardless of
 *  the picked photo (that class's own KDoc: "not capacity-derived from the cover"). Exact's
 *  capacity is whatever [ImageStegoCarrier.maxPayloadBytes] computes for the picked photo's own
 *  dimensions, passed in as [exactCapacityBytes] since building an [ImageStegoCarrier] has a real
 *  cost this pure function shouldn't repeat per call. */
internal fun hidePhotoCapacityBytes(technique: HideTechnique, exactCapacityBytes: Int): Int = when (technique) {
    HideTechnique.STURDY -> SturdyImageCarrier.PAYLOAD_BYTES
    HideTechnique.EXACT -> exactCapacityBytes
}

/** True once [messageBytes] is both non-empty and within [capacityBytes] -- the same "non-empty
 *  and within capacity" rule every other embed action in this app enables its confirm button on. */
internal fun canHidePhotoMessage(messageBytes: Int, capacityBytes: Int): Boolean =
    messageBytes in 1..capacityBytes

/** ASCII space -- see this file's own KDoc for why space, not a zero byte, pads a
 *  shorter-than-64-byte message out to [SturdyImageCarrier.PAYLOAD_BYTES]. */
private const val STURDY_PADDING_BYTE: Byte = 0x20

/** Pads [message]'s UTF-8 bytes out to exactly [SturdyImageCarrier.PAYLOAD_BYTES] with trailing
 *  ASCII spaces, or returns them unchanged if already exactly that length. Throws if [message] is
 *  longer than capacity -- callers gate `hide it` on [canHidePhotoMessage] first, so this should
 *  be unreachable in practice, same "should not happen, but crash loud rather than silently
 *  truncate" posture [ImageStegoCarrier.encode]'s own `require` calls take. */
internal fun sturdyPayloadBytes(message: String): ByteArray {
    val raw = message.encodeToByteArray()
    require(raw.size <= SturdyImageCarrier.PAYLOAD_BYTES) {
        "message (${raw.size} bytes) exceeds sturdy's fixed ${SturdyImageCarrier.PAYLOAD_BYTES}-byte payload"
    }
    if (raw.size == SturdyImageCarrier.PAYLOAD_BYTES) return raw
    return raw + ByteArray(SturdyImageCarrier.PAYLOAD_BYTES - raw.size) { STURDY_PADDING_BYTE }
}

/** What `hide it` actually produced: the bytes to persist/share, the extension
 *  [FireflyRepository.insertWithMedia] stores media under, the `technique` value the new
 *  [FireflyRecord] carries (matching [SturdyImageFireflyDecoder.STURDY_TECHNIQUE] for sturdy,
 *  null for exact -- the same convention the pre-v6 exact embed path in `ImageStegoScreen.kt`
 *  already uses), and the [OutgoingKind] the share sheet opens with. */
internal data class EncodedForSend(
    val bytes: ByteArray,
    val extension: String,
    val technique: String?,
    val outgoingKind: OutgoingKind,
)

/** Real CPU work (JPEG/PNG encode) -- callers run this off the composition thread
 *  ([kotlinx.coroutines.Dispatchers.Default]), same discipline every other encode call in this
 *  app follows. */
internal fun encodeForSend(selection: HidePhotoSelection, technique: HideTechnique, message: String): EncodedForSend =
    when (technique) {
        HideTechnique.STURDY -> {
            val cover = (selection.coverPrep as SturdyCoverPrep.Ready).bitmap
            val payload = sturdyPayloadBytes(message)
            val stegoSurface = SturdyImageCarrier(BitmapPixelSurface(cover)).encode(payload)
            val jpegBytes = encodeSturdyJpeg(stegoSurface.toBitmap())
            EncodedForSend(
                bytes = jpegBytes,
                extension = "jpg",
                technique = SturdyImageFireflyDecoder.STURDY_TECHNIQUE,
                outgoingKind = OutgoingKind.IMAGE_STURDY,
            )
        }
        HideTechnique.EXACT -> {
            val stego = ImageStegoCarrier(selection.original).encode(message.encodeToByteArray())
            EncodedForSend(
                bytes = encodePngBytes(stego),
                extension = "png",
                technique = null,
                outgoingKind = OutgoingKind.IMAGE_EXACT,
            )
        }
    }
