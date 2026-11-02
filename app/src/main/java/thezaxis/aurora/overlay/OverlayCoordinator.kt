package thezaxis.aurora.overlay

import android.content.Context
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.OverScroller
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import thezaxis.aurora.data.AuroraRepository
import thezaxis.aurora.domain.*
import thezaxis.aurora.notifications.ActiveNotificationStore
import thezaxis.aurora.notifications.NotificationActionBridge
import thezaxis.aurora.system.ForegroundAppMonitor
import thezaxis.aurora.system.usageAccessGranted

/** One transparent, touch-transparent-outside overlay. It exists only while lights exist. */
object OverlayCoordinator {
    private var controller: EdgeOverlayController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var quietMonitor: Job? = null
    // Aurora's own UI is a design surface: every enabled light stays visible there,
    // while other apps only receive lights that have a live notification.
    @Volatile private var inAuroraForeground = false
    fun refresh(context: Context) { scope.launch {
        if (!Settings.canDrawOverlays(context)) { controller?.dismiss(); controller = null; return@launch }
        val repository = AuroraRepository(context)
        val configs = repository.configs.first()
        val preferences = repository.preferences.first()
        val active = ActiveNotificationStore.notifications.value
        val lit = configs.filter { c -> c.enabled && active.any { it.configId == c.id } }
        val foreground = if (preferences.quietPackageNames.isNotEmpty() && usageAccessGranted(context)) ForegroundAppMonitor.foregroundPackage(context) else null
        val quiet = foreground in preferences.quietPackageNames
        val shown = if (inAuroraForeground) configs.filter { it.enabled } else lit
        if (shown.isEmpty() || quiet) { controller?.dismiss(); controller = null; if (shown.isEmpty()) quietMonitor?.cancel() }
        else {
            (controller ?: EdgeOverlayController(context.applicationContext).also { controller = it })
                .show(shown, active, preferences, inAppPreview = inAuroraForeground)
            if (!inAuroraForeground) startQuietMonitor(context.applicationContext, preferences)
        }
    } }
    /** Shows all enabled segments only while Aurora itself is in the foreground. */
    fun showInApp(context: Context, configs: List<EdgeLightConfig>) {
        inAuroraForeground = true
        scope.launch {
        if (!Settings.canDrawOverlays(context)) return@launch
        val repository = AuroraRepository(context.applicationContext)
        val preferences = repository.preferences.first()
        (controller ?: EdgeOverlayController(context.applicationContext).also { controller = it })
            .show(configs.filter { it.enabled }, ActiveNotificationStore.notifications.value, preferences, inAppPreview = true)
    } }
    /** Return to the real-notification-only policy when Aurora is no longer visible. */
    fun leaveInApp(context: Context) {
        inAuroraForeground = false
        refresh(context)
    }
    private fun startQuietMonitor(context: Context, preferences: AuroraPreferences) {
        if (preferences.quietPackageNames.isEmpty() || !usageAccessGranted(context) || quietMonitor?.isActive == true) return
        quietMonitor = scope.launch {
            try {
                // Usage access has no foreground-change callback. This short-lived
                // check runs only while a real light is active and stops when idle.
                while (isActive) { delay(750); refresh(context) }
            } finally { quietMonitor = null }
        }
    }
    fun test(context: Context, config: EdgeLightConfig) { scope.launch {
        if (!Settings.canDrawOverlays(context)) return@launch
        val preferences = AuroraRepository(context.applicationContext).preferences.first()
        (controller ?: EdgeOverlayController(context.applicationContext).also { controller = it })
            .show(listOf(config), emptyList(), preferences, inAppPreview = true)
        delay(4_000)
        refresh(context)
    } }
    /** Editor-only live preview. It deliberately does not synthesize a notification. */
    fun preview(context: Context, config: EdgeLightConfig) { scope.launch {
        if (!Settings.canDrawOverlays(context)) return@launch
        val repository = AuroraRepository(context.applicationContext)
        val preferences = repository.preferences.first()
        // While editing, render every enabled configuration (plus the unsaved draft),
        // not merely currently active notifications. This makes lane splitting and
        // potential edge collisions visible before the user commits a light.
        val active = ActiveNotificationStore.notifications.value
        val enabledConfigs = repository.configs.first().filter { it.enabled }
        val shown = (enabledConfigs.filterNot { it.id == config.id } + config).filter { it.enabled }
        (controller ?: EdgeOverlayController(context.applicationContext).also { controller = it })
            .show(shown, active, preferences, inAppPreview = true)
    } }
    fun stopPreview(context: Context) = refresh(context)
}

