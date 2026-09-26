package dev.pivisolutions.dictus.service

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Wraps the Android AudioRecord API to capture 16kHz mono Float32 audio.
 *
 * Audio samples accumulate in an in-memory buffer (ArrayList<Float>).
 * RMS energy is calculated per read chunk and stored in a rolling 30-entry
 * history for waveform visualization.
 *
 * Capture PCM_16BIT, Android's universally supported PCM format, then normalize
 * to Float32 for whisper.cpp. Availability of the float API does not guarantee
 * that every device's microphone path can initialize with that format.
 *
 * WHY 16kHz: Whisper models expect 16kHz mono audio. Capturing at this rate
 * avoids resampling.
 */
class AudioCaptureManager(
    private val audioManager: AudioManager? = null,
) {

    companion object {
        private const val SAMPLE_RATE = RecordingDurationPolicy.SAMPLE_RATE_HZ
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_ENERGY_HISTORY = 30

        internal fun checkedMinimumBufferSize(): Int =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT).also {
                check(it > 0) { "Microphone buffer configuration failed: $it" }
            }
    }

    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null
    private val samples = ArrayList<Float>()
    private val energyHistory = ArrayDeque<Float>(MAX_ENERGY_HISTORY).apply {
        // Pre-fill with zeros so the waveform renders all 30 bars immediately.
        // Without this, bars appear to "slide in" from the left as the history fills up.
        repeat(MAX_ENERGY_HISTORY) { addLast(0f) }
    }

    /** Callback invoked on each energy update with a normalized 0.0-1.0 value. */
    var onEnergyUpdate: ((Float) -> Unit)? = null
    var onCaptureError: ((RuntimeException) -> Unit)? = null
    /** PCM16 little-endian chunks for cloud streaming; callback must never block. */
    var onPcmChunk: ((ByteArray) -> Unit)? = null

    /**
     * Start capturing audio.
     *
     * Creates an AudioRecord instance and launches a coroutine that continuously
     * reads samples into the in-memory buffer. Energy updates are posted via
     * [onEnergyUpdate].
     *
     * @param scope CoroutineScope tied to the service lifecycle. The read loop
     *              runs on Dispatchers.Default (background thread pool) so it
     *              doesn't block the main thread.
     */
    fun start(scope: CoroutineScope) {
        val minBufferSize = checkedMinimumBufferSize()

        // Double the minimum buffer to reduce the risk of buffer underruns
        val bufferSize = minBufferSize * 2

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord failed to initialize (state=${rec.state})"
            }
            val routeResult = audioManager?.let { manager ->
                preferBuiltInMicrophone(AndroidAudioInputRouteBackend(manager, rec))
            } ?: AudioInputRouteResult.NoBuiltInMic
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter recording state"
            }
            Timber.d(
                "AudioRecord started: ${SAMPLE_RATE}Hz mono PCM16, buffer=$bufferSize, " +
                    "inputPreference=$routeResult, routedType=${rec.routedDevice?.type ?: "unknown"}",
            )
        } catch (failure: RuntimeException) {
            rec.release()
            throw failure
        }
        recorder = rec

        // Read loop on a background thread.
        // Each read produces a chunk of float samples. We accumulate them
        // and compute RMS energy for the waveform display.
        captureJob = scope.launch(Dispatchers.Default) {
            val pcmBuffer = ShortArray(SAMPLE_RATE / 5)
            val readBuffer = FloatArray(pcmBuffer.size)
            try {
                while (isActive) {
                    val read = rec.read(pcmBuffer, 0, pcmBuffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) {
                        if (!isActive || rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                        throw IllegalStateException("Microphone read failed: $read")
                    }
                    pcm16ToFloat(pcmBuffer, readBuffer, read)
                    synchronized(samples) {
                        for (i in 0 until read) samples.add(readBuffer[i])
                    }
                    onPcmChunk?.let { callback ->
                        val audio = ByteArray(read * 2)
                        for (index in 0 until read) {
                            val sample = pcmBuffer[index].toInt()
                            audio[index * 2] = sample.toByte()
                            audio[index * 2 + 1] = (sample shr 8).toByte()
                        }
                        callback(audio)
                    }
                    val rms = calculateRmsEnergy(readBuffer, read)
                    val normalized = normalizeEnergy(rms)
                    addEnergyToHistory(normalized)
                    onEnergyUpdate?.invoke(normalized)
                }
            } catch (failure: RuntimeException) {
                if (isActive) onCaptureError?.invoke(failure)
            }
        }
    }

    /**
     * Stop capturing and return the collected audio samples.
     *
     * @return FloatArray of all captured samples at 16kHz mono.
     */
    fun stop(): FloatArray {
        val job = captureJob
        val activeRecorder = recorder
        stopCaptureAndAwait(job) { stopRecorderSafely(activeRecorder) }
        captureJob = null
        activeRecorder?.release()
        recorder = null
        Timber.d("AudioRecord stopped, captured ${samples.size} samples")
        val result: FloatArray
        synchronized(samples) {
            result = samples.toFloatArray()
            samples.clear()
        }
        resetEnergyHistory()
        return result
    }

    /**
     * Cancel capturing and discard all audio data.
     */
    fun cancel() {
        val job = captureJob
        val activeRecorder = recorder
        job?.cancel()
        stopCaptureAndAwait(job) { stopRecorderSafely(activeRecorder) }
        captureJob = null
        activeRecorder?.release()
        recorder = null
        synchronized(samples) {
            samples.clear()
        }
        resetEnergyHistory()
        Timber.d("AudioRecord cancelled, samples discarded")
    }

    /**
     * Calculate the root-mean-square energy of a buffer of audio samples.
     *
     * RMS = sqrt( sum(sample^2) / count )
     *
     * This gives a single energy value representing the "loudness" of the chunk.
     * Public for testability.
     *
     * @param buffer Array of float samples (typically -1.0 to 1.0).
     * @param count Number of valid samples in the buffer (may be less than buffer.size).
     * @return RMS energy value. Returns 0f if count is 0.
     */
    fun calculateRmsEnergy(buffer: FloatArray, count: Int): Float {
        if (count == 0) return 0f
        var sum = 0f
        for (i in 0 until count) {
            sum += buffer[i] * buffer[i]
        }
        return sqrt(sum / count)
    }

    /**
     * Normalize a raw RMS energy value to the 0.0-1.0 range for display.
     *
     * Uses a power curve (sqrt) for better perceptual mapping: quiet speech
     * still produces visible bar movement, while loud speech reaches full height.
     * The 20x multiplier maps typical speech RMS (0.005-0.15) to ~0.3-1.0
     * after the sqrt curve, giving responsive visual feedback.
     *
     * @param rms Raw RMS energy value.
     * @return Normalized value in [0.0, 1.0].
     */
    fun normalizeEnergy(rms: Float): Float {
        val amplified = (rms * 20f).coerceIn(0f, 1f)
        return sqrt(amplified) // sqrt curve: boosts quiet sounds, compresses loud
    }

    /**
     * Add a normalized energy value to the rolling history.
     *
     * The history maintains at most [MAX_ENERGY_HISTORY] (30) entries.
     * When full, the oldest entry is dropped. This provides the data
     * for the 30-bar waveform visualization.
     */
    fun addEnergyToHistory(energy: Float) {
        synchronized(energyHistory) {
            energyHistory.addLast(energy)
            if (energyHistory.size > MAX_ENERGY_HISTORY) {
                energyHistory.removeFirst()
            }
        }
    }

    /**
     * Get a snapshot of the current energy history for waveform display.
     *
     * @return Immutable list of up to 30 normalized energy values.
     */
    fun getEnergyHistory(): List<Float> = synchronized(energyHistory) {
        energyHistory.toList()
    }

    /**
     * Reset energy history to 30 zero entries.
     * Ensures the next recording starts with a full-width flat waveform
     * instead of bars sliding in from the left.
     */
    private fun resetEnergyHistory() {
        synchronized(energyHistory) {
            energyHistory.clear()
            repeat(MAX_ENERGY_HISTORY) { energyHistory.addLast(0f) }
        }
    }

    /**
     * Stop the recorder before waiting for the capture loop. AudioRecord.stop()
     * releases a blocking read, allowing its final chunk to be appended before
     * the caller snapshots [samples]. The controller API is synchronous, so the
     * short join is intentionally bridged here rather than exposing a racy result.
     */
    internal fun stopCaptureAndAwait(job: Job?, stopRecorder: () -> Unit) {
        stopRecorder()
        if (job != null) {
            runBlocking { job.join() }
        }
    }

    private fun stopRecorderSafely(activeRecorder: AudioRecord?) {
        try {
            if (activeRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                activeRecorder.stop()
            }
        } catch (failure: IllegalStateException) {
            Timber.w(failure, "Microphone was already unavailable during cleanup")
        }
    }

    internal fun pcm16ToFloat(input: ShortArray, output: FloatArray, count: Int) {
        for (index in 0 until count) output[index] = input[index] / 32768f
    }
}
