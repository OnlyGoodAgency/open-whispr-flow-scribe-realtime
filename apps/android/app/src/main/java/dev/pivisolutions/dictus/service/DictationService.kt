package dev.pivisolutions.dictus.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dev.pivisolutions.dictus.R
import dev.pivisolutions.dictus.BuildConfig
import dev.pivisolutions.dictus.audio.DictationSoundPlayer
import dev.pivisolutions.dictus.core.preferences.PreferenceKeys
import dev.pivisolutions.dictus.core.logging.PrivacySafeLog
import dev.pivisolutions.dictus.core.service.DictationController
import dev.pivisolutions.dictus.core.service.DictationState
import dev.pivisolutions.dictus.core.service.SttEngineState
import dev.pivisolutions.dictus.core.service.TranscriptionRetention
import dev.pivisolutions.dictus.core.stt.SttProvider
import dev.pivisolutions.dictus.model.ModelCatalog
import dev.pivisolutions.dictus.history.TranscriptionHistoryMetadata
import dev.pivisolutions.dictus.history.TranscriptionHistoryWriter
import dev.pivisolutions.dictus.history.SupabaseHistorySyncClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.pivisolutions.dictus.asr.ParakeetProvider
import dev.pivisolutions.dictus.core.whisper.TextPostProcessor
import dev.pivisolutions.dictus.model.AiProvider
import dev.pivisolutions.dictus.model.ModelManager
import timber.log.Timber

/**
 * Foreground service that manages audio recording for voice dictation.
 *
 * WHY a foreground service: Android kills background audio capture aggressively.
 * A foreground service with microphone type keeps the process alive and shows a
 * notification so the user knows recording is active. This is mandatory since
 * Android 14 (API 34) for microphone access from a service.
 *
 * HOW the IME uses this: DictusImeService binds to DictationService via
 * [LocalBinder] (same-process, zero overhead). It observes [state] with
 * collectAsState() to switch between keyboard and recording UI, and calls
 * [startRecording]/[stopRecording]/[cancelRecording] via the binder reference.
 *
 * WHY LocalBinder (not AIDL): The IME and this service run in the same process.
 * Local binding gives direct object access without IPC serialization overhead.
 */
