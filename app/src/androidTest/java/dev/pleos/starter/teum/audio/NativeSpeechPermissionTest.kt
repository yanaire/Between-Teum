package dev.pleos.starter.teum.audio

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Denial boundary fixture; never grants/revokes device permissions or opens a microphone. */
@RunWith(AndroidJUnit4::class)
class NativeSpeechPermissionTest {
    @Test fun deniedPermissionRefusesCaptureBeforeMicrophoneInitialization() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val denied = object : ContextWrapper(instrumentation.targetContext) {
            override fun getApplicationContext(): Context = this
            override fun checkSelfPermission(permission: String): Int = PackageManager.PERMISSION_DENIED
        }
        val results = mutableListOf<SpeechCaptureResult>()
        instrumentation.runOnMainSync {
            NativeSpeechCapture(denied, onResult = results::add).use { capture ->
                assertFalse(capture.start())
                capture.finish()
                capture.cancel()
            }
        }
        assertEquals(1, results.size)
        assertTrue(results.single() is SpeechCaptureResult.Failed)
    }
}
