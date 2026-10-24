package app.prashant.castbridge

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import android.util.Size
import kotlin.concurrent.thread

/**
 * Mirrors the screen into a VirtualDisplay backed by an H.264 encoder and emits
 * Annex-B access units (SPS/PPS prepended to every keyframe).
 */
class ScreenEncoder(
    private val projection: MediaProjection,
    private val densityDpi: Int,
    private val onFrame: (data: ByteArray, durationUs: Long, isKeyframe: Boolean) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    var size: Size = Size(0, 0)
        private set

    /**
     * Starts encoding onto a fixed landscape canvas. Android letterboxes the mirrored screen
     * into it in either orientation, so rotation needs no encoder restart.
     */
    @Synchronized
    fun start() {
        stopCodec()
        size = canvasSize()
        val format = MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(size))
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            // 30 fps halves the work for the TV's decoder and the Wi-Fi hop vs. the phone's 60+ Hz.
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, FPS.toFloat())
            // Keyframes are big bursts on Wi-Fi; new receivers get the cached GOP or an
            // on-demand keyframe instead, so they can be rare.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
            // Keep frames flowing on a static screen so a newly joined TV gets a picture.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        }
        val c = try {
            MediaCodec.createEncoderByType(MIME).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Exception) {
            // Some encoders reject the High profile hint; fall back to defaults.
            Log.w(TAG, "configure failed, retrying without profile", e)
            format.removeKey(MediaFormat.KEY_PROFILE)
            format.removeKey(MediaFormat.KEY_LEVEL)
            MediaCodec.createEncoderByType(MIME).also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        }
        val surface = c.createInputSurface()
        c.start()
        codec = c

        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection.createVirtualDisplay(
                "CastBridge", size.width, size.height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
            )
        } else {
            vd.resize(size.width, size.height, densityDpi)
            vd.surface = surface
        }

        running = true
        drainThread = thread(name = "video-drain") { drain(c) }
        Log.i(TAG, "encoding ${size.width}x${size.height}")
    }

    fun requestKeyframe() {
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: IllegalStateException) {
            // codec is being restarted
        }
    }

    @Synchronized
    fun stop() {
        stopCodec()
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun stopCodec() {
        running = false
        drainThread?.join(500)
        drainThread = null
        codec?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        codec = null
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var csd = ByteArray(0)
        var lastPtsUs = -1L
        while (running) {
            val index = try {
                c.dequeueOutputBuffer(info, 50_000)
            } catch (e: IllegalStateException) {
                break
            }
            if (index < 0) continue
            val buf = c.getOutputBuffer(index)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    csd = bytes
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val duration = if (lastPtsUs < 0) FRAME_US else (info.presentationTimeUs - lastPtsUs).coerceIn(1_000, 5_000_000)
                    lastPtsUs = info.presentationTimeUs
                    onFrame(if (key) csd + bytes else bytes, duration, key)
                }
            }
            c.releaseOutputBuffer(index, false)
        }
    }

    companion object {
        private const val TAG = "ScreenEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val FPS = 30
        private const val FRAME_US = 1_000_000L / FPS

        private fun bitrateFor(s: Size): Int =
            (s.width.toLong() * s.height * 3).coerceIn(2_000_000, 6_000_000).toInt()

        /**
         * Samsung TV decoders (seen on a 2021 Q60A) reject video taller than 1080 px, so a
         * portrait-shaped stream never plays. Use 1080p landscape, or 720p if unsupported.
         */
        fun canvasSize(): Size {
            val caps = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } }
                ?.getCapabilitiesForType(MIME)?.videoCapabilities
            return if (caps == null || caps.isSizeSupported(1920, 1080)) Size(1920, 1080) else Size(1280, 720)
        }
    }
}
