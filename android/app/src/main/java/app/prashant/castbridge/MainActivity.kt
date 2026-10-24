package app.prashant.castbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.prashant.castbridge.ui.CastBridgeTheme
import app.prashant.castbridge.ui.CastScreen
import app.prashant.castbridge.ui.CastUiState
import app.prashant.castbridge.ui.Phase
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private val main = Handler(Looper.getMainLooper())

    private var tvs by mutableStateOf<List<TvInfo>>(emptyList())
    private var scanning by mutableStateOf(false)
    /** Show scan progress only for scans the user asked for (or before any TV is known). */
    private var scanVisible by mutableStateOf(false)
    private var onWifi by mutableStateOf(true)
    private var audio by mutableStateOf(true)
    /** Waiting for permissions / Android's capture prompt / the service to start. */
    private var starting by mutableStateOf(false)
    /** The user's pick: a TV ip, [MANUAL] for "open the TV app myself", or [AUTO] (first TV). */
    private var pick by mutableStateOf(AUTO)
    private var visible = false
    private val appVersion by lazy { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }

    /** The TV that "Start casting" will use, or null for none. */
    private val selectedIp: String?
        get() = when (pick) {
            MANUAL -> null
            AUTO -> tvs.firstOrNull()?.ip
            else -> pick.takeIf { ip -> tvs.any { it.ip == ip } } ?: tvs.firstOrNull()?.ip
        }

    // While the screen is showing and nothing is casting, look for TVs every few seconds.
    private val autoScan = object : Runnable {
        override fun run() {
            if (visible && !CastState.running) scan()
            main.postDelayed(this, AUTO_SCAN_MS)
        }
    }

    // A new or changed Wi-Fi network usually means different TVs: scan straight away.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scanSoon()
        override fun onLost(network: Network) = scanSoon()
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = scanSoon()
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) audio = false
        askForScreenCapture()
    }

    private val captureRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            starting = false
            return@registerForActivityResult
        }
        val tvIp = selectedIp
        startForegroundService(
            Intent(this, CastService::class.java)
                .putExtra(CastService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(CastService.EXTRA_DATA, data)
                .putExtra(CastService.EXTRA_AUDIO, audio)
                .putExtra(CastService.EXTRA_TV_IP, tvIp)
                .putExtra(CastService.EXTRA_TV_NAME, tvs.firstOrNull { it.ip == tvIp }?.name)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // The service reports back through CastState; leave "Starting…" once it has.
            LaunchedEffect(CastState.running, CastState.message) {
                if (CastState.running || CastState.message.isNotEmpty()) starting = false
            }
            val state = CastUiState(
                phase = when {
                    CastState.running -> Phase.Casting
                    starting -> Phase.Starting
                    else -> Phase.Idle
                },
                onWifi = onWifi,
                scanning = scanning && (scanVisible || tvs.isEmpty()),
                tvs = tvs,
                selectedIp = selectedIp,
                audio = audio,
                castingTo = CastState.tvName,
                viewers = CastState.viewers,
                message = CastState.message,
                version = appVersion,
            )
            CastBridgeTheme {
                CastScreen(
                    state = state,
                    onSelect = { ip -> pick = ip ?: MANUAL },
                    onRescan = { scan(userInitiated = true) },
                    onAudioChange = { audio = it },
                    onStart = ::startCasting,
                    onStop = { CastService.stop(this) },
                    onOpenWifiSettings = { startActivity(Intent(Settings.Panel.ACTION_WIFI)) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        scan()
        main.postDelayed(autoScan, AUTO_SCAN_MS)
        val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(wifi, networkCallback)
    }

    override fun onStop() {
        visible = false
        main.removeCallbacks(autoScan)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        super.onStop()
    }

    private fun scanSoon() {
        main.post { if (visible && !CastState.running) scan() }
    }

    private fun scan(userInitiated: Boolean = false) {
        onWifi = Net.wifiAddress(this) != null
        if (userInitiated) scanVisible = true
        if (scanning || !onWifi) return
        scanning = true
        thread {
            // Stable order, so the list doesn't reshuffle between scans.
            val found = SamsungTv(this).discover().sortedBy { it.ip }
            main.post {
                scanning = false
                scanVisible = false
                tvs = found
            }
        }
    }

    private fun startCasting() {
        starting = true
        val needed = buildList {
            if (audio) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) askForScreenCapture() else permissionRequest.launch(needed.toTypedArray())
    }

    private fun askForScreenCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        captureRequest.launch(mpm.createScreenCaptureIntent())
    }

    companion object {
        private const val AUTO_SCAN_MS = 5000L
        private const val AUTO = "auto"
        private const val MANUAL = "manual"
    }
}
