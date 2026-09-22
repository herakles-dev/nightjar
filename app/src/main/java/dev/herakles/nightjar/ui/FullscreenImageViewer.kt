package dev.herakles.nightjar.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.herakles.nightjar.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * Owner-requested (v6 addition) — a reusable fullscreen image viewer shared by both visual
 * languages in this app: the technical screens and the Firefly Jar
 * disguise surface. Deliberately palette-neutral (a near-black
 * scrim, [TextPrimary] for the one glyph drawn on it) rather than bound to either identity's
 * tokens — a modal photo-viewer overlay reads the same way regardless of which surface opened it,
 * the same way a platform photo viewer would.
 *
 * Gestures: pinch-to-zoom and pan (bounds-constrained so the image can never be lost off-screen —
 * see [clampPanOffset]), double-tap to toggle between fit and [DOUBLE_TAP_ZOOM_SCALE], and three
 * ways to dismiss — system/predictive back, a single tap on the image or surrounding scrim, or
 * the small close glyph in the corner. No external gesture library: [detectTransformGestures] and
 * [detectTapGestures] are both plain `androidx.compose.foundation.gestures`, already a transitive
 * dependency of this project's `material3` artifact.
 *
 * The zoom/pan math below ([clampZoomScale], [fitContentSize], [maxPanOffset],
 * [clampPanOffset]) is plain `Float` arithmetic with no Compose or Android type in its signature —
 * same "pure, `internal`, directly testable" split this codebase already uses for its other
 * display-logic functions (e.g. `audioCarrierViewOptions`, `fireflyCapacityLine`).
 */

/** Minimum zoom — always exactly "fit," never smaller. Combined with [clampPanOffset] this is
 *  what makes "the image can't be lost off-screen" true: at this scale the content is never
 *  larger than the viewport in either axis, so the pan clamp always resolves to zero. */
internal const val MIN_ZOOM_SCALE = 1f

/** Maximum pinch zoom. Loose but not unbounded — five times fit is already well past the point a
 *  96dp thumbnail's source pixels stop offering anything new to see. */
internal const val MAX_ZOOM_SCALE = 5f

/** Where a double-tap zooms in to, when it isn't just returning to fit. */
internal const val DOUBLE_TAP_ZOOM_SCALE = 2.5f

/** Platform default motion (the platform's motion/icon doctrine § Color & Type Defaults: "default
 *  duration 200ms, default easing ease-out (cubic-bezier(0.2, 0.0, 0.0, 1.0))"). Used here rather
 *  than either identity doc's own motion rule, since this component sits outside both — it's
 *  cross-app shared UI, not a surface either doctrine owns, so the platform baseline is the
 *  correct default to reach for. */
private val ViewerEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val ZOOM_ANIM_MS = 220

/** Clamps a zoom factor to [MIN_ZOOM_SCALE, MAX_ZOOM_SCALE]. Pure. */
internal fun clampZoomScale(scale: Float, min: Float = MIN_ZOOM_SCALE, max: Float = MAX_ZOOM_SCALE): Float =
    scale.coerceIn(min, max)

/**
 * `ContentScale.Fit`'s own sizing rule, worked out by hand so it's a plain testable function
 * rather than something only knowable by rendering a real `Image`: the largest box with
 * [sourceWidth]/[sourceHeight]'s aspect ratio that fits inside a [containerWidth]x[containerHeight]
 * box. Degenerates to the container's own size when either input is non-positive, so a caller mid
 * first-layout-pass (container not measured yet, or an empty bitmap) gets a harmless zero-size
 * result instead of a divide-by-zero.
 */
internal fun fitContentSize(
    sourceWidth: Int,
    sourceHeight: Int,
    containerWidth: Float,
    containerHeight: Float,
): Pair<Float, Float> {
    if (sourceWidth <= 0 || sourceHeight <= 0 || containerWidth <= 0f || containerHeight <= 0f) {
        return containerWidth to containerHeight
    }
    val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
    val containerAspect = containerWidth / containerHeight
    return if (sourceAspect > containerAspect) {
        containerWidth to (containerWidth / sourceAspect)
    } else {
        (containerHeight * sourceAspect) to containerHeight
    }
}