private class EdgeOverlayController(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var view: EdgeOverlayView? = null
    private val hotspots = mutableListOf<View>()
    private var peekView: PeekCardView? = null
    private var peekEdge: Edge? = null
    private var peekWindowParams: WindowManager.LayoutParams? = null
    private var peekExitX = 0f
    private var peekExitY = 0f
    private var peekExitWidth = 0
    private var peekExitHeight = 0
    private var peekDismissing = false
    private var current: List<ActiveAuroraNotification> = emptyList()
    private var currentConfigs: List<EdgeLightConfig> = emptyList()
    private var preferences = AuroraPreferences()
    private var inAppPreview = false
    private var attachedForLockScreen = false
    fun show(configs: List<EdgeLightConfig>, active: List<ActiveAuroraNotification>, prefs: AuroraPreferences, inAppPreview: Boolean = false) {
        current = active
        currentConfigs = configs
        preferences = prefs
        this.inAppPreview = inAppPreview
        // Keyguard visibility is decided when an overlay is attached on several Android
        // builds. Reattach on a preference change instead of leaving a stale window
        // that was created without FLAG_SHOW_WHEN_LOCKED.
        if (root != null && attachedForLockScreen != prefs.showOnLockScreen) dismiss()
        if (root == null) {
            val container = FrameLayout(context)
            @Suppress("DEPRECATION")
            container.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            val edge = EdgeOverlayView(context) { _, _ -> Unit }
            container.addView(edge, FrameLayout.LayoutParams(-1, -1))
            root = container; view = edge
            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            val flags = overlayFlags(prefs) or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager.addView(container, WindowManager.LayoutParams(-1, -1, type, flags, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            })
            attachedForLockScreen = prefs.showOnLockScreen
        }
        val layout = SegmentLayout.resolve(configs)
        view?.setLights(configs, layout)
        syncHotspots(layout)
    }
    private fun syncHotspots(layout: List<RenderSegment>) {
        hotspots.forEach { runCatching { windowManager.removeView(it) } }; hotspots.clear()
        val metrics = context.resources.displayMetrics; val width = metrics.widthPixels; val height = metrics.heightPixels
        val d = metrics.density
        layout.forEach { segment ->
            val vertical = segment.edge == Edge.LEFT || segment.edge == Edge.RIGHT
            val config = currentConfigs.firstOrNull { it.id == segment.configId }
            // The renderer overscans a full-length light so it reaches both physical
            // screen ends. The hit window uses that same clipped on-screen geometry.
            val range = if (vertical) height else width
            val start = if (segment.start <= .001f) 0 else (segment.start * range).toInt()
            val end = if (segment.end >= .999f) range else (segment.end * range).toInt()
            val segmentPixels = (end - start).coerceAtLeast(1)
            // Keep the hit zone narrow enough to avoid stray touches, but give an
            // invisible 0dp core a practical 20dp inward target. A visible bar adds
            // its own thickness to that allowance (e.g. 10dp bar = 30dp target).
            val visualThickness = ((config?.thicknessDp ?: 3f) * d).toInt().coerceAtLeast(0)
            val touchThickness = ((20f * d).toInt() + visualThickness).coerceAtLeast(1)
            val w = if (vertical) touchThickness else segmentPixels
            val h = if (vertical) segmentPixels else touchThickness
            val x = when (segment.edge) {
                Edge.LEFT -> 0
                Edge.RIGHT -> width - w
                Edge.TOP, Edge.BOTTOM -> start.coerceIn(0, width - w)
            }
            val y = when (segment.edge) {
                Edge.TOP -> 0
                Edge.BOTTOM -> height - h
                Edge.LEFT, Edge.RIGHT -> start.coerceIn(0, height - h)
            }
            val hotspot = SegmentTouchView(context, segment.configId, segment.edge) { id, inward ->
                // A visible peek owns the interaction. The edge bar becomes an outside
                // target until it has cleanly animated away, preventing duplicate windows.
                if (peekView != null || peekDismissing) {
                    if (peekView != null) dismissPeek()
                } else if ((inward && preferences.swipeToOpen) || (!inward && preferences.tapToOpen)) {
                    openPeek(id, segment)
                }
            }
            val params = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x.coerceAtLeast(0); this.y = y.coerceAtLeast(0) }
            windowManager.addView(hotspot, params); hotspots += hotspot
        }
    }
    private fun openPeek(configId: String, segment: RenderSegment) {
        if (peekDismissing) return
        val configForPeek = currentConfigs.firstOrNull { it.id == configId }
        val notices = peekNotifications(configId).ifEmpty {
            if (inAppPreview && configForPeek != null) listOf(sampleNotification(configForPeek)) else emptyList()
        }
        val notice = notices.firstOrNull() ?: return
        val locked = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
        val config = configForPeek
        val accent = config?.colorToken?.argb?.toInt() ?: Color.rgb(69,230,168)
        val edge = config?.edge ?: Edge.RIGHT
        val metrics = context.resources.displayMetrics
        val d = metrics.density
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val margin = (20 * d).toInt()
        val cardWidth = minOf((288 * d).toInt(), screenWidth - margin * 2)
        val maxCardHeight = minOf(screenHeight - margin * 2, (340 * d).toInt())
        val cardHeight = if (locked) (128 * d).toInt() else PeekCardView.heightFor(notices, d, maxCardHeight)
        val alongEdge = (segment.start + segment.end) / 2f
        val x = when (edge) {
            Edge.LEFT -> margin
            Edge.RIGHT -> screenWidth - cardWidth - margin
            Edge.TOP, Edge.BOTTOM -> (alongEdge * screenWidth - cardWidth / 2f).toInt().coerceIn(margin, screenWidth - cardWidth - margin)
        }
        val y = when (edge) {
            Edge.LEFT, Edge.RIGHT -> (alongEdge * screenHeight - cardHeight / 2f).toInt().coerceIn(margin, screenHeight - cardHeight - margin)
            Edge.TOP -> margin + (18 * d).toInt()
            Edge.BOTTOM -> screenHeight - cardHeight - margin - (18 * d).toInt()
        }
        // The rendered core sits 3dp from the physical edge and is clipped outside
        // the display. The opening shape follows the actual segment, rather than the
        // larger invisible touch target, then expands into the card.
        val barInnerEdge = (3f * d + (config?.thicknessDp ?: 3f) * d / 2f).toInt()
        val vertical = edge == Edge.LEFT || edge == Edge.RIGHT
        val segmentLength = (((segment.end - segment.start) * if (vertical) screenHeight else screenWidth).toInt()).coerceAtLeast(2)
        val coreThickness = ((config?.thicknessDp ?: 3f) * d).toInt().coerceAtLeast(2)
        val segmentWindowWidth = if (vertical) coreThickness else segmentLength
        val segmentWindowHeight = if (vertical) segmentLength else coreThickness
        val segmentWindowX = when (edge) {
            Edge.LEFT -> barInnerEdge
            Edge.RIGHT -> screenWidth - barInnerEdge - segmentWindowWidth
            Edge.TOP, Edge.BOTTOM -> (segment.start * screenWidth).toInt()
        }
        val segmentWindowY = when (edge) {
            Edge.TOP -> barInnerEdge
            Edge.BOTTOM -> screenHeight - barInnerEdge - segmentWindowHeight
            Edge.LEFT, Edge.RIGHT -> (segment.start * screenHeight).toInt()
        }
        val card = PeekCardView(context, notices, accent, locked,
            onOpen = { selected ->
                if (selected.isSample) { dismissPeek(); return@PeekCardView }
                // A content intent is preferred, but a broken/stale PendingIntent must not make the card a dead end.
                val opened = runCatching {
                    val pending = selected.contentIntent ?: return@runCatching false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        // This call originates in an explicit tap on Aurora's visible overlay.
                        // Android 14+ otherwise may treat it as a background activity launch.
                        val options = ActivityOptions.makeBasic().apply {
                            setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                            )
                        }
                        pending.send(context, 0, null, null, null, null, options.toBundle())
                    } else pending.send()
                    true
                }
                    .onFailure { Log.w("Aurora", "Notification intent was unavailable", it) }
                    .getOrDefault(false)
                if (!opened) runCatching {
                    context.packageManager.getLaunchIntentForPackage(selected.packageName)
                        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let(context::startActivity)
                }
                if (preferences.clearAfterReading) NotificationActionBridge.clear(selected)
                dismissPeek()
            }, onClear = { selected ->
                NotificationActionBridge.clear(selected)
                current = current.filterNot { it.key == selected.key }
            }, onClose = { dismissPeek() })
        dismissPeek(animated = false)
        // Move the WindowManager window itself rather than translating the view inside it.
        // A translated child is clipped by its compact overlay window at the 20dp margin.
        card.alpha = 0f
        val growsFromSegment = preferences.previewCardAnimation == PreviewCardAnimation.SEGMENT_GROW
        // Edge slide keeps the entire card intact and moves it from the light's inner
        // edge. Segment grow starts as the exact glowing segment and expands into it.
        val startWidth = if (growsFromSegment) segmentWindowWidth else cardWidth
        val startHeight = if (growsFromSegment) segmentWindowHeight else cardHeight
        val startX = if (growsFromSegment) segmentWindowX else when (edge) {
            Edge.LEFT -> barInnerEdge
            Edge.RIGHT -> screenWidth - barInnerEdge - cardWidth
            Edge.TOP, Edge.BOTTOM -> x
        }
        val startY = if (growsFromSegment) segmentWindowY else when (edge) {
            Edge.TOP -> barInnerEdge
            Edge.BOTTOM -> screenHeight - barInnerEdge - cardHeight
            Edge.LEFT, Edge.RIGHT -> y
        }
        val windowParams = WindowManager.LayoutParams(
            startWidth, startHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; this.x = startX; this.y = startY }
        windowManager.addView(card, windowParams)
        peekView = card; peekEdge = edge; peekWindowParams = windowParams
        peekExitX = startX.toFloat(); peekExitY = startY.toFloat()
        peekExitWidth = startWidth; peekExitHeight = startHeight; peekDismissing = false
        card.post {
            if (peekView === card) animatePeekWindow(card, windowParams, x, y, cardWidth, cardHeight, 180L, 0f, 1f)
        }
    }
    /** Prefer child entries over an app's group summary, then keep newest messages first. */
    private fun peekNotifications(configId: String): List<ActiveAuroraNotification> = current
        .filter { it.configId == configId }
        .groupBy { it.packageName }
        .flatMap { (_, items) -> if (items.any { !it.isGroupSummary }) items.filterNot { it.isGroupSummary } else items }
        .sortedByDescending { it.timestamp }
    private fun sampleNotification(config: EdgeLightConfig) = ActiveAuroraNotification(
        key = "sample:${config.id}", packageName = config.packageNames.firstOrNull().orEmpty(),
        timestamp = System.currentTimeMillis(), appLabel = config.name,
        title = "No active notifications", text = "This is a preview for ${config.name}.",
        configId = config.id, isSample = true
    )
    private fun overlayFlags(prefs: AuroraPreferences): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_IN_OVERSCAN or
            if (prefs.showOnLockScreen) WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED else 0
    private fun dismissPeek(animated: Boolean = true) {
        val card = peekView ?: return
        val params = peekWindowParams ?: return
        val exitX = peekExitX.toInt(); val exitY = peekExitY.toInt()
        val exitWidth = peekExitWidth; val exitHeight = peekExitHeight
        peekView = null; peekEdge = null; peekWindowParams = null
        if (!animated) { peekDismissing=false; runCatching { windowManager.removeView(card) }; return }
        peekDismissing=true
        animatePeekWindow(card, params, exitX, exitY, exitWidth, exitHeight, 150L, card.alpha, 0f) {
            runCatching { windowManager.removeView(card) }
            peekDismissing=false
        }
    }
    private fun animatePeekWindow(
        card: View,
        params: WindowManager.LayoutParams,
        targetX: Int,
        targetY: Int,
        targetWidth: Int,
        targetHeight: Int,
        duration: Long,
        startAlpha: Float,
        endAlpha: Float,
        onEnd: (() -> Unit)? = null
    ) {
        val startX = params.x; val startY = params.y; val startWidth = params.width; val startHeight = params.height
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                params.x = (startX + (targetX - startX) * t).toInt()
                params.y = (startY + (targetY - startY) * t).toInt()
                params.width = (startWidth + (targetWidth - startWidth) * t).toInt().coerceAtLeast(2)
                params.height = (startHeight + (targetHeight - startHeight) * t).toInt().coerceAtLeast(2)
                card.alpha = startAlpha + (endAlpha - startAlpha) * t
                runCatching { windowManager.updateViewLayout(card, params) }
            }
            if (onEnd != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd.invoke()
            })
            start()
        }
    }
    fun dismiss() { dismissPeek(animated = false); hotspots.forEach { runCatching { windowManager.removeView(it) } }; hotspots.clear(); root?.let { runCatching { windowManager.removeView(it) } }; root = null; view = null; attachedForLockScreen = false }
}

