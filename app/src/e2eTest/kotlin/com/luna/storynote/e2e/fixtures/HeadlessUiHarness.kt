package com.luna.storynote.e2e.fixtures

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.luna.storynote.BookStudioController
import com.luna.storynote.model.StudioUiState
import com.luna.storynote.ui.StoryStudioRoot
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayInputStream
import java.lang.reflect.Field
import java.util.Collections
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.math.abs

data class SemanticsBounds(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float,
    val density: Float,
) {
    val widthPx: Float get() = rightPx - leftPx
    val heightPx: Float get() = bottomPx - topPx
    val centerX: Float get() = (leftPx + rightPx) / 2f
    val centerY: Float get() = (topPx + bottomPx) / 2f
    val leftDp: Float get() = leftPx / density
    val topDp: Float get() = topPx / density
    val rightDp: Float get() = rightPx / density
    val bottomDp: Float get() = bottomPx / density
    val widthDp: Float get() = widthPx / density
    val heightDp: Float get() = heightPx / density
}

data class CapturedSemanticsNode(
    val id: Int,
    val testTag: String?,
    val texts: List<String>,
    val contentDescriptions: List<String>,
    val bounds: SemanticsBounds,
)

data class RenderedFrame(
    val pngBytes: ByteArray,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val density: Float,
    val semanticsNodes: List<CapturedSemanticsNode>,
    val semanticsTexts: List<String>,
    val contentDescriptions: List<String>,
    val testTags: List<String>,
    val semanticsBounds: Map<String, Pair<Float, Float>>,
    val nodeRects: Map<String, SemanticsBounds>,
    val hapticEvents: List<HapticFeedbackType>,
) {
    fun hasTag(tag: String): Boolean = testTags.contains(tag)

    fun boundsForTag(tag: String): SemanticsBounds? = nodeRects[tag]

    fun countTagsWithPrefix(prefix: String): Int =
        testTags.count { it.startsWith(prefix) }

    fun hasText(substring: String): Boolean =
        semanticsTexts.any { it.contains(substring) }

    fun isValidPng(): Boolean =
        pngBytes.size > 32 &&
            pngBytes[0] == 0x89.toByte() &&
            pngBytes[1] == 0x50.toByte() &&
            pngBytes[2] == 0x4E.toByte() &&
            pngBytes[3] == 0x47.toByte()

    fun distinctColorBuckets(): Int {
        val img = ImageIO.read(ByteArrayInputStream(pngBytes)) ?: return 0
        val buckets = HashSet<Int>()
        val stepX = (img.width / 32).coerceAtLeast(1)
        val stepY = (img.height / 32).coerceAtLeast(1)
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                val rgb = img.getRGB(x, y)
                val q = ((rgb ushr 20) and 0xF shl 8) or ((rgb ushr 12) and 0xF shl 4) or ((rgb ushr 4) and 0xF)
                buckets.add(q)
                x += stepX
            }
            y += stepY
        }
        return buckets.size
    }

    fun pixelDiffRatio(other: RenderedFrame): Double {
        val a = ImageIO.read(ByteArrayInputStream(pngBytes)) ?: return 1.0
        val b = ImageIO.read(ByteArrayInputStream(other.pngBytes)) ?: return 1.0
        if (a.width != b.width || a.height != b.height) return 1.0
        var diff = 0
        var total = 0
        val stepX = (a.width / 48).coerceAtLeast(1)
        val stepY = (a.height / 48).coerceAtLeast(1)
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val pa = a.getRGB(x, y)
                val pb = b.getRGB(x, y)
                val d = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF)) +
                    abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF)) +
                    abs((pa and 0xFF) - (pb and 0xFF))
                if (d > 12) diff++
                total++
                x += stepX
            }
            y += stepY
        }
        return if (total == 0) 0.0 else diff.toDouble() / total.toDouble()
    }
}

