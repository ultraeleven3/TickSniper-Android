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
import android.util.Base64
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText
    private lateinit var btnPickImage: Button
    private lateinit var btnScanChart: Button
    private lateinit var ivPreview: ImageView
    private lateinit var rgDirection: RadioGroup
    private lateinit var rbBuy: RadioButton
    private lateinit var rbSell: RadioButton
    private lateinit var etEntry: EditText
    private lateinit var etSl: EditText
    private lateinit var etTp: EditText
    private lateinit var spSymbol: Spinner
    private lateinit var btnStartWatcher: Button
    private lateinit var btnStopWatcher: Button
    private lateinit var tvStatus: TextView

    private var selectedBitmap: Bitmap? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                try {
                    contentResolver.openInputStream(it)?.use { stream ->
                        selectedBitmap = BitmapFactory.decodeStream(stream)
                        ivPreview.setImageBitmap(selectedBitmap)
                        tvStatus.text = "Chart loaded. Tap '2. Scan Chart with AI'."
                    }
                } catch (e: Exception) {
                    tvStatus.text = "Failed to load image: \${e.message}"
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        btnPickImage = findViewById(R.id.btnPickImage)
        btnScanChart = findViewById(R.id.btnScanChart)
        ivPreview = findViewById(R.id.ivPreview)
        rgDirection = findViewById(R.id.rgDirection)
        rbBuy = findViewById(R.id.rbBuy)
        rbSell = findViewById(R.id.rbSell)
        etEntry = findViewById(R.id.etEntry)
        etSl = findViewById(R.id.etSl)
        etTp = findViewById(R.id.etTp)
        spSymbol = findViewById(R.id.spSymbol)
        btnStartWatcher = findViewById(R.id.btnStartWatcher)
        btnStopWatcher = findViewById(R.id.btnStopWatcher)
        tvStatus = findViewById(R.id.tvStatus)

        requestNotificationPermissionIfNeeded()

        val prefs = getSharedPreferences("ticksniper_prefs", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("gemini_api_key", ""))

        val symbols = listOf("GC=F", "BTC-USD")
        spSymbol.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, symbols)
        val savedSymbol = prefs.getString("symbol", "GC=F") ?: "GC=F"
        val idx = symbols.indexOf(savedSymbol)
        if (idx >= 0) spSymbol.setSelection(idx)

        btnPickImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnScanChart.setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            if (apiKey.isEmpty()) {
                tvStatus.text = "Enter your Gemini API Key first."
                return@setOnClickListener
            }
            val bmp = selectedBitmap
            if (bmp == null) {
                tvStatus.text = "Select a chart screenshot first."
                return@setOnClickListener
            }
            prefs.edit().putString("gemini_api_key", apiKey).apply()
            scanChartWithGemini(apiKey, bmp)
        }

        btnStartWatcher.setOnClickListener {
            val entry = etEntry.text.toString().trim().toDoubleOrNull()
            val sl = etSl.text.toString().trim().toDoubleOrNull()
            val tp = etTp.text.toString().trim().toDoubleOrNull()

            if (entry == null || sl == null || tp == null) {
                tvStatus.text = "Enter valid Entry, SL, and TP numbers first."
                return@setOnClickListener
            }

            val direction = if (rbSell.isChecked) "SELL" else "BUY"
            val chosenSymbol = spSymbol.selectedItem?.toString() ?: "GC=F"

            val setupObj = JSONObject().apply {
                put("direction", direction)
                put("entry", entry)
                put("sl", sl)
                put("tp", tp)
                put("tapped", false)
                put("tap_ts", 0L)
            }

            val setupsArr = JSONArray().apply { put(setupObj) }
            prefs.edit()
                .putString("gemini_api_key", etApiKey.text.toString().trim())
                .putString("symbol", chosenSymbol)
                .putString("setups", setupsArr.toString())
                .apply()

            val serviceIntent = Intent(this, SniperWatcherService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            tvStatus.text = "Watcher Armed (\$chosenSymbol): $direction @ $entry | SL: $sl \vert{} TP:$tp"
        }

        btnStopWatcher.setOnClickListener {
            prefs.edit().putString("setups", "[]").apply()
            stopService(Intent(this, SniperWatcherService::class.java))
            tvStatus.text = "Watcher stopped and active setups cleared."
        }
    }

    private fun scanChartWithGemini(apiKey: String, bitmap: Bitmap) {
        btnScanChart.isEnabled = false
        btnScanChart.text = "Scanning Chart..."
        tvStatus.text = "Compressing chart & contacting AI..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val maxDim = 800
                val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1f)
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt(),
                        (bitmap.height * scale).toInt(),
                        true
                    )
                } else bitmap

                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

                val prompt = """
                    Analyze this trading chart screenshot. Find the Buy or Sell position tool (green/red risk-reward box) or marked Entry, Stop Loss (SL), and Take Profit (TP) price levels on the right price axis.
                    Reply ONLY with valid JSON and nothing else:
                    {"direction":"BUY","entry":0.0,"sl":0.0,"tp":0.0}
                """.trimIndent()

                val requestJson = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", prompt))
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", base64Image)
                                    })
                                })
                            })
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.1)
                        put("responseMimeType", "application/json")
                    })
                }

                val models = listOf(
                    "gemini-3.6-flash",
                    "gemini-3.1-flash-lite",
                    "gemini-3.8-flash"
                )

                var parsedResult: JSONObject? = null
                var lastError = "Unknown error"

                for (model in models) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "Trying \$model..."
                    }

                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                    val request = Request.Builder()
                        .url(url)
                        .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                        .build()

                    try {
                        httpClient.newCall(request).execute().use { response ->
                            val bodyStr = response.body?.string() ?: ""
                            if (response.isSuccessful) {
                                val root = JSONObject(bodyStr)
                                val rawText = root.getJSONArray("candidates")
                                    .getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts")
                                    .getJSONObject(0)
                                    .getString("text")
                                    .trim()

                                val cleanJson = rawText
                                    .removePrefix("```json")
                                    .removePrefix("```")
                                    .removeSuffix("```")
                                    .trim()

                                parsedResult = JSONObject(cleanJson)
                            } else {
                                val errMsg = try {
                                    JSONObject(bodyStr).getJSONObject("error").getString("message")
                                } catch (_: Exception) {
                                    "HTTP ${response.code}"
                                }
                                lastError = "$model -> $errMsg"
                            }
                        }
                    } catch (e: Exception) {
                        lastError = "$model -> ${e.message}"
                    }

                    if (parsedResult != null) break
                    delay(800)
                }

                withContext(Dispatchers.Main) {
                    btnScanChart.isEnabled = true
                    btnScanChart.text = "2. Scan Chart with AI"

                    if (parsedResult != null) {
                        val dir = parsedResult!!.optString("direction", "BUY").uppercase()
                        val entry = parsedResult!!.optDouble("entry", 0.0)
                        val sl = parsedResult!!.optDouble("sl", 0.0)
                        val tp = parsedResult!!.optDouble("tp", 0.0)

                        if (dir == "SELL") rbSell.isChecked = true else rbBuy.isChecked = true
                        if (entry > 0) etEntry.setText(entry.toString())
                        if (sl > 0) etSl.setText(sl.toString())
                        if (tp > 0) etTp.setText(tp.toString())

                        tvStatus.text = "AI Extracted: $dir | Entry: $entry | SL: $sl | TP: $tp"
                    } else {
                        tvStatus.text = "Scan Failed: $lastError"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    btnScanChart.isEnabled = true
                    btnScanChart.text = "2. Scan Chart with AI"
                    tvStatus.text = "App Error: ${e.message}"
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
}
