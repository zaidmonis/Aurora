package thezaxis.aurora.domain

import kotlin.math.max
import kotlin.math.min

enum class ConfigKind { APP, GROUP }
enum class Edge { LEFT, RIGHT, TOP, BOTTOM }
enum class AuroraColor(val argb: Long) {
    PINK(0xFFFF4F86), CORAL(0xFFFF785A), SUN(0xFFFFC857), MINT(0xFF45E6A8),
    CYAN(0xFF40D9FF), PERIWINKLE(0xFF8692FF), VIOLET(0xFFB179FF)
}
enum class GlowAnimation { BREATHE, AURORA, COMET, PULSE, EMBER, NEON, WAVE }
/** How a notification peek enters from its matching edge light. */
enum class PreviewCardAnimation { EDGE_SLIDE, SEGMENT_GROW }

data class EdgeLightConfig(
    val id: String,
    val kind: ConfigKind,
    val name: String,
    val packageNames: Set<String>,
    val enabled: Boolean = true,
    val edge: Edge = Edge.RIGHT,
    val positionFraction: Float = .5f,
    val lengthFraction: Float = .12f,
    val thicknessDp: Float = 3f,
    val bloomDp: Float = 10f,
    val colorToken: AuroraColor = AuroraColor.MINT,
    val animation: GlowAnimation = GlowAnimation.BREATHE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun sanitized() = copy(
        name = name.trim().take(60),
        packageNames = packageNames.filter { it.isNotBlank() }.toSet(),
        positionFraction = positionFraction.coerceIn(0f, 1f),
        lengthFraction = lengthFraction.coerceIn(.04f, 1f),
        thicknessDp = thicknessDp.coerceIn(0f, 12f),
        bloomDp = bloomDp.coerceIn(0f, 32f)
    )
}

data class AuroraPreferences(
    val tapToOpen: Boolean = true,
    val swipeToOpen: Boolean = true,
    val openOnArrival: Boolean = false,
    val clearAfterReading: Boolean = false,
    val quietPackageNames: Set<String> = emptySet(),
    val showOnLockScreen: Boolean = false,
    val wakeForNewNotifications: Boolean = false,
    val wakeBrightnessPercent: Int = 12,
    val wakeDurationSeconds: Int = 15,
    val showClock: Boolean = true,
    val previewCardAnimation: PreviewCardAnimation = PreviewCardAnimation.SEGMENT_GROW,
    val entered: Boolean = false
) {
    fun sanitized() = copy(wakeBrightnessPercent = wakeBrightnessPercent.coerceIn(1, 100), wakeDurationSeconds = wakeDurationSeconds.coerceIn(3, 60))
}

data class ActiveAuroraNotification(
    val key: String,
    val packageName: String,
    val timestamp: Long,
    val appLabel: String,
    val title: String,
    val text: String,
    val configId: String,
    val contentIntent: android.app.PendingIntent? = null,
    val clearable: Boolean = false,
    /** Group summaries are hidden in a peek when Android also exposes their children. */
    val isGroupSummary: Boolean = false,
    /** Local in-app teaching card; never represents or cancels a system notification. */
    val isSample: Boolean = false
)

data class RenderSegment(val configId: String, val edge: Edge, val start: Float, val end: Float)

/** Splits collisions into stable lanes, leaving breathing room between lights. */
object SegmentLayout {
    fun resolve(configs: List<EdgeLightConfig>): List<RenderSegment> = configs
        .filter { it.enabled }
        .groupBy { it.edge }
        .flatMap { (edge, lights) ->
            lights.sortedBy { it.id }.mapIndexed { index, light ->
                val peers = lights.filter { overlaps(it, light) }.sortedBy { it.id }
                val lane = peers.indexOfFirst { it.id == light.id }.coerceAtLeast(0)
                val laneWidth = 1f / peers.size
                val desired = if (peers.size == 1) light.lengthFraction else light.lengthFraction.coerceAtMost(laneWidth * .82f)
                val center = light.positionFraction.coerceIn(0f, 1f)
                val laneStart = lane * laneWidth
                val start = max(laneStart + .02f, center - desired / 2).coerceAtMost(laneStart + laneWidth - desired - .02f)
                RenderSegment(light.id, edge, start.coerceIn(0f, 1f), min(1f, start + desired))
            }
        }

    private fun overlaps(a: EdgeLightConfig, b: EdgeLightConfig): Boolean {
        if (a.edge != b.edge) return false
        val a0 = a.positionFraction - a.lengthFraction / 2; val a1 = a.positionFraction + a.lengthFraction / 2
        val b0 = b.positionFraction - b.lengthFraction / 2; val b1 = b.positionFraction + b.lengthFraction / 2
        return a0 < b1 && b0 < a1
    }
}

object NotificationRouter {
    fun configFor(packageName: String, configs: List<EdgeLightConfig>): EdgeLightConfig? = configs
        .filter { it.enabled && packageName in it.packageNames }
        .sortedWith(compareBy<EdgeLightConfig> { it.kind != ConfigKind.APP }.thenBy { it.id })
        .firstOrNull()
}
