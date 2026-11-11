package dev.pleos.starter.teum.journey

import dev.pleos.starter.teum.session.ClockReading
import java.io.IOException

internal class TestJourneyClock : JourneyClock {
    var wall = 1_800_000_000_000L
    var monotonic = 12_345L
    override fun reading() = ClockReading(wall, monotonic)
    fun advance(ms: Long) { wall += ms; monotonic += ms }
}

/** Raw JSON survives owner recreation, exercising the production codec on every write. */
internal class TestJourneyStore : JourneySnapshotStore {
    var raw: String? = null
    var failWrites = false
    override fun load(): String? = raw
    override fun save(json: String) {
        if (failWrites) throw IOException("Durable write failed")
        JourneySnapshotCodec.decode(json)
        raw = json
    }
    override fun clear() {
        if (failWrites) throw IOException("Durable clear failed")
        raw = null
    }
}

internal class JourneyFixture(route: JourneyRoute = JourneyRoute.MORNING, minutes: Int = 20) {
    val clock = TestJourneyClock()
    val store = TestJourneyStore()
    val runtime = JourneyRuntime(clock, store)
    init { runtime.start(JourneyPlan(route, minutes)) }
    val state: JourneySnapshot get() = requireNotNull(runtime.state)
    fun send(request: JourneyRequest) = runtime.request(runtime.token, request)
    fun tick(ms: Long): JourneyUpdate { clock.advance(ms); return runtime.tick() }
    fun breathing() {
        if (state.phase == JourneyPhase.MORNING_GREETING) send(JourneyRequest.Continue)
        send(JourneyRequest.ChooseFeeling(Feeling.TENSE))
    }
    fun intention() { breathing(); tick(180_000) }
    fun nap() { send(JourneyRequest.Continue); send(JourneyRequest.Continue) }
}
