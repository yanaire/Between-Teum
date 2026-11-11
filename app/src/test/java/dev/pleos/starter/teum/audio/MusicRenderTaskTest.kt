package dev.pleos.starter.teum.audio

import dev.pleos.starter.teum.journey.MusicChoice
import dev.pleos.starter.teum.records.JourneyKind
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MusicRenderTaskTest {
    private val piece = OriginalMusic.select(JourneyKind.PREPARATION, MusicChoice.SUPPORT_SONG)

    @Test fun renderingRuntimeFailureIsDeliveredInsteadOfHiddenInTheFuture() = withRenderer { executor ->
        val observation = CompletableFuture<Result<ShortArray>>()
        val deliveries = AtomicInteger()
        // Fault injection through existing score data, without replacing the real renderer.
        val task = musicRenderTask(piece.copy(bpm = 0)) { result ->
            deliveries.incrementAndGet()
            observation.complete(result)
        }

        val submitted = executor.submit(task)

        submitted.get(5, SECONDS)
        assertTrue(observation.get(5, SECONDS).exceptionOrNull() is ArithmeticException)
        assertEquals(1, deliveries.get())
    }

    @Test fun successfulRenderingDeliversThePcmToItsOwner() = withRenderer { executor ->
        val observation = CompletableFuture<Result<ShortArray>>()
        val task = musicRenderTask(piece) { observation.complete(it) }

        val submitted = executor.submit(task)

        submitted.get(5, SECONDS)
        assertEquals(piece.frames, observation.get(5, SECONDS).getOrThrow().size)
    }

    @Test fun interruptionPreservesTheFlagWithoutDeliveringAFailureOrPcm() = withRenderer { executor ->
        val observation = CompletableFuture<Result<ShortArray>>()
        val task = musicRenderTask(piece) { observation.complete(it) }

        val submitted = executor.submit<Boolean> {
            Thread.currentThread().interrupt()
            task.run()
            Thread.currentThread().isInterrupted
        }

        assertTrue(submitted.get(5, SECONDS))
        assertFalse(observation.isDone)
    }

    private fun withRenderer(check: (ExecutorService) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            check(executor)
        } finally {
            executor.shutdownNow()
            assertTrue("Renderer thread did not terminate", executor.awaitTermination(5, SECONDS))
        }
    }
}
