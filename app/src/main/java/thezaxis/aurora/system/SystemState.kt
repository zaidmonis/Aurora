package thezaxis.aurora.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.AppOpsManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

interface PermissionChecker { fun notificationAccess(): Boolean; fun overlayAccess(): Boolean }
class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    override fun notificationAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    override fun overlayAccess(): Boolean = Settings.canDrawOverlays(context)
}
fun notificationAccessIntent() = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
fun overlayIntent(context: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
fun usageAccessIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
fun usageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}
