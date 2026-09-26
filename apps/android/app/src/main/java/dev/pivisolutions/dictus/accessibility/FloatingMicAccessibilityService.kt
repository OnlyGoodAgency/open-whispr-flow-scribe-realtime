package dev.pivisolutions.dictus.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.pivisolutions.dictus.R
import dev.pivisolutions.dictus.core.preferences.PreferenceKeys
import dev.pivisolutions.dictus.core.service.DictationController
import dev.pivisolutions.dictus.core.service.DictationState
import dev.pivisolutions.dictus.core.service.SttEngineState
import dev.pivisolutions.dictus.core.service.TranscriptionRetention
import dev.pivisolutions.dictus.service.DictationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.abs

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface FloatingMicEntryPoint {
    fun dataStore(): DataStore<Preferences>
}

/**
 * Shows a draggable microphone beside editable fields while any keyboard remains selected.
 * Accessibility text access is limited to the focused, non-password editor at insertion time.
 */
class FloatingMicAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dataStore: DataStore<Preferences> by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            FloatingMicEntryPoint::class.java,
        ).dataStore()
    }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }

    private var controller: DictationController? = null
    private var isControllerBound = false
    private var stateJob: Job? = null
    private var engineJob: Job? = null
    private var consentGranted = false
    private var pendingStart = false
    private var ownsRecording = false
    private var dictationState: DictationState = DictationState.Idle
    private var engineState: SttEngineState = SttEngineState.Cold
    private var targetNode: AccessibilityNodeInfo? = null
    private var bubble: ImageButton? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private val dictationConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            controller = (binder as? DictationService.LocalBinder)?.getService()
            isControllerBound = controller != null
            observeController()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            stateJob?.cancel()
            engineJob?.cancel()
            controller = null
            isControllerBound = false
            pendingStart = false
            updateBubble()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        createBubble()
        scope.launch {
            dataStore.data
                .map { it[PreferenceKeys.FLOATING_MIC_DISCLOSURE_ACCEPTED] == true }
                .distinctUntilChanged()
                .collect { accepted ->
                    consentGranted = accepted
                    if (accepted) bindDictationService() else disconnectDictationService()
                    updateBubbleVisibility()
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!consentGranted) return
        val source = event?.source
        if (source != null && isEligibleEditor(source)) rememberTarget(source)
        updateBubbleVisibility()
    }

    override fun onInterrupt() {
        pendingStart = false
        if (ownsRecording) controller?.cancelRecording()
        ownsRecording = false
        updateBubble()
    }

    override fun onDestroy() {
        pendingStart = false
        if (ownsRecording) controller?.cancelRecording()
        ownsRecording = false
        disconnectDictationService()
        targetNode?.recycle()
        targetNode = null
        bubble?.let { view -> runCatching { windowManager.removeView(view) } }
        bubble = null
        bubbleParams = null
        scope.cancel()
        super.onDestroy()
    }

    private fun bindDictationService() {
        if (isControllerBound) return
        isControllerBound = bindService(
            Intent(this, DictationService::class.java),
            dictationConnection,
            Context.BIND_AUTO_CREATE,
        )
    }

    private fun disconnectDictationService() {
        stateJob?.cancel()
        engineJob?.cancel()
        stateJob = null
        engineJob = null
        if (isControllerBound) runCatching { unbindService(dictationConnection) }
        isControllerBound = false
        controller = null
    }

    private fun observeController() {
        val activeController = controller ?: return
        stateJob?.cancel()
        engineJob?.cancel()
        stateJob = scope.launch {
            activeController.state.collect { state ->
                val previousState = dictationState
                dictationState = state
                if (state == DictationState.Idle && previousState != DictationState.Idle) {
                    ownsRecording = false
                }
                updateBubble()
                updateBubbleVisibility()
            }
        }
        engineJob = scope.launch {
            activeController.engineState.collect { state ->
                engineState = state
                if (pendingStart) {
                    when (state) {
                        is SttEngineState.Ready -> {
                            pendingStart = false
                            launchRecordingActivity()
                        }
                        is SttEngineState.ModelMissing -> {
                            pendingStart = false
                            showToast(R.string.floating_mic_model_missing)
                        }
                        is SttEngineState.Failed -> {
                            pendingStart = false
                            showToast(R.string.floating_mic_engine_failed)
                        }
                        else -> Unit
                    }
                }
                updateBubble()
            }
        }
    }

    private fun onBubbleTapped() {
        val activeController = controller
        if (activeController == null) {
            showToast(R.string.floating_mic_service_unavailable)
            return
        }
        when (dictationState) {
            DictationState.Idle -> requestRecording(activeController)
            is DictationState.Recording -> {
                if (!ownsRecording) return
                scope.launch {
                    val text = activeController.confirmAndTranscribe(TranscriptionRetention.EPHEMERAL)
                    if (text.isNullOrBlank()) {
                        showToast(R.string.floating_mic_no_transcription)
                    } else if (!insertIntoFocusedEditor(text)) {
                        showToast(R.string.floating_mic_insert_failed)
                    }
                }
            }
            DictationState.Transcribing -> Unit
        }
    }

    private fun requestRecording(activeController: DictationController) {
        val editor = findEditableFocus()
        if (editor == null) {
            showToast(R.string.floating_mic_no_text_field)
            updateBubbleVisibility()
            return
        }
        rememberTarget(editor)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showToast(R.string.floating_mic_permission_missing)
            return
        }
        when (engineState) {
            is SttEngineState.Ready -> launchRecordingActivity()
            else -> {
                pendingStart = true
                activeController.prewarmEngine()
                updateBubble()
            }
        }
    }

    private fun launchRecordingActivity() {
        try {
            ownsRecording = true
            startActivity(
                Intent(this, FloatingMicStartActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                },
            )
            scope.launch {
                delay(1_500L)
                if (dictationState == DictationState.Idle) {
                    ownsRecording = false
                    updateBubbleVisibility()
                }
            }
        } catch (failure: RuntimeException) {
            ownsRecording = false
            Timber.e(failure, "Unable to open floating microphone recording bridge")
            showToast(R.string.recording_start_error)
        }
    }

    private fun insertIntoFocusedEditor(spoken: String): Boolean {
        val node = findEditableFocus() ?: targetNode?.takeIf {
            runCatching { it.refresh() && isEligibleEditor(it) }.getOrDefault(false)
        } ?: return false
        rememberTarget(node)
        val reportedStart = node.textSelectionStart
        val reportedEnd = node.textSelectionEnd
        val existing = resolveExistingEditorText(
            text = node.text,
            hintText = node.hintText,
            isShowingHintText = node.isShowingHintText,
            selectionStart = reportedStart,
            selectionEnd = reportedEnd,
        )
        val start = reportedStart.takeIf { it >= 0 } ?: existing.length
        val end = reportedEnd.takeIf { it >= 0 } ?: start
        val insertion = composeFloatingTextInsertion(existing, start, end, spoken)
        val setText = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                insertion.text,
            )
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setText)) return false
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, insertion.cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, insertion.cursor)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        return true
    }

    private fun findEditableFocus(): AccessibilityNodeInfo? {
        val roots = buildList {
            rootInActiveWindow?.let(::add)
            runCatching {
                windows.mapNotNull { it.root }.forEach(::add)
            }
        }
        roots.forEach { root ->
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && isEligibleEditor(focused)) return focused
        }
        return null
    }

    private fun isEligibleEditor(node: AccessibilityNodeInfo): Boolean =
        node.isEditable && node.isEnabled && node.isFocused && !node.isPassword &&
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    @Suppress("DEPRECATION")
    private fun rememberTarget(node: AccessibilityNodeInfo) {
        if (targetNode !== node) targetNode?.recycle()
        targetNode = node
    }

    private fun updateBubbleVisibility() {
        val active = ownsRecording || pendingStart
        val editorAvailable = findEditableFocus()?.also(::rememberTarget) != null
        val controllerAvailable = dictationState == DictationState.Idle || ownsRecording
        bubble?.visibility = if (
            consentGranted && controllerAvailable && (active || editorAvailable)
        ) View.VISIBLE else View.GONE
    }

    private fun createBubble() {
        if (bubble != null) return
        val size = dp(58)
        val view = ImageButton(this).apply {
            elevation = dp(8).toFloat()
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { onBubbleTapped() }
            setOnTouchListener(FloatingMicDragListener(this))
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = dp(16)
            y = dp(120)
        }
        try {
            windowManager.addView(view, params)
            bubble = view
            bubbleParams = params
            updateBubble()
        } catch (failure: RuntimeException) {
            Timber.e(failure, "Unable to add floating microphone accessibility overlay")
        }
    }

    private fun updateBubble() {
        val view = bubble ?: return
        val (color, icon, description) = when {
            dictationState is DictationState.Recording && ownsRecording ->
                Triple(0xFFEF4444.toInt(), R.drawable.ic_stop, R.string.floating_mic_stop_cd)
            (dictationState is DictationState.Transcribing && ownsRecording) || pendingStart ->
                Triple(0xFF6B7280.toInt(), R.drawable.ic_mic, R.string.floating_mic_busy_cd)
            else -> Triple(0xFF3D7EFF.toInt(), R.drawable.ic_mic, R.string.floating_mic_record_cd)
        }
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), Color.WHITE)
        }
        view.setImageDrawable(ContextCompat.getDrawable(this, icon))
        view.contentDescription = getString(description)
        view.isEnabled = !(dictationState is DictationState.Transcribing && ownsRecording) && !pendingStart
        view.alpha = if (view.isEnabled) 1f else 0.75f
    }

    private inner class FloatingMicDragListener(private val view: View) : View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(this@FloatingMicAccessibilityService).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(ignored: View?, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX - (event.rawX - downRawX).toInt()).coerceAtLeast(0)
                    params.y = (startY - (event.rawY - downRawY).toInt()).coerceAtLeast(0)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downRawX) > touchSlop ||
                        abs(event.rawY - downRawY) > touchSlop
                    if (!moved) view.performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private fun showToast(message: Int) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
