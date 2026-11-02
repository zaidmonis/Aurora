# Aurora

Aurora is a local-only Android notification companion. Selected apps illuminate a small coloured segment on a screen edge; an inward edge gesture opens a compact notification peek.

## Build and run

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

On first launch, enable **Notification access** and **Display over other apps** in the settings pages Aurora opens. Add an installed app, choose a colour and edge, then use **Try it on this phone** in its editor to verify the overlay without creating a fake notification. A real, clearable notification from that configured app creates the live light.

## Privacy and permissions

- `SYSTEM_ALERT_WINDOW` is used solely for the transparent edge-light/peek overlay, after the user grants Android's standard overlay permission.
- `BIND_NOTIFICATION_LISTENER_SERVICE` is the system-protected binding required by Android for notification listener services. Notification contents stay on the device; the app has no Internet permission.
- Battery optimisation is optional. Android/OEM battery controls can delay listener delivery.

## Platform limits

Android and individual OEMs decide whether overlays can be visible on a secure lock screen. Aurora deliberately does not reveal notification text there. Wake/screen-off options are persisted as user preferences but remain best-effort, rather than claiming a background wake that Android rejects. Direct user taps may launch a notification's `PendingIntent`; Aurora never launches apps autonomously on arrival.

## Verification

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The unit suite covers geometry validation, collision splitting, notification routing, and settings range persistence policy. Device verification should cover listener/overlay grants, notification post/remove, and tap/swipe behaviour because those rely on Android-owned surfaces.