/**
 * Hilt entry point for DictationService.
 *
 * WHY EntryPointAccessors (not @AndroidEntryPoint): DictationService uses a
 * LocalBinder pattern — the IME holds a direct reference to the service instance.
 * @AndroidEntryPoint wraps the service class via code generation and can interfere
 * with the LocalBinder pattern. EntryPointAccessors gives us Hilt-managed
 * singletons without changing the service's class hierarchy.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DictationServiceEntryPoint {
    fun dataStore(): DataStore<Preferences>
    fun transcriptionHistoryWriter(): TranscriptionHistoryWriter
    fun supabaseHistorySyncClient(): SupabaseHistorySyncClient
}

class DictationService : Service(), DictationController {

    companion object {
        const val CHANNEL_ID = "dictus_recording"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "dev.pivisolutions.dictus.action.START"
        const val ACTION_STOP = "dev.pivisolutions.dictus.action.STOP"
        private const val TRANSCRIPTION_TIMEOUT_MS = 120_000L
        private const val ENGINE_IDLE_TIMEOUT_MS = 10 * 60 * 1_000L
        private const val START_CUE_ECHO_SETTLE_MS = 120L
    }

    /**
     * DataStore accessed via EntryPoint so that DictationService can read
     * preferences without being an @AndroidEntryPoint component itself.
     */
    private val dataStore: DataStore<Preferences> by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            DictationServiceEntryPoint::class.java,
        ).dataStore()
    }

    private val transcriptionHistoryWriter: TranscriptionHistoryWriter by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            DictationServiceEntryPoint::class.java,
        ).transcriptionHistoryWriter()
    }

    private val providerSlot by lazy {
        SttProviderSlot(serviceScope, ENGINE_IDLE_TIMEOUT_MS)
    }
    private val modelManager by lazy { ModelManager(applicationContext) }
    private val modelDownloader by lazy { ModelDownloader(modelManager) }
    private val cloudSttClient by lazy {
        val sessions = EntryPointAccessors.fromApplication(
            applicationContext,
            DictationServiceEntryPoint::class.java,
        ).supabaseHistorySyncClient()
        CloudSttClient(BuildConfig.CLOUD_GATEWAY_URL, sessions::accessToken)
    }
    private val realtimeSttClient by lazy {
        val sessions = EntryPointAccessors.fromApplication(
            applicationContext, DictationServiceEntryPoint::class.java,
        ).supabaseHistorySyncClient()
        RealtimeSttClient(BuildConfig.CLOUD_GATEWAY_URL, sessions::accessToken)
    }
    private var realtimeSession: RealtimeSttClient.Session? = null
    private var cleanupContext = ""

    override fun setCleanupContext(text: String) {
        cleanupContext = text.take(1000)
    }

    /**
     * Local binder for same-process binding.
     *
     * The IME gets a direct reference to DictationService through this binder,
     * allowing it to call methods and observe StateFlow without any IPC overhead.
     */
    inner class LocalBinder : Binder() {
        fun getService(): DictationService = this@DictationService
    }

    private val binder = LocalBinder()

    // Coroutine scope tied to service lifecycle.
    // SupervisorJob ensures one child failure doesn't cancel others.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var audioCaptureManager: AudioCaptureManager? = null
    private var timerJob: Job? = null
    private var prewarmJob: Job? = null
    private var captureStartJob: Job? = null
    private var elapsedMs: Long = 0L

    // Sound feedback for recording lifecycle events.
    // Initialized in onCreate(); conditionally played based on SOUND_ENABLED preference.
    // Sound names and volume are reactively observed from DataStore so changes in
    // SoundSettingsScreen take effect without a service restart.
    private lateinit var soundPlayer: DictationSoundPlayer
    private var soundEnabled: Boolean = false
    private var soundVolume: Float = 0.5f

    // State machine exposed to the IME via the binder.
    // MutableStateFlow is thread-safe; updates from any coroutine are fine.
    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)

    /** Observable state for the IME to collect. */
    override val state: StateFlow<DictationState> = _state.asStateFlow()

    /** Native engine readiness for IME/UI loading gates. */
    override val engineState: StateFlow<SttEngineState>
        get() = providerSlot.state

    override fun onBind(intent: Intent): IBinder {
        prewarmEngine()
        return binder
    }

    override fun prewarmEngine() {
        if (prewarmJob?.isActive == true) return
        prewarmJob = serviceScope.launch(Dispatchers.Default) { prewarmActiveModel() }
    }

    override fun onCreate() {
        super.onCreate()
        soundPlayer = DictationSoundPlayer(applicationContext)

        // Load sounds with user-selected names from DataStore on first launch.
        // Subsequent preference changes are handled by the reactive observers below.
        serviceScope.launch {
            val prefs = dataStore.data.first()
            val startSound = prefs[PreferenceKeys.RECORD_START_SOUND] ?: "electronic_01f"
            val stopSound = prefs[PreferenceKeys.RECORD_STOP_SOUND] ?: "electronic_02b"
            val cancelSound = prefs[PreferenceKeys.RECORD_CANCEL_SOUND] ?: "electronic_03c"
            soundPlayer.loadSounds(startSound, stopSound, cancelSound)

            soundPlayer.volume = prefs[PreferenceKeys.SOUND_VOLUME] ?: 0.5f
        }

        // Reactively observe the SOUND_ENABLED preference so changes take effect
        // without a service restart. Runs on the service scope (Main dispatcher).
        serviceScope.launch {
            dataStore.data
                .map { it[PreferenceKeys.SOUND_ENABLED] ?: false }
                .collect { soundEnabled = it }
        }

        // Reactively observe volume changes.
        serviceScope.launch {
            dataStore.data
                .map { it[PreferenceKeys.SOUND_VOLUME] ?: 0.5f }
                .collect { volume ->
                    soundVolume = volume
                    soundPlayer.volume = volume
                }
        }

        // Reactively observe sound name changes and reload the affected slot.
        serviceScope.launch {
            dataStore.data
                .map { it[PreferenceKeys.RECORD_START_SOUND] ?: "electronic_01f" }
                .collect { soundPlayer.reloadSound("start", it) }
        }
        serviceScope.launch {
            dataStore.data
                .map { it[PreferenceKeys.RECORD_STOP_SOUND] ?: "electronic_02b" }
                .collect { soundPlayer.reloadSound("stop", it) }
        }
        serviceScope.launch {
            dataStore.data
                .map { it[PreferenceKeys.RECORD_CANCEL_SOUND] ?: "electronic_03c" }
                .collect { soundPlayer.reloadSound("cancel", it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (_state.value is DictationState.Recording) return START_NOT_STICKY
                try {
                    requireMicrophonePermission()
                    // Promote before initializing AudioRecord to meet Android's deadline.
                    createNotificationChannel()
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    )
                    startAudioCapture()
                    Timber.d("DictationService started foreground with ACTION_START")
                } catch (failure: RuntimeException) {
                    handleRecordingFailure(failure)
                }
            }
            ACTION_STOP -> {
                stopRecordingInternal(discard = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Timber.d("DictationService stopped via ACTION_STOP")
            }
        }
        // START_NOT_STICKY: don't restart automatically if the system kills us.
        // Recording state is transient; the user will re-tap mic if needed.
        return START_NOT_STICKY
    }

    /**
     * Start recording via foreground service promotion.
     *
     * Sends ACTION_START to self, which triggers onStartCommand to call
     * startForeground() (required for microphone access) then startAudioCapture().
     */
    override fun startRecording() {
        if (_state.value != DictationState.Idle) return
        val intent = Intent(this, DictationService::class.java).apply {
            action = ACTION_START
        }
        try {
            requireMicrophonePermission()
            startForegroundService(intent)
        } catch (failure: RuntimeException) {
            handleRecordingFailure(failure)
        }
    }

    private fun requireMicrophonePermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is not granted")
        }
    }

    private fun handleRecordingFailure(failure: RuntimeException) {
        Timber.e(failure, "Unable to start or continue microphone recording")
        stopRecordingInternal(discard = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        val message = if (failure is SecurityException) {
            R.string.recording_permission_error
        } else {
            R.string.recording_start_error
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Stop recording and return the captured audio buffer.
     *
     * The audio data is returned as a FloatArray of 16kHz mono samples,
     * ready to be passed to whisper.cpp for transcription (Phase 3).
     *
     * @return FloatArray of captured audio samples, or empty array if not recording.
     */
    override fun stopRecording(): FloatArray {
        cleanupContext = ""
        realtimeSession?.cancel()
        realtimeSession = null
        captureStartJob?.cancel()
        captureStartJob = null
        val samples = audioCaptureManager?.stop() ?: FloatArray(0)
        timerJob?.cancel()
        timerJob = null
        elapsedMs = 0L
        if (soundEnabled) soundPlayer.playStop()
        _state.value = DictationState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Timber.d("Recording stopped, ${samples.size} samples captured")
        return samples
    }

    /**
     * Cancel recording and discard all audio data.
     *
     * Used when the user taps the X button during recording.
     * Returns to idle state without producing any audio output.
     */
    override fun cancelRecording() {
        cleanupContext = ""
        if (soundEnabled) soundPlayer.playCancel()
        stopRecordingInternal(discard = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Timber.d("Recording cancelled, samples discarded")
    }

    /**
     * Stop recording, transcribe audio, and return processed text.
     *
     * Full pipeline: stop recording -> ensure model downloaded -> init engine ->
     * transcribe with 30s timeout -> post-process -> return text.
     *
     * WHY 30s timeout: On Pixel 4 with tiny model, transcription takes 2-5s for
     * typical dictation (5-30s audio). 30s covers worst-case long recordings.
     * A stuck JNI call should not block the UI indefinitely.
     */
    override suspend fun confirmAndTranscribe(retention: TranscriptionRetention): String? {
        if (_state.value !is DictationState.Recording) return null
        val liveSession = realtimeSession
        realtimeSession = null
        val recordingContext = cleanupContext
        cleanupContext = ""
        // 1. Stop recording and get audio samples
        captureStartJob?.cancel()
        captureStartJob = null
        val samples = audioCaptureManager?.stop() ?: FloatArray(0)
        timerJob?.cancel()
        timerJob = null
        elapsedMs = 0L
        audioCaptureManager = null

        if (samples.isEmpty()) {
            liveSession?.cancel()
            Timber.w("confirmAndTranscribe: no audio samples captured")
            _state.value = DictationState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return null
        }

        if (!RecordingDurationPolicy.canTranscribe(samples.size)) {
            liveSession?.cancel()
            Timber.w(
                "confirmAndTranscribe: clip too short (%d samples, minimum=%d)",
                samples.size,
                RecordingDurationPolicy.MINIMUM_SAMPLE_COUNT,
            )
            _state.value = DictationState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return null
        }

        // Play stop cue when audio capture ends and transcription begins.
        if (soundEnabled) soundPlayer.playStop()

        Timber.d("confirmAndTranscribe: %d samples captured, transitioning to Transcribing", samples.size)
        _state.value = DictationState.Transcribing

        return try {
            // 2. Read user preferences at transcription time so changes take effect
            //    without needing a service restart.
            val prefs = dataStore.data.first()
            val cleanupOptions = DictationCleanupOptions.from(prefs, recordingContext).toString()
            val activeModelKey = prefs[PreferenceKeys.ACTIVE_MODEL] ?: ModelCatalog.DEFAULT_KEY
            val languagePref = prefs[PreferenceKeys.TRANSCRIPTION_LANGUAGE] ?: "auto"
            // "auto" maps to null for whisper.cpp which triggers its own language detection.
            val whisperLanguage = if (languagePref == "auto") null else languagePref

            val remoteEnabled = liveSession != null || (prefs[PreferenceKeys.REMOTE_STT_ENABLED] ?: false)
            val fallbackLocal = prefs[PreferenceKeys.REMOTE_STT_FALLBACK_LOCAL] ?: true

            suspend fun transcribeLocally(): String? {
                val modelPath = modelDownloader.ensureModelAvailable(activeModelKey) ?: return null
                return transcribeWithProvider(
                    modelKey = activeModelKey,
                    modelPath = modelPath,
                    samples = samples,
                    language = whisperLanguage ?: "fr",
                )
            }

            var providerName = ModelCatalog.findByKey(activeModelKey)?.provider?.name ?: "UNKNOWN"
            val rawText = if (remoteEnabled) {
                try {
                    if (liveSession != null) {
                        try {
                            liveSession.finish().also { providerName = "REALTIME" }
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            Timber.tag("RealtimeDictation").w("using_batch_fallback")
                            Toast.makeText(this, R.string.realtime_using_batch_fallback, Toast.LENGTH_LONG).show()
                            cloudSttClient.transcribe(samples, whisperLanguage, cleanupOptions).also { providerName = "REMOTE" }
                        }
                    } else {
                        cloudSttClient.transcribe(samples, whisperLanguage, cleanupOptions).also { providerName = "REMOTE" }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (remoteFailure: Exception) {
                    if (fallbackLocal) {
                        Timber.tag("CloudDictation").w("using_local_fallback")
                        Toast.makeText(this, R.string.cloud_using_local_fallback, Toast.LENGTH_LONG).show()
                        transcribeLocally()
                    } else {
                        showCloudFailure(remoteFailure)
                        throw remoteFailure
                    }
                }
            } else {
                transcribeLocally()
            }

            if (rawText == null) {
                Timber.e("Provider initialization failed or transcription timed out")
                _state.value = DictationState.Idle
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return null
            }

            // 6. Post-process (trim + punctuation)
            val processedText = if (providerName == "REALTIME" || providerName == "REMOTE") {
                TextPostProcessor.processCloud(rawText)
            } else TextPostProcessor.process(rawText)
            Timber.d(PrivacySafeLog.transcriptionProcessed(rawText, processedText))

            if (processedText.isNotEmpty()) {
                val model = ModelCatalog.findByKey(activeModelKey)
                transcriptionHistoryWriter.persist(
                    retention = retention,
                    text = processedText,
                    metadata = TranscriptionHistoryMetadata(
                        requestedLanguage = languagePref,
                        durationMillis = samples.size * 1_000L / RecordingDurationPolicy.SAMPLE_RATE_HZ,
                        modelKey = when (providerName) {
                            "REALTIME" -> "scribe_v2_realtime"
                            "REMOTE" -> "server-managed"
                            else -> activeModelKey
                        },
                        provider = providerName,
                    ),
                )
            }

            _state.value = DictationState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            if (processedText.isEmpty()) null else processedText
        } catch (cancellation: CancellationException) {
            _state.value = DictationState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            throw cancellation
        } catch (failure: LinkageError) {
            Timber.e(failure, "Native transcription engine could not be loaded")
            _state.value = DictationState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            null
        } catch (e: Exception) {
            Timber.e("Transcription failed (%s)", e::class.java.simpleName)
            _state.value = DictationState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            null
        } finally {
            liveSession?.cancel()
        }
    }

    private fun showCloudFailure(failure: Exception) {
        val message = when (failure) {
            is CloudAuthenticationException -> R.string.cloud_auth_failed
            is RemoteSttException -> when (failure.statusCode) {
                401, 403 -> R.string.cloud_access_rejected
                404 -> R.string.cloud_endpoint_missing
                502, 503, 504 -> R.string.cloud_provider_failed
                else -> R.string.cloud_transcription_failed
            }
            else -> R.string.cloud_transcription_failed
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /**
     * Get or initialize the correct STT provider based on the active model's provider type.
     *
     * WHY dynamic dispatch: Phase 9 adds Parakeet as a second engine. The provider is
     * determined by the active model's AiProvider enum, not a separate setting.
     * Strict single-engine policy: if the provider type changes, release() the current
     * one before initialize() on the new one — prevents OOM from dual engine loading.
     */
    private suspend fun transcribeWithProvider(
        modelKey: String,
        modelPath: String,
        samples: FloatArray,
        language: String,
    ): String? {
        return withProviderForModel(modelKey, modelPath) { provider ->
            withTimeoutOrNull(TRANSCRIPTION_TIMEOUT_MS) {
                provider.transcribe(samples, language)
            }
        }
    }

    /**
     * Initialize the downloaded active model when the first client binds.
     *
     * This deliberately uses [ModelManager.getModelPath] instead of the downloader:
     * showing the keyboard must never trigger an unexpected network transfer. The
     * same provider slot used by transcription provides the single-flight guarantee.
     */
    private suspend fun prewarmActiveModel() {
        val preferences = dataStore.data.first()
        val activeModelKey = preferences[PreferenceKeys.ACTIVE_MODEL]
            ?: ModelCatalog.DEFAULT_KEY
        if (preferences[PreferenceKeys.REMOTE_STT_ENABLED] == true) {
            providerSlot.markRemoteReady()
            Timber.d("Remote transcription selected; native engine prewarm skipped")
            return
        }
        val modelPath = modelManager.getModelPath(activeModelKey)
        if (modelPath == null) {
            providerSlot.markModelMissing(activeModelKey)
            Timber.i("Engine prewarm skipped: model=%s is not downloaded yet", activeModelKey)
            return
        }

        try {
            val ready = withProviderForModel(activeModelKey, modelPath) { true } == true
            if (ready) {
                Timber.d("Engine prewarm complete for model=%s", activeModelKey)
            } else {
                Timber.w("Engine prewarm failed for model=%s", activeModelKey)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: LinkageError) {
            Timber.e(
                failure,
                "Engine prewarm failed for model=%s because native code could not load",
                activeModelKey,
            )
            providerSlot.markFailed(activeModelKey)
        } catch (failure: Exception) {
            Timber.e(failure, "Engine prewarm failed for model=%s", activeModelKey)
        }
    }

    private suspend fun <T> withProviderForModel(
        modelKey: String,
        modelPath: String,
        block: suspend (SttProvider) -> T,
    ): T? {
        val info = ModelCatalog.findByKey(modelKey) ?: return null
        val neededProviderClass = when (info.provider) {
            AiProvider.WHISPER -> WhisperProvider::class
            AiProvider.PARAKEET -> ParakeetProvider::class
        }

        return providerSlot.withProvider(
            requestedModelKey = modelKey,
            modelPath = modelPath,
            requiredProviderClass = neededProviderClass,
            createProvider = {
                when (info.provider) {
                    AiProvider.WHISPER -> WhisperProvider()
                    AiProvider.PARAKEET -> ParakeetProvider()
                }
            },
            useProvider = block,
        )
    }

    /**
     * Internal helper to stop recording, optionally discarding samples.
     */
    private fun stopRecordingInternal(discard: Boolean) {
        cleanupContext = ""
        realtimeSession?.cancel()
        realtimeSession = null
        captureStartJob?.cancel()
        captureStartJob = null
        if (discard) {
            audioCaptureManager?.cancel()
        } else {
            audioCaptureManager?.stop()
        }
        audioCaptureManager = null
        timerJob?.cancel()
        timerJob = null
        elapsedMs = 0L
        _state.value = DictationState.Idle
    }

    /**
     * Initialize AudioCaptureManager and start the read loop + timer.
     */
    private fun startAudioCapture() {
        // Reject an unsupported microphone before announcing a recording or
        // opening a billable provider connection.
        AudioCaptureManager.checkedMinimumBufferSize()
        _state.value = DictationState.Recording(elapsedMs = 0L, energy = emptyList())
        captureStartJob?.cancel()
        captureStartJob = serviceScope.launch {
            try {
                val prefs = dataStore.data.first()
                if (prefs[PreferenceKeys.REMOTE_STT_ENABLED] == true) {
                    val language = prefs[PreferenceKeys.TRANSCRIPTION_LANGUAGE]?.takeIf { it != "auto" }
                    lateinit var session: RealtimeSttClient.Session
                    session = realtimeSttClient.start(serviceScope, language, DictationCleanupOptions.from(prefs, cleanupContext)) { text ->
                        serviceScope.launch {
                            if (realtimeSession === session) {
                                _state.update { current ->
                                    (current as? DictationState.Recording)?.copy(liveText = text) ?: current
                                }
                            }
                        }
                    }
                    realtimeSession = session
                    Timber.tag("RealtimeDictation").i("started")
                }
                if (soundEnabled) {
                    // The start cue is played through the phone speaker. Starting AudioRecord
                    // first feeds that cue into Parakeet, which can decode it as a phantom word.
                    // Show the recording state immediately, then open the microphone only after
                    // the selected WAV and a short device-echo tail have finished.
                    val cueDurationMs = soundPlayer.playStart()
                    delay(cueDurationMs + START_CUE_ECHO_SETTLE_MS)
                }
                captureStartJob = null
                startAudioCaptureNow()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                handleRecordingFailure(
                    failure as? RuntimeException ?: IllegalStateException("Recording setup failed", failure),
                )
            }
        }
    }

    private fun startAudioCaptureNow() {
        val manager = AudioCaptureManager(getSystemService(AudioManager::class.java))
        audioCaptureManager = manager
        val session = realtimeSession
        if (session != null) manager.onPcmChunk = session::offerAudio
        manager.onCaptureError = { failure ->
            serviceScope.launch {
                if (audioCaptureManager === manager) handleRecordingFailure(failure)
            }
        }

        // Energy updates come from the capture read loop (Dispatchers.Default).
        // We update the state flow with the latest energy history each time.
        manager.onEnergyUpdate = { _ ->
            _state.update { current ->
                (current as? DictationState.Recording)?.copy(
                    elapsedMs = elapsedMs, energy = manager.getEnergyHistory(),
                ) ?: current
            }
        }

        manager.start(serviceScope)

        // Timer coroutine: increments elapsed time every second.
        // Runs on Main dispatcher since it only updates the state flow.
        elapsedMs = 0L
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                elapsedMs += 1000L
                _state.update { current ->
                    (current as? DictationState.Recording)?.copy(
                        elapsedMs = elapsedMs, energy = manager.getEnergyHistory(),
                    ) ?: current
                }
            }
        }

        _state.value = DictationState.Recording(elapsedMs = 0L, energy = emptyList())

    }

    /**
     * Create the notification channel for recording notifications.
     *
     * IMPORTANCE_LOW: no sound or vibration, just a persistent icon in the
     * status bar. This is appropriate for an ongoing recording indicator.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dictus Recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Active recording notification"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Build the foreground notification shown during recording.
     *
     * Shows "Dictus - Recording" with a Stop action button.
     * Body tap is a no-op (per user decision -- the IME is already visible).
     */
    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, DictationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dictus - Recording")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        realtimeSession?.cancel()
        realtimeSession = null
        super.onDestroy()
        if (::soundPlayer.isInitialized) soundPlayer.release()
        serviceScope.cancel()
        cleanupScope.launch {
            try {
                providerSlot.release()
            } finally {
                cleanupScope.cancel()
            }
        }
        Timber.d("DictationService destroyed")
    }
}
