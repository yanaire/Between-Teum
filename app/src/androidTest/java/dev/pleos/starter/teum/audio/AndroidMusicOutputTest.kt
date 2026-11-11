package dev.pleos.starter.teum.audio

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pleos.starter.MainActivity
import dev.pleos.starter.teum.journey.MusicChoice
import dev.pleos.starter.teum.records.JourneyKind
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real AudioTrack regression. Run only in the native QA owner's isolated fixture. */
@RunWith(AndroidJUnit4::class)
class AndroidMusicOutputTest {
    @Test fun newlyCreatedStaticTrackAcceptsPcmAndReportsPlaybackProgress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val renderer = Executors.newSingleThreadExecutor()
        val progress = CompletableFuture<Int>()
        var output: AndroidMusicOutput? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        val created = AndroidMusicOutput(activity, renderer) { true }
                        output = created
                        created.prepare(
                            OriginalMusic.select(JourneyKind.PREPARATION, MusicChoice.SUPPORT_SONG),
                            object : MusicPlayback.Output.Listener {
                                override fun progress(frames: Int) {
                                    if (frames > 0) progress.complete(frames)
                                }
                                override fun failed(reason: String) {
                                    progress.completeExceptionally(AssertionError(reason))
                                }
                                override fun ended() {
                                    progress.completeExceptionally(
                                        AssertionError("Playback ended without positive progress")
                                    )
                                }
                            }
                        )
                    }
                    assertTrue(progress.get(20, SECONDS) > 0)
                } finally {
                    instrumentation.runOnMainSync { output?.close() }
                }
            }
        } finally {
            renderer.shutdownNow()
            assertTrue("Renderer thread did not terminate", renderer.awaitTermination(5, SECONDS))
        }
    }
}
