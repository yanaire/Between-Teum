package dev.pleos.starter.teum.content

/**
 * Primitive string situation IDs owned by the content lane.
 *
 * Other lanes must use these raw strings (never import the session lane).
 * Values are stable storage keys: do not rename once shipped.
 */
object SituationIds {
    const val COMMUTE_MORNING = "commute-morning"
    const val LUNCH_BREAK = "lunch-break"
    const val MEETING = "meeting"
    const val COMMUTE_EVENING = "commute-evening"

    val ALL: List<String> = listOf(COMMUTE_MORNING, LUNCH_BREAK, MEETING, COMMUTE_EVENING)

    fun isKnown(situationId: String): Boolean = ALL.contains(situationId)
}
