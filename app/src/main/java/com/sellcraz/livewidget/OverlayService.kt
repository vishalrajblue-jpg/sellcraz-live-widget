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
 * The floating bid widget. Runs as a foreground service so Android keeps it
 * alive while the user is inside Instagram.
 *
 * Safety rules built in (from the launch pre-mortem):
 *  - hold-to-bid, never a single tap
 *  - the amount bid is the one on screen when the hold started; if the price
 *    has moved, place_bid rejects it server-side
 *  - no bidding while data is stale (no fresh update for 4s)
 *  - countdown uses the server's clock, not the phone's
 */
class OverlayService : Service() {

    companion object {
        const val EXTRA_LOT_ID = "lot_id"
        const val ACTION_STOP = "com.sellcraz.livewidget.STOP"
        private const val CHANNEL_ID = "widget"
        private const val NOTIF_ID = 4201
        private const val POLL_MS = 1000L
        private const val STALE_MS = 4000L
        private const val HOLD_MS = 650L

        private val CORAL = 0xFFFF5A36.toInt()
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
    private val bidIo = Executors.newSingleThreadExecutor()
    private val imgIo = Executors.newSingleThreadExecutor()

    private var lotId: String? = null
    private var state: LotState? = null
    private var lastOkAt = 0L
    private var clockOffset: Long? = null
    private var fetchInFlight = false
    private var bidInFlight = false
    private var holding = false
    private var loadedImageUrl: String? = null
    private val lotsIBidOn = HashSet<String>()
    private var lastEndedFlag = false
    private var lastStaleFlag = true

    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubble: LinearLayout
    private lateinit var bubblePrice: TextView
    private lateinit var bubbleTime: TextView
    private lateinit var card: LinearLayout
    private lateinit var thumb: ImageView
    private lateinit var titleView: TextView
    private lateinit var priceView: TextView
    private lateinit var statusView: TextView
    private lateinit var timeView: TextView
    private lateinit var bidButton: TextView
    private lateinit var holdBar: ProgressBar
    private lateinit var msgView: TextView
    private var holdAnim: ValueAnimator? = null

    private val inr: NumberFormat = NumberFormat.getInstance(Locale("en", "IN"))

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

    private var holdAmount: Long = 0
    private val holdComplete = Runnable {
        if (!holding) return@Runnable
        holding = false
        holdAnim?.cancel()
        holdBar.progress = 0
        bidButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        placeBid(holdAmount)
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
        val newLot = intent?.getStringExtra(EXTRA_LOT_ID) ?: prefs.lotId
        if (newLot != lotId) {
            lotId = newLot
            state = null
            lastOkAt = 0L
            loadedImageUrl = null
        }
        if (root == null) buildOverlay()
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
        bidIo.shutdownNow()
        imgIo.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- data

    private fun fetchNow() {
        val id = lotId ?: return
        if (fetchInFlight) return
        fetchInFlight = true
        io.execute {
            try {
                val f = api.fetchLot(id)
                val s = LotState.from(f.json, prefs.defaultIncrement)
                main.post {
                    fetchInFlight = false
                    if (id != lotId || root == null) return@post
                    // Server Date header is truncated to the second and sent
                    // before the response arrives, so every sample is a slight
                    // underestimate. Keeping the max converges on the truth.
                    f.serverTimeMs?.let { server ->
                        val sample = server - f.localTimeMs
                        clockOffset = clockOffset?.let { max(it, sample) } ?: sample
                    }
                    val first = state == null
                    state = s
                    lastOkAt = SystemClock.elapsedRealtime()
                    if (first) showMessage("", WHITE70)
                    render()
                    maybeLoadImage(s.imageUrl)
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

    private fun placeBid(amount: Long) {
        val id = lotId ?: return
        bidInFlight = true
        showMessage("Placing ${money(amount)}…", WHITE70)
        render()
        bidIo.execute {
            try {
                api.placeBid(id, amount)
                main.post {
                    bidInFlight = false
                    lotsIBidOn.add(id)
                    showMessage("Bid placed: ${money(amount)} ✓", GREEN)
                    render()
                    fetchNow()
                }
            } catch (e: Exception) {
                main.post {
                    bidInFlight = false
                    showMessage(e.message ?: "Bid failed", RED)
                    render()
                    fetchNow()
                }
            }
        }
    }

    private fun maybeLoadImage(url: String?) {
        if (url == null || url == loadedImageUrl) return
        loadedImageUrl = url
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

    private fun isStale(): Boolean =
        lastOkAt == 0L || SystemClock.elapsedRealtime() - lastOkAt > STALE_MS

    private fun remainingMs(endsAt: Long): Long =
        endsAt - (System.currentTimeMillis() + (clockOffset ?: 0L))

    private fun ended(s: LotState): Boolean =
        s.isClosedStatus || (s.endsAtMs != null && remainingMs(s.endsAtMs) <= 0)

    private fun canBid(): Boolean {
        val s = state ?: return false
        return prefs.accessToken != null && !isStale() && !ended(s) &&
            s.nextBid != null && !bidInFlight
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        if (root == null) return
        val s = state
        val stale = isStale()

        if (s == null) {
            titleView.text = if (lotId == null) "No lot selected" else "Loading lot…"
            priceView.text = "–"
            bubblePrice.text = "SC"
            setStatus("", WHITE70)
        } else {
            titleView.text = s.title
            val shown = s.currentBid ?: s.startPrice
            priceView.text = shown?.let { money(it) } ?: "–"
            bubblePrice.text = shown?.let { compact(it) } ?: "SC"
            val mine = s.leaderId != null && s.leaderId == prefs.userId
            val iBid = lotsIBidOn.contains(s.id) || lotsIBidOn.contains(lotId)
            when {
                ended(s) && mine -> setStatus("Auction ended · you won 🎉", GREEN)
                ended(s) -> setStatus(if (s.isClosedStatus) "Auction ended" else "Time's up · settling", WHITE70)
                stale -> setStatus("Reconnecting…", AMBER)
                mine -> setStatus("You're winning", GREEN)
                s.currentBid == null -> setStatus("No bids yet · be first", WHITE70)
                iBid && s.leaderId != null -> setStatus("You've been outbid", RED)
                else -> setStatus("Bidding open", WHITE70)
            }
        }

        priceView.alpha = if (stale) 0.4f else 1f
        val mine = s?.leaderId != null && s.leaderId == prefs.userId
        (bubble.background as GradientDrawable).setColor(
            when {
                stale && s != null -> AMBER
                mine -> GREEN
                else -> CORAL
            }
        )

        if (!holding) {
            val ok = canBid()
            bidButton.text = when {
                prefs.accessToken == null -> "Sign in inside the app first"
                s == null -> "Loading…"
                ended(s) -> "Bidding closed"
                stale -> "Waiting for connection…"
                bidInFlight -> "Placing bid…"
                s.nextBid == null -> "Price unknown"
                else -> "HOLD TO BID ${money(s.nextBid!!)}"
            }
            (bidButton.background as GradientDrawable).setColor(if (ok) CORAL else GREY)
            bidButton.alpha = if (ok) 1f else 0.8f
        }
        renderClock()
    }

    private fun renderClock() {
        if (root == null) return
        val s = state
        val rem = s?.endsAtMs?.let { remainingMs(it) }
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
        timeView.setTextColor(if (rem != null && rem in 1..10_000) RED else WHITE)
        bubbleTime.text = short
        bubbleTime.visibility = if (short.isEmpty()) View.GONE else View.VISIBLE

        val nowEnded = s != null && ended(s)
        val nowStale = isStale()
        if (nowEnded != lastEndedFlag || nowStale != lastStaleFlag) {
            lastEndedFlag = nowEnded
            lastStaleFlag = nowStale
            if (nowEnded || nowStale) cancelHold()
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

    // ---------------------------------------------------------------- hold to bid

    private fun startHold() {
        val amount = state?.nextBid ?: return
        holdAmount = amount
        holding = true
        bidButton.text = "Keep holding… ${money(amount)}"
        holdAnim?.cancel()
        holdAnim = ValueAnimator.ofInt(0, 1000).apply {
            duration = HOLD_MS
            addUpdateListener { holdBar.progress = it.animatedValue as Int }
            start()
        }
        // The bid is triggered by this timer, not the animation, so it still
        // needs a real hold even if the phone has animations switched off.
        main.removeCallbacks(holdComplete)
        main.postDelayed(holdComplete, HOLD_MS)
    }

    private fun cancelHold() {
        main.removeCallbacks(holdComplete)
        if (!holding) return
        holding = false
        holdAnim?.cancel()
        holdAnim = null
        holdBar.progress = 0
        render()
    }

    // ---------------------------------------------------------------- views

    private fun buildOverlay() {
        val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Collapsed bubble
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

        // Expanded card
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
        val brand = text(12f, WHITE70, true).apply {
            text = "● LIVE  SellCraz"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(6), 0, dp(6))
        }
        brand.setOnTouchListener(dragHandler { })
        header.addView(brand)
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

        card.addView(text(11f, WHITE50, true).apply {
            text = "CURRENT BID"
            letterSpacing = 0.08f
            setPadding(0, dp(10), 0, 0)
        })
        priceView = text(30f, WHITE, true)
        card.addView(priceView)
        statusView = text(13f, WHITE70, true)
        card.addView(statusView)
        timeView = text(13f, WHITE, false).apply { setPadding(0, dp(2), 0, dp(10)) }
        card.addView(timeView)

        bidButton = text(15f, WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = rounded(GREY, dp(12))
        }
        bidButton.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (canBid()) startHold()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHold()
            }
            true
        }
        card.addView(bidButton)

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
            .setContentTitle("SellCraz bid widget is on")
            .setContentText("Floating over your screen. Tap Stop to close it.")
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