/** Half the amount [contentWidth]x[contentHeight] overhangs [containerWidth]x[containerHeight]
 *  once scaled by [scale] — the farthest a pan can go in each axis before the content's own edge
 *  would pull inside the viewport and leave a gap on the opposite side. Zero in an axis where the
 *  scaled content still fits (this is what pins offset to (0,0) at [MIN_ZOOM_SCALE]). */
internal fun maxPanOffset(
    contentWidth: Float,
    contentHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
    scale: Float,
): Pair<Float, Float> {
    val maxX = ((contentWidth * scale - containerWidth) / 2f).coerceAtLeast(0f)
    val maxY = ((contentHeight * scale - containerHeight) / 2f).coerceAtLeast(0f)
    return maxX to maxY
}

/** Clamps a proposed pan to [maxPanOffset] — the bounds-constraint the brief calls for, applied
 *  every frame a pinch/pan gesture moves the image, so the content can never drift off-screen with
 *  no way back. Pure. */
internal fun clampPanOffset(
    offsetX: Float,
    offsetY: Float,
    contentWidth: Float,
    contentHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
    scale: Float,
): Pair<Float, Float> {
    val (maxX, maxY) = maxPanOffset(contentWidth, contentHeight, containerWidth, containerHeight, scale)
    return offsetX.coerceIn(-maxX, maxX) to offsetY.coerceIn(-maxY, maxY)
}

/**
 * Fullscreen, pinch-zoomable, pannable [bitmap] over a dark scrim. [contentDescription] should be
 * the same honest string the inline preview already uses (e.g. the P5-fixed
 * `fireflyImageContentDescription` on the Firefly Jar surface) — this composable never invents its
 * own.
 *
 * Hosted in a [Dialog] rather than composed directly into the caller's layout tree — an earlier
 * version did the latter and, confirmed on device (`dumpsys window windows` showed only the
 * MainActivity window, no Dialog/Popup), rendered as an inline band inside the caller's own
 * scrolling `Column` instead of covering the screen: the underlying content stayed visible around
 * and through it, with no scrim over any of it. A [Dialog] with `usePlatformDefaultWidth = false`
 * and `decorFitsSystemWindows = false` gets its own window sized to the full display, which is
 * what "fullscreen" actually requires here — [Box.fillMaxSize] only ever fills the space its
 * *parent* already gave it, and a caller's `Column` slot was never that. `dismissOnBackPress` is
 * off in favor of the explicit [BackHandler] below, so there's exactly one back-dismiss path, not
 * two racing to invoke [onDismiss].
 */
@Composable
fun FullscreenImageViewer(
    bitmap: Bitmap,
    contentDescription: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        FullscreenImageViewerContent(bitmap = bitmap, contentDescription = contentDescription, onDismiss = onDismiss)
    }
}

/** The viewer's actual content, pulled out of [FullscreenImageViewer] so the [Dialog] wrapper
 *  above stays a thin, obviously-correct shell around it. */
@Composable
private fun FullscreenImageViewerContent(
    bitmap: Bitmap,
    contentDescription: String,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(MIN_ZOOM_SCALE) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var containerSize by remember { mutableStateOf(Size.Zero) }

    val contentSize = remember(bitmap, containerSize) {
        val (w, h) = fitContentSize(bitmap.width, bitmap.height, containerSize.width, containerSize.height)
        Size(w, h)
    }

    fun clamped(newScale: Float, targetX: Float, targetY: Float): Pair<Float, Float> = clampPanOffset(
        offsetX = targetX,
        offsetY = targetY,
        contentWidth = contentSize.width,
        contentHeight = contentSize.height,
        containerWidth = containerSize.width,
        containerHeight = containerSize.height,
        scale = newScale,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            .onSizeChanged { containerSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = {
                        scope.launch {
                            if (scale.value > MIN_ZOOM_SCALE) {
                                scale.animateTo(MIN_ZOOM_SCALE, tween(ZOOM_ANIM_MS, easing = ViewerEasing))
                                offsetX.animateTo(0f, tween(ZOOM_ANIM_MS, easing = ViewerEasing))
                                offsetY.animateTo(0f, tween(ZOOM_ANIM_MS, easing = ViewerEasing))
                            } else {
                                val (cx, cy) = clamped(DOUBLE_TAP_ZOOM_SCALE, offsetX.value, offsetY.value)
                                scale.animateTo(DOUBLE_TAP_ZOOM_SCALE, tween(ZOOM_ANIM_MS, easing = ViewerEasing))
                                offsetX.animateTo(cx, tween(ZOOM_ANIM_MS, easing = ViewerEasing))
                                offsetY.animateTo(cy, tween(ZOOM_ANIM_MS, easing = ViewerEasing))
                            }
                        }
                    },
                    onTap = { onDismiss() },
                )
            }
            .pointerInput(bitmap) {
                detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                    val newScale = clampZoomScale(scale.value * zoom)
                    val (cx, cy) = clamped(newScale, offsetX.value + pan.x, offsetY.value + pan.y)
                    scope.launch {
                        scale.snapTo(newScale)
                        offsetX.snapTo(cx)
                        offsetY.snapTo(cy)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offsetX.value
                    translationY = offsetY.value
                },
        )

        GlyphChip(
            onClick = onDismiss,
            onClickLabel = "close",
            tint = TextPrimary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            draw = { drawCloseGlyph(it) },
        )
    }
}

