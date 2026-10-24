package app.prashant.castbridge

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlin.concurrent.thread

/**
 * Captures what other apps are playing (AudioPlaybackCapture, Android 10+) as raw
 * 48 kHz stereo 16-bit PCM. Sent uncompressed (~1.5 Mbps), which is fine on a LAN and
 * lets the TV play it with Web Audio at minimal latency. Apps that opt out stay silent.
 */
class AudioCapture(
    private val projection: MediaProjection,
    private val onChunk: (pcm: ByteArray, durationUs: Long) -> Unit,
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked before casting starts
    fun start() {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record.startRecording()

        running = true
        worker = thread(name = "audio-capture") {
            try {
                while (running) {
                    val pcm = ByteArray(CHUNK_BYTES)
                    var filled = 0
                    while (running && filled < pcm.size) {
                        val n = record.read(pcm, filled, pcm.size - filled)
                        if (n < 0) throw IllegalStateException("AudioRecord.read=$n")
                        filled += n
                    }
                    if (filled == pcm.size) onChunk(pcm, CHUNK_US)
                }
            } catch (e: Exception) {
                Log.w(TAG, "audio loop ended", e)
            } finally {
                record.stop()
                record.release()
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 48_000
        private const val FRAMES_PER_CHUNK = 960 // 20 ms
        private const val CHUNK_BYTES = FRAMES_PER_CHUNK * 2 * 2
        private const val CHUNK_US = FRAMES_PER_CHUNK * 1_000_000L / SAMPLE_RATE
    }
}
