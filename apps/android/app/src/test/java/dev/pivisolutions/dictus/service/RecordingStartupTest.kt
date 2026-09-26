package dev.pivisolutions.dictus.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.media.AudioRecord
import androidx.test.core.app.ApplicationProvider
import dev.pivisolutions.dictus.R
import dev.pivisolutions.dictus.core.service.DictationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecordingStartupTest {
    private lateinit var app: Application
    private lateinit var service: DictationService

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Attach the real service without starting unrelated model/preference observers.
        service = Robolectric.buildService(DictationService::class.java).get()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun `mic tap without permission stays open and explains how to recover`() {
        service.startRecording()

        assertEquals(DictationState.Idle, service.state.value)
        assertNull(shadowOf(app).nextStartedService)
        assertEquals(app.getString(R.string.recording_permission_error), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `permission revoked before service promotion does not crash`() {
        val result = service.onStartCommand(Intent(DictationService.ACTION_START), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(DictationState.Idle, service.state.value)
        assertEquals(app.getString(R.string.recording_permission_error), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `mic can be retried after permission is restored`() {
        service.startRecording()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        service.startRecording()

        assertEquals(DictationService.ACTION_START, shadowOf(app).nextStartedService.action)
    }

    @Test
    @Config(shadows = [DeniedMicrophoneService::class])
    fun `foreground microphone rejection with permission granted does not crash`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        service.onStartCommand(Intent(DictationService.ACTION_START), 0, 1)

        assertEquals(DictationState.Idle, service.state.value)
        assertEquals(app.getString(R.string.recording_permission_error), ShadowToast.getTextOfLatestToast())
    }

    @Test
    @Config(shadows = [UnavailableMicrophone::class])
    fun `device recorder configuration failure does not crash or pretend to record`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        service.onStartCommand(Intent(DictationService.ACTION_START), 0, 1)

        assertEquals(DictationState.Idle, service.state.value)
        assertEquals(app.getString(R.string.recording_start_error), ShadowToast.getTextOfLatestToast())
        assertEquals(true, shadowOf(service).isForegroundStopped)
    }

    @Implements(AudioRecord::class)
    class UnavailableMicrophone {
        companion object {
            @JvmStatic
            @Implementation
            fun native_get_min_buff_size(sampleRate: Int, channels: Int, format: Int): Int = -2
        }
    }

    @Implements(Service::class)
    class DeniedMicrophoneService : ShadowService() {
        @Implementation
        protected override fun startForeground(id: Int, notification: Notification, type: Int) {
            throw SecurityException("Microphone foreground service is not eligible")
        }
    }
}
