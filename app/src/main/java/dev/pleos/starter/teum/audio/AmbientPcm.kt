package dev.pleos.starter.teum.audio

import dev.pleos.starter.teum.preferences.RestSound
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Original procedural textures, not recordings of nature or licensed music. */
object AmbientPcm {
    const val SAMPLE_RATE = 16000
    const val PROVENANCE = "Locally synthesized ambient texture; not a field recording."

    fun meaning(sound: RestSound): String = when (sound) {
        RestSound.RAIN -> "Synthetic rain-like filtered noise"
        RestSound.WAVES -> "Synthetic slow surf-like noise swells"
        RestSound.FOREST -> "Synthetic rustling noise and bird-like oscillators"
        RestSound.CAMPFIRE -> "Synthetic low noise and crackling impulses"
        RestSound.SINGING_BOWL -> "Synthetic decaying harmonic bowl-like tones"
        RestSound.WIND -> "Synthetic slowly filtered wind-like noise"
        RestSound.WHITE_NOISE -> "Synthetic uniform white noise"
        RestSound.NONE -> "Explicit silence"
    }

    /** Eight-second mono PCM16 loop, with a short boundary fade to avoid clicks. */
    fun render(sound: RestSound): ShortArray {
        if (sound == RestSound.NONE) return ShortArray(0)
        val count = SAMPLE_RATE * 8
        val random = Random(713L + sound.ordinal)
        var low = 0.0
        var crackle = 0.0
        return ShortArray(count) { index ->
            val t = index.toDouble() / SAMPLE_RATE
            val noise = random.nextDouble() * 2 - 1
            low += 0.025 * (noise - low)
            crackle = if (random.nextDouble() < 0.0007) noise else crackle * 0.94
            val sample = when (sound) {
                RestSound.RAIN -> 0.12 * noise + 0.24 * low
                RestSound.WAVES -> (0.10 + 0.10 * sin(2 * PI * t / 8)) * (noise * 0.3 + low * 3)
                RestSound.FOREST -> 0.20 * low + 0.07 * sin(2 * PI * (1800 * t + 80 * sin(2 * PI * t))) *
                    exp(-12 * ((t % 2) - 0.5) * ((t % 2) - 0.5))
                RestSound.CAMPFIRE -> 0.4 * low + 0.24 * crackle
                RestSound.SINGING_BOWL -> 0.12 * exp(-(t % 4)) *
                    (sin(2 * PI * 220 * t) + 0.5 * sin(2 * PI * 441 * t) + 0.25 * sin(2 * PI * 663 * t))
                RestSound.WIND -> low * (0.55 + 0.3 * sin(2 * PI * t / 8))
                RestSound.WHITE_NOISE -> noise * 0.16
                RestSound.NONE -> 0.0
            }
            val fade = minOf(1.0, index / 320.0, (count - 1 - index) / 320.0)
            (sample.coerceIn(-0.8, 0.8) * fade * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
