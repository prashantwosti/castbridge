package app.prashant.castbridge

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlin.concurrent.thread

/** Cast session state, read by the UI as Compose state. Written on the main thread only. */
object CastState {
    var running by mutableStateOf(false)
        private set
    /** Name of the TV being cast to, if one was picked. */
    var tvName by mutableStateOf<String?>(null)
        private set
    /** Receivers (TVs) currently connected to the stream. */
    var viewers by mutableIntStateOf(0)
        private set
    /** Latest status or error line; empty when there's nothing to say. */
    var message by mutableStateOf("")
        private set

    internal fun update(running: Boolean = this.running, tvName: String? = this.tvName, message: String = this.message) {
        this.running = running
        this.tvName = tvName
        this.message = message
        if (!running) viewers = 0
    }

    internal fun setViewers(count: Int) { viewers = count }
}

class CastService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var server: StreamServer? = null
    private var screen: ScreenEncoder? = null
    private var audio: AudioCapture? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var tvIp: String? = null
    private var savedMediaVolume: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode != Activity.RESULT_OK || data == null || projection != null) return START_NOT_STICKY

        // Must be foreground before obtaining the projection (Android 14+).
        startForeground(
            NOTIFICATION_ID, buildNotification(intent.getStringExtra(EXTRA_TV_NAME)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        try {
            start(
                resultCode, data, intent.getBooleanExtra(EXTRA_AUDIO, true),
                intent.getStringExtra(EXTRA_TV_IP), intent.getStringExtra(EXTRA_TV_NAME),
            )
        } catch (e: Exception) {
            Log.e(TAG, "failed to start", e)
            CastState.update(running = false, message = "Failed: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(resultCode: Int, data: Intent, withAudio: Boolean, tvIp: String?, tvName: String?) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(resultCode, data) ?: error("screen capture not granted")
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        projection = proj

        val srv = StreamServer(
            assets, PORT,
            onClientJoined = { screen?.requestKeyframe() },
            onClientsChanged = { addresses -> main.post { onReceiversChanged(addresses) } },
        )
        srv.configMessage = """{"type":"config","audio":$withAudio}"""
        srv.start()
        server = srv

        screen = ScreenEncoder(proj, resources.displayMetrics.densityDpi) { frame, durationUs, key ->
            srv.broadcast(StreamServer.KIND_VIDEO, durationUs, frame, key)
        }.also { it.start() }
        if (withAudio) {
            audio = AudioCapture(proj) { pcm, durationUs ->
                srv.broadcast(StreamServer.KIND_AUDIO, durationUs, pcm, false)
            }.also { it.start() }
            // Sound should come from the TV only. Playback capture taps the mix before the
            // media volume is applied, so silencing the speaker doesn't silence the TV.
            val am = getSystemService(AudioManager::class.java)
            savedMediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }

        @Suppress("DEPRECATION")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CastBridge:cast").apply { acquire() }
        // Wi-Fi power saving batches packets (60-110 ms ping spikes measured on a Pixel 8 Pro).
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CastBridge:cast")
            .apply { setReferenceCounted(false); acquire() }

        val url = "http://${Net.wifiIp(this) ?: "phone-ip"}:$PORT/"
        CastState.update(running = true, tvName = tvName, message = "")
        this.tvIp = tvIp
        if (tvIp != null) {
            CastState.update(message = "Opening CastBridge on the TV…")
            SamsungTv(this).launchReceiver(tvIp, url) { msg ->
                main.post { CastState.update(message = msg) }
            }
        }
    }

    override fun onDestroy() {
        audio?.stop()
        savedMediaVolume?.let {
            getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
        }
        screen?.stop()
        server?.stop()
        tvIp?.let { SamsungTv(this).closeReceiver(it) }
        projection?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        audio = null; screen = null; server = null; projection = null
        CastState.update(running = false, tvName = null, message = "")
        super.onDestroy()
    }

    /**
     * Keeps the viewer count current and, when no TV was picked up front ("I'll open CastBridge
     * on the TV"), names the TV that connected by asking it for its device info.
     */
    private fun onReceiversChanged(addresses: List<String>) {
        CastState.setViewers(addresses.size)
        val ip = addresses.firstOrNull() ?: return
        if (CastState.tvName != null) return
        thread {
            val name = SamsungTv(this).info(ip)?.name ?: return@thread
            main.post {
                if (!CastState.running || CastState.tvName != null) return@post
                CastState.update(tvName = name)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(name))
            }
        }
    }

    private fun buildNotification(tvName: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(tvName?.let { "Casting to $it" } ?: "Casting to your TV")
            .setContentText("Tap to open CastBridge")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 1
        const val PORT = 8080
        const val ACTION_STOP = "app.prashant.castbridge.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_AUDIO = "audio"
        const val EXTRA_TV_IP = "tvIp"
        const val EXTRA_TV_NAME = "tvName"

        fun stop(context: Context) {
            context.startService(Intent(context, CastService::class.java).setAction(ACTION_STOP))
        }
    }
}
