package com.apexquant.ticksniper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var selectedBitmap: Bitmap? = null

    private lateinit var symbolSpinner: Spinner
    private lateinit var apiKeyInput: EditText
    private lateinit var previewImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var dirSpinner: Spinner
    private lateinit var entryInput: EditText
    private lateinit var slInput: EditText
    private lateinit var tpInput: EditText
    private lateinit var watchlistText: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { stream ->
                selectedBitmap = BitmapFactory.decodeStream(stream)
            }
            previewImage.setImageBitmap(selectedBitmap)
            statusText.text = "Chart loaded. Tap 'Scan Chart with AI'."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        val prefs = getSharedPreferences("ticksniper_prefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("price_offset", 0f).apply()

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "🎯 Pip Sniper 1m AI Watcher"
            textSize = 22f
            setPadding(0, 0, 0, 20)
        }

        symbolSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("XAUUSD", "BTCUSD"))
            setSelection(if (prefs.getString("ui_symbol", "XAUUSD") == "BTCUSD") 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    val uiSym = if (pos == 1) "BTCUSD" else "XAUUSD"
                    val yahooSym = if (pos == 1) "BTC-USD" else "GC=F"
                    val prevSym = prefs.getString("ui_symbol", "XAUUSD")
                    if (prevSym != uiSym) {
                        prefs.edit()
                            .putString("ui_symbol", uiSym)
                            .putString("symbol", yahooSym)
                            .putString("last_price", "Fetching...")
                            .apply()
                    } else {
                        prefs.edit().putString("symbol", yahooSym).apply()
                    }
                    refreshWatchlist()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        apiKeyInput = EditText(this).apply {
            hint = "Gemini API Key"
            setText(prefs.getString("gemini_key", ""))
        }

        val saveKeyBtn = Button(this).apply {
            text = "Save Settings & Start Background Watcher"
            setOnClickListener {
                prefs.edit()
                    .putString("gemini_key", apiKeyInput.text.toString().trim())
                    .putFloat("price_offset", 0f)
                    .apply()
                startWatcherService()
                Toast.makeText(this@MainActivity, "Settings saved & watcher active!", Toast.LENGTH_SHORT).show()
            }
        }

        val uploadBtn = Button(this).apply {
            text = "1. Pick Signal Screenshot from Gallery"
            setOnClickListener { pickImage.launch("image/*") }
        }

        previewImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 450)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val scanBtn = Button(this).apply {
            text = "2. Scan Chart with AI"
            setOnClickListener {
                val bmp = selectedBitmap
                val key = apiKeyInput.text.toString().trim()
                if (bmp == null || key.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Enter API Key & pick a chart first!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("gemini_key", key).apply()
                statusText.text = "Scanning Entry, SL & TP..."
                scanChartWithGemini(bmp, key)
            }
        }

        statusText = TextView(this).apply {
            text = "Ready."
            setPadding(0, 15, 0, 15)
        }

        dirSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("BUY", "SELL"))
        }
        entryInput = EditText(this).apply {
            hint = "Entry Price"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        slInput = EditText(this).apply {
            hint = "Stop Loss (SL)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        tpInput = EditText(this).apply {
            hint = "Take Profit (TP)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val trackBtn = Button(this).apply {
            text = "3. Start 1m Confirmation Watcher"
            setOnClickListener {
                val dir = dirSpinner.selectedItem.toString()
                val entry = entryInput.text.toString().toDoubleOrNull()
                val sl = slInput.text.toString().toDoubleOrNull()
                val tp = tpInput.text.toString().toDoubleOrNull()

                if (entry == null || sl == null || tp == null) {
                    Toast.makeText(this@MainActivity, "Verify valid numbers first!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (dir == "BUY" && (sl >= entry || tp <= entry)) {
                    Toast.makeText(this@MainActivity, "Invalid BUY: SL must be below Entry and TP above!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (dir == "SELL" && (sl <= entry || tp >= entry)) {
                    Toast.makeText(this@MainActivity, "Invalid SELL: SL must be above Entry and TP below!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val arr = JSONArray(prefs.getString("setups", "[]") ?: "[]")
                arr.put(JSONObject().apply {
                    put("direction", dir)
                    put("entry", entry)
                    put("sl", sl)
                    put("tp", tp)
                    put("tapped", false)
                    put("tap_ts", 0L)
                })
                prefs.edit().putString("setups", arr.toString()).apply()

                startWatcherService()
                refreshWatchlist()
                Toast.makeText(this@MainActivity, "Armed! Alarm will fire on 1m closed confirmation.", Toast.LENGTH_LONG).show()
            }
        }

        val clearBtn = Button(this).apply {
            text = "Clear Active Watchlist"
            setOnClickListener {
                prefs.edit().putString("setups", "[]").apply()
                refreshWatchlist()
            }
        }

        watchlistText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 25, 0, 25)
        }

        layout.addView(title)
        layout.addView(symbolSpinner)
        layout.addView(apiKeyInput)
        layout.addView(saveKeyBtn)
        layout.addView(uploadBtn)
        layout.addView(previewImage)
        layout.addView(scanBtn)
        layout.addView(statusText)
        layout.addView(dirSpinner)
        layout.addView(entryInput)
        layout.addView(slInput)
        layout.addView(tpInput)
        layout.addView(trackBtn)
        layout.addView(watchlistText)
        layout.addView(clearBtn)

        scroll.addView(layout)
        setContentView(scroll)
        startWatcherService()

        scope.launch {
            while (isActive) {
                refreshWatchlist()
                delay(4000)
            }
        }
    }

    private fun startWatcherService() {
        val intent = Intent(this, SniperWatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun refreshWatchlist() {
        val prefs = getSharedPreferences("ticksniper_prefs", Context.MODE_PRIVATE)
        val sym = prefs.getString("ui_symbol", "XAUUSD") ?: "XAUUSD"
        val arr = JSONArray(prefs.getString("setups", "[]") ?: "[]")
        val price = prefs.getString("last_price", "Waiting...")

        val sb = StringBuilder("Live $sym: $$price\nActive Watchlist (${arr.length()}):\n\n")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val st = if (s.optBoolean("tapped")) "👀 Zone Tapped (Watching 1m Close)" else "⏳ Waiting for Zone Tap"
            sb.append("#${i + 1} ${s.getString("direction")} @ ${s.getDouble("entry")} | SL: ${s.getDouble("sl")} | TP: ${s.getDouble("tp")}\n$st\n\n")
        }
        watchlistText.text = sb.toString()
    }

    private fun scanChartWithGemini(bmp: Bitmap, apiKey: String) {
        scope.launch(Dispatchers.IO) {
            try {
                // Compress screenshot to 768px max width for fast, low-load API inference
                val scaledBmp = if (bmp.width > 768) {
                    val ratio = 768.0 / bmp.width
                    Bitmap.createScaledBitmap(bmp, 768, (bmp.height * ratio).toInt(), true)
                } else bmp

                val out = ByteArrayOutputStream()
                scaledBmp.compress(Bitmap.CompressFormat.JPEG, 78, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

                val prompt = """
                    Analyze this TradingView/MT5 chart screenshot.
                    If small Stop-Loss box is ABOVE large Take-Profit box -> SELL.
                    If small Stop-Loss box is BELOW large Take-Profit box -> BUY.
                    Return ONLY raw JSON with no markdown: {"direction":"BUY","entry":3702.24,"sl":3699.57,"tp":3721.01}
                """.trimIndent()

                val genPayload = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray()
                            .put(JSONObject().put("text", prompt))
                            .put(JSONObject().put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", b64)
                            }))
                        )
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.0)
                        put("maxOutputTokens", 256)
                        put("thinkingConfig", JSONObject().apply {
                            put("thinkingLevel", "minimal")
                        })
                    })
                }

                // Rotate across Flash & Flash-Lite models with retry backoff on high-demand spikes
                val attempts = listOf(
                    "gemini-3.8-flash",
                    "gemini-3.8-flash-lite",
                    "gemini-3.0-flash",
                    "gemini-2.5-flash-lite",
                    "gemini-3.8-flash"
                )

                var lastError = "Unknown Gemini Error"
                var successText: String? = null

                for ((index, model) in attempts.withIndex()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Scanning with $model (attempt ${index + 1}/${attempts.size})..."
                    }

                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    val req = Request.Builder()
                        .url(url)
                        .post(genPayload.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    client.newCall(req).execute().use { resp ->
                        val resStr = resp.body?.string() ?: ""
                        val rootJson = JSONObject(resStr)
                        if (rootJson.has("error")) {
                            lastError = "[$model] " + rootJson.getJSONObject("error").optString("message", resStr)
                            return@use
                        }
                        val parts = rootJson
                            .getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts")
                        for (i in 0 until parts.length()) {
                            val p = parts.getJSONObject(i)
                            if (p.has("text") && !p.optBoolean("thought", false)) {
                                successText = extractJsonBlock(p.getString("text"))
                                break
                            }
                        }
                    }

                    if (successText != null) break
                    delay(2000)
                }

                if (successText == null) throw Exception(lastError)

                val parsed = JSONObject(successText!!)
                withContext(Dispatchers.Main) {
                    dirSpinner.setSelection(if (parsed.getString("direction").uppercase(Locale.US) == "BUY") 0 else 1)
                    entryInput.setText(parsed.getDouble("entry").toString())
                    slInput.setText(parsed.getDouble("sl").toString())
                    tpInput.setText(parsed.getDouble("tp").toString())
                    statusText.text = "✅ Levels extracted! Verify & tap Start Watcher."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Scan error: ${e.message}"
                }
            }
        }
    }

    private fun extractJsonBlock(raw: String): String {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start != -1 && end > start) cleaned.substring(start, end + 1) else cleaned
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
