package thezaxis.aurora.system

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Local-only foreground-app reader used exclusively while a light is active. */
object ForegroundAppMonitor {
    private var lastCheckedAt = 0L
    private var lastForegroundPackage: String? = null

    fun foregroundPackage(context: Context): String? {
        if (!usageAccessGranted(context)) return null
        val now = System.currentTimeMillis()
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // A first notification can arrive long after the user opened an app, before
        // there is a fresh MOVE_TO_FOREGROUND event in our small event window.
        if (lastCheckedAt == 0L) {
            lastForegroundPackage = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now)
                .maxByOrNull { it.lastTimeUsed }
                ?.packageName
        }
        val from = if (lastCheckedAt == 0L) now - 10_000L else (lastCheckedAt - 1_000L).coerceAtLeast(0L)
        val events = manager.queryEvents(from, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForegroundPackage = event.packageName
            }
        }
        lastCheckedAt = now
        return lastForegroundPackage
    }
}
