package com.example.wifiguardian

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
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
    private lateinit var exportButton: Button
    private var gateway: String? = null
    private var localIp: String? = null
    private var lastResults = mutableListOf<Pair<String, String>>()
    private val prefs by lazy { getSharedPreferences("guardian", MODE_PRIVATE) }

    private fun label(s: String, size: Float = 15f, bold: Boolean = false): TextView = TextView(this).apply {
        text = s; textSize = size; setTextColor(Color.rgb(30, 49, 71))
        setPadding(8, 12, 8, 12)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 32)
            setBackgroundColor(Color.rgb(245, 249, 253))
        }
        scroll.addView(root)
        root.addView(label("WiFi Guardian", 27f, true))
        root.addView(label("Local network visibility • No root required", 13f))
        status = label("Connect to Wi-Fi and tap Scan devices")
        root.addView(status)
        summary = label("No scan yet", 15f, true)
        root.addView(summary)
        scanButton = Button(this).apply { text = "Scan devices"; setOnClickListener { scan() } }
        root.addView(scanButton)
        val gatewayButton = Button(this).apply {
            text = "Router management"
            setOnClickListener {
                val g = gateway
                if (g == null) toast("Connect and scan to detect the gateway")
                else {
                    // Opening a router's UI does not grant access or automatically block clients.
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$g")))
                }
            }
        }
        root.addView(gatewayButton)
        exportButton = Button(this).apply { text = "Share scan as CSV"; setOnClickListener { shareCsv() } }
        root.addView(exportButton)
        historyButton = Button(this).apply { text = "View scan history"; setOnClickListener { showHistory() } }
        root.addView(historyButton)
        root.addView(label("Devices are discovered through limited TCP reachability checks. A missing device is not necessarily offline. Device disconnection and other clients' traffic require supported, authorized router management.", 12f))
        root.addView(label("DISCOVERED ADDRESSES", 17f, true))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)
        setContentView(scroll)
        refreshNetworkInfo()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private data class NetworkInfo(val ip: String, val prefix: Int, val gateway: String?)

    private fun networkInfo(): NetworkInfo? {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        // Prefer an active Wi-Fi network. A VPN may hide Wi-Fi from activeNetwork.
        val net = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return null
        val props: LinkProperties = cm.getLinkProperties(net) ?: return null
        val addr = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val g = props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        return NetworkInfo(addr.address.hostAddress ?: return null, addr.prefixLength, g)
    }

    private fun refreshNetworkInfo() {
        val info = networkInfo()
        gateway = info?.gateway
        localIp = info?.ip
        status.text = if (info == null) "No accessible Wi-Fi IPv4 connection" else
            "Phone: ${info.ip}/${info.prefix}   Gateway: ${info.gateway ?: "unknown"}"
    }

    private fun reachable(ip: String): Boolean {
        // TCP connect probes are incomplete by design; do not treat failure as proof of absence.
        for (port in intArrayOf(80, 443, 22, 53, 8080)) {
            try { Socket().use { it.connect(InetSocketAddress(ip, port), 170) }; return true }
            catch (_: Exception) { }
        }
        return false
    }

    private fun scan() {
        refreshNetworkInfo()
        val info = networkInfo() ?: run { toast("Connect to a Wi-Fi network first"); return }
        if (info.prefix != 24) {
            status.text = "This release scans /24 networks only. Detected: ${info.ip}/${info.prefix}"
            return
        }
        val parts = info.ip.split(".")
        if (parts.size != 4) return
        val base = parts.take(3).joinToString(".")
        scanButton.isEnabled = false
        results.removeAllViews()
        summary.text = "Scanning $base.0/24 ..."
        bg.execute {
            val found = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            val done = AtomicInteger(0)
            val jobs = (1..254).map { i ->
                probes.submit {
                    val host = "$base.$i"
                    val role = when (host) {
                        info.ip -> "This phone"
                        info.gateway -> "Gateway (router)"
                        else -> "TCP responsive"
                    }
                    if (host == info.ip || host == info.gateway || reachable(host)) found.add(host to role)
                    val progress = done.incrementAndGet()
                    if (progress % 40 == 0) runOnUiThread { summary.text = "Scanned $progress / 254 addresses..." }
                }
            }
            jobs.forEach { try { it.get(40, TimeUnit.SECONDS) } catch (_: Exception) { it.cancel(true) } }
            val sorted = found.distinctBy { it.first }.sortedWith(compareBy { it.first.substringAfterLast('.').toIntOrNull() ?: 999 })
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val previous = prefs.getString("last_ips", "")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
            val current = sorted.map { it.first }.toSet()
            val newIps = current - previous
            val missingIps = previous - current
            prefs.edit().putString("last_ips", current.joinToString(",")).apply()
            val history = JSONArray(prefs.getString("history", "[]"))
            history.put(JSONObject().put("at", timestamp).put("count", sorted.size).put("new", newIps.size).put("missing", missingIps.size))
            while (history.length() > 30) history.remove(0)
            prefs.edit().putString("history", history.toString()).apply()
            runOnUiThread {
                lastResults = sorted.toMutableList()
                results.removeAllViews()
                sorted.forEach { (ip, role) ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(4, 4, 4, 4) }
                    row.addView(label("$ip  •  $role${if (ip in newIps) "  • NEW" else ""}", 15f, true))
                    val copy = Button(this).apply {
                        text = "Copy IP"
                        setOnClickListener {
                            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("IP address", ip))
                            toast("Copied $ip")
                        }
                    }
                    row.addView(copy)
                    results.addView(row)
                }
                summary.text = "${sorted.size} listed • ${newIps.size} new • ${missingIps.size} no longer detected\nLast scan: $timestamp"
                scanButton.isEnabled = true
            }
        }
    }

    private fun shareCsv() {
        if (lastResults.isEmpty()) { toast("Run a scan first"); return }
        val csv = buildString {
            append("ip,classification\n")
            lastResults.forEach { (ip, role) -> append("\"$ip\",\"$role\"\n") }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WiFi Guardian scan.csv")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(intent, "Share scan data"))
    }

    private fun showHistory() {
        val arr = JSONArray(prefs.getString("history", "[]"))
        val content = if (arr.length() == 0) "No scans yet" else (arr.length() - 1 downTo 0).joinToString("\n") { i ->
            val obj = arr.getJSONObject(i)
            "${obj.optString("at")}: ${obj.optInt("count")} listed, ${obj.optInt("new")} new, ${obj.optInt("missing")} no longer detected"
        }
        android.app.AlertDialog.Builder(this).setTitle("Recent scans (up to 30)")
            .setMessage(content).setPositiveButton("Close", null).show()
    }

    override fun onDestroy() {
        bg.shutdownNow(); probes.shutdownNow(); super.onDestroy()
    }
}
