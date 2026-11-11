package dev.pleos.starter.teum.audio

import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pleos.starter.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Pleos ON_DEVICE service contract. Synthetic PCM only; never opens a microphone. */
@RunWith(AndroidJUnit4::class)
class PleosPcmRecognitionTest {
    @Test(timeout = 90_000)
    fun onDevicePcmRecognizesTwoDistinctSyntheticKoreanInputs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission("pleos.car.permission.STT_SERVICE"))
        val observations = JSONArray()
        val receipt = File(context.cacheDir, "teum_synthetic_speech_proof.json")
        val fixtures = listOf(
            "teum_synthetic_ko_first.pcm" to listOf("\ucc28\ubd84", "\ub9c8\ubb34\ub9ac"),
            "teum_synthetic_ko_second.pcm" to listOf("\ud68c\uc758", "\uc9c8\ubb38")
        )

        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                for ((asset, expectedWords) in fixtures) {
                    val pcm = instrumentation.context.assets.open(asset).use { input -> input.readBytes() }
                    val completed = CountDownLatch(1)
                    val result = AtomicReference<Pair<String?, RecognitionOutcome>>()
                    var recognizer: PleosPcmRecognition? = null
                    try {
                        instrumentation.runOnMainSync {
                            val created = PleosPcmRecognition(context) { text, outcome ->
                                result.set(text to outcome)
                                completed.countDown()
                            }
                            recognizer = created
                            created.start(pcm)
                        }
                        val arrived = completed.await(40, TimeUnit.SECONDS)
                        val observed = result.get()
                        observations.put(JSONObject()
                            .put("asset", asset)
                            .put("pcmBytes", pcm.size)
                            .put("callbackArrived", arrived)
                            .put("outcome", observed?.second?.toString() ?: "NO_CALLBACK")
                            .put("text", observed?.first ?: JSONObject.NULL))
                        assertTrue("No SDK callback for $asset", arrived)
                        val actual = requireNotNull(observed)
                        assertEquals("SDK outcome for $asset", RecognitionOutcome.Recognized, actual.second)
                        val normalized = requireNotNull(actual.first)
                            .replace(Regex("[^\uAC00-\uD7A3A-Za-z0-9]"), "")
                        expectedWords.forEach { word ->
                            assertTrue("SDK output did not recognize $word in $asset: ${actual.first}",
                                normalized.contains(word))
                        }
                    } finally {
                        instrumentation.runOnMainSync { recognizer?.close() }
                    }
                }
            } finally {
                receipt.writeText(JSONObject()
                    .put("method", "Real Pleos ON_DEVICE external PCM; local synthetic speech; no microphone")
                    .put("observations", observations)
                    .toString(2))
            }
        }
    }
}
