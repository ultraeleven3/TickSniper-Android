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
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val client = OkHttpClient()
    private var selectedBitmap: Bitmap? = null

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
            val stream = contentResolver.openInputStream(it)
            selectedBitmap = BitmapFactory.decodeStream(stream)
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
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "📈 TickSniper 1m XAUUSD"
            textSize = 22f
            setPadding(0, 0, 0, 20)
        }

        apiKeyInput = EditText(this).apply {
            hint = "Gemini API Key"
            setText(prefs.getString("gemini_key", ""))
        }

        val saveKeyBtn = Button(this).apply {
            text = "Save API Key & Start Background Watcher"
            setOnClickListener {
                prefs.edit().putString("gemini_key", apiKeyInput.text.toString().trim()).apply()
                startWatcherService()
                Toast.makeText(this@MainActivity, "Watcher running in background!", Toast.LENGTH_SHORT).show()
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
        entryInput = EditText(this).apply { hint = "Entry Price" }
        slInput = EditText(this).apply { hint = "Stop Loss (SL)" }
        tpInput = EditText(this).apply { hint = "Take Profit (TP)" }

        val trackBtn = Button(this).apply {
            text = "3. Start 1m Confirmation Watcher"
            setOnClickListener {
                val entry = entryInput.text.toString().toDoubleOrNull()
                val sl = slInput.text.toString().toDoubleOrNull()
                val tp = tpInput.text.toString().toDoubleOrNull()
                if (entry == null || sl == null || tp == null) {
                    Toast.makeText(this@MainActivity, "Verify valid numbers first!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val arr = JSONArray(prefs.getString("setups", "[]"))
                arr.put(JSONObject().apply {
                    put("direction", dirSpinner.selectedItem.toString())
                    put("entry", entry)
                    put("sl", sl)
                    put("tp", tp)
                    put("tapped", false)
                })
                prefs.edit().putString("setups", arr.toString()).apply()
                startWatcherService()
                refreshWatchlist()
                Toast.makeText(this@MainActivity, "Added! Your phone will alarm on 1m confirmation.", Toast.LENGTH_LONG).show()
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
                delay(5000)
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
        val arr = JSONArray(prefs.getString("setups", "[]"))
        val price = prefs.getString("
