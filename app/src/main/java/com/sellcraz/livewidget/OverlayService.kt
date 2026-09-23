package com.sellcraz.livewidget

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * The floating card over Instagram. Follows a SHOW, so it moves to each new
 * lot on its own. Two modes:
 *   buyer: current lot, hold to bid the exact next valid amount
 *   host:  current lot and top bid, hold for Next lot / Close lot / End show
 *
 * Every action is a hold (never a tap), the amount bid is the one on screen
 * when the hold started, and nothing can be pressed on stale data.
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_SHOW_ID = "show_id"
        const val EXTRA_HOST = "host"
        const val ACTION_STOP = "com.sellcraz.livewidget.STOP"
        private const val CHANNEL_ID = "widget"
        private const val NOTIF_ID = 4201
        private const val POLL_MS = 1000L
        private const val STALE_MS = 4000L
        private const val HOLD_MS = 600L

        private val CORAL = 0xFFF05023.toInt()
        private val GREEN = 0xFF2ECC71.toInt()
        private val AMBER = 0xFFF5A623.toInt()
        private val RED = 0xFFFF4D4F.toInt()
        private val GREY = 0xFF55555C.toInt()
        private val CARD_BG = 0xF0151518.toInt()
        private val WHITE = 0xFFFFFFFF.toInt()
        private val WHITE70 = 0xB3FFFFFF.toInt()
        private val WHITE50 = 0x80FFFFFF.toInt()
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var api: SupabaseApi
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val actIo = Executors.newSingleThreadExecutor()
    private val imgIo = Executors.newSingleThreadExecutor()

    private var showId: String? = null
    private var isHost = false
    private var state: WidgetState? = null
    private var lastOkAt = 0L
    private var fetchInFlight = false
    private var actionInFlight = false
    private var loadedImageUrl: String? = null
    private val lotsIBidOn = HashSet<String>()
    private var lastFlags = ""

    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubble: LinearLayout
    private lateinit var bubblePrice: TextView
    private lateinit var bubbleTime: TextView
    private lateinit var card: LinearLayout
    private lateinit var brand: TextView
    private lateinit var thumb: ImageView
    private lateinit var titleView: TextView
    private lateinit var priceLabel: TextView
    private lateinit var priceView: TextView
    private lateinit var statusView: TextView
    private lateinit var timeView: TextView
    private lateinit var primaryBtn: TextView
    private lateinit var secondaryBtn: TextView
    private lateinit var holdBar: ProgressBar
    private lateinit var msgView: TextView

    private val inr: NumberFormat = NumberFormat.getInstance(Locale("en", "IN"))

    // hold-to-act
    private var holdingView: TextView? = null
    private var holdAction: (() -> Unit)? = null
    private var holdAnim: ValueAnimator? = null
    private val holdComplete = Runnable {
        val act = holdAction ?: return@Runnable
        holdingView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        clearHold()
        act()
    }

    private val pollTick = object : Runnable {
        override fun run() {
            fetchNow()
            main.postDelayed(this, POLL_MS)
        }
    }
    private val clockTick = object : Runnable {
        override fun run() {
            renderClock()
            main.postDelayed(this, 250)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        api = SupabaseApi(prefs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val newShow = intent?.getStringExtra(EXTRA_SHOW_ID)
        if (newShow == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (newShow != showId) {
            showId = newShow
            state = null
            lastOkAt = 0L
            loadedImageUrl = null
        }
        isHost = intent.getBooleanExtra(EXTRA_HOST, false)
        if (root == null) buildOverlay()
        brand.text = if (isHost) "● YOUR SHOW  SellCraz" else "● LIVE  SellCraz"
        setExpanded(true)
        showMessage("Connecting…", WHITE70)
        render()
        main.removeCallbacks(pollTick)
        main.post(pollTick)
        main.removeCallbacks(clockTick)
        main.post(clockTick)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        holdAnim?.cancel()
        root?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                // already gone
            }
        }
        root = null
        io.shutdownNow()
        actIo.shutdownNow()
        imgIo.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- data

    private fun fetchNow() {
        val id = showId ?: return
        if (fetchInFlight) return
        fetchInFlight = true
        io.execute {
            try {
                val s = api.widgetState(id)
                main.post {
                    fetchInFlight = false
                    if (id != showId || root == null) return@post
                    applyState(s)
                }
            } catch (e: Exception) {
                main.post {
                    fetchInFlight = false
                    if (root == null) return@post
                    if (state == null) showMessage(e.message ?: "Connection problem", RED)
                    render()
                }
            }
        }
    }

    private fun applyState(s: WidgetState) {
        val first = state == null
        state = s
        lastOkAt = SystemClock.elapsedRealtime()
        if (first) showMessage("", WHITE70)
        render()
        maybeLoadImage(s.lot?.imageUrl)
        if (s.showStatus == "ended") {
            main.removeCallbacks(pollTick) // nothing more will happen
        }
    }

    /** Runs an RPC off the main thread and reports the result on the card. */
    private fun act(busyText: String, okText: String, call: () -> WidgetState?) {
        actionInFlight = true
        showMessage(busyText, WHITE70)
        render()
        actIo.execute {
            try {
                val s = call()
                main.post {
                    actionInFlight = false
                    showMessage(okText, GREEN)
                    if (s != null) applyState(s) else render()
                    fetchNow()
                }
            } catch (e: Exception) {
                main.post {
                    actionInFlight = false
                    showMessage(e.message ?: "Something went wrong", RED)
                    render()
                    fetchNow()
                }
            }
        }
    }

    private fun maybeLoadImage(url: String?) {
        if (url == loadedImageUrl) return
        loadedImageUrl = url
        if (url == null) {
            thumb.visibility = View.GONE
            return
        }
        val size = dp(52)
        imgIo.execute {
            val bmp = ImageLoader.load(url, size * 2)
            main.post {
                if (root == null || url != loadedImageUrl) return@post
                if (bmp != null) {
                    thumb.setImageBitmap(bmp)
                    thumb.visibility = View.VISIBLE
                } else {
                    thumb.visibility = View.GONE
                }
            }
        }
    }

    private fun isStale() = lastOkAt == 0L || SystemClock.elapsedRealtime() - lastOkAt > STALE_MS

    private fun remainingMs(s: WidgetState, endsAt: Long) =
        endsAt - (System.currentTimeMillis() + s.clockOffsetMs)

    private fun lotRunning(s: WidgetState): Boolean {
        val l = s.lot ?: return false
        return l.status == "active" && (l.endsAtMs == null || remainingMs(s, l.endsAtMs) > 0)
    }

    private fun lotExpired(s: WidgetState): Boolean {
        val l = s.lot ?: return false
        return l.status == "active" && l.endsAtMs != null && remainingMs(s, l.endsAtMs) <= 0
    }

    // ---------------------------------------------------------------- what the buttons do

    private data class Btn(val label: String, val enabled: Boolean, val action: (() -> Unit)?)

    private fun primaryButton(s: WidgetState?): Btn {
        if (s == null) return Btn("Loading…", false, null)
        if (s.showStatus == "ended") return Btn("Show ended", false, null)
        if (isStale()) return Btn("Waiting for connection…", false, null)
        if (actionInFlight) return Btn("Working…", false, null)
        val id = s.showId

        if (isHost) {
            if (s.showStatus != "live") return Btn("Go live in SellCraz first", false, null)
            if (!prefs.hasFreshSession(5_000)) return Btn("Session expired · tap ↗", false, null)
            if (lotRunning(s)) return Btn("Lot running…", false, null)
            if (s.queuedCount > 0) {
                val label = if (s.lot == null) "HOLD: START FIRST LOT" else "HOLD: NEXT LOT"
                return Btn(label, true) {
                    act("Starting next lot…", "Next lot is live") { api.advanceLot(id) }
                }
            }
            if (lotExpired(s)) {
                return Btn("HOLD: CLOSE FINAL LOT", true) {
                    act("Closing lot…", "Lot closed") { api.advanceLot(id) }
                }
            }
            return Btn("HOLD: END SHOW", true) {
                act("Ending show…", "Show ended") { api.endShow(id); null }
            }
        }

        // buyer
        val l = s.lot
        if (l == null || !lotRunning(s)) return Btn("Waiting for the next lot", false, null)
        if (!prefs.hasFreshSession(5_000)) return Btn("Log in to bid · tap ↗", false, null)
        val amount = s.nextBid ?: return Btn("Price unknown", false, null)
        return Btn("HOLD TO BID ${money(amount)}", true) {
            val lotId = l.id
            act("Placing ${money(amount)}…", "Bid placed: ${money(amount)} ✓") {
                api.placeBid(lotId, amount)
                lotsIBidOn.add(lotId)
                null
            }
        }
    }

    private fun secondaryButton(s: WidgetState?): Btn? {
        if (!isHost || s == null || isStale() || actionInFlight) return null
        val l = s.lot ?: return null
        if (!lotRunning(s) || !prefs.hasFreshSession(5_000)) return null
        return Btn("Hold to close this lot now", true) {
            act("Closing lot…", "Lot closed") { api.closeLotNow(l.id); null }
        }
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        if (root == null) return
        val s = state
        val l = s?.lot
        val me = prefs.userId

        titleView.text = when {
            s == null -> "Loading show…"
            l == null -> s.title
            else -> l.name
        }

        when {
            l == null -> {
                priceLabel.text = if (s?.nextLotName != null) "UP NEXT" else ""
                priceView.text = s?.nextLotName ?: "–"
                priceView.textSize = 18f
                bubblePrice.text = "SC"
            }
            l.status == "active" -> {
                val shown = if (l.currentBid > 0) l.currentBid else l.startingBid
                priceLabel.text = if (l.currentBid > 0) "CURRENT BID" else "STARTING BID"
                priceView.text = money(shown)
                priceView.textSize = 30f
                bubblePrice.text = compact(shown)
            }
            else -> {
                priceLabel.text = if (l.status == "sold") "SOLD FOR" else "UNSOLD"
                priceView.text = if (l.status == "sold") money(l.currentBid) else "–"
                priceView.textSize = 30f
                bubblePrice.text = if (l.status == "sold") compact(l.currentBid) else "SC"
            }
        }

        val stale = isStale()
        val leading = l != null && me != null && l.leaderId == me
        when {
            s == null -> setStatus("", WHITE70)
            s.showStatus == "ended" -> setStatus("Show ended. Thanks for watching!", WHITE70)
            stale -> setStatus("Reconnecting…", AMBER)
            l == null -> setStatus(if (s.showStatus == "live") "Waiting for the first lot" else "Show hasn't started", WHITE70)
            l.status == "sold" && me != null && l.winnerId == me -> setStatus("You won this lot 🎉", GREEN)
            l.status == "sold" || l.status == "unsold" ->
                setStatus(s.nextLotName?.let { "Up next: $it" } ?: "That was the last lot", WHITE70)
            isHost -> setStatus(if (l.currentBid > 0) "Bidding is on" else "No bids yet", WHITE70)
            leading -> setStatus("You're winning", GREEN)
            l.currentBid == 0L -> setStatus("No bids yet · be first", WHITE70)
            lotsIBidOn.contains(l.id) -> setStatus("You've been outbid", RED)
            else -> setStatus("Bidding open", WHITE70)
        }

        priceView.alpha = if (stale) 0.4f else 1f
        (bubble.background as GradientDrawable).setColor(
            when {
                stale && s != null -> AMBER
                leading && !isHost -> GREEN
                else -> CORAL
            }
        )

        if (holdingView == null) {
            applyBtn(primaryBtn, primaryButton(s), CORAL)
            val sec = secondaryButton(s)
            if (sec == null) {
                secondaryBtn.visibility = View.GONE
            } else {
                secondaryBtn.visibility = View.VISIBLE
                applyBtn(secondaryBtn, sec, 0xFF3A3A40.toInt())
            }
        }
        renderClock()
    }

    private fun applyBtn(v: TextView, b: Btn, color: Int) {
        v.text = b.label
        v.tag = b
        (v.background as GradientDrawable).setColor(if (b.enabled) color else GREY)
        v.alpha = if (b.enabled) 1f else 0.8f
    }

    private fun renderClock() {
        if (root == null) return
        val s = state
        val l = s?.lot
        val rem = if (s != null && l != null && l.status == "active" && l.endsAtMs != null) {
            remainingMs(s, l.endsAtMs)
        } else {
            null
        }
        val short = when {
            rem == null -> ""
            rem <= 0 -> "0:00"
            else -> fmtDur(rem)
        }
        timeView.text = when {
            rem == null -> ""
            rem <= 0 -> "Time's up"
            else -> "Ends in $short"
        }
        timeView.visibility = if (rem == null) View.GONE else View.VISIBLE
        timeView.setTextColor(if (rem != null && rem in 1..10_000) RED else WHITE)
        bubbleTime.text = short
        bubbleTime.visibility = if (short.isEmpty()) View.GONE else View.VISIBLE

        // Re-render when the timer or connection state crosses a line.
        val flags = "${s?.let { lotRunning(it) }}|${isStale()}|${prefs.hasFreshSession(5_000)}"
        if (flags != lastFlags) {
            lastFlags = flags
            cancelHold()
            render()
        }
    }

    private fun setStatus(t: String, color: Int) {
        statusView.text = t
        statusView.setTextColor(color)
        statusView.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showMessage(t: String, color: Int) {
        if (root == null) return
        msgView.text = t
        msgView.setTextColor(color)
        msgView.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- hold to act

    private fun startHold(v: TextView) {
        val b = v.tag as? Btn ?: return
        if (!b.enabled || b.action == null) return
        holdingView = v
        holdAction = b.action
        v.text = "Keep holding…"
        holdAnim?.cancel()
        holdAnim = ValueAnimator.ofInt(0, 1000).apply {
            duration = HOLD_MS
            addUpdateListener { holdBar.progress = it.animatedValue as Int }
            start()
        }
        // The timer fires the action, not the animation, so a real hold is
        // needed even with the phone's animations switched off.
        main.removeCallbacks(holdComplete)
        main.postDelayed(holdComplete, HOLD_MS)
    }

    private fun clearHold() {
        main.removeCallbacks(holdComplete)
        holdAnim?.cancel()
        holdAnim = null
        holdBar.progress = 0
        holdingView = null
        holdAction = null
    }

    private fun cancelHold() {
        if (holdingView == null) return
        clearHold()
        render()
    }

    private val holdTouch = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> startHold(v as TextView)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
        }
        true
    }

    // ---------------------------------------------------------------- views

    private fun buildOverlay() {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CORAL)
                setStroke(dp(2), WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(dp(66), dp(66))
        }
        bubblePrice = text(13f, WHITE, true).apply { text = "SC"; gravity = Gravity.CENTER }
        bubbleTime = text(10f, WHITE, false).apply { gravity = Gravity.CENTER }
        bubble.addView(bubblePrice)
        bubble.addView(bubbleTime)
        bubble.setOnTouchListener(dragHandler { setExpanded(true) })

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
            background = rounded(CARD_BG, dp(18))
            layoutParams = LinearLayout.LayoutParams(dp(272), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand = text(12f, WHITE70, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(6), 0, dp(6))
        }
        brand.setOnTouchListener(dragHandler { })
        header.addView(brand)
        header.addView(iconButton("↗") { openApp() })
        header.addView(iconButton("–") { setExpanded(false) })
        header.addView(iconButton("✕") { stopSelf() })
        card.addView(header)

        val lotRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(10) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xFF2A2A30.toInt(), dp(10))
            clipToOutline = true
            visibility = View.GONE
        }
        titleView = text(14f, WHITE, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        lotRow.addView(thumb)
        lotRow.addView(titleView)
        card.addView(lotRow)

        priceLabel = text(11f, WHITE50, true).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(priceLabel)
        priceView = text(30f, WHITE, true)
        card.addView(priceView)
        statusView = text(13f, WHITE70, true)
        card.addView(statusView)
        timeView = text(13f, WHITE, false).apply { setPadding(0, dp(2), 0, 0) }
        card.addView(timeView)

        primaryBtn = actionButton(15f).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        card.addView(primaryBtn)

        holdBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply {
                topMargin = dp(6)
            }
        }
        card.addView(holdBar)

        secondaryBtn = actionButton(13f).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        card.addView(secondaryBtn)

        msgView = text(12f, WHITE70, false).apply {
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        card.addView(msgView)

        r.addView(bubble)
        r.addView(card)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(140)
        }
        wm.addView(r, params)
        root = r
    }

    private fun actionButton(size: Float) = text(size, WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(13), dp(12), dp(13))
        background = rounded(GREY, dp(12))
        setOnTouchListener(holdTouch)
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
    }

    private fun setExpanded(expanded: Boolean) {
        if (root == null) return
        if (!expanded) cancelHold()
        card.visibility = if (expanded) View.VISIBLE else View.GONE
        bubble.visibility = if (expanded) View.GONE else View.VISIBLE
        root?.let { wm.updateViewLayout(it, params) }
    }

    private fun dragHandler(onTap: () -> Unit) = object : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = max(0, startY + dy)
                        root?.let { wm.updateViewLayout(it, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            return true
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Floating bid widget", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SellCraz widget is on")
            .setContentText("Floating over Instagram. Tap Stop to close it.")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop", stopPi
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun iconButton(label: String, onClick: () -> Unit) = text(16f, WHITE70, true).apply {
        text = label
        setPadding(dp(10), dp(4), dp(4), dp(4))
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun money(v: Long): String = "₹" + inr.format(v)

    private fun compact(v: Long): String {
        fun one(d: Double) = String.format(Locale.US, "%.1f", d).removeSuffix(".0")
        return when {
            v >= 1_00_00_000 -> "₹" + one(v / 1e7) + "Cr"
            v >= 1_00_000 -> "₹" + one(v / 1e5) + "L"
            v >= 1_000 -> "₹" + one(v / 1e3) + "k"
            else -> "₹$v"
        }
    }

    private fun fmtDur(ms: Long): String {
        val total = (ms + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
