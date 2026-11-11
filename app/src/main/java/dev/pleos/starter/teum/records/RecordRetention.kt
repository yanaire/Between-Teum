package dev.pleos.starter.teum.records

import java.time.Instant
import java.time.ZoneId

data class MorningReflection(val recordId: String, val story: String?, val greeting: String?)
data class HistoryPolicy(val zone: ZoneId, val deleteEveningStoryAfterNextMorning: Boolean)
data class HistoryMaintenance(
    val records: List<ActivityRecord>,
    val deletedRecordIds: List<String>,
    val redactedReflectionIds: List<String>
)

object RecordRetention {
    fun apply(
        records: List<ActivityRecord>,
        nowEpochMs: Long,
        policy: HistoryPolicy
    ): HistoryMaintenance {
        val now = Instant.ofEpochMilli(nowEpochMs)
        val cutoff = now.atZone(policy.zone).minusYears(1).toInstant().toEpochMilli()
        val deleted = mutableListOf<String>()
        val redacted = mutableListOf<String>()
        val retained = records.mapNotNull { record ->
            val time = record.knownTimeEpochMs()
            if (time != null && time <= cutoff) {
                deleted.add(record.id)
                return@mapNotNull null
            }
            val facts = record.journeyFacts
            val retainedFacts = facts?.let { retainReflection(it, nowEpochMs, policy) }
            if (retainedFacts != facts) {
                redacted.add(record.id)
                record.copy(journeyFacts = retainedFacts, updatedAtEpochMs = nowEpochMs)
            } else record
        }
        return HistoryMaintenance(retained, deleted, redacted)
    }

    /** Saved records and unsaved activity checkpoints share the same text expiry. */
    fun retainReflection(facts: JourneyFacts, nowEpochMs: Long, policy: HistoryPolicy): JourneyFacts {
        val reflection = facts.reflection ?: return facts
        val reflectionTime = facts.finishedAtEpochMs ?: facts.startedAtEpochMs
        val expires = reflectionTime?.let {
            Instant.ofEpochMilli(it).atZone(policy.zone).toLocalDate()
                .plusDays(2).atStartOfDay(policy.zone).toInstant()
        }
        return if (policy.deleteEveningStoryAfterNextMorning &&
            (reflection.story != null || reflection.greeting != null) &&
            (reflection.consumedAtEpochMs != null ||
                (expires != null && !Instant.ofEpochMilli(nowEpochMs).isBefore(expires)))) {
            facts.copy(reflection = EveningReflection(consumedAtEpochMs = reflection.consumedAtEpochMs))
        } else facts
    }
}
