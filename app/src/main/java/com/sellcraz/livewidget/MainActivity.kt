package com.sellcraz.livewidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * The SellCraz app: the website in a WebView, plus the Instagram handoff.
 *
 * Whenever the page is a show (/show/<id>) the app checks show_widget_state.
 * If the show's bidding venue is Instagram and it is live, the floating
 * widget starts (seller controls for the host, bid button for everyone else)
 * and the seller's Instagram opens. Everything else is the website as-is.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: SupabaseApi
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var currentShowId: String? = null
    private val handedOff = HashSet<String>()
    private var pendingHandoff: WidgetState? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null

    private val showRegex = Regex("/show/([0-9a-fA-F-]{36})")

    companion object {
        private const val REQ_FILE = 11
        private const val REQ_MEDIA = 12
        private const val REQ_NOTIF = 13
        private const val SHOW_POLL_MS = 3000L
        private const val SESSION_SYNC_MS = 30_000L
    }

    private val showPoll = object : Runnable {
        override fun run() {
            checkShow()
            main.postDelayed(this, SHOW_POLL_MS)
        }
    }

    private val sessionSync = object : Runnable {
        override fun run() {
            SessionBridge.sync(prefs, web)
            main.postDelayed(this, SESSION_SYNC_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = SupabaseApi(prefs)

        web = WebView(this)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(web)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Lets the website tell it's inside the app, e.g. to hide an
            // "install the app" banner later.
            userAgentString = "$userAgentString SellCrazApp/1"
        }
        web.webViewClient = ShellClient()
        web.webChromeClient = ShellChrome()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(startUrl(intent))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { web.loadUrl(startUrl(intent)) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        main.post(sessionSync)
        onUrl(web.url)
        // Came back from the overlay settings screen with a handoff waiting.
        pendingHandoff?.let { s ->
            if (Settings.canDrawOverlays(this)) {
                pendingHandoff = null
                handoff(s)
            }
        }
    }

    override fun onPause() {
        main.removeCallbacks(sessionSync)
        main.removeCallbacks(showPoll)
        CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun startUrl(i: Intent?): String {
        val d = i?.data
        return if (d != null && d.host?.endsWith(Uri.parse(prefs.siteUrl).host ?: "") == true) {
            d.toString()
        } else {
            prefs.siteUrl
        }
    }

    // ---------------------------------------------------------------- show detection

    private fun onUrl(url: String?) {
        val id = url?.let { showRegex.find(it)?.groupValues?.get(1) }
        currentShowId = id
        main.removeCallbacks(showPoll)
        if (id != null) main.post(showPoll)
    }

    /** Polls while a show page is open, so "Go live" is caught without a navigation. */
    private fun checkShow() {
        val id = currentShowId ?: return
        if (handedOff.contains(id)) return
        io.execute {
            val s = try {
                api.widgetState(id)
            } catch (e: Exception) {
                null
            } ?: return@execute
            main.post {
                if (id != currentShowId || handedOff.contains(id)) return@post
                if (s.isInstagram && s.showStatus == "live" && !s.instagramHandle.isNullOrBlank()) {
                    SessionBridge.sync(prefs, web) { handoff(s) }
                }
            }
        }
    }

    private fun handoff(s: WidgetState) {
        if (handedOff.contains(s.showId)) return
        if (!Settings.canDrawOverlays(this)) {
            pendingHandoff = s
            AlertDialog.Builder(this)
                .setTitle("Bidding for this show is on Instagram")
                .setMessage(
                    "SellCraz shows a small bid card on top of Instagram. " +
                        "Turn on \"Display over other apps\" for SellCraz on the next screen.\n\n" +
                        "If Android says access was denied: Settings > Apps > SellCraz > ⋮ > " +
                        "Allow restricted settings, then try again."
                )
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton("Not now", null)
                .show()
            return
        }
        handedOff.add(s.showId)

        val isHost = prefs.userId != null && prefs.userId == s.sellerId
        startForegroundService(
            Intent(this, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_SHOW_ID, s.showId)
                .putExtra(OverlayService.EXTRA_HOST, isHost)
        )

        if (isHost) {
            Toast.makeText(this, "In Instagram, tap + then Live to start your broadcast", Toast.LENGTH_LONG).show()
        } else if (!prefs.hasFreshSession()) {
            Toast.makeText(this, "Log in to SellCraz to bid", Toast.LENGTH_LONG).show()
        }
        openInstagram(s.instagramHandle ?: return)
    }

    private fun openInstagram(handle: String) {
        val uri = Uri.parse("https://www.instagram.com/$handle/")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.instagram.android"))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    // ---------------------------------------------------------------- web plumbing

    private inner class ShellClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val siteHost = Uri.parse(prefs.siteUrl).host ?: ""
            val host = uri.host ?: ""
            val ours = host == siteHost || host.endsWith("." + siteHost.removePrefix("www.")) ||
                host == siteHost.removePrefix("www.")
            if (ours && (uri.scheme == "https" || uri.scheme == "http")) return false
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                true
            } catch (e: ActivityNotFoundException) {
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            onUrl(url)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            SessionBridge.sync(prefs, view)
            onUrl(url)
        }

        // Next.js navigates with pushState, which does not trigger onPageStarted.
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            onUrl(url)
        }
    }

    private inner class ShellChrome : WebChromeClient() {
        // File inputs: avatar upload, KYC documents, CSV import.
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                startActivityForResult(params.createIntent(), REQ_FILE)
                true
            } catch (e: ActivityNotFoundException) {
                fileCallback = null
                false
            }
        }

        // Camera and microphone for going live on SellCraz itself (LiveKit).
        override fun onPermissionRequest(request: PermissionRequest) {
            val siteHost = Uri.parse(prefs.siteUrl).host
            if (request.origin.host != siteHost) {
                request.deny()
                return
            }
            val need = ArrayList<String>()
            if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) need.add(Manifest.permission.CAMERA)
            if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) need.add(Manifest.permission.RECORD_AUDIO)
            val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) {
                request.grant(request.resources)
            } else {
                pendingWebPermission = request
                requestPermissions(missing.toTypedArray(), REQ_MEDIA)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_MEDIA) {
            val req = pendingWebPermission ?: return
            pendingWebPermission = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                req.grant(req.resources)
            } else {
                req.deny()
            }
        }
    }

    override fun onDestroy() {
        io.shutdownNow()
        web.destroy()
        super.onDestroy()
    }
}
