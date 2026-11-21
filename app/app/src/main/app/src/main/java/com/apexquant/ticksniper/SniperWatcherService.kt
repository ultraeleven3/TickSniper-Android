package com.apexquant.ticksniper

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SniperWatcherService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()
    private val FG_CHANNEL = "ticksniper_bg"
    private val ALERT_CHANNEL = "ticksniper_alerts"

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val notification = NotificationCompat.Builder(this, FG_CHANNEL)
            .setContentTitle("TickSniper Active")
            .setContentText("Monitoring XAUUSD 1m candles in background...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(101, notification)
        startLoop()
    }

    private fun startLoop() {
        scope.launch {
            while (isActive) {
                try {
                    val prefs = getSharedPreferences("ticksniper_prefs", Context.MODE_PRIVATE)
                    val setupsStr = prefs.getString("setups", "[]") ?: "[]"
                    val setups = JSONArray(setupsStr)

                    if (setups.length() > 0) {
                        val candles = fetch1mGoldCandles()
                        if (candles.size >= 10) {
                            val currentPrice = candles.last().close
                            prefs.edit().putString("last_price", String.format("%.2f", currentPrice)).apply()

                            val recentLow = candles.takeLast(3).minOf { it.low }
                            val recentHigh = candles.takeLast(3).maxOf { it.high }
                            val updated = JSONArray()

                            for (i in 0 until setups.length()) {
                                val s = setups.getJSONObject(i)
                                val dir = s.getString("direction")
                                val entry = s.getDouble("entry")
                                val sl = s.getDouble("sl")
                                val tp = s.getDouble("tp")
                                var tapped = s.optBoolean("tapped", false)

                                if ((dir == "BUY" && currentPrice < sl) || (dir == "SELL" && currentPrice > sl)) {
                                    sendAlert("Setup Invalidated", "$dir @ $entry breached SL $sl before confirmation.", false)
                                    continue
                                }

                                if (!tapped) {
                                    if ((dir == "BUY" && recentLow <= entry + 0.80) || (dir == "SELL" && recentHigh >= entry - 0.80)) {
                                        tapped = true
                                        s.put("tapped", true)
                                        sendAlert("Zone Tapped!", "XAUUSD tapped $entry ($dir). Watching 1m close for CHoCH/rejection...", false)
                                    }
                                }

                                if (tapped && check1mConfirmation(candles, dir)) {
                                    sendAlert(
                                        "$dir XAUUSD CONFIRMED!",
                                        "Live: ${String.format("%.2f", currentPrice)} | Entry: $entry | SL: $sl | TP: $tp",
                                        true
                                    )
                                    continue
                                }
                                updated.put(s)
                            }
                            prefs.edit().putString("setups", updated.toString()).apply()
                        }
                    }
                } catch (_: Exception) {
                }
                delay(15_000)
            }
        }
    }

    data class Candle(val open: Double, val high: Double, val low: Double, val close: Double)

    private fun fetch1mGoldCandles(): List<Candle> {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/GC=F?interval=1m&range=1d"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val body = client.newCall(req).execute().body?.string() ?: return emptyList()
        val json = JSONObject(body)
        val result = json.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val quote = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val opens = quote.getJSONArray("open")
        val highs = quote.getJSONArray("high")
        val lows = quote.getJSONArray("low")
        val closes = quote.getJSONArray("close")

        val list = mutableListOf<Candle>()
        for (i in 0 until closes.length()) {
            if (!closes.isNull(i) && !opens.isNull(i)) {
                list.add(Candle(opens.getDouble(i), highs.getDouble(i), lows.getDouble(i), closes.getDouble(i)))
            }
        }
        return list.takeLast(30)
    }

    private fun check1mConfirmation(candles: List<Candle>, dir: String): Boolean {
        val c1 = candles[candles.size - 2]
        val c2 = candles[candles.size - 3]
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
            nm.createNotificationChannel(NotificationChannel(FG_CHANNEL, "Background Watcher", NotificationManager.IMPORTANCE_LOW))
            val alertCh = NotificationChannel(ALERT_CHANNEL, "Trade Confirmations", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 800)
            }
            nm.createNotificationChannel(alertCh)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
