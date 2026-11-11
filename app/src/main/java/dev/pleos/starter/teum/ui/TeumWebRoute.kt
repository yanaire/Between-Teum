package dev.pleos.starter.teum.ui

/** Public route identifiers from the supplied revision-nine screen contract. */
enum class TeumWebRoute {
    ON_01, ON_02, ON_03, ON_04, ON_05, ON_06, ON_07, ON_08,
    H_01, H_02, V_01, M_F1, F2_01, F3_01, F4_01, C_03,
    F1_01, F1_02, F1_03, F1_03a, F1_04, F1_04a, F1_05, F1_06,
    F2_02, F2_03, F2_04, F2_05, F2_06, F2_07, F2_07a,
    F3_00, F3_03, F3_04, F3_05, F3_06, F3_07, F3_08,
    F4_02, F4_03, F4_04, F4_05, F4_06,
    Q1_01, Q1_B, Q1_02, R_01, R_02,
    ST_01, ST_02, ST_03, ST_04, ST_05, ST_06, ST_07, ST_08, ST;

    val id: String get() = name.replace('_', '-')

    companion object {
        fun fromId(id: String): TeumWebRoute? = entries.firstOrNull { it.id == id }
    }
}
