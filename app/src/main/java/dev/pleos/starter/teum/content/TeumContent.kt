package dev.pleos.starter.teum.content

/** One step inside a situation journey. Audio playback is owned by the UI lane; promptText is the exact text shown and sent to TTS. */
data class ActivityStep(
    val id: String,
    val title: String,
    val promptText: String,
    val durationSeconds: Int,
    val questionOptions: List<String> = emptyList()
)

/** Full content of one situation: its steps, immediate-stop label and closing question. */
data class SituationContent(
    val situationId: String,
    val title: String,
    val activityTitle: String,
    val steps: List<ActivityStep>,
    val stopNowLabel: String = "바로 종료",
    val closingQuestion: String = "",
    val closingOptions: List<String> = emptyList()
) {
    fun totalSeconds(): Int = steps.sumOf { it.durationSeconds }
}

object TeumContentCatalog {
    private val COMMUTE_MORNING = SituationContent(
        situationId = SituationIds.COMMUTE_MORNING,
        title = "출근 준비",
        activityTitle = "회의 자료 첫 장 확인",
        steps = listOf(
            ActivityStep(
                id = "prepare",
                title = "준비",
                promptText = "주차 상태를 확인하고 편하게 앉으세요. 오늘 첫 회의 자료를 준비합니다.",
                durationSeconds = 30
            ),
            ActivityStep(
                id = "breathing",
                title = "호흡",
                promptText = "편안하게 호흡하며 잠시 마음을 가다듬으세요.",
                durationSeconds = 180
            ),
            ActivityStep(
                id = "first-action",
                title = "첫 행동",
                promptText = "회의 자료의 첫 장을 확인하고 오늘의 첫 행동을 정하세요.",
                durationSeconds = 60,
                questionOptions = listOf("다음 행동을 기록한다", "기록하지 않는다")
            ),
            ActivityStep(
                id = "finish",
                title = "마무리",
                promptText = "활동을 마무리하고 소지품을 챙기세요.",
                durationSeconds = 30
            )
        ),
        closingQuestion = "다음 행동을 기록할까요?",
        closingOptions = listOf("기록한다", "건너뛴다")
    )

    private val LUNCH_BREAK = SituationContent(
        situationId = SituationIds.LUNCH_BREAK,
        title = "점심 휴식",
        activityTitle = "짧은 낮 휴식",
        steps = listOf(
            ActivityStep(
                id = "prepare",
                title = "휴식 준비",
                promptText = "의자를 기대고 눈을 감으세요. 1분 뒤에 휴식이 시작됩니다.",
                durationSeconds = 60
            ),
            ActivityStep(
                id = "rest",
                title = "휴식",
                promptText = "조용히 쉬세요. 알람이 울리기 전까지는 아무것도 하지 않아도 됩니다.",
                durationSeconds = 1080
            ),
            ActivityStep(
                id = "wake",
                title = "깨우기 및 복원",
                promptText = "천천히 눈을 뜨고 기지개를 켜세요. 시트와 주변을 원래대로 정리합니다.",
                durationSeconds = 180
            ),
            ActivityStep(
                id = "move",
                title = "복귀 이동",
                promptText = "복귀 시간에 맞춰 이동을 시작하세요.",
                durationSeconds = 180
            )
        ),
        closingQuestion = "휴식 메모를 남길까요?",
        closingOptions = listOf("기록한다", "건너뛴다")
    )

    private val MEETING = SituationContent(
        situationId = SituationIds.MEETING,
        title = "미팅 준비",
        activityTitle = "미팅 핵심 문장 정리",
        steps = listOf(
            ActivityStep(
                id = "prepare",
                title = "준비",
                promptText = "주차 상태를 확인하세요. 미팅에서 전할 핵심을 정리합니다.",
                durationSeconds = 30
            ),
            ActivityStep(
                id = "message",
                title = "핵심 정리",
                promptText = "미팅에서 꼭 전할 세 가지를 떠올리세요. 종이에 적어도 좋습니다.",
                durationSeconds = 180
            ),
            ActivityStep(
                id = "rehearsal",
                title = "핵심 문장 연습",
                promptText = "가장 중요한 한 문장을 입력하거나 소리 내어 연습해 보세요.",
                durationSeconds = 60,
                questionOptions = listOf("문장을 기록한다", "기록하지 않는다")
            ),
            ActivityStep(
                id = "summary",
                title = "요약",
                promptText = "전할 내용을 한 번 더 간단히 정리하세요.",
                durationSeconds = 90
            ),
            ActivityStep(
                id = "finish",
                title = "마무리",
                promptText = "준비를 마무리하고 주변을 정리하세요.",
                durationSeconds = 60
            )
        ),
        closingQuestion = "핵심 문장을 기록할까요?",
        closingOptions = listOf("기록한다", "건너뛴다")
    )

    private val COMMUTE_EVENING = SituationContent(
        situationId = SituationIds.COMMUTE_EVENING,
        title = "퇴근 정리",
        activityTitle = "자료 요청 메일 보내기",
        steps = listOf(
            ActivityStep(
                id = "prepare",
                title = "준비",
                promptText = "주차 상태를 확인하고 하루를 정리합니다. 요청할 자료를 떠올리세요.",
                durationSeconds = 30
            ),
            ActivityStep(
                id = "reflect",
                title = "하루 돌아보기",
                promptText = "오늘 있었던 일을 돌아보고 남은 일을 떠올려 보세요.",
                durationSeconds = 120
            ),
            ActivityStep(
                id = "tomorrow",
                title = "내일 준비",
                promptText = "내일 먼저 할 일을 하나 정해 보세요.",
                durationSeconds = 60
            ),
            ActivityStep(
                id = "daily-action",
                title = "다음 행동",
                promptText = "자료 요청 메일을 보내거나 다음 행동을 한 줄로 기록하세요.",
                durationSeconds = 60,
                questionOptions = listOf("다음 출근 때 다시 본다", "다시 보지 않는다")
            ),
            ActivityStep(
                id = "finish",
                title = "마무리",
                promptText = "활동을 마무리하고 주변을 정리하세요.",
                durationSeconds = 30
            )
        ),
        closingQuestion = "다음 출근 때 이 기록을 다시 볼까요?",
        closingOptions = listOf("다시 본다", "다시 보지 않는다")
    )

    private val ALL: List<SituationContent> =
        listOf(COMMUTE_MORNING, LUNCH_BREAK, MEETING, COMMUTE_EVENING)

    fun all(): List<SituationContent> = ALL

    fun get(situationId: String): SituationContent? = ALL.find { it.situationId == situationId }
}
