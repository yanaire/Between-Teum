package dev.pleos.starter.teum.ui

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.pleos.starter.teum.audio.MusicPlayback
import dev.pleos.starter.teum.audio.OriginalMusic
import dev.pleos.starter.teum.journey.JourneySnapshot
import dev.pleos.starter.teum.journey.JourneyToken
import dev.pleos.starter.teum.journey.MusicChoice
import dev.pleos.starter.teum.journey.PlaybackState
import dev.pleos.starter.teum.records.JourneyKind

/** Catalog selection is available offline. It is separate from observed playback. */
internal fun musicProjection(journey: JourneySnapshot?, observed: MusicPlayback.Observation?): JsonObject {
    val kind = journey?.plan?.kind ?: JourneyKind.PREPARATION
    val selected = journey?.musicChoice?.let { OriginalMusic.select(kind, it) }
    val current = observed?.takeIf {
        journey?.active == true && it.token == JourneyToken(journey.sessionId, journey.revision) &&
            it.piece == selected
    }
    val playback = if (journey?.active == true) journey.playback else PlaybackState.UNREQUESTED
    return JsonObject().apply {
        addProperty("status", playback.name)
        addProperty("statusLabel", when (playback) {
            PlaybackState.UNREQUESTED -> "음악을 고른 뒤 듣기를 눌러 주세요"
            PlaybackState.REQUESTED -> "앱 내 음악을 준비하고 있어요"
            PlaybackState.PLAYING -> "앱 내 연주곡 재생 중"
            PlaybackState.ENDED -> "연주가 끝났어요"
            PlaybackState.UNAVAILABLE -> "재생할 수 없어요 · 다른 곡 고르기로 다시 선택해 주세요"
        })
        addProperty("title", selected?.title)
        addProperty("durationMs", selected?.durationMs)
        addProperty("positionMs", current?.positionMs ?: 0)
        addProperty("reason", current?.reason)
        addProperty("source", "SELF_AUTHORED_OFFLINE_SCORE")
        addProperty("selectionMethod", "NATIVE_JOURNEY_CONTEXT")
        add("choices", JsonArray().apply {
            MusicChoice.entries.forEach { choice ->
                val piece = OriginalMusic.select(kind, choice)
                add(JsonObject().apply {
                    addProperty("title", piece.title)
                    addProperty("subtitle", "${piece.bpm} BPM · 틈 자작 연주곡 · 오프라인")
                    addProperty("available", true)
                    addProperty("durationMs", piece.durationMs)
                })
            }
        })
    }
}
