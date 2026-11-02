package thezaxis.aurora.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import thezaxis.aurora.domain.*
import java.util.UUID
import java.util.Base64

private val Context.auroraStore by preferencesDataStore("aurora_settings")

/** Compact versioned local codec. No notification content is persisted here. */
class AuroraRepository(private val context: Context) {
    private val configsKey = stringPreferencesKey("v1_configs")
    private val prefsKey = stringPreferencesKey("v1_preferences")
    val configs: Flow<List<EdgeLightConfig>> = context.auroraStore.data.map { decodeConfigs(it[configsKey]) }
    val preferences: Flow<AuroraPreferences> = context.auroraStore.data.map { decodePrefs(it[prefsKey]) }

    suspend fun saveConfig(config: EdgeLightConfig) = context.auroraStore.edit { p ->
        val all = decodeConfigs(p[configsKey]).toMutableList()
        val value = config.sanitized().copy(updatedAt = System.currentTimeMillis())
        val i = all.indexOfFirst { it.id == value.id }; if (i >= 0) all[i] = value else all += value
        p[configsKey] = encodeConfigs(all)
    }
    suspend fun removeConfig(id: String) = context.auroraStore.edit { p -> p[configsKey] = encodeConfigs(decodeConfigs(p[configsKey]).filterNot { it.id == id }) }
    suspend fun updatePreferences(transform: (AuroraPreferences) -> AuroraPreferences) = context.auroraStore.edit { p -> p[prefsKey] = encodePrefs(transform(decodePrefs(p[prefsKey])).sanitized()) }
    fun newId() = UUID.randomUUID().toString()

    private fun enc(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
    private fun dec(value: String) = runCatching { String(Base64.getUrlDecoder().decode(value)) }.getOrDefault("")
    private fun encodeConfigs(items: List<EdgeLightConfig>) = items.joinToString("\n") { c -> listOf(c.id, c.kind, enc(c.name), c.packageNames.joinToString(","), c.enabled, c.edge, c.positionFraction, c.lengthFraction, c.thicknessDp, c.bloomDp, c.colorToken, c.animation, c.createdAt, c.updatedAt).joinToString("|") }
    private fun decodeConfigs(raw: String?): List<EdgeLightConfig> = raw.orEmpty().lineSequence().mapNotNull { line ->
        val x = line.split("|"); if (x.size != 14) null else runCatching { EdgeLightConfig(x[0], ConfigKind.valueOf(x[1]), dec(x[2]), x[3].split(',').filter(String::isNotBlank).toSet(), x[4].toBoolean(), Edge.valueOf(x[5]), x[6].toFloat(), x[7].toFloat(), x[8].toFloat(), x[9].toFloat(), AuroraColor.valueOf(x[10]), GlowAnimation.valueOf(x[11]), x[12].toLong(), x[13].toLong()).sanitized() }.getOrNull()
    }.toList()
    private fun encodePrefs(p: AuroraPreferences) = listOf(
        p.tapToOpen, p.swipeToOpen, p.openOnArrival, p.clearAfterReading,
        p.quietPackageNames.joinToString(","), p.showOnLockScreen, p.wakeForNewNotifications,
        p.wakeBrightnessPercent, p.wakeDurationSeconds, p.showClock, p.previewCardAnimation, p.entered
    ).joinToString("|")

    private fun decodePrefs(raw: String?): AuroraPreferences = raw?.split("|")?.let { x -> runCatching {
        when (x.size) {
            // Preserve existing installations while adopting the current segment-grow default.
            11 -> AuroraPreferences(
                tapToOpen = x[0].toBoolean(), swipeToOpen = x[1].toBoolean(),
                openOnArrival = x[2].toBoolean(), clearAfterReading = x[3].toBoolean(),
                quietPackageNames = x[4].split(',').filter(String::isNotBlank).toSet(),
                showOnLockScreen = x[5].toBoolean(), wakeForNewNotifications = x[6].toBoolean(),
                wakeBrightnessPercent = x[7].toInt(), wakeDurationSeconds = x[8].toInt(),
                showClock = x[9].toBoolean(), entered = x[10].toBoolean()
            )
            12 -> AuroraPreferences(
                tapToOpen = x[0].toBoolean(), swipeToOpen = x[1].toBoolean(),
                openOnArrival = x[2].toBoolean(), clearAfterReading = x[3].toBoolean(),
                quietPackageNames = x[4].split(',').filter(String::isNotBlank).toSet(),
                showOnLockScreen = x[5].toBoolean(), wakeForNewNotifications = x[6].toBoolean(),
                wakeBrightnessPercent = x[7].toInt(), wakeDurationSeconds = x[8].toInt(),
                showClock = x[9].toBoolean(), previewCardAnimation = PreviewCardAnimation.valueOf(x[10]),
                entered = x[11].toBoolean()
            )
            else -> error("Unsupported preferences schema")
        }.sanitized()
    }.getOrNull() } ?: AuroraPreferences()
}
