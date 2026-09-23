package com.sellcraz.livewidget

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen: connect, pick a lot, launch the floating widget. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var api: SupabaseApi
    private lateinit var col: LinearLayout

    private lateinit var urlIn: EditText
    private lateinit var anonIn: EditText
    private lateinit var emailIn: EditText
    private lateinit var passIn: EditText
    private lateinit var lotIn: EditText
    private lateinit var tableIn: EditText
    private lateinit var rpcIn: EditText
    private lateinit var lotParamIn: EditText
    private lateinit var amountParamIn: EditText
    private lateinit var incIn: EditText
    private lateinit var authStatus: TextView
    private lateinit var lotStatus: TextView
    private lateinit var rawView: TextView

    private val uuidRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        api = SupabaseApi(prefs)

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(48))
        }
        setContentView(ScrollView(this).apply { addView(col) })

        heading("SellCraz Live", 24f)
        para("Demo build. A floating bid button that sits on top of Instagram Live.")

        heading("1 · Connect", 18f)
        urlIn = field("Supabase URL", prefs.supabaseUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        anonIn = field("Supabase anon key", prefs.anonKey, InputType.TYPE_CLASS_TEXT)
        emailIn = field(
            "Email or phone", prefs.email ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        )
        buttonRow("Send code" to { sendCode() })
        passIn = field("Login code", "", InputType.TYPE_CLASS_NUMBER)
        buttonRow(
            "Sign in" to { signIn() },
            "Sign out" to { prefs.signOut(); updateAuthStatus() }
        )
        authStatus = para("")
        updateAuthStatus()

        heading("2 · Lot", 18f)
        lotIn = field("Lot link or lot ID", prefs.lotId ?: "", InputType.TYPE_CLASS_TEXT)
        buttonRow("Paste" to { pasteLot() }, "Test load" to { testLoad() })
        lotStatus = para("")
        rawView = para("").apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }

        heading("3 · Go live", 18f)
        buttonRow("Start floating widget" to { startWidget() })
        buttonRow("Open Instagram" to { openInstagram() }, "Stop widget" to { stopWidget() })

        heading("Advanced · only if bids fail", 18f)
        para("These must match the database. Test load shows what the app can read.")
        tableIn = field("Lots table", prefs.lotTable, InputType.TYPE_CLASS_TEXT)
        rpcIn = field("Bid function", prefs.rpcName, InputType.TYPE_CLASS_TEXT)
        lotParamIn = field("Lot parameter name", prefs.lotParam, InputType.TYPE_CLASS_TEXT)
        amountParamIn = field("Amount parameter name", prefs.amountParam, InputType.TYPE_CLASS_TEXT)
        incIn = field(
            "Default bid increment (₹) if the lot has none",
            prefs.defaultIncrement.toString(), InputType.TYPE_CLASS_NUMBER
        )
        buttonRow("Save advanced" to { saveAdvanced(); toast("Saved") })

        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { lotIn.setText(it) }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun saveConnection() {
        prefs.supabaseUrl = urlIn.text.toString()
        prefs.anonKey = anonIn.text.toString()
    }

    private fun saveAdvanced() {
        prefs.lotTable = tableIn.text.toString()
        prefs.rpcName = rpcIn.text.toString()
        prefs.lotParam = lotParamIn.text.toString()
        prefs.amountParam = amountParamIn.text.toString()
        incIn.text.toString().toLongOrNull()?.let { prefs.defaultIncrement = it }
    }

    private fun identity(): String {
        val t = emailIn.text.toString().trim()
        if (t.contains('@')) return t
        val digits = t.filter { it.isDigit() }
        return when {
            digits.length == 10 -> "+91$digits"
            digits.length == 12 && digits.startsWith("91") -> "+$digits"
            else -> t
        }
    }

    private fun sendCode() {
        saveConnection()
        val id = identity()
        if (id.isEmpty()) {
            toast("Enter your email or phone")
            return
        }
        authStatus.text = "Sending code…"
        Thread {
            try {
                api.sendOtp(id)
                runOnUiThread { authStatus.text = "Code sent to $id. Enter it above and tap Sign in." }
            } catch (e: Exception) {
                runOnUiThread { authStatus.text = "Couldn't send code: ${e.message}" }
            }
        }.start()
    }

    private fun signIn() {
        saveConnection()
        val id = identity()
        val code = passIn.text.toString().trim()
        if (id.isEmpty() || code.isEmpty()) {
            toast("Enter your email or phone and the code")
            return
        }
        authStatus.text = "Signing in…"
        Thread {
            try {
                api.verifyOtp(id, code)
                runOnUiThread {
                    passIn.setText("")
                    updateAuthStatus()
                }
            } catch (e: Exception) {
                runOnUiThread { authStatus.text = "Sign-in failed: ${e.message}" }
            }
        }.start()
    }

    private fun updateAuthStatus() {
        authStatus.text = if (prefs.accessToken != null) {
            "Signed in as ${prefs.email ?: "?"}  (user ${prefs.userId?.take(8) ?: "?"}…)"
        } else {
            "Not signed in. The widget can show prices but can't bid."
        }
    }

    private fun extractLotId(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        uuidRegex.find(t)?.let { return it.value }
        if (t.contains('/')) {
            return t.substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
        }
        return t
    }

    private fun pasteLot() {
        val cm = getSystemService(ClipboardManager::class.java)
        val txt = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (txt.isNullOrBlank()) toast("Clipboard is empty") else lotIn.setText(txt)
    }

    private fun testLoad() {
        saveConnection()
        saveAdvanced()
        val id = extractLotId(lotIn.text.toString())
        if (id == null) {
            toast("Enter a lot link or ID")
            return
        }
        prefs.lotId = id
        lotStatus.text = "Loading lot $id…"
        rawView.text = ""
        Thread {
            try {
                val f = api.fetchLot(id)
                val s = LotState.from(f.json, prefs.defaultIncrement)
                runOnUiThread {
                    lotStatus.text = s.describe()
                    rawView.text = "Raw row from the database (screenshot this for Claude if something looks off):\n\n" +
                        f.json.toString(2)
                }
            } catch (e: Exception) {
                runOnUiThread { lotStatus.text = "Couldn't load lot: ${e.message}" }
            }
        }.start()
    }

    private fun startWidget() {
        saveConnection()
        saveAdvanced()
        val id = extractLotId(lotIn.text.toString())
        if (id == null) {
            toast("Enter a lot link or ID first")
            return
        }
        prefs.lotId = id

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("Turn on \"Display over other apps\" for SellCraz Live, then come back and tap Start again")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        if (prefs.accessToken == null) toast("Not signed in: the widget will show prices but can't bid")
        startForegroundService(Intent(this, OverlayService::class.java).putExtra(OverlayService.EXTRA_LOT_ID, id))
        toast("Widget is on. Open Instagram.")
    }

    private fun stopWidget() {
        stopService(Intent(this, OverlayService::class.java))
    }

    private fun openInstagram() {
        val i = packageManager.getLaunchIntentForPackage("com.instagram.android")
        if (i == null) toast("Instagram isn't installed") else startActivity(i)
    }

    // ---------------------------------------------------------------- ui helpers

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun heading(t: String, size: Float) {
        col.addView(TextView(this).apply {
            text = t
            textSize = size
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(6))
        })
    }

    private fun para(t: String): TextView {
        val v = TextView(this).apply {
            text = t
            textSize = 14f
            setPadding(0, dp(4), 0, dp(4))
        }
        col.addView(v)
        return v
    }

    private fun field(label: String, value: String, type: Int): EditText {
        col.addView(TextView(this).apply {
            text = label
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(8), 0, 0)
        })
        val e = EditText(this).apply {
            setText(value)
            inputType = type
            isSingleLine = true
        }
        col.addView(e)
        return e
    }

    private fun buttonRow(vararg buttons: Pair<String, () -> Unit>) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        buttons.forEachIndexed { i, (label, action) ->
            row.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                }
            })
        }
        col.addView(row)
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    }
}