/** Deliberately compact notification surface: identity first, then one message, with no stock dialog chrome. */
private class LegacyPeekCardView(
    context: Context,
    private val notice: ActiveAuroraNotification,
    private val accent: Int,
    private val locked: Boolean,
    private val onOpen: () -> Unit,
    private val onClear: () -> Unit,
    private val onClose: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val icon = runCatching { context.packageManager.getApplicationIcon(notice.packageName) }.getOrNull()
    private var downX = 0f; private var downY = 0f
    private var swipeOffset = 0f
    private var isDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    // The card is attached from the edge hotspot's ACTION_UP. Ignore that originating
    // gesture if WindowManager forwards it as an outside touch to the new window.
    private val outsideDismissAfter = SystemClock.uptimeMillis() + 320L
    private var opened = false
    init { contentDescription = "${notice.appLabel} notification. Tap to open, swipe to clear."; setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        val radius=30*density; val w=width.toFloat(); val h=height.toFloat(); val inset=1.5f*density
        // A low-key clear surface stays behind the card. It is only revealed by an
        // intentional drag, mirroring the physical affordance in the system shade.
        if (swipeOffset != 0f) {
            paint.style=Paint.Style.FILL; paint.color=Color.rgb(72,30,37)
            canvas.drawRoundRect(inset,inset,w-inset,h-inset,radius,radius,paint)
            val clearX = if (swipeOffset > 0f) 31*density else w-31*density
            paint.color=Color.rgb(255,142,139); canvas.drawCircle(clearX,h/2,14*density,paint)
            paint.color=Color.rgb(30,18,20); paint.typeface=Typeface.DEFAULT_BOLD; paint.textSize=19*density; paint.textAlign=Paint.Align.CENTER
            canvas.drawText("×",clearX,h/2+7*density,paint); paint.textAlign=Paint.Align.LEFT
        }
        canvas.save(); canvas.translate(swipeOffset,0f)
        paint.style=Paint.Style.FILL; paint.color=Color.rgb(15,15,19); paint.maskFilter=BlurMaskFilter(8*density,BlurMaskFilter.Blur.NORMAL); canvas.drawRoundRect(inset,inset,w-inset,h-inset,radius,radius,paint)
        paint.maskFilter=null; paint.style=Paint.Style.STROKE; paint.strokeWidth=1.2f*density; paint.color=Color.argb(185,Color.red(accent),Color.green(accent),Color.blue(accent)); canvas.drawRoundRect(inset,inset,w-inset,h-inset,radius,radius,paint)
        val left=20*density; val iconSize=38*density; paint.style=Paint.Style.FILL; paint.color=Color.argb(58,Color.red(accent),Color.green(accent),Color.blue(accent)); canvas.drawCircle(left+iconSize/2,15*density+iconSize/2,iconSize/2+4*density,paint)
        icon?.setBounds(left.toInt(),(15*density).toInt(),(left+iconSize).toInt(),(15*density+iconSize).toInt()); icon?.draw(canvas)
        paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); paint.textSize=13.5f*density; paint.color=accent; canvas.drawText(notice.appLabel,left+iconSize+12*density,35*density,paint)
        paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL); paint.textSize=11*density; paint.color=Color.rgb(165,161,158); canvas.drawText("Tap to open · swipe to clear",left+iconSize+12*density,53*density,paint)
        paint.strokeWidth=2.1f*density; paint.color=Color.rgb(172,170,168); val cx=w-26*density; canvas.drawLine(cx-7*density,25*density,cx+7*density,39*density,paint); canvas.drawLine(cx+7*density,25*density,cx-7*density,39*density,paint)
        paint.strokeWidth=1*density; paint.color=Color.rgb(48,48,55); canvas.drawLine(0f,68*density,w,68*density,paint)
        if (locked) { paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); paint.textSize=13.5f*density; paint.color=Color.rgb(245,242,236); canvas.drawText("Unlock to view this notification",left,100*density,paint) }
        else {
            paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); paint.textSize=14.5f*density; paint.color=Color.rgb(245,242,236); canvas.drawText(notice.title.ifBlank { "New notification" },left,95*density,paint)
            paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL); paint.textSize=12*density; paint.color=Color.rgb(186,182,178); canvas.drawText(notice.text.take(78),left,117*density,paint)
            paint.textSize=11*density; paint.color=Color.rgb(148,145,142); paint.textAlign=Paint.Align.RIGHT; canvas.drawText(relativeTime(notice.timestamp),w-20*density,95*density,paint); paint.textAlign=Paint.Align.LEFT
        }
        canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = when(event.actionMasked) {
        MotionEvent.ACTION_DOWN -> { downX=event.x; downY=event.y; isDragging=false; true }
        MotionEvent.ACTION_MOVE -> {
            val delta = event.x-downX
            if (!isDragging && kotlin.math.abs(delta) > touchSlop) isDragging=true
            if (isDragging) { swipeOffset=delta.coerceIn(-width*.72f,width*.72f); invalidate() }
            true
        }
        MotionEvent.ACTION_UP -> {
            if (isDragging) {
                if (kotlin.math.abs(swipeOffset) > width*.24f) animateSwipeTo(if(swipeOffset < 0f) -width.toFloat() else width.toFloat(), onClear)
                else animateSwipeTo(0f)
            } else if (event.x>width-70*density && event.y<74*density) onClose()
            else if (!locked && !opened) { opened=true; onOpen() }
            true
        }
        MotionEvent.ACTION_CANCEL -> { if (isDragging) animateSwipeTo(0f); true }
        MotionEvent.ACTION_OUTSIDE -> { if (SystemClock.uptimeMillis() >= outsideDismissAfter) onClose(); true }
        else -> true
    }
    private fun animateSwipeTo(target: Float, finished: (() -> Unit)? = null) {
        ValueAnimator.ofFloat(swipeOffset,target).apply {
            duration=if(finished == null) 180L else 145L
            addUpdateListener { swipeOffset=it.animatedValue as Float; invalidate() }
            if (finished != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finished.invoke()
            })
            start()
        }
    }
    private fun relativeTime(time:Long):String { val m=((System.currentTimeMillis()-time)/60_000).coerceAtLeast(0); return if(m<60) "${m}m" else "${m/60}h" }
}

