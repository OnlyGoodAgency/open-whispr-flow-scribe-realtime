package dev.pivisolutions.dictus.accessibility

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.pivisolutions.dictus.service.DictationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FloatingMicStartActivityTest {
    @Test
    fun `visible bridge forwards tap to recording service and immediately finishes`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val activity = Robolectric.buildActivity(FloatingMicStartActivity::class.java).create().get()

        val serviceIntent = shadowOf(app).nextStartedService
        assertEquals(DictationService::class.java.name, serviceIntent.component?.className)
        assertEquals(DictationService.ACTION_START, serviceIntent.action)
        assertTrue(activity.isFinishing)
    }
}
