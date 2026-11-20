package com.example.wifiguardian

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.app.AlertDialog
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import android.net.wifi.WifiManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ConnectException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val bg = Executors.newSingleThreadExecutor()
    private val probes = Executors.newFixedThreadPool(24)
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var results: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var historyButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var emptyState: TextView
    private lateinit var exportButton: Button
    private lateinit var trafficSummary: TextView
    private lateinit var trafficList: LinearLayout
    private val trafficRequestCode = 411
    private lateinit var liveSummary: TextView
    private lateinit var liveList: LinearLayout
    private var selectedDeviceFilter = ""
    private lateinit var filterStatus: TextView
    private lateinit var liveButton: Button
    private val liveHandler = Handler(Looper.getMainLooper())
    private var liveEnabled = false
    private var liveEndpoint = ""
    private var liveSessionToken = "" // Session only; never saved in preferences.
    private var previousLive: Map<String, LiveDevice> = emptyMap()
    private var previousLiveTimeMs = 0L
    private data class LiveDevice(
        val id: String, val ip: String, val name: String, val kind: String,
        val rx: Long, val tx: Long, val source: String
    )
    private val liveTick = object : Runnable {
        override fun run() {
            if (!liveEnabled) return
            fetchLiveTraffic()
        }
    }
    private data class TrafficEntry(val ip: String, val name: String, val rx: Long, val tx: Long, val observedAt: String)
    private var trafficEntries: List<TrafficEntry> = emptyList()
    private var gateway: String? = null
    private var localIp: String? = null
    private var lastResults = mutableListOf<Pair<String, String>>()
    private val prefs by lazy { getSharedPreferences("guardian", MODE_PRIVATE) }

    private val ink = Color.rgb(21, 40, 67)
    private val muted = Color.rgb(98, 115, 135)
    private val blue = Color.rgb(35, 102, 224)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun label(s: String, size: Float = 15f, bold: Boolean = false): TextView = TextView(this).apply {
        text = s; textSize = size; setTextColor(ink)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun small(s: String): TextView = label(s, 13f).apply { setTextColor(muted) }
    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
