package dev.pleos.starter.teum.records

import java.util.UUID

object ActivityOutcome {
    const val COMPLETED = "completed"
    const val INTERRUPTED = "interrupted"
    const val SAFETY_INTERRUPTED = "safety_interrupted"

    val ALL: List<String> = listOf(COMPLETED, INTERRUPTED, SAFETY_INTERRUPTED)

    fun isKnown(outcome: String): Boolean = ALL.contains(outcome)
}

data class ActivityRecord(
    val id: String,
    val situationId: String,
    val activityTitle: String,
    val totalSteps: Int,
    val completedStepIndexes: List<Int>,
    val elapsedSeconds: Long,
    val outcome: String,
    val note: String = "",
    val nextAction: String = "",
    val recallAccepted: Boolean = false,
    val historyConsentAtSave: Boolean = false,
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L
) {
    fun completedStepCount(): Int = completedStepIndexes.size

    fun isFullyCompleted(): Boolean =
        outcome == ActivityOutcome.COMPLETED &&
            totalSteps > 0 &&
            completedStepIndexes.size == totalSteps

    fun validate() {
        require(id.isNotBlank()) { "id must not be blank" }
        require(dev.pleos.starter.teum.content.SituationIds.isKnown(situationId)) {
            "unknown situationId: $situationId"
        }
        require(activityTitle.isNotBlank()) { "activityTitle must not be blank" }
        require(totalSteps >= 0) { "totalSteps must be >= 0" }
        require(elapsedSeconds >= 0) { "elapsedSeconds must be >= 0" }
        require(ActivityOutcome.isKnown(outcome)) { "unknown outcome: $outcome" }
        require(completedStepIndexes.all { it in 0 until totalSteps }) {
            "completedStepIndexes out of range"
        }
        require(completedStepIndexes.size == completedStepIndexes.toSet().size) {
            "completedStepIndexes must not contain duplicates"
        }
        if (outcome == ActivityOutcome.COMPLETED) {
            require(totalSteps > 0 && completedStepIndexes.size == totalSteps) {
                "completed outcome requires every step to be completed"
            }
        }
    }
}

fun newActivityRecord(
    situationId: String,
    activityTitle: String,
    totalSteps: Int,
    completedStepIndexes: List<Int>,
    elapsedSeconds: Long,
    outcome: String,
    note: String = "",
    nextAction: String = "",
    recallAccepted: Boolean = false,
    historyConsentAtSave: Boolean = false,
    nowEpochMs: Long = System.currentTimeMillis()
): ActivityRecord {
    val record = ActivityRecord(
        id = UUID.randomUUID().toString(),
        situationId = situationId,
        activityTitle = activityTitle,
        totalSteps = totalSteps,
        completedStepIndexes = completedStepIndexes.toList(),
        elapsedSeconds = elapsedSeconds,
        outcome = outcome,
        note = note,
        nextAction = nextAction,
        recallAccepted = recallAccepted,
        historyConsentAtSave = historyConsentAtSave,
        createdAtEpochMs = nowEpochMs,
        updatedAtEpochMs = nowEpochMs
    )
    record.validate()
    return record
}
