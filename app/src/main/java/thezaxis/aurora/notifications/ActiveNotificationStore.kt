package thezaxis.aurora.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import thezaxis.aurora.domain.ActiveAuroraNotification
import org.json.JSONArray
import org.json.JSONObject

/** Process-local by design: Android remains source of truth for active notifications. */
object ActiveNotificationStore {
    private val mutable = MutableStateFlow<List<ActiveAuroraNotification>>(emptyList())
    private val locallyDismissedKeys = mutableSetOf<String>()
    val notifications: StateFlow<List<ActiveAuroraNotification>> = mutable
    fun put(value: ActiveAuroraNotification) {
        if (value.key in locallyDismissedKeys) return
        mutable.value = (mutable.value.filterNot { it.key == value.key } + value).sortedByDescending { it.timestamp }
    }
    fun remove(key: String) { mutable.value = mutable.value.filterNot { it.key == key } }
    fun replace(values: List<ActiveAuroraNotification>) { mutable.value = values.filterNot { it.key in locallyDismissedKeys }.sortedByDescending { it.timestamp } }
    fun dismissLocally(key: String) { locallyDismissedKeys += key; remove(key) }
}

/** The listener owns Android's privileged cancellation API; overlay UI only requests it. */
object NotificationActionBridge {
    var cancel: ((String) -> Unit)? = null
    fun clear(notification: ActiveAuroraNotification) {
        if (notification.clearable) cancel?.invoke(notification.key)
        // Some bundled MessagingStyle rows have no independently cancelable SBN.
        // They remain locally dismissed until a genuinely new message arrives.
        ActiveNotificationStore.dismissLocally(notification.key)
    }
}

/** Minimal, device-local recovery cache; PendingIntents are intentionally never serialized. */
object ActiveNotificationPersistence {
    private const val PREFS = "aurora_active_notifications"
    fun save(context: Context, values: List<ActiveAuroraNotification>) = runCatching {
        val json = JSONArray()
        values.forEach { n -> json.put(JSONObject().apply {
            put("key", n.key); put("package", n.packageName); put("time", n.timestamp)
            put("label", n.appLabel); put("title", n.title); put("text", n.text); put("config", n.configId); put("clearable", n.clearable)
        }) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("v1", json.toString()).apply()
    }
}