class StudioSceneSession(
    private val controller: BookStudioController,
    widthDp: Int,
    heightDp: Int,
    private val densityValue: Float = 2f,
    private val fixedUiState: StudioUiState? = null,
) : AutoCloseable {
    private val widthPx = (widthDp * densityValue).toInt().coerceAtLeast(1)
    private val heightPx = (heightDp * densityValue).toInt().coerceAtLeast(1)
    private val recordedHaptics: MutableList<HapticFeedbackType> =
        Collections.synchronizedList(mutableListOf())
    private var virtualTimeNs: Long = 0L

    private val recordingHaptics = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            recordedHaptics.add(hapticFeedbackType)
        }
    }

    private val scene = ImageComposeScene(
        width = widthPx,
        height = heightPx,
        density = Density(densityValue),
    ).apply {
        setContent {
            val state = fixedUiState ?: controller.uiState.collectAsState().value
            MaterialTheme {
                CompositionLocalProvider(LocalHapticFeedback provides recordingHaptics) {
                    StoryStudioRoot(uiState = state, controller = controller)
                }
            }
        }
    }

    val haptics: List<HapticFeedbackType>
        get() = recordedHaptics.toList()

    fun clearHaptics() {
        recordedHaptics.clear()
    }

    fun renderFrames(frames: Int = 16, frameStepNs: Long = 160_000_000L): RenderedFrame {
        drainSwingEdt()
        var img = scene.render(virtualTimeNs)
        for (i in 1 until frames.coerceAtLeast(1)) {
            virtualTimeNs += frameStepNs
            drainSwingEdt()
            img = scene.render(virtualTimeNs)
        }
        if (scene.hasInvalidations()) {
            drainSwingEdt()
            img = scene.render(virtualTimeNs)
        }
        val bytes = img.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?: throw IllegalStateException("Headless PNG encoding failed")
        val nodes = collectAllSemanticsNodes()
        val texts = nodes.flatMap { it.texts }.distinct()
        val descs = nodes.flatMap { it.contentDescriptions }.distinct()
        val tags = nodes.mapNotNull { it.testTag }.distinct()
        val centerMap = LinkedHashMap<String, Pair<Float, Float>>()
        val rectMap = LinkedHashMap<String, SemanticsBounds>()
        for (n in nodes) {
            val tag = n.testTag
            if (tag != null) {
                centerMap[tag] = n.bounds.centerX to n.bounds.centerY
                rectMap[tag] = n.bounds
            }
        }
        return RenderedFrame(
            pngBytes = bytes,
            imageWidthPx = widthPx,
            imageHeightPx = heightPx,
            density = densityValue,
            semanticsNodes = nodes,
            semanticsTexts = texts,
            contentDescriptions = descs,
            testTags = tags,
            semanticsBounds = centerMap,
            nodeRects = rectMap,
            hapticEvents = recordedHaptics.toList(),
        )
    }

    fun tapTag(tag: String, settleFrames: Int = 8): RenderedFrame {
        val current = renderFrames(frames = 2, frameStepNs = 16_000_000L)
        val bounds = current.boundsForTag(tag)
            ?: throw AssertionError("Cannot tap missing semantics tag '$tag'. Available tags: ${current.testTags}")
        return tapAt(bounds.centerX, bounds.centerY, settleFrames)
    }

    fun tapAt(xPx: Float, yPx: Float, settleFrames: Int = 8): RenderedFrame {
        val tMs = (virtualTimeNs / 1_000_000L) + 16L
        scene.sendPointerEvent(
            eventType = PointerEventType.Press,
            position = Offset(xPx, yPx),
            timeMillis = tMs,
        )
        virtualTimeNs += 32_000_000L
        scene.render(virtualTimeNs)
        scene.sendPointerEvent(
            eventType = PointerEventType.Release,
            position = Offset(xPx, yPx),
            timeMillis = tMs + 32L,
        )
        return renderFrames(frames = settleFrames, frameStepNs = 50_000_000L)
    }

    fun longPressTag(tag: String, holdMs: Long = 750L, settleFrames: Int = 8): RenderedFrame {
        val current = renderFrames(frames = 2, frameStepNs = 16_000_000L)
        val bounds = current.boundsForTag(tag)
            ?: throw AssertionError("Cannot long-press missing semantics tag '$tag'. Available tags: ${current.testTags}")
        val startMs = (virtualTimeNs / 1_000_000L) + 16L
        scene.sendPointerEvent(
            eventType = PointerEventType.Press,
            position = Offset(bounds.centerX, bounds.centerY),
            timeMillis = startMs,
        )
        val steps = ((holdMs / 50L).toInt()).coerceAtLeast(1)
        for (i in 1..steps) {
            virtualTimeNs += 50_000_000L
            scene.render(virtualTimeNs)
        }
        Thread.sleep(holdMs.coerceAtMost(800L))
        virtualTimeNs += 50_000_000L
        scene.render(virtualTimeNs)
        scene.sendPointerEvent(
            eventType = PointerEventType.Release,
            position = Offset(bounds.centerX, bounds.centerY),
            timeMillis = startMs + holdMs + 50L,
        )
        return renderFrames(frames = settleFrames, frameStepNs = 50_000_000L)
    }

    private fun collectAllSemanticsNodes(): List<CapturedSemanticsNode> {
        val owners = extractSemanticsOwners()
        val out = ArrayList<CapturedSemanticsNode>()
        val seenIds = HashSet<Int>()
        for (owner in owners) {
            walkSemantics(owner.unmergedRootSemanticsNode, seenIds, out)
            walkSemantics(owner.rootSemanticsNode, seenIds, out)
        }
        return out
    }

    private fun walkSemantics(
        node: SemanticsNode,
        seen: MutableSet<Int>,
        out: MutableList<CapturedSemanticsNode>,
    ) {
        if (seen.add(node.id)) {
            val cfg = node.config
            val tag = cfg.getOrNull(SemanticsProperties.TestTag)
            val texts = cfg.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
            val descs = cfg.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val winRect = node.boundsInWindow
            val rect = if (winRect.right - winRect.left > 0f || winRect.bottom - winRect.top > 0f) {
                winRect
            } else {
                node.boundsInRoot
            }
            out.add(
                CapturedSemanticsNode(
                    id = node.id,
                    testTag = tag,
                    texts = texts,
                    contentDescriptions = descs,
                    bounds = SemanticsBounds(
                        leftPx = rect.left,
                        topPx = rect.top,
                        rightPx = rect.right,
                        bottomPx = rect.bottom,
                        density = densityValue,
                    ),
                ),
            )
        }
        for (child in node.children) {
            walkSemantics(child, seen, out)
        }
    }

    private fun drainSwingEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeAndWait {}
        }
    }

    private fun extractSemanticsOwners(): List<SemanticsOwner> {
        val owners = ArrayList<SemanticsOwner>()
        val sceneField = findField(scene::class.java, "scene")
            ?: throw IllegalStateException("ImageComposeScene.scene field not found via reflection")
        sceneField.isAccessible = true
        val composeScene = sceneField.get(scene)
            ?: throw IllegalStateException("ImageComposeScene.scene is null")

        val mainOwnerField = findField(composeScene::class.java, "mainOwner")
            ?: throw IllegalStateException("${composeScene::class.java.name}.mainOwner field not found via reflection")
        mainOwnerField.isAccessible = true
        val mainOwner = mainOwnerField.get(composeScene)
            ?: throw IllegalStateException("${composeScene::class.java.name}.mainOwner is null")
        owners.add(invokeSemanticsOwnerGetter(mainOwner))

        val layersField = findField(composeScene::class.java, "layers")
            ?: throw IllegalStateException("${composeScene::class.java.name}.layers field not found via reflection")
        layersField.isAccessible = true
        val layers = layersField.get(composeScene) as? List<*>
            ?: throw IllegalStateException("${composeScene::class.java.name}.layers is not a List")
        for (layer in layers) {
            if (layer == null) continue
            val ownerGetter = (layer::class.java.methods + layer::class.java.declaredMethods)
                .firstOrNull { it.name == "getOwner" && it.parameterCount == 0 }
                ?: throw IllegalStateException("${layer::class.java.name}.getOwner() not found")
            ownerGetter.isAccessible = true
            val rootOwner = ownerGetter.invoke(layer)
                ?: throw IllegalStateException("${layer::class.java.name}.getOwner() returned null")
            owners.add(invokeSemanticsOwnerGetter(rootOwner))
        }
        return owners
    }

    private fun invokeSemanticsOwnerGetter(rootNodeOwner: Any?): SemanticsOwner {
        val target = rootNodeOwner
            ?: throw IllegalStateException("RootNodeOwner is null when extracting SemanticsOwner")
        val getter = (target::class.java.methods + target::class.java.declaredMethods)
            .firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            ?: throw IllegalStateException("${target::class.java.name}.getSemanticsOwner() not found")
        getter.isAccessible = true
        return getter.invoke(target) as? SemanticsOwner
            ?: throw IllegalStateException("${target::class.java.name}.getSemanticsOwner() returned null")
    }

    private fun findField(cls: Class<*>, name: String): Field? {
        var cur: Class<*>? = cls
        while (cur != null) {
            try {
                return cur.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                cur = cur.superclass
            }
        }
        return null
    }

    override fun close() {
        scene.close()
    }
}

object HeadlessUiHarness {
    val PHONE = 390 to 844
    val TABLET_PORTRAIT = 560 to 900
    val TABLET_LANDSCAPE_MIN = 853 to 533
    val TABLET_LANDSCAPE = 900 to 560

    fun openSession(
        controller: BookStudioController,
        widthDp: Int,
        heightDp: Int,
        density: Float = 2f,
        fixedUiState: StudioUiState? = null,
    ): StudioSceneSession = StudioSceneSession(
        controller = controller,
        widthDp = widthDp,
        heightDp = heightDp,
        densityValue = density,
        fixedUiState = fixedUiState,
    )

    fun renderStudio(
        controller: BookStudioController,
        widthDp: Int,
        heightDp: Int,
        density: Float = 2f,
        frames: Int = 16,
        fixedUiState: StudioUiState? = null,
    ): RenderedFrame {
        openSession(controller, widthDp, heightDp, density, fixedUiState).use { session ->
            return session.renderFrames(frames = frames)
        }
    }
}
