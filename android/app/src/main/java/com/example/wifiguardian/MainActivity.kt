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
import android.os.Build
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
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(17), dp(16), dp(17), dp(16))
        background = rounded(Color.WHITE)
        elevation = dp(1).toFloat()
    }
    private fun mainButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title; isAllCaps = false; textSize = 15f
        setTextColor(Color.WHITE); background = rounded(blue, 12)
        setOnClickListener { action() }
        minHeight = dp(52)
    }
    private fun secondaryButton(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title; isAllCaps = false; textSize = 14f
        setTextColor(ink); background = rounded(Color.rgb(229, 237, 249), 12)
        setOnClickListener { action() }
    }

    private fun expandableSection(
        title: String,
        description: String,
        content: LinearLayout.() -> Unit
    ): LinearLayout {
        val section = card()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(title, 17f, true))
        text.addView(gap(3))
        text.addView(small(description))
        header.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val arrow = label("+", 24f, true).apply {
            setTextColor(blue)
            gravity = Gravity.CENTER
            contentDescription = "Expand section"
        }
        header.addView(arrow, LinearLayout.LayoutParams(dp(36), dp(40)))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }.apply(content)
        header.setOnClickListener {
            val expanded = body.visibility != View.VISIBLE
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            arrow.text = if (expanded) "−" else "+"
            arrow.contentDescription = if (expanded) "Collapse section" else "Expand section"
        }
        section.addView(header)
        section.addView(gap(10))
        section.addView(body)
        return section
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(16, 39, 78)
        window.navigationBarColor = Color.rgb(241, 245, 251)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(32))
            setBackgroundColor(Color.rgb(241, 245, 251))
        }
        scroll.addView(root)

        val header = card().apply { background = rounded(Color.rgb(16, 39, 78), 20) }
        header.addView(label("WiFi Guardian", 27f, true).apply { setTextColor(Color.WHITE) })
        header.addView(gap(5))
        header.addView(label("See devices this phone can find on your Wi-Fi", 14f).apply { setTextColor(Color.rgb(194, 218, 251)) })
        root.addView(header)
        root.addView(gap(16))

        val netCard = card()
        netCard.addView(small("WI-FI CONNECTION"))
        netCard.addView(gap(8))
        status = label("Checking Wi-Fi connection...", 15f, true)
        netCard.addView(status)
        netCard.addView(gap(9))
        netCard.addView(small("Discovery is best-effort. Some routers or devices may hide from scans."))
        root.addView(netCard)
        root.addView(gap(14))

        scanButton = mainButton("Find devices on this Wi-Fi") { scan() }
        root.addView(scanButton, LinearLayout.LayoutParams(-1, dp(54)))
        root.addView(gap(10))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 254; progress = 0; visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(5)))
        root.addView(gap(14))

        val summaryCard = card()
        summaryCard.addView(small("LATEST SCAN"))
        summaryCard.addView(gap(5))
        summary = label("No scan yet. Tap “Find devices” to get started.", 15f, true)
        summaryCard.addView(summary)
        root.addView(summaryCard)
        root.addView(gap(18))

        root.addView(label("Devices found", 21f, true))
        root.addView(gap(5))
        root.addView(small("Names and MAC addresses appear only when the network makes them available."))
        root.addView(gap(10))
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)
        emptyState = small("Your results will appear here. Connect to Wi-Fi, then tap “Find devices”.").apply {
            setPadding(dp(15), dp(18), dp(15), dp(18))
            background = rounded(Color.WHITE)
        }
        results.addView(emptyState)
        root.addView(gap(20))

        root.addView(expandableSection(
            "Live traffic",
            "Optional • Needs traffic data from your router or collector"
        ) {
            liveSummary = label("Not set up", 15f, true)
            addView(liveSummary)
            addView(gap(8))
            addView(small("This is separate from finding devices. Configure a trusted data source to view per-device usage."))
            addView(gap(10))
            addView(secondaryButton("Set up traffic source") { configureLive() })
            addView(gap(8))
            liveButton = mainButton("Start monitoring") { toggleLive() }
            addView(liveButton)
            addView(gap(8))
            addView(secondaryButton("Choose a device") { configureDeviceFilter() })
            addView(gap(6))
            filterStatus = small("Showing all reported devices")
            addView(filterStatus)
            addView(gap(10))
            liveList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(liveList)
        })
        root.addView(gap(20))

        root.addView(expandableSection(
            "Router traffic report",
            "Import a CSV exported by your router"
        ) {
            trafficSummary = label("No report imported", 15f, true)
            addView(trafficSummary)
            addView(gap(8))
            addView(small("Reports show data supplied by your router; they are not captured by this app."))
            addView(gap(10))
            addView(mainButton("Import traffic CSV") { pickTrafficCsv() })
            addView(gap(8))
            addView(secondaryButton("CSV format and example") { showTrafficHelp() })
            addView(gap(8))
            addView(secondaryButton("Clear imported report") { clearTraffic() })
            addView(gap(10))
            trafficList = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            addView(trafficList)
        })
        root.addView(gap(10))

        root.addView(expandableSection("More options", "Router settings, scan history, and export") {
            addView(secondaryButton("Open router settings") {
                val g = gateway
                if (g == null) toast("Router gateway not detected")
                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$g")))
            })
            addView(gap(8))
            exportButton = secondaryButton("Share device list as CSV") { shareCsv() }
            addView(exportButton)
            addView(gap(8))
            historyButton = secondaryButton("View scan history") { showHistory() }
            addView(historyButton)
            addView(gap(12))
            addView(small("Blocking devices and viewing other devices’ traffic require a compatible router integration."))
        })
        setContentView(scroll)
        refreshNetworkInfo()
        restoreTraffic()
        liveEndpoint = prefs.getString("live_endpoint", "") ?: ""
        if (liveEndpoint.isNotBlank()) liveSummary.text = "Source saved • Start monitoring when ready"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private data class NetworkInfo(val ip: String, val prefix: Int, val gateway: String?)

    private fun ipv4Value(address: String): Long? {
        val octets = address.split(".")
        if (octets.size != 4) return null
        var value = 0L
        octets.forEach { part ->
            val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    private fun ipv4Address(value: Long): String =
        (3 downTo 0).joinToString(".") { shift -> ((value shr (shift * 8)) and 0xff).toString() }

    private fun isInSubnet(address: String, info: NetworkInfo): Boolean {
        val candidate = ipv4Value(address) ?: return false
        val local = ipv4Value(info.ip) ?: return false
        if (info.prefix !in 1..30) return false
        val hostMask = (1L shl (32 - info.prefix)) - 1
        val networkMask = 0xFFFFFFFFL xor hostMask
        return (candidate and networkMask) == (local and networkMask)
    }

    private fun subnetHosts(info: NetworkInfo): List<String>? {
        val local = ipv4Value(info.ip) ?: return null
        if (info.prefix !in 1..30) return null
        val hostBits = 32 - info.prefix
        val hostMask = (1L shl hostBits) - 1
        val hostCount = hostMask - 1
        if (hostCount > 1022) return null
        val networkMask = 0xFFFFFFFFL xor hostMask
        val network = local and networkMask
        return (network + 1 until network + 1 + hostCount).map(::ipv4Address)
    }

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
        status.text = if (info == null) "Not connected to an accessible Wi-Fi network" else
            "Connected to Wi-Fi\nThis phone: ${info.ip}/${info.prefix}\nRouter: ${info.gateway ?: "not detected"}"
    }

    private fun reachable(ip: String): Boolean {
        // Open TCP port OR connection-refused (a TCP RST) indicates a responding IP.
        // Silent firewalls and phones with no exposed services may remain invisible.
        for (port in intArrayOf(80, 443, 22, 53, 8080, 8000, 8888, 554, 445, 139, 9100)) {
            try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 180) }
                return true
            } catch (e: ConnectException) {
                // Only an explicit refusal is positive evidence of a response.
                if (e.message?.contains("refused", ignoreCase = true) == true) return true
            } catch (_: Exception) { }
        }
        return false
    }

    private fun discoverSsdp(): Set<String> {
        // UPnP/SSDP discovery finds *some* TVs, printers, media players, etc.
        // It does not enumerate all Wi-Fi clients.
        val discovered = mutableSetOf<String>()
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("WifiGuardian-ssdp").apply { setReferenceCounted(false) }
        try {
            lock.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 350
                val payload = ("M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 1\r\n" +
                    "ST: ssdp:all\r\n\r\n").toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("239.255.255.250"), 1900))
                val until = System.currentTimeMillis() + 2500
                while (System.currentTimeMillis() < until) {
                    try {
                        val response = DatagramPacket(ByteArray(2048), 2048)
                        socket.receive(response)
                        response.address.hostAddress?.let { discovered.add(it) }
                    } catch (_: java.net.SocketTimeoutException) { }
                }
            }
        } catch (_: Exception) {
            // Discovery is best-effort. Local TCP scanning still works.
        } finally {
            if (lock.isHeld) lock.release()
        }
        return discovered
    }

    private fun readArpNeighbors(info: NetworkInfo): Map<String, String> {
        return try {
            java.io.File("/proc/net/arp").useLines { lines ->
                lines.drop(1).mapNotNull { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    if (columns.size < 6) return@mapNotNull null
                    val ip = columns[0]
                    val flags = columns[2].removePrefix("0x").toIntOrNull(16) ?: 0
                    val mac = columns[3]
                    if (isInSubnet(ip, info) && ip != info.ip && ip != info.gateway &&
                        (flags and 0x2) != 0 &&
                        mac != "00:00:00:00:00:00") ip to mac else null
                }.toMap()
            }
        } catch (e: java.io.IOException) {
            android.util.Log.w("WiFiGuardian", "Could not read the IPv4 neighbor cache", e)
            emptyMap()
        } catch (e: SecurityException) {
            android.util.Log.w("WiFiGuardian", "Access to the IPv4 neighbor cache was denied", e)
            emptyMap()
        }
    }

    private fun resolveHostnames(ips: List<String>): Map<String, String> {
        return try {
            probes.invokeAll(
                ips.map { ip ->
                    java.util.concurrent.Callable {
                        val hostname = try {
                            InetAddress.getByName(ip).canonicalHostName.trimEnd('.')
                        } catch (_: Exception) {
                            ""
                        }
                        ip to hostname.takeIf { it.isNotBlank() && !it.equals(ip, ignoreCase = true) }
                    }
                },
                2,
                TimeUnit.SECONDS
            ).mapNotNull { future ->
                try {
                    future.get()?.let { (ip, hostname) -> hostname?.let { ip to it } }
                } catch (_: Exception) {
                    null
                }
            }.toMap()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            android.util.Log.w("WiFiGuardian", "Hostname lookup was interrupted", e)
            emptyMap()
        }
    }

    private fun scan() {
        refreshNetworkInfo()
        val info = networkInfo() ?: run { toast("Connect to a Wi-Fi network first"); return }
        val hosts = subnetHosts(info)
        if (hosts == null) {
            status.text = "This network is too large for this scan (maximum 1,022 host addresses): ${info.ip}/${info.prefix}"
            return
        }
        scanButton.isEnabled = false
        scanButton.text = "Scanning devices..."
        progress.progress = 0
        progress.max = hosts.size
        progress.visibility = View.VISIBLE
        results.removeAllViews()
        summary.text = "Scanning ${info.ip}/${info.prefix} ..."
        bg.execute {
            val found = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            // SSDP replies provide an additional discovery signal for compatible devices.
            val ssdpHosts = discoverSsdp().filter { isInSubnet(it, info) }.toSet()
            ssdpHosts.forEach { found.add(it to "UPnP/SSDP responding") }
            val done = AtomicInteger(0)
            val jobs = hosts.map { host ->
                probes.submit {
                    val role = when (host) {
                        info.ip -> "This phone"
                        info.gateway -> "Gateway (router)"
                        else -> "TCP responding"
                    }
                    if (host == info.ip || host == info.gateway || reachable(host)) found.add(host to role)
                    val progress = done.incrementAndGet()
                    if (progress % 20 == 0 || progress == hosts.size) runOnUiThread {
                        summary.text = "Checking addresses... $progress / ${hosts.size}"
                        this.progress.progress = progress
                    }
                }
            }
            jobs.forEach { try { it.get(40, TimeUnit.SECONDS) } catch (_: Exception) { it.cancel(true) } }
            val arpNeighbors = readArpNeighbors(info)
            val knownIps = synchronized(found) { found.map { it.first }.toSet() }
            arpNeighbors.keys.filterNot { it in knownIps }.forEach {
                found.add(it to "LAN neighbor cache")
            }
            val sorted = synchronized(found) {
                found.distinctBy { it.first }
                    .sortedWith(compareBy { ipv4Value(it.first) ?: Long.MAX_VALUE })
            }
            val hostnames = resolveHostnames(sorted.map { it.first }.filter { it != info.ip })
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
                if (sorted.isEmpty()) {
                    results.addView(small("No devices were found in this scan. Some connected devices may not respond to discovery."))
                }
                sorted.forEach { (ip, role) ->
                    val row = card()
                    val roleLabel = when (role) {
                        "This phone" -> "This phone"
                        "Gateway (router)" -> "Wi-Fi router"
                        "UPnP/SSDP responding" -> "Smart / media device"
                        "LAN neighbor cache" -> "LAN neighbor"
                        else -> "Network device"
                    }
                    val identity = when {
                        ip == info.ip -> "${Build.MANUFACTURER} ${Build.MODEL} (this phone)".trim()
                        else -> hostnames[ip] ?: roleLabel
                    }
                    row.addView(label("$identity${if (ip in newIps) "  • NEW" else ""}", 16f, true))
                    row.addView(gap(4))
                    row.addView(small("IP: $ip  •  $roleLabel"))
                    arpNeighbors[ip]?.let { mac ->
                        row.addView(gap(4))
                        row.addView(small("MAC: $mac"))
                    }
                    trafficEntries.firstOrNull { it.ip == ip }?.let { t ->
                        row.addView(gap(5))
                        row.addView(small("Router report: ↓ ${formatBytes(t.rx)}  ↑ ${formatBytes(t.tx)}"))
                    }
                    row.addView(gap(8))
                    row.addView(secondaryButton("Copy IP address") {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("IP address", ip))
                        toast("Copied $ip")
                    })
                    results.addView(row)
                    results.addView(gap(9))
                }
                summary.text = "${sorted.size} devices found • ${newIps.size} new • ${missingIps.size} seen before but not found this time\nLast scan: $timestamp\nThis may not include every device connected to Wi-Fi."
                scanButton.isEnabled = true
                scanButton.text = "Scan again"
                progress.visibility = View.GONE
            }
        }
    }

    // Filter only changes the traffic displayed; it cannot discover or capture
    // packets from any device not reporting counters via the configured source.
    private fun configureDeviceFilter() {
        val input = EditText(this).apply {
            hint = "Device ID or IP (blank = all)"
            setSingleLine(true)
            setText(selectedDeviceFilter)
        }
        AlertDialog.Builder(this).setTitle("Choose traffic device")
            .setMessage("Enter the device ID or IPv4 address shown in the live feed. This does not intercept devices that have not opted in.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                selectedDeviceFilter = input.text.toString().trim().take(100)
                filterStatus.text = if (selectedDeviceFilter.isBlank())
                    "Showing all reported devices" else "Selected: $selectedDeviceFilter"
                liveList.removeAllViews()
                previousLive = emptyMap()
                previousLiveTimeMs = 0L
            }.show()
    }

    // LIVE TRAFFIC: the phone reads counters exposed by a router or a trusted collector.
    // Ordinary Wi-Fi access does not expose other clients' traffic.
    private fun configureLive() {
        val address = EditText(this).apply {
            setSingleLine(true)
            hint = "http://192.168.1.10:8088/traffic"
            setText(liveEndpoint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = EditText(this).apply {
            setSingleLine(true)
            hint = "Bearer token (optional, session only)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(liveSessionToken)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), 0)
            addView(small("Use an endpoint on your own LAN that returns per-device cumulative byte counters. HTTP is allowed for private LAN IPs; use HTTPS when available."))
            addView(address)
            addView(token)
        }
        AlertDialog.Builder(this).setTitle("Live traffic source")
            .setView(form).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val value = address.text.toString().trim()
                if (value.isNotEmpty() && !validSourceUrl(value)) {
                    toast("Use a private IPv4 LAN URL, e.g. http://192.168.1.10:8088/traffic")
                } else {
                    stopLive()
                    liveEndpoint = value
                    liveSessionToken = token.text.toString().trim()
                    prefs.edit().putString("live_endpoint", value).apply()
                    liveSummary.text = if (value.isBlank()) "Not configured • No live data" else "Configured • Tap Start live monitoring"
                    liveList.removeAllViews()
                }
            }.show()
    }

    // Limit traffic data sources to numeric private LAN IPv4 hosts. Prevent accidental
    // requests to public endpoints and avoid storing credentials in a URL.
    private fun validSourceUrl(value: String): Boolean {
        return try {
        val uri = Uri.parse(value)
        val host = uri.host ?: return false
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val privateHost = octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
        privateHost && (uri.scheme == "http" || uri.scheme == "https") &&
            java.net.URI(value).rawUserInfo == null && uri.fragment == null
        } catch (_: Exception) { false }
    }

    private fun toggleLive() {
        if (liveEnabled) { stopLive(); liveSummary.text = "Monitoring stopped"; return }
        if (!validSourceUrl(liveEndpoint)) { configureLive(); return }
        liveEnabled = true
        liveButton.text = "Stop live monitoring"
        liveSummary.text = "Connecting to traffic source..."
        previousLive = emptyMap()
        previousLiveTimeMs = 0L
        liveHandler.post(liveTick)
    }

    private fun stopLive() {
        liveEnabled = false
        liveHandler.removeCallbacks(liveTick)
        if (::liveButton.isInitialized) liveButton.text = "Start live monitoring"
    }

    private fun fetchLiveTraffic() {
        val endpoint = liveEndpoint
        val token = liveSessionToken
        bg.execute {
            try {
                // Wi-Fi sockets are bound to the Wi-Fi network even if a VPN is active.
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val wifiNet = cm.allNetworks.firstOrNull {
                    cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } ?: error("Connect to Wi-Fi first")
                val connection = wifiNet.openConnection(URL(endpoint)) as HttpURLConnection
                val body = try {
                    connection.connectTimeout = 3500
                    connection.readTimeout = 3500
                    connection.requestMethod = "GET"
                    if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                    require(connection.responseCode == 200) { "Data source returned HTTP ${connection.responseCode}" }
                    connection.inputStream.bufferedReader().use { reader -> reader.readText().take(300_000) }
                } finally { connection.disconnect() }
                val root = JSONObject(body)
                val entries = root.getJSONArray("devices")
                require(entries.length() <= 512) { "Too many devices (max 512)" }
                val devices = (0 until entries.length()).map { i ->
                    val d = entries.getJSONObject(i)
                    val id = d.optString("id").ifBlank { d.optString("mac") }.ifBlank { d.optString("ip") }
                    require(id.isNotBlank()) { "Device identifier missing" }
                    val rx = d.getLong("download_bytes")
                    val tx = d.getLong("upload_bytes")
                    require(rx >= 0 && tx >= 0) { "Traffic counters cannot be negative" }
                    LiveDevice(id, d.optString("ip"), d.optString("name", "Unnamed device"),
                        categoryOf(d.optString("type", "other")), rx, tx,
                        d.optString("source", "Router/collector"))
                }
                val measuredAt = System.currentTimeMillis()
                runOnUiThread { if (liveEnabled) renderLive(devices, measuredAt) }
            } catch (e: Exception) {
                runOnUiThread {
                    if (liveEnabled) {
                        liveSummary.text = "Live feed unavailable: ${e.message ?: "Unknown error"}"
                        liveList.removeAllViews()
                        previousLive = emptyMap()
                        previousLiveTimeMs = 0L
                    }
                }
            } finally {
                runOnUiThread { if (liveEnabled) liveHandler.postDelayed(liveTick, 3000L) }
            }
        }
    }

    private fun categoryOf(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
        "phone", "mobile", "smartphone", "android", "iphone" -> "Phones"
        "laptop", "desktop", "pc", "computer" -> "Computers"
        "tv", "smart_tv", "television", "streaming" -> "TV & Media"
        "camera", "iot", "sensor", "smart_home", "speaker" -> "Smart Home"
        "router", "gateway", "access_point" -> "Network Equipment"
        else -> "Other Devices"
    }

    private fun renderLive(devices: List<LiveDevice>, now: Long) {
        liveList.removeAllViews()
        val shownDevices = if (selectedDeviceFilter.isBlank()) devices else devices.filter {
            it.id.equals(selectedDeviceFilter, ignoreCase = true) || it.ip == selectedDeviceFilter
        }
        val seconds = if (previousLiveTimeMs == 0L) 0.0 else (now - previousLiveTimeMs) / 1000.0
        val totalDown = devices.sumOf { it.rx.toDouble() }.toLong()
        val totalUp = devices.sumOf { it.tx.toDouble() }.toLong()
        liveSummary.text = "LIVE • ${shownDevices.size} selected / ${devices.size} reported\n↓ ${formatBytes(shownDevices.sumOf { it.rx.toDouble() }.toLong())}  ↑ ${formatBytes(shownDevices.sumOf { it.tx.toDouble() }.toLong())} cumulative\nUpdates about every 3 seconds"
        if (shownDevices.isEmpty()) liveList.addView(small("No matching device reported. Check the ID/IP, proxy settings and data source."))
        val categories = listOf("Phones", "Computers", "TV & Media", "Smart Home", "Network Equipment", "Other Devices")
        categories.forEach { cat ->
            val group = shownDevices.filter { it.kind == cat }
            if (group.isNotEmpty()) {
                liveList.addView(label("$cat (${group.size})", 17f, true))
                liveList.addView(gap(9))
                group.forEach { dev ->
                    val prior = previousLive[dev.id]
                    val hasRate = prior != null && seconds > 0 && dev.rx >= prior.rx && dev.tx >= prior.tx
                    val downRate = if (hasRate) ((dev.rx - prior!!.rx) / seconds).toLong() else 0L
                    val upRate = if (hasRate) ((dev.tx - prior!!.tx) / seconds).toLong() else 0L
                    val item = card()
                    item.addView(label(dev.name.ifBlank { "Unnamed device" }, 16f, true))
                    item.addView(gap(4))
                    item.addView(small("Device ID: ${dev.id}  •  IP: ${dev.ip.ifBlank { "Unknown" }}"))
                    item.addView(gap(7))
                    item.addView(label(if (hasRate) "↓ ${formatBytes(downRate)}/s    ↑ ${formatBytes(upRate)}/s" else "Calculating live rates...", 15f, true))
                    item.addView(gap(5))
                    item.addView(small("Total ↓ ${formatBytes(dev.rx)}  ↑ ${formatBytes(dev.tx)}  •  ${dev.source}"))
                    liveList.addView(item)
                    liveList.addView(gap(9))
                }
            }
        }
        previousLive = devices.associateBy { it.id }
        previousLiveTimeMs = now
    }

    // Traffic reporting is explicitly imported from a router export. This app does not
    // sniff other devices or imply that ordinary Wi-Fi clients can inspect their traffic.
    private fun pickTrafficCsv() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/csv", "text/plain", "application/vnd.ms-excel"))
        }
        startActivityForResult(intent, trafficRequestCode)
    }

    @Deprecated("Legacy activity result API remains compatible with minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != trafficRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    buffer.write(chunk, 0, count)
                    require(buffer.size() <= 2_000_000) { "CSV is too large (maximum 2 MB)" }
                }
                buffer.toByteArray()
            } ?: error("Unable to open CSV")
            val csv = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            val parsed = parseTrafficCsv(csv)
            trafficEntries = parsed
            persistTraffic()
            renderTraffic()
            toast("Imported traffic for ${parsed.size} devices")
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("Unable to import traffic")
                .setMessage(e.message ?: "Invalid CSV format")
                .setPositiveButton("OK", null).show()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val item = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            when (line[i]) {
                '"' -> {
                    if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                        item.append('"'); i++
                    } else quoted = !quoted
                }
                ',' -> if (!quoted) { result.add(item.toString().trim()); item.setLength(0) }
                       else item.append(',')
                else -> item.append(line[i])
            }
            i++
        }
        require(!quoted) { "Unclosed quote in CSV" }
        result.add(item.toString().trim())
        return result
    }

    private fun parseTrafficCsv(csv: String): List<TrafficEntry> {
        val rows = csv.lineSequence().filter { it.isNotBlank() }.take(1002).toList()
        require(rows.size >= 2) { "CSV must have a header and at least one device" }
        val header = parseCsvLine(rows.first()).map { it.lowercase(Locale.ROOT) }
        val ipCol = header.indexOf("ip")
        val rxCol = header.indexOf("download_bytes")
        val txCol = header.indexOf("upload_bytes")
        require(ipCol >= 0 && rxCol >= 0 && txCol >= 0) {
            "Required columns: ip,download_bytes,upload_bytes"
        }
        val nameCol = header.indexOf("device_name")
        val dateCol = header.indexOf("observed_at")
        val needed = maxOf(ipCol, rxCol, txCol, nameCol, dateCol)
        val aggregate = linkedMapOf<String, TrafficEntry>()
        rows.drop(1).forEachIndexed { index, line ->
            val cells = parseCsvLine(line)
            require(cells.size > needed) { "Row ${index + 2} is missing columns" }
            val ip = cells[ipCol]
            require(isValidIpv4(ip)) { "Invalid IPv4 address at row ${index + 2}" }
            val rx = cells[rxCol].toLongOrNull()
            val tx = cells[txCol].toLongOrNull()
            require(rx != null && rx >= 0 && tx != null && tx >= 0) {
                "Invalid traffic byte count at row ${index + 2}"
            }
            val old = aggregate[ip]
            require(old == null || (Long.MAX_VALUE - old.rx >= rx && Long.MAX_VALUE - old.tx >= tx)) {
                "Traffic count overflow"
            }
            val name = if (nameCol >= 0) cells[nameCol].take(70) else ""
            val at = if (dateCol >= 0) cells[dateCol].take(70) else ""
            aggregate[ip] = TrafficEntry(ip, name.ifBlank { old?.name ?: "Network device" },
                (old?.rx ?: 0L) + rx, (old?.tx ?: 0L) + tx,
                at.ifBlank { old?.observedAt ?: "Not provided" })
        }
        require(aggregate.isNotEmpty()) { "CSV contains no device traffic" }
        return aggregate.values.sortedByDescending { it.rx + it.tx }
    }

    private fun isValidIpv4(ip: String): Boolean {
        val p = ip.split('.')
        return p.size == 4 && p.all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' } &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun persistTraffic() {
        val saved = JSONArray()
        trafficEntries.forEach {
            saved.put(JSONObject().put("ip", it.ip).put("name", it.name).put("rx", it.rx)
                .put("tx", it.tx).put("at", it.observedAt))
        }
        prefs.edit().putString("router_traffic", saved.toString()).apply()
    }

    private fun restoreTraffic() {
        try {
            val arr = JSONArray(prefs.getString("router_traffic", "[]"))
            trafficEntries = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TrafficEntry(obj.getString("ip"), obj.optString("name", "Network device"),
                    obj.getLong("rx"), obj.getLong("tx"), obj.optString("at"))
            }
        } catch (_: Exception) { trafficEntries = emptyList() }
        renderTraffic()
    }

    private fun renderTraffic() {
        trafficList.removeAllViews()
        if (trafficEntries.isEmpty()) {
            trafficSummary.text = "No router traffic report imported"
            trafficList.addView(small("Import a CSV exported by your router or an authorized network monitor."))
            return
        }
        val totalRx = trafficEntries.sumOf { it.rx.toDouble() }.toLong()
        val totalTx = trafficEntries.sumOf { it.tx.toDouble() }.toLong()
        trafficSummary.text = "${trafficEntries.size} devices • ↓ ${formatBytes(totalRx)} • ↑ ${formatBytes(totalTx)}"
        trafficEntries.forEach { entry ->
            val row = card()
            row.addView(label(entry.name.ifBlank { "Network device" }, 16f, true))
            row.addView(gap(4))
            row.addView(small("${entry.ip} • Router-reported data"))
            row.addView(gap(7))
            row.addView(label("↓ ${formatBytes(entry.rx)}    ↑ ${formatBytes(entry.tx)}", 15f, true))
            row.addView(gap(4))
            row.addView(small("Reported at: ${entry.observedAt}"))
            trafficList.addView(row)
            trafficList.addView(gap(9))
        }
    }

    private fun clearTraffic() {
        AlertDialog.Builder(this).setTitle("Clear imported traffic?")
            .setMessage("This removes the imported report from this phone. It does not affect your router.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                trafficEntries = emptyList(); prefs.edit().remove("router_traffic").apply(); renderTraffic()
            }.show()
    }

    private fun showTrafficHelp() {
        AlertDialog.Builder(this).setTitle("Router traffic CSV format")
            .setMessage("Required columns:\nip,download_bytes,upload_bytes\n\n" +
                "Optional columns:\ndevice_name,observed_at\n\n" +
                "Example:\nip,download_bytes,upload_bytes,device_name,observed_at\n" +
                "192.168.1.104,10485760,2097152,Living Room TV,2026-11-21 11:00\n\n" +
                "Counts must be nonnegative bytes for a known reporting interval. " +
                "Multiple rows with the same IP are added together. " +
                "This import does not measure or intercept live traffic.")
            .setPositiveButton("Got it", null).show()
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
        stopLive(); bg.shutdownNow(); probes.shutdownNow(); super.onDestroy()
    }
}