/** App header with a real, independently interactive row for every current message. */
private class LegacyPeekStackView(
    context: Context,
    notices: List<ActiveAuroraNotification>,
    private val accent: Int,
    private val locked: Boolean,
    private val onOpen: (ActiveAuroraNotification) -> Unit,
    private val onClear: (ActiveAuroraNotification) -> Unit,
    private val onClose: () -> Unit
) : View(context) {
    private val rows = notices.take(4).toMutableList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val icon = rows.firstOrNull()?.let { runCatching { context.packageManager.getApplicationIcon(it.packageName) }.getOrNull() }
    private val headerHeight get() = 68f * density
    private val rowHeight get() = 58f * density
    private var downX = 0f; private var activeRow = -1; private var swipeOffset = 0f; private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val outsideDismissAfter = SystemClock.uptimeMillis() + 320L
    init { contentDescription = "${rows.firstOrNull()?.appLabel ?: "App"} notifications. Tap a message to open, or swipe a message to clear."; setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    override fun onDraw(canvas: Canvas) {
        val radius=26*density; val w=width.toFloat(); val h=height.toFloat(); val inset=1.5f*density; val left=19*density
        paint.style=Paint.Style.FILL; paint.color=Color.rgb(15,15,19); paint.maskFilter=BlurMaskFilter(8*density,BlurMaskFilter.Blur.NORMAL); canvas.drawRoundRect(inset,inset,w-inset,h-inset,radius,radius,paint)
        paint.maskFilter=null; paint.style=Paint.Style.STROKE; paint.strokeWidth=1.15f*density; paint.color=Color.argb(175,Color.red(accent),Color.green(accent),Color.blue(accent)); canvas.drawRoundRect(inset,inset,w-inset,h-inset,radius,radius,paint)
        val iconSize=32*density; paint.style=Paint.Style.FILL; paint.color=Color.argb(58,Color.red(accent),Color.green(accent),Color.blue(accent)); canvas.drawCircle(left+iconSize/2,14*density+iconSize/2,iconSize/2+4*density,paint)
        icon?.setBounds(left.toInt(),(14*density).toInt(),(left+iconSize).toInt(),(14*density+iconSize).toInt()); icon?.draw(canvas)
        paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); paint.textSize=13*density; paint.color=accent; canvas.drawText(rows.firstOrNull()?.appLabel.orEmpty(),left+iconSize+11*density,32*density,paint)
        paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL); paint.textSize=10.5f*density; paint.color=Color.rgb(165,161,158); canvas.drawText(if(locked) "Unlock to view notifications" else "Tap a message · swipe to clear",left+iconSize+11*density,49*density,paint)
        paint.strokeWidth=2*density; paint.color=Color.rgb(172,170,168); val closeX=w-25*density; canvas.drawLine(closeX-6*density,24*density,closeX+6*density,36*density,paint); canvas.drawLine(closeX+6*density,24*density,closeX-6*density,36*density,paint)
        paint.strokeWidth=1*density; paint.color=Color.rgb(48,48,55); canvas.drawLine(0f,headerHeight,w,headerHeight,paint)
        // The card's rounded silhouette is also the clipping boundary for every
        // swipe layer, so a lower row can never square off a rounded bottom corner.
        val contentClip = Path().apply { addRoundRect(inset, inset, w-inset, h-inset, radius, radius, Path.Direction.CW) }
        canvas.save(); canvas.clipPath(contentClip)
        if (!locked) rows.forEachIndexed { index, notice -> drawRow(canvas,notice,index,left,w) }
        canvas.restore()
    }
    private fun drawRow(canvas: Canvas, notice: ActiveAuroraNotification, index: Int, left: Float, width: Float) {
        val top=headerHeight+index*rowHeight; val bottom=top+rowHeight
        canvas.save(); canvas.clipRect(1.5f*density,top,width-1.5f*density,bottom)
        if(index==activeRow && swipeOffset!=0f) { paint.style=Paint.Style.FILL; paint.color=Color.rgb(72,30,37); canvas.drawRect(1.5f*density,top,width-1.5f*density,bottom,paint); val x=if(swipeOffset>0) 28*density else width-28*density; paint.color=Color.rgb(255,142,139); canvas.drawCircle(x,top+rowHeight/2,12*density,paint); paint.color=Color.rgb(30,18,20); paint.typeface=Typeface.DEFAULT_BOLD; paint.textSize=17*density; paint.textAlign=Paint.Align.CENTER; canvas.drawText("×",x,top+rowHeight/2+6*density,paint); paint.textAlign=Paint.Align.LEFT }
        canvas.save(); if(index==activeRow) canvas.translate(swipeOffset,0f)
        // This is the original row surface moving with its content; the red action
        // area is only revealed behind the portion the user has dragged away.
        paint.style=Paint.Style.FILL; paint.color=Color.rgb(15,15,19); canvas.drawRect(1.5f*density,top,width-1.5f*density,bottom,paint)
        paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); paint.textSize=13*density; paint.color=Color.rgb(245,242,236); canvas.drawText(notice.title.ifBlank{"New message"}.take(34),left,top+22*density,paint)
        paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL); paint.textSize=11.5f*density; paint.color=Color.rgb(186,182,178); canvas.drawText(notice.text.ifBlank{"Notification"}.take(48),left,top+42*density,paint)
        paint.textSize=10.5f*density; paint.color=Color.rgb(148,145,142); paint.textAlign=Paint.Align.RIGHT; canvas.drawText(relativeTime(notice.timestamp),width-18*density,top+22*density,paint); paint.textAlign=Paint.Align.LEFT
        if(index<rows.lastIndex) { paint.strokeWidth=1*density; paint.color=Color.rgb(42,42,49); canvas.drawLine(left,bottom,width-left,bottom,paint) }
        canvas.restore(); canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean = when(event.actionMasked) {
        MotionEvent.ACTION_DOWN -> { downX=event.x; dragging=false; activeRow=if(!locked && event.y>=headerHeight) ((event.y-headerHeight)/rowHeight).toInt().takeIf { it in rows.indices } ?: -1 else -1; true }
        MotionEvent.ACTION_MOVE -> { if(activeRow>=0) { val delta=event.x-downX; if(!dragging && kotlin.math.abs(delta)>touchSlop) dragging=true; if(dragging) { swipeOffset=delta.coerceIn(-width*.72f,width*.72f); invalidate() } }; true }
        MotionEvent.ACTION_UP -> { if(dragging && activeRow>=0) { if(kotlin.math.abs(swipeOffset)>width*.24f) { val item=rows[activeRow]; animateSwipeTo(if(swipeOffset<0) -width.toFloat() else width.toFloat()) { rows.remove(item); onClear(item); activeRow=-1; swipeOffset=0f; if(rows.isEmpty()) onClose() else invalidate() } } else animateSwipeTo(0f) } else if(event.x>width-70*density && event.y<headerHeight) onClose() else if(activeRow>=0) onOpen(rows[activeRow]); true }
        MotionEvent.ACTION_CANCEL -> { if(dragging) animateSwipeTo(0f); true }
        MotionEvent.ACTION_OUTSIDE -> { if(SystemClock.uptimeMillis()>=outsideDismissAfter) onClose(); true }
        else -> true
    }
    private fun animateSwipeTo(target:Float, finished:(()->Unit)?=null) { ValueAnimator.ofFloat(swipeOffset,target).apply { duration=if(finished==null) 180L else 145L; addUpdateListener { swipeOffset=it.animatedValue as Float; invalidate() }; if(finished!=null) addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator)=finished.invoke()}); start() } }
    private fun relativeTime(time:Long):String { val m=((System.currentTimeMillis()-time)/60_000).coerceAtLeast(0); return if(m<60) "${m}m" else "${m/60}h" }
}

