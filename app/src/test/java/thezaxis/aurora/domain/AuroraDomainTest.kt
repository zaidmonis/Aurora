package thezaxis.aurora.domain

import org.junit.Assert.*
import org.junit.Test

class AuroraDomainTest {
    @Test fun configValidationClampsUnsafeGeometry() {
        val clean = EdgeLightConfig("one", ConfigKind.APP, "  Mail  ", setOf("pkg"), positionFraction = -2f, lengthFraction = 9f, thicknessDp = 40f, bloomDp = -3f).sanitized()
        assertEquals("Mail", clean.name); assertEquals(0f, clean.positionFraction); assertEquals(1f, clean.lengthFraction); assertEquals(12f, clean.thicknessDp); assertEquals(0f, clean.bloomDp)
    }
    @Test fun collidingSegmentsReceiveDistinctStableLanes() {
        val a = EdgeLightConfig("a", ConfigKind.APP, "A", setOf("a"), edge=Edge.RIGHT, positionFraction=.5f, lengthFraction=.4f)
        val b = EdgeLightConfig("b", ConfigKind.APP, "B", setOf("b"), edge=Edge.RIGHT, positionFraction=.5f, lengthFraction=.4f)
        val segments = SegmentLayout.resolve(listOf(b,a)).sortedBy { it.configId }
        assertEquals(2, segments.size); assertTrue(segments[0].end <= segments[1].start || segments[1].end <= segments[0].start)
    }
    @Test fun standaloneConfigWinsOverGroupForSamePackage() {
        val group = EdgeLightConfig("g", ConfigKind.GROUP, "People", setOf("chat"))
        val app = EdgeLightConfig("a", ConfigKind.APP, "Chat", setOf("chat"))
        assertEquals("a", NotificationRouter.configFor("chat", listOf(group,app))?.id)
        assertNull(NotificationRouter.configFor("missing", listOf(group,app)))
    }
    @Test fun preferencesAreClamped() {
        val prefs = AuroraPreferences(wakeBrightnessPercent=400, wakeDurationSeconds=1).sanitized()
        assertEquals(100, prefs.wakeBrightnessPercent); assertEquals(3, prefs.wakeDurationSeconds)
    }
}