/**
 * The subtle "this image opens fullscreen" affordance (owner-requested tap affordance pass):
 * a small corner-bracket glyph, drawn rather than pulled from an icon font (the "custom-drawn
 * over generic glyph" default this app follows),
 * sized to read as a quiet hint, not a button. Purely decorative — no `clickable`, no semantics of
 * its own — so it never adds a second TalkBack stop next to the image it sits on; the image itself
 * carries the `onClickLabel`.
 */
@Composable
fun ExpandGlyph(modifier: Modifier = Modifier, tint: Color) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(10.dp)) { drawExpandGlyph(tint) }
    }
}

/** Shared chrome for [ExpandGlyph] and the viewer's own close affordance — a small flat, sharp-
 *  cornered scrim square (never a circle, never a drop shadow) behind a hand-drawn glyph. */
@Composable
private fun GlyphChip(
    onClick: () -> Unit,
    onClickLabel: String,
    tint: Color,
    modifier: Modifier = Modifier,
    draw: DrawScope.(Color) -> Unit,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(onClickLabel = onClickLabel, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(16.dp)) { draw(tint) }
    }
}

/** Two opposite corner brackets — the smallest silhouette that still reads unambiguously as
 *  "expand," at 24x24 or smaller (the platform's motion/icon doctrine). Flat strokes
 *  only: no fill, no glow, no inner shadow. */
private fun DrawScope.drawExpandGlyph(color: Color) {
    val armLength = size.width * 0.42f
    val stroke = (size.width * 0.14f).coerceAtLeast(1.2f)
    // top-left corner
    drawLine(color, Offset(0f, armLength), Offset(0f, 0f), stroke, StrokeCap.Round)
    drawLine(color, Offset(0f, 0f), Offset(armLength, 0f), stroke, StrokeCap.Round)
    // bottom-right corner
    drawLine(color, Offset(size.width, size.height - armLength), Offset(size.width, size.height), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width, size.height), Offset(size.width - armLength, size.height), stroke, StrokeCap.Round)
}

/** A plain "x" — the viewer's close affordance, same flat-stroke discipline as [drawExpandGlyph]. */
private fun DrawScope.drawCloseGlyph(color: Color) {
    val inset = size.width * 0.24f
    val stroke = (size.width * 0.12f).coerceAtLeast(1.2f)
    drawLine(color, Offset(inset, inset), Offset(size.width - inset, size.height - inset), stroke, StrokeCap.Round)
    drawLine(color, Offset(size.width - inset, inset), Offset(inset, size.height - inset), stroke, StrokeCap.Round)
}

@Preview(name = "Fullscreen Image Viewer", showBackground = true, backgroundColor = 0xFF0D1117)
@Composable
private fun FullscreenImageViewerPreview() {
    val previewBitmap = remember {
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF30363D.toInt()) }
    }
    FullscreenImageViewer(
        bitmap = previewBitmap,
        contentDescription = "preview image",
        onDismiss = {},
    )
}