/** Scrollable, app-sectioned peek for group lights and chat-heavy apps. */
private class PeekCardView(
    context: Context,
    notices: List<ActiveAuroraNotification>,
    private val accent: Int,
    private val locked: Boolean,
    private val onOpen: (ActiveAuroraNotification) -> Unit,
    private val onClear: (ActiveAuroraNotification) -> Unit,
    private val onClose: () -> Unit
) : View(context) {
    private data class Section(val label:String,val packageName:String,val messages:MutableList<ActiveAuroraNotification>)
    private val allRows=notices.toMutableList(); private var sections=sectionsFor(allRows)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG); private val density=resources.displayMetrics.density
    private val headerHeight get()=58f*density; private val sectionHeight get()=60f*density; private val rowHeight get()=54f*density
    private var downX=0f; private var downY=0f; private var startScroll=0f; private var scroll=0f; private var swipe=0f; private var active:ActiveAuroraNotification?=null; private var activeSection:Section?=null; private var mode=0
    private val scrollFling=OverScroller(context); private var velocityTracker:VelocityTracker?=null
    private var gestureStartedInside=false; private var ignoreOutsideUntil=0L
    private val collapsedPackages=mutableSetOf<String>()
    private val slop=ViewConfiguration.get(context).scaledTouchSlop; private val outsideDismissAfter=SystemClock.uptimeMillis()+320L
    init { contentDescription="Notifications. Scroll for messages, tap a message to open, or swipe a message to clear."; setLayerType(LAYER_TYPE_SOFTWARE,null) }
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(MeasureSpec.getSize(w),MeasureSpec.getSize(h))
    private fun contentHeight()=sections.sumOf { sectionHeight.toInt()+(if(it.packageName in collapsedPackages) 0 else it.messages.size*rowHeight.toInt()) }.toFloat()
    private fun maxScroll()=(contentHeight()-(height-headerHeight)).coerceAtLeast(0f)
    override fun onDraw(canvas:Canvas) {
        val w=width.toFloat(); val h=height.toFloat(); val inset=1.5f*density; val radius=26*density; val left=18*density
        paint.style=Paint.Style.FILL; paint.color=Color.rgb(15,15,19); paint.maskFilter=BlurMaskFilter(8*density,BlurMaskFilter.Blur.NORMAL); canvas.drawRoundRect(inset,inset,w-inset,h-inset,radius,radius,paint)
        paint.maskFilter=null; paint.style=Paint.Style.STROKE; paint.strokeWidth=1.15f*density; paint.color=Color.argb(175,Color.red(accent),Color.green(accent),Color.blue(accent)); canvas.drawRoundRect(inset,inset,w-inset,h-inset,radius,radius,paint)
        paint.style=Paint.Style.FILL; paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); paint.textSize=13*density; paint.color=accent; canvas.drawText(if(sections.size>1) "Grouped notifications" else sections.firstOrNull()?.label ?: "Notifications",left,28*density,paint)
        paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL); paint.textSize=10.5f*density; paint.color=Color.rgb(165,161,158); canvas.drawText(if(locked) "Unlock to view notifications" else "Scroll messages · swipe to clear",left,45*density,paint)
        paint.strokeWidth=2*density; paint.color=Color.rgb(172,170,168); val cx=w-25*density; canvas.drawLine(cx-6*density,20*density,cx+6*density,32*density,paint); canvas.drawLine(cx+6*density,20*density,cx-6*density,32*density,paint)
        paint.strokeWidth=1*density; paint.color=Color.rgb(48,48,55); canvas.drawLine(0f,headerHeight,w,headerHeight,paint)
        if(locked) return
        val clip=Path().apply { addRoundRect(inset,inset,w-inset,h-inset,radius,radius,Path.Direction.CW) }; canvas.save(); canvas.clipPath(clip); canvas.clipRect(0f,headerHeight,w,h); canvas.translate(0f,headerHeight-scroll)
        var y=0f; sections.forEach { section ->
            val groupStart=y; val groupEnd=y+sectionHeight+(if(section.packageName in collapsedPackages) 0f else section.messages.size*rowHeight)
            if(section===activeSection&&swipe!=0f) drawGroupDismissBackdrop(canvas,groupStart,groupEnd,w)
            canvas.save(); if(section===activeSection) canvas.translate(swipe,0f)
            val collapsed=section.packageName in collapsedPackages; drawSection(canvas,section,y,left,w,collapsed); y+=sectionHeight; if(!collapsed) section.messages.forEach { message -> drawMessage(canvas,message,y,left,w); y+=rowHeight }; canvas.restore()
        }; canvas.restore()
    }
    private fun drawSection(canvas:Canvas,section:Section,y:Float,left:Float,width:Float,collapsed:Boolean) {
        val iconSize=26*density; val icon=runCatching{context.packageManager.getApplicationIcon(section.packageName)}.getOrNull(); paint.style=Paint.Style.FILL; paint.color=Color.argb(45,Color.red(accent),Color.green(accent),Color.blue(accent)); canvas.drawCircle(left+iconSize/2,y+sectionHeight/2,iconSize/2+3*density,paint); icon?.setBounds(left.toInt(),(y+sectionHeight/2-iconSize/2).toInt(),(left+iconSize).toInt(),(y+sectionHeight/2+iconSize/2).toInt());icon?.draw(canvas); paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);paint.textSize=12.5f*density;paint.color=Color.rgb(225,222,216);canvas.drawText(section.label.take(30),left+iconSize+10*density,y+25*density,paint);paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL);paint.textSize=10.5f*density;paint.color=Color.rgb(148,145,142);canvas.drawText("${section.messages.size} notification${if(section.messages.size==1)""else"s"}",left+iconSize+10*density,y+43*density,paint);val ax=width-27*density;val ay=y+sectionHeight/2;paint.strokeWidth=2*density;paint.style=Paint.Style.STROKE;paint.color=Color.rgb(172,170,168);if(collapsed){canvas.drawLine(ax-4*density,ay-6*density,ax+4*density,ay,paint);canvas.drawLine(ax+4*density,ay,ax-4*density,ay+6*density,paint)}else{canvas.drawLine(ax-6*density,ay-3*density,ax,ay+3*density,paint);canvas.drawLine(ax,ay+3*density,ax+6*density,ay-3*density,paint)};paint.strokeWidth=1*density;paint.color=Color.rgb(42,42,49);canvas.drawLine(left,y+sectionHeight,width-left,y+sectionHeight,paint)
    }
    private fun drawGroupDismissBackdrop(canvas:Canvas,top:Float,bottom:Float,width:Float) { paint.style=Paint.Style.FILL;paint.color=Color.rgb(72,30,37);canvas.drawRect(1.5f*density,top,width-1.5f*density,bottom,paint);val x=if(swipe>0)28*density else width-28*density;val y=(top+bottom)/2;paint.color=Color.rgb(255,142,139);canvas.drawCircle(x,y,14*density,paint);paint.color=Color.rgb(30,18,20);paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=19*density;paint.textAlign=Paint.Align.CENTER;canvas.drawText("×",x,y+7*density,paint);paint.textAlign=Paint.Align.LEFT }
    private fun drawMessage(canvas:Canvas,message:ActiveAuroraNotification,y:Float,left:Float,width:Float) {
        val isActive=message.key==active?.key; val bottom=y+rowHeight; canvas.save();canvas.clipRect(1.5f*density,y,width-1.5f*density,bottom)
        if(isActive&&swipe!=0f){paint.style=Paint.Style.FILL;paint.color=Color.rgb(72,30,37);canvas.drawRect(1.5f*density,y,width-1.5f*density,bottom,paint);val x=if(swipe>0)28*density else width-28*density;paint.color=Color.rgb(255,142,139);canvas.drawCircle(x,y+rowHeight/2,12*density,paint);paint.color=Color.rgb(30,18,20);paint.typeface=Typeface.DEFAULT_BOLD;paint.textSize=17*density;paint.textAlign=Paint.Align.CENTER;canvas.drawText("×",x,y+rowHeight/2+6*density,paint);paint.textAlign=Paint.Align.LEFT}
        canvas.save();if(isActive)canvas.translate(swipe,0f);paint.style=Paint.Style.FILL;paint.color=Color.rgb(15,15,19);canvas.drawRect(1.5f*density,y,width-1.5f*density,bottom,paint);paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);paint.textSize=13*density;paint.color=Color.rgb(245,242,236);canvas.drawText(message.title.ifBlank{"New message"}.take(34),left,y+21*density,paint);paint.typeface=Typeface.create("sans-serif",Typeface.NORMAL);paint.textSize=11.5f*density;paint.color=Color.rgb(186,182,178);canvas.drawText(message.text.ifBlank{"Notification"}.take(48),left,y+41*density,paint);paint.textSize=10.5f*density;paint.color=Color.rgb(148,145,142);paint.textAlign=Paint.Align.RIGHT;canvas.drawText(relativeTime(message.timestamp),width-18*density,y+21*density,paint);paint.textAlign=Paint.Align.LEFT;paint.strokeWidth=1*density;paint.color=Color.rgb(42,42,49);canvas.drawLine(left,bottom,width-left,bottom,paint);canvas.restore();canvas.restore()
    }
    private fun messageAt(contentY:Float):ActiveAuroraNotification? { var y=0f; sections.forEach{section->y+=sectionHeight;if(section.packageName !in collapsedPackages)section.messages.forEach{message->if(contentY in y..(y+rowHeight))return message;y+=rowHeight}};return null }
    private fun sectionAt(contentY:Float):Section? { var y=0f; sections.forEach { section -> if(contentY in y..(y+sectionHeight)) return section; y+=sectionHeight+(if(section.packageName in collapsedPackages)0f else section.messages.size*rowHeight) }; return null }
    override fun onTouchEvent(e:MotionEvent):Boolean=when(e.actionMasked){
        MotionEvent.ACTION_DOWN->{scrollFling.forceFinished(true);velocityTracker?.recycle();velocityTracker=VelocityTracker.obtain().also{it.addMovement(e)};gestureStartedInside=true;downX=e.x;downY=e.y;startScroll=scroll;swipe=0f;mode=0;val contentY=e.y-headerHeight+scroll;activeSection=if(!locked&&e.y>=headerHeight)sectionAt(contentY)else null;active=if(activeSection==null&&!locked&&e.y>=headerHeight)messageAt(contentY)else null;true}
        MotionEvent.ACTION_MOVE->{velocityTracker?.addMovement(e);val dx=e.x-downX;val dy=e.y-downY;if(mode==0&&(kotlin.math.abs(dx)>slop||kotlin.math.abs(dy)>slop))mode=if((active!=null||activeSection!=null)&&kotlin.math.abs(dx)>kotlin.math.abs(dy))1 else 2;if(mode==1&&(active!=null||activeSection!=null)){swipe=dx.coerceIn(-width*.72f,width*.72f);invalidate()}else if(mode==2){scroll=(startScroll-dy).coerceIn(0f,maxScroll());invalidate()};true}
        MotionEvent.ACTION_UP->{velocityTracker?.addMovement(e);if(mode==1&&(active!=null||activeSection!=null)){if(kotlin.math.abs(swipe)>width*.24f){val item=active;val section=activeSection;animateSwipe(if(swipe<0)-width.toFloat()else width.toFloat()){val removed=if(section!=null)section.messages.toList()else listOfNotNull(item);allRows.removeAll{row->removed.any{it.key==row.key}};if(section!=null)collapsedPackages.remove(section.packageName);sections=sectionsFor(allRows);removed.forEach(onClear);active=null;activeSection=null;swipe=0f;scroll=scroll.coerceAtMost(maxScroll());if(allRows.isEmpty())onClose()else invalidate()}}else animateSwipe(0f)}else if(mode==2){velocityTracker?.computeCurrentVelocity(1000);scrollFling.fling(0,scroll.toInt(),0,(-velocityTracker!!.yVelocity).toInt(),0,0,0,maxScroll().toInt());postInvalidateOnAnimation()}else if(e.x>width-70*density&&e.y<headerHeight)dismissAll()else if(mode==0&&activeSection!=null&&e.x>width-64*density){val key=activeSection!!.packageName;if(!collapsedPackages.add(key))collapsedPackages.remove(key);activeSection=null;scroll=scroll.coerceAtMost(maxScroll());invalidate()}else if(mode==0&&active!=null)onOpen(active!!);velocityTracker?.recycle();velocityTracker=null;gestureStartedInside=false;ignoreOutsideUntil=SystemClock.uptimeMillis()+260L;true}
        MotionEvent.ACTION_CANCEL->{if(mode==1)animateSwipe(0f);velocityTracker?.recycle();velocityTracker=null;gestureStartedInside=false;ignoreOutsideUntil=SystemClock.uptimeMillis()+260L;true}
        MotionEvent.ACTION_OUTSIDE->{if(!gestureStartedInside&&SystemClock.uptimeMillis()>=outsideDismissAfter&&SystemClock.uptimeMillis()>=ignoreOutsideUntil)onClose();true}
        else->true}
    override fun computeScroll(){if(scrollFling.computeScrollOffset()){scroll=scrollFling.currY.toFloat();invalidate();postInvalidateOnAnimation()}}
    private fun dismissAll(){val removed=allRows.toList();allRows.clear();sections=emptyList();removed.forEach(onClear);onClose()}
    private fun animateSwipe(target:Float,end:(()->Unit)?=null){ValueAnimator.ofFloat(swipe,target).apply{duration=if(end==null)180L else 145L;addUpdateListener{swipe=it.animatedValue as Float;invalidate()};if(end!=null)addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator)=end.invoke()});start()}}
    private fun relativeTime(time:Long):String{val m=((System.currentTimeMillis()-time)/60_000).coerceAtLeast(0);return if(m<60)"${m}m"else"${m/60}h"}
    companion object { private fun sectionsFor(items:List<ActiveAuroraNotification>)=items.groupBy{it.packageName}.map{(_,rows)->Section(rows.first().appLabel,rows.first().packageName,rows.sortedByDescending{it.timestamp}.toMutableList())}.sortedByDescending{it.messages.firstOrNull()?.timestamp?:0}; fun heightFor(items:List<ActiveAuroraNotification>,density:Float,maxHeight:Int):Int{val groups=items.groupBy{it.packageName}.size;return ((58+groups*60+items.size*54+4)*density).toInt().coerceAtMost(maxHeight)} }
}

