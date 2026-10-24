package app.prashant.castbridge

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class TvInfo(val ip: String, val name: String)

/** Finds Samsung TVs on the LAN and opens the receiver page on them. */
class SamsungTv(private val context: Context) {

    private val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)

    /**
     * Finds Samsung TVs: SSDP first, then a direct probe of the local /24 for routers that
     * drop multicast. Blocking; call off the main thread.
     */
    fun discover(timeoutMs: Int = 3000): List<TvInfo> {
        // SSDP replies get lost now and then, so TVs seen before are also asked directly;
        // otherwise a TV would flicker in and out of the list between scans.
        val candidates = ssdpSearch(timeoutMs) + prefs.getStringSet(KNOWN_TVS, emptySet()).orEmpty()
        val found = probe(candidates.toList(), connectTimeoutMs = 1000).ifEmpty { scanSubnet() }
        if (found.isNotEmpty()) prefs.edit().putStringSet(KNOWN_TVS, found.map { it.ip }.toSet()).apply()
        return found
    }

    private fun ssdpSearch(timeoutMs: Int): Set<String> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("castbridge-ssdp").apply { setReferenceCounted(false); acquire() }
        val ips = linkedSetOf<String>()
        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                // Newer Samsung TVs (2021+) no longer answer the RemoteControlReceiver search,
                // so also ask for DIAL and media renderers; info() filters out non-Samsung hits.
                repeat(2) {
                    for (st in SEARCH_TARGETS) {
                        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\nST: $st\r\n\r\n").toByteArray()
                        socket.send(DatagramPacket(msg, msg.size, group, 1900))
                    }
                }
                val buf = ByteArray(2048)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        ips += packet.address.hostAddress ?: continue
                    } catch (e: SocketTimeoutException) {
                        // keep listening until the deadline
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP search failed", e)
        } finally {
            lock.release()
        }
        return ips
    }

    /** Tries port 8001 on every address of the phone's /24. */
    private fun scanSubnet(): List<TvInfo> {
        val own = Net.wifiIp(context) ?: return emptyList()
        val prefix = own.substringBeforeLast('.')
        return probe((1..254).map { "$prefix.$it" }.filter { it != own }, connectTimeoutMs = 400)
    }

    /** Asks each address in parallel whether it's a Samsung TV. */
    private fun probe(ips: List<String>, connectTimeoutMs: Int): List<TvInfo> {
        if (ips.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(32, ips.size))
        try {
            val probes = ips.map { ip ->
                pool.submit(Callable {
                    try {
                        Socket().use { it.connect(InetSocketAddress(ip, 8001), connectTimeoutMs) }
                        info(ip)
                    } catch (e: Exception) {
                        null
                    }
                })
            }
            return probes.mapNotNull { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Reads the TV's device info; null if [ip] isn't a Samsung TV. */
    fun info(ip: String): TvInfo? = try {
        val req = Request.Builder().url("http://$ip:8001/api/v2/").build()
        http.newCall(req).execute().use { res ->
            val device = JSONObject(res.body!!.string()).getJSONObject("device")
            TvInfo(ip, device.optString("name", "Samsung TV"))
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Starts the CastBridge TV app, which finds the phone by itself. If it isn't installed,
     * falls back to asking the TV browser to open [url] (ignored by some firmware, e.g. 2021
     * models). Reports a status line for the UI.
     */
    fun launchReceiver(ip: String, url: String, onResult: (message: String) -> Unit) {
        Thread {
            val launched = try {
                val req = Request.Builder()
                    .url("http://$ip:8001/api/v2/applications/$TV_APP_ID")
                    .post(ByteArray(0).toRequestBody())
                    .build()
                http.newCall(req).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                Log.w(TAG, "TV app launch failed", e)
                false
            }
            if (launched) {
                onResult("Casting to TV")
            } else {
                openUrl(ip, url)
                onResult("CastBridge TV app not installed. Asked the TV browser to open $url — if nothing appears, open it on the TV.")
            }
        }.start()
    }

    /** Closes the CastBridge TV app, returning the TV to what it showed before. Fire-and-forget. */
    fun closeReceiver(ip: String) {
        Thread {
            try {
                val req = Request.Builder().url("http://$ip:8001/api/v2/applications/$TV_APP_ID").delete().build()
                http.newCall(req).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "TV app close failed", e)
            }
        }.start()
    }

    /** Asks the TV browser to open [url]. The TV prompts to allow "CastBridge" the first time. */
    private fun openUrl(ip: String, url: String) {
        val name = Base64.encodeToString("CastBridge".toByteArray(), Base64.NO_WRAP)
        val token = prefs.getString("token_$ip", null)
        val wsUrl = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name" +
            (token?.let { "&token=$it" } ?: "")
        http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = JSONObject(text)
                if (msg.optString("event") != "ms.channel.connect") return
                msg.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }?.let {
                    prefs.edit().putString("token_$ip", it).apply()
                }
                // The browser's app id differs across model years; the TV ignores unknown ids.
                for (appId in BROWSER_IDS) {
                    val data = JSONObject().put("appId", appId).put("action_type", "NATIVE_LAUNCH").put("metaTag", url)
                    webSocket.send(emit("ed.apps.launch", data))
                }
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "remote API unreachable", t)
            }
        })
    }

    private fun emit(event: String, data: JSONObject?): String {
        val params = JSONObject().put("event", event).put("to", "host")
        if (data != null) params.put("data", data)
        return JSONObject().put("method", "ms.channel.emit").put("params", params).toString()
    }

    companion object {
        private const val TAG = "SamsungTv"
        private const val KNOWN_TVS = "known_tvs"
        /** Must match tizen/config.xml. */
        private const val TV_APP_ID = "CstBrdg001.CastBridge"
        private val BROWSER_IDS = listOf("3202010022079", "org.tizen.browser")
        private val SEARCH_TARGETS = listOf(
            "urn:samsung.com:device:RemoteControlReceiver:1",
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
        )

        // The TV's API uses a self-signed certificate. This client only talks to TVs on the LAN.
        private val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        private val http: OkHttpClient by lazy {
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
