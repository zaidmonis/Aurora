package thezaxis.aurora.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import thezaxis.aurora.data.AuroraRepository
import thezaxis.aurora.domain.ActiveAuroraNotification
import thezaxis.aurora.domain.NotificationRouter
import thezaxis.aurora.overlay.OverlayCoordinator

class AuroraNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository by lazy { AuroraRepository(applicationContext) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationActionBridge.cancel = { key -> runCatching { cancelNotification(key) } }
        refresh(activeNotifications?.toList().orEmpty())
    }
    override fun onDestroy() { NotificationActionBridge.cancel = null; super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { route(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { ActiveNotificationStore.remove(sbn.key); ActiveNotificationPersistence.save(applicationContext, ActiveNotificationStore.notifications.value); OverlayCoordinator.refresh(applicationContext) }
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) { refresh(activeNotifications?.toList().orEmpty()) }

    private fun refresh(items: List<StatusBarNotification>) = scope.launch {
        val configs = repository.configs.first()
        ActiveNotificationStore.replace(items.flatMap { map(it, configs) })
        ActiveNotificationPersistence.save(applicationContext, ActiveNotificationStore.notifications.value)
        OverlayCoordinator.refresh(applicationContext)
    }
    private fun route(sbn: StatusBarNotification) = scope.launch {
        val configs = repository.configs.first()
        map(sbn, configs).forEach(ActiveNotificationStore::put)
        ActiveNotificationPersistence.save(applicationContext, ActiveNotificationStore.notifications.value)
        OverlayCoordinator.refresh(applicationContext)
    }
    private fun map(sbn: StatusBarNotification, configs: List<thezaxis.aurora.domain.EdgeLightConfig>): List<ActiveAuroraNotification> {
        val n = sbn.notification
        if (sbn.isOngoing || n.category == Notification.CATEGORY_SERVICE || n.category == Notification.CATEGORY_SYSTEM) return emptyList()
        val config = NotificationRouter.configFor(sbn.packageName, configs) ?: return emptyList()
        val extras = n.extras
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        // WhatsApp and similar chat apps often expose a single summary SBN whose
        // MessagingStyle payload contains the actual conversations. Expand those
        // payloads into local rows instead of displaying "2 messages from 2 chats".
        val bundledMessages = if (isGroupSummary) runCatching {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            )
        }.getOrDefault(emptyList()) else emptyList()
        if (bundledMessages.isNotEmpty()) return bundledMessages.mapIndexed { index, message ->
            val sender = message.senderPerson?.name?.toString()
                ?: message.sender?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            ActiveAuroraNotification(
                key = "${sbn.key}:message:$index:${message.timestamp}", packageName = sbn.packageName,
                timestamp = message.timestamp.takeIf { it > 0L } ?: sbn.postTime, appLabel = label,
                title = sender, text = message.text?.toString().orEmpty(), configId = config.id,
                contentIntent = n.contentIntent,
                // Android only provides cancellation for the summary SBN, not each
                // message inside it. The row can still be dismissed locally.
                clearable = false
            )
        }
        return listOf(ActiveAuroraNotification(sbn.key, sbn.packageName, sbn.postTime, label,
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(), config.id, n.contentIntent, sbn.isClearable, isGroupSummary))
    }
}