private class EdgeOverlayView(context: Context, private val onPeek: (String, Boolean) -> Unit) : View(context) {
    private var lights: List<EdgeLightConfig> = emptyList(); private var segments: List<RenderSegment> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG); private var touched: String? = null; private var downX = 0f; private var downY = 0f
    private var pulse = 0f; private var running = false; private var reducedMotion = false
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
    fun setLights(value: List<EdgeLightConfig>, layout: List<RenderSegment>) {
        lights = value; segments = layout
        reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (!running && !reducedMotion) startPulse(); invalidate()
    }
    private fun startPulse() { running = true; val start = System.nanoTime(); post(object: Runnable { override fun run() { pulse = ((System.nanoTime()-start)/1_000_000_000.0).toFloat(); invalidate(); if (lights.isNotEmpty()) postDelayed(this, 32) else running=false } }) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val density = resources.displayMetrics.density
        segments.forEach { segment ->
            val config = lights.firstOrNull { it.id == segment.configId } ?: return@forEach
            val range = if (segment.edge == Edge.LEFT || segment.edge == Edge.RIGHT) height.toFloat() else width.toFloat()
            // Slight overscan prevents a 100% light from stopping short at a device's bottom gesture inset.
            val start = if (segment.start <= .001f) -range*.06f else segment.start * range
            val end = if (segment.end >= .999f) range*1.06f else segment.end * range
            val line = when (segment.edge) {
                Edge.LEFT -> floatArrayOf(3*density,start,3*density,end); Edge.RIGHT -> floatArrayOf(width-3*density,start,width-3*density,end)
                Edge.TOP -> floatArrayOf(start,3*density,end,3*density); Edge.BOTTOM -> floatArrayOf(start,height-3*density,end,height-3*density)
            }
            val mode = if (reducedMotion) GlowAnimation.NEON else config.animation
            val sine = .5f + .5f*kotlin.math.sin(pulse * Math.PI * .72).toFloat(); val travel = .5f + .5f*kotlin.math.sin(pulse * Math.PI * .32).toFloat()
            val intensity = when (mode) {
                GlowAnimation.BREATHE -> .54f + .46f * sine
                GlowAnimation.AURORA -> .78f + .22f * kotlin.math.sin(pulse * Math.PI * .55).toFloat()
                GlowAnimation.COMET -> .82f; GlowAnimation.PULSE -> .55f + .45f * kotlin.math.max(0f,sine)
                GlowAnimation.EMBER -> .58f + .42f * kotlin.math.abs(kotlin.math.sin(pulse * Math.PI * 7.3)).toFloat()
                GlowAnimation.NEON -> .9f + .1f * kotlin.math.sin(pulse * Math.PI * 14).toFloat()
                GlowAnimation.WAVE -> .72f
            }
            val hsv = FloatArray(3); Color.colorToHSV(config.colorToken.argb.toInt(), hsv)
            if (mode == GlowAnimation.AURORA) hsv[0] = (hsv[0] + 22f * kotlin.math.sin(pulse * Math.PI).toFloat() + 360f) % 360f
            val color = if (mode == GlowAnimation.AURORA) Color.HSVToColor(hsv) else config.colorToken.argb.toInt()
            val core = config.thicknessDp * density
            val bloom = kotlin.math.max(config.bloomDp * density, core * 2.5f)
            drawIrregularBloom(canvas, segment.edge, start, end, color, bloom, pulse, mode)
            // A directional falloff provides the wide, atmospheric halo rather than a generic blurred stroke.
            val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
            val soft = Color.argb((72 * intensity).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            val bright = Color.argb((210 * intensity).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            paint.maskFilter = null; paint.alpha = 255
            if (mode == GlowAnimation.NEON || mode == GlowAnimation.COMET || mode == GlowAnimation.WAVE) when (segment.edge) {
                Edge.RIGHT -> { paint.shader = LinearGradient(width-bloom*4,0f,width.toFloat(),0f,intArrayOf(transparent,soft,bright),floatArrayOf(0f,.78f,1f),Shader.TileMode.CLAMP); canvas.drawRect(width-bloom*4,start,width.toFloat(),end,paint) }
                Edge.LEFT -> { paint.shader = LinearGradient(0f,0f,bloom*4,0f,intArrayOf(bright,soft,transparent),floatArrayOf(0f,.22f,1f),Shader.TileMode.CLAMP); canvas.drawRect(0f,start,bloom*4,end,paint) }
                Edge.TOP -> { paint.shader = LinearGradient(0f,0f,0f,bloom*4,intArrayOf(bright,soft,transparent),floatArrayOf(0f,.22f,1f),Shader.TileMode.CLAMP); canvas.drawRect(start,0f,end,bloom*4,paint) }
                Edge.BOTTOM -> { paint.shader = LinearGradient(0f,height-bloom*4,0f,height.toFloat(),intArrayOf(transparent,soft,bright),floatArrayOf(0f,.78f,1f),Shader.TileMode.CLAMP); canvas.drawRect(start,height-bloom*4,end,height.toFloat(),paint) }
            }
            paint.shader = null
            fun drawStroke(alpha: Int, width: Float, radius: Float) {
                paint.shader = null; paint.color = color; paint.alpha = alpha.coerceIn(0,255); paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND
                paint.maskFilter = if (radius > 0) BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) else null
                canvas.drawLine(line[0],line[1],line[2],line[3],paint)
            }
            if (mode == GlowAnimation.NEON || mode == GlowAnimation.COMET || mode == GlowAnimation.WAVE) drawStroke((22 * intensity).toInt(), core + bloom * 2.5f, bloom * 1.8f)
            drawStroke((105 * intensity).toInt(), core + bloom, bloom)
            drawStroke((180 * intensity).toInt(), core + bloom*.28f, bloom*.28f)
            // A zero-width light is intentionally bloom-only. Canvas treats a zero
            // stroke as a one-pixel hairline, so do not issue a core draw at all.
            if (core > 0f) drawStroke((255 * intensity).toInt(), core * if (mode == GlowAnimation.PULSE) 1.35f else 1f, 0f)
            if (mode == GlowAnimation.COMET || mode == GlowAnimation.WAVE) {
                val tailT = (travel - if (mode == GlowAnimation.COMET) .28f else .13f).coerceAtLeast(0f)
                val tx = line[0] + (line[2]-line[0])*tailT; val ty = line[1] + (line[3]-line[1])*tailT
                val hx = line[0] + (line[2]-line[0])*travel; val hy = line[1] + (line[3]-line[1])*travel
                paint.maskFilter = BlurMaskFilter(bloom*.45f, BlurMaskFilter.Blur.NORMAL); paint.strokeWidth = maxOf(core*2.3f, bloom*.22f); paint.alpha=255
                paint.shader = LinearGradient(tx,ty,hx,hy,Color.argb(0,Color.red(color),Color.green(color),Color.blue(color)),Color.WHITE,Shader.TileMode.CLAMP)
                canvas.drawLine(tx,ty,hx,hy,paint); paint.shader = null; paint.maskFilter = null
                if (core > 0f) { paint.color = Color.WHITE; paint.strokeWidth = core*1.05f; canvas.drawCircle(hx,hy,core*.65f,paint) }
            }
        }
    }
    /** Independently drifting translucent ribbons keep every glow mode organic rather than mechanically uniform. */
    private fun drawIrregularBloom(canvas: Canvas, edge: Edge, start: Float, end: Float, color: Int, bloom: Float, time: Float, mode: GlowAnimation) {
        val vertical = edge == Edge.LEFT || edge == Edge.RIGHT
        val layers = when(mode) { GlowAnimation.EMBER -> 4; GlowAnimation.AURORA -> 3; GlowAnimation.WAVE, GlowAnimation.COMET -> 2; else -> 2 }
        val speed = when(mode) { GlowAnimation.EMBER -> 1.35f; GlowAnimation.WAVE -> .95f; GlowAnimation.COMET -> .7f; GlowAnimation.PULSE -> 1.1f; else -> .42f }
        val irregularity = when(mode) { GlowAnimation.EMBER -> .42f; GlowAnimation.WAVE -> .34f; GlowAnimation.NEON -> .10f; GlowAnimation.PULSE -> .24f; else -> .28f }
        repeat(layers) { layer ->
            val phase = time * (speed + layer*.13f) + layer*1.73f
            val reach = bloom * (1.7f + layer*.64f)
            val path = Path(); val steps = 22
            if (vertical) {
                val x = if (edge == Edge.RIGHT) width.toFloat() else 0f; val direction = if (edge == Edge.RIGHT) -1f else 1f
                path.moveTo(x,start); path.lineTo(x,end)
                for (i in steps downTo 0) {
                    val t=i/steps.toFloat(); val y=start+(end-start)*t
                    val wobble=.56f+irregularity*kotlin.math.sin(t*10.4f+phase).toFloat()+irregularity*.7f*kotlin.math.sin(t*23.7f-phase*1.7f).toFloat()
                    path.lineTo(x+direction*reach*wobble,y)
                }
                path.close(); paint.shader=LinearGradient(if(edge==Edge.RIGHT) width-reach else 0f,0f,if(edge==Edge.RIGHT) width.toFloat() else reach,0f,
                    if(edge==Edge.RIGHT) intArrayOf(Color.TRANSPARENT,Color.argb(52-layer*9,Color.red(color),Color.green(color),Color.blue(color))) else intArrayOf(Color.argb(52-layer*9,Color.red(color),Color.green(color),Color.blue(color)),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            } else {
                val y=if(edge==Edge.BOTTOM) height.toFloat() else 0f; val direction=if(edge==Edge.BOTTOM) -1f else 1f
                path.moveTo(start,y); path.lineTo(end,y)
                for(i in steps downTo 0) {
                    val t=i/steps.toFloat(); val x=start+(end-start)*t
                    val wobble=.56f+irregularity*kotlin.math.sin(t*10.4f+phase).toFloat()+irregularity*.7f*kotlin.math.sin(t*23.7f-phase*1.7f).toFloat()
                    path.lineTo(x,y+direction*reach*wobble)
                }
                path.close(); paint.shader=LinearGradient(0f,if(edge==Edge.BOTTOM) height-reach else 0f,0f,if(edge==Edge.BOTTOM) height.toFloat() else reach,
                    if(edge==Edge.BOTTOM) intArrayOf(Color.TRANSPARENT,Color.argb(52-layer*9,Color.red(color),Color.green(color),Color.blue(color))) else intArrayOf(Color.argb(52-layer*9,Color.red(color),Color.green(color),Color.blue(color)),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            }
            paint.maskFilter=BlurMaskFilter(bloom*.32f,BlurMaskFilter.Blur.NORMAL); paint.alpha=255; canvas.drawPath(path,paint); paint.shader=null
        }
        paint.maskFilter=null
    }
    override fun onTouchEvent(e: MotionEvent): Boolean { when(e.actionMasked) { MotionEvent.ACTION_DOWN -> { downX=e.x;downY=e.y;touched=hit(e.x,e.y);return touched!=null }; MotionEvent.ACTION_UP -> { val id=touched; if(id!=null) onPeek(id, isInward(id, e.x-downX, e.y-downY)); touched=null; return id!=null } }; return touched!=null }
    private fun isInward(id: String, dx: Float, dy: Float): Boolean = segments.firstOrNull { it.configId == id }?.let { s -> when(s.edge) { Edge.LEFT -> dx > 36; Edge.RIGHT -> dx < -36; Edge.TOP -> dy > 36; Edge.BOTTOM -> dy < -36 } } == true
    private fun hit(x:Float,y:Float): String? = segments.firstOrNull { s -> when(s.edge) { Edge.LEFT -> x<56 && y in s.start*height..s.end*height; Edge.RIGHT -> x>width-56 && y in s.start*height..s.end*height; Edge.TOP -> y<56 && x in s.start*width..s.end*width; Edge.BOTTOM -> y>height-56 && x in s.start*width..s.end*width } }?.configId
}

/** A small, transparent input window. Unlike a full-screen overlay it lets every other tap reach the app below. */
private class SegmentTouchView(
    context: Context,
    private val configId: String,
    private val edge: Edge,
    private val onGesture: (String, Boolean) -> Unit
) : View(context) {
    private var downX = 0f; private var downY = 0f
    override fun onTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; true }
        MotionEvent.ACTION_UP -> {
            val dx = event.rawX - downX; val dy = event.rawY - downY
            val inward = when (edge) { Edge.LEFT -> dx > 36; Edge.RIGHT -> dx < -36; Edge.TOP -> dy > 36; Edge.BOTTOM -> dy < -36 }
            onGesture(configId, inward); true
        }
        else -> true
    }
}
