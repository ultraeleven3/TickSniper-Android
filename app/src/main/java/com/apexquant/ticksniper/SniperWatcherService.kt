package com.apexquant.ticksniper

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SniperWatcherService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val FG_CHANNEL = "pipsniper_bg_v2"
    private val ALERT_CHANNEL = "pipsniper_alerts_v2"
    private var wakeLock: PowerManager.WakeLock? = null

    data class Candle(
        val timestamp: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    )

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PipSniper::WatcherLock")

        startForeground(101, buildForegroundNotification("Initializing live feed..."))
        startLoop()
    }

    private fun startLoop() {
        scope.launch {
            while (isActive) {
                try {
                    val prefs = getSharedPreferences("ticksniper_prefs", Context.MODE_PRIVATE)
                    val initialSetups = JSONArray(prefs.getString("setups", "[]") ?: "[]")

                    if (initialSetups.length() == 0) {
                        releaseWakeLock()
                        updateForegroundNotification("Armed: 0 setups | Waiting for zones...")
                        delay(15_000)
                        continue
                    }

                    acquireWakeLock()

                    // Determine symbol (supports BTC-USD if configured, defaults to GC=F)
                    val symbol = prefs.getString("symbol", "GC=F") ?: "GC=F"
                    val priceOffset = prefs.getFloat("price_offset", 0f).toDouble()
                    val candles = fetch1mCandles(symbol, priceOffset)

                    if (candles.size >= 10) {
                        val liveCandle = candles.last()
                        val closedCandle = candles[candles.size - 2]
                        val currentPrice = liveCandle.close
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                        // Re-read prefs AFTER network call so we never overwrite newly added setups
                        val freshSetups = JSONArray(prefs.getString("setups", "[]") ?: "[]")
                        val updated = JSONArray()
                        var nearestDist = Double.MAX_VALUE

                        for (i in 0 until freshSetups.length()) {
                            val s = freshSetups.getJSONObject(i)
                            val dir = s.getString("direction").uppercase(Locale.US)
                            val entry = s.getDouble("entry")
                            val sl = s.getDouble("sl")
                            val tp = s.getDouble("tp")
                            var tapped = s.optBoolean("tapped", false)
                            var tapTimestamp = s.optLong("tap_ts", 0L)

                            nearestDist = min(nearestDist, abs(currentPrice - entry))

                            // 1. Check Invalidation (Stop Loss breached)
                            if ((dir == "BUY" && currentPrice <= sl) || (dir == "SELL" && currentPrice >= sl)) {
                                sendAlert(
                                    "Setup Invalidated ($dir)",
                                    "$dir @ $entry hit SL $sl before 1m confirmation.",
                                    urgent = false
                                )
                                continue
                            }

                            // 2. Check Zone Tap (using recent 2 candles high/low + 0.80 buffer)
                            val recentLow = min(liveCandle.low, closedCandle.low)
                            val recentHigh = max(liveCandle.high, closedCandle.high)

                            if (!tapped) {
                                val hitZone = if (dir == "BUY") {
                                    recentLow <= entry + 0.80 && currentPrice > sl
                                } else {
                                    recentHigh >= entry - 0.80 && currentPrice < sl
                                }

                                if (hitZone) {
                                    tapped = true
                                    // Record the exact candle timestamp where tap occurred
                                    tapTimestamp = if (
                                        (dir == "BUY" && closedCandle.low <= entry + 0.80) ||
                                        (dir == "SELL" && closedCandle.high >= entry - 0.80)
                                    ) closedCandle.timestamp else liveCandle.timestamp

                                    s.put("tapped", true)
                                    s.put("tap_ts", tapTimestamp)
                                    sendAlert(
                                        "Zone Tapped! ($dir)",
                                        "Price tapped $entry. Waiting for 1m closed candle confirmation...",
                                        urgent = false
                                    )
                                }
                            }

                            // 3. Check 1m Confirmation ONLY on a closed candle at/after the tap timestamp
                            if (tapped && closedCandle.timestamp >= tapTimestamp) {
                                if (check1mConfirmation(candles, dir)) {
                                    sendAlert(
                                        "$dir CONFIRMED!",
                                        "Live: ${String.format(Locale.US, "%.2f", currentPrice)} | Entry: $entry | SL: $sl | TP: $tp",
                                        urgent = true
                                    )
                                    continue
                                }
                            }

                            updated.put(s)
                        }

                        prefs.edit()
                            .putString("last_price", String.format(Locale.US, "%.2f", currentPrice))
                            .putString("setups", updated.toString())
                            .apply()

                        val distLabel = if (nearestDist < Double.MAX_VALUE) {
                            String.format(Locale.US, "%.2f", nearestDist)
                        } else "--"

                        updateForegroundNotification(
                            "Live: ${String.format(Locale.US, "%.2f", currentPrice)} | Dist: $distLabel | Tick: $timeStr"
                        )
                    } else {
                        updateForegroundNotification("Feed stalled — retrying Yahoo Finance...")
                    }
                } catch (e: Exception) {
                    updateForegroundNotification("Network hiccup — retrying in 15s...")
                }
                delay(15_000)
            }
        }
    }

    private fun fetch1mCandles(symbol: String, offset: Double): List<Candle> {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$symbol?interval=1m&range=1d"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()

            val json = JSONObject(body)
            val result = json.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val timestamps = result.getJSONArray("timestamp")
            val quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)

            val opens = quote.getJSONArray("open")
            val highs = quote.getJSONArray("high")
            val lows = quote.getJSONArray("low")
            val closes = quote.getJSONArray("close")

            val list = mutableListOf<Candle>()
            for (i in 0 until closes.length()) {
                if (!closes.isNull(i) && !opens.isNull(i) && !highs.isNull(i) && !lows.isNull(i)) {
                    list.add(
                        Candle(
                            timestamp = timestamps.getLong(i),
                            open = opens.getDouble(i) + offset,
                            high = highs.getDouble(i) + offset,
                            low = lows.getDouble(i) + offset,
                            close = closes.getDouble(i) + offset
                        )
                    )
                }
            }
            return list.takeLast(30)
        }
    }

    private fun check1mConfirmation(candles: List<Candle>, dir: String): Boolean {
        if (candles.size < 7) return false
        val c1 = candles[candles.size - 2] // Last CLOSED 1m candle
        val c2 = candles[candles.size - 3] // Candle before c1
        val prevSlice = candles.subList(candles.size - 6, candles.size - 2)
        val recentHigh = prevSlice.maxOf { it.high }
        val recentLow = prevSlice.minOf { it.low }

        val body = abs(c1.close - c1.open)
        val upperWick = c1.high - max(c1.close, c1.open)
        val lowerWick = min(c1.close, c1.open) - c1.low

        return if (dir == "BUY") {
            val choch = c1.close > recentHigh && c1.close > c1.open
            val pinbar = lowerWick >= 1.8 * max(body, 0.05) && c1.close >= c1.open
            val engulfing = c1.close > c1.open && c2.close < c2.open && c1.close > c2.open
            choch || pinbar || engulfing
        } else {
            val choch = c1.close < recentLow && c1.close < c1.open
            val pinbar = upperWick >= 1.8 * max(body, 0.05) && c1.close <= c1.open
            val engulfing = c1.close < c1.open && c2.close > c2.open && c1.close < c2.open
            choch || pinbar || engulfing
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, FG_CHANNEL)
            .setContentTitle("Pip Sniper Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateForegroundNotification(statusText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(101, buildForegroundNotification(statusText))
    }

    private fun sendAlert(title: String, msg: String, urgent: Boolean) {
        val alarmSound = RingtoneManager.getDefaultUri(
            if (urgent) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
        )
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setContentTitle(title)
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSound(alarmSound)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 800))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(FG_CHANNEL, "Pip Sniper Watcher", NotificationManager.IMPORTANCE_LOW)
            )

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val audioAttrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val alertCh = NotificationChannel(
                ALERT_CHANNEL,
                "Pip Sniper Trade Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
                setSound(alarmUri, audioAttrs)
            }
            nm.createNotificationChannel(alertCh)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hour max safety window, auto-renewed while active
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }
}
